package com.mok.buylist.cloud

import android.content.Context
import android.util.AtomicFile
import com.mok.buylist.data.AppDatabase
import com.mok.buylist.data.ShoppingRepository
import com.mok.buylist.data.UserProfile
import com.mok.buylist.sync.SyncMerge
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.File

internal data class CloudSpaceStatus(val syncing: Boolean = false, val lastSuccess: Long = 0, val issue: String? = null)

internal class CloudSpaceSession(
    val invitation: CloudInvitation,
    val repository: ShoppingRepository,
    val grant: CloudGrant,
    val database: AppDatabase,
) {
    val space get() = invitation.space
    val writable get() = grant.canWrite
    val status = MutableStateFlow(CloudSpaceStatus())
    val mutex = Mutex()
}

/** Isolated databases and journals; no import into the ordinary shopping list. */
internal class CloudSpaces(private val context: Context, private val scope: CoroutineScope) {
    private val dir = File(context.noBackupFilesDir, "cloud-invitations-v1")
    private val prefs = context.getSharedPreferences("cloud-invitations-v1", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val _sessions = MutableStateFlow<List<CloudSpaceSession>>(emptyList())
    val sessions: StateFlow<List<CloudSpaceSession>> = _sessions
    private val _issue = MutableStateFlow<String?>(null)
    val issue: StateFlow<String?> = _issue
    private var started = false

    @Synchronized fun start() {
        if (started) return
        started = true
        scope.launch(Dispatchers.IO) {
            lock.withLock {
                for (file in dir.listFiles().orEmpty().filter { it.name.endsWith(".blinvite") }.sortedBy { it.name }) {
                    try {
                        val bytes = readBounded(AtomicFile(file).openRead())
                        val invitation = CloudInvitation.decode(bytes)
                        require(prefs.getString("admin", null) == invitation.admin)
                        openSession(invitation)
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { _issue.value = "Не удалось открыть один из облачных списков. Данные сохранены." }
                    catch (_: LinkageError) { _issue.value = "Не удалось загрузить облачный модуль." }
                }
            }
            while (isActive) {
                for (session in _sessions.value) sync(session)
                delay(45_000)
            }
        }
    }

    suspend fun importInvitation(bytes: ByteArray, confirmedAdmin: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val invitation = CloudInvitation.decode(bytes)
            require(invitation.adminFingerprint == confirmedAdmin) { "Код администратора не совпадает" }
            val id = CloudDeviceIdentity.fingerprint()
            invitation.open(id, CloudDeviceIdentity.encryptionPair().private)
            val pinned = prefs.getString("admin", null)
            require(pinned == null || pinned == invitation.admin) { "Другой администратор. Смена доверенного ключа запрещена." }
            val existing = _sessions.value.singleOrNull { it.space == invitation.space }
            if (existing != null) {
                require(existing.invitation.signed.manifest.canonical().contentEquals(invitation.signed.manifest.canonical()))
                val privateKey = CloudDeviceIdentity.encryptionPair().private
                require(java.security.MessageDigest.isEqual(existing.invitation.open(id, privateKey).encoded, invitation.open(id, privateKey).encoded))
            }
            check(dir.isDirectory || dir.mkdirs())
            if (pinned == null) check(prefs.edit().putString("admin", invitation.admin).commit())
            val file = AtomicFile(File(dir, invitation.space.key.replace(':', '-') + ".blinvite"))
            if (existing == null && file.baseFile.exists()) {
                val old = CloudInvitation.decode(readBounded(file.openRead()))
                require(old.signed.manifest.canonical().contentEquals(invitation.signed.manifest.canonical()))
                val privateKey = CloudDeviceIdentity.encryptionPair().private
                require(java.security.MessageDigest.isEqual(old.open(id, privateKey).encoded, invitation.open(id, privateKey).encoded))
            }
            val out = file.startWrite()
            try { out.write(bytes); file.finishWrite(out) }
            catch (e: Exception) { file.failWrite(out); throw e }
            if (existing == null) openSession(invitation)
            _issue.value = null
        }
        _sessions.value.singleOrNull { it.space == CloudInvitation.decode(bytes).space }?.let { requestSync(it) }
    }

    private suspend fun openSession(invitation: CloudInvitation) {
        val id = CloudDeviceIdentity.fingerprint()
        val grant = invitation.verify(id)
        invitation.open(id, CloudDeviceIdentity.encryptionPair().private)
        if (_sessions.value.any { it.space == invitation.space }) return
        val name = invitation.space.key.replace(':', '-')
        val database = AppDatabase.create(context, "cloud-$name.db")
        try {
            val profile = UserProfile(context, "cloud-profile-$name")
            val needsProfile = profile.id != grant.userId || profile.profile.value?.name != invitation.userName
            if (needsProfile) profile.adopt(grant.userId, invitation.userName)
            val repo = ShoppingRepository(context, database, profile, isolatedWorkspace = true)
            if (needsProfile) repo.saveProfile(invitation.userName)
            _sessions.value = _sessions.value + CloudSpaceSession(invitation, repo, grant, database)
        } catch (e: Exception) { database.close(); throw e }
    }

    fun requestSync(session: CloudSpaceSession) {
        scope.launch(Dispatchers.IO) { sync(session) }
    }

    suspend fun edit(session: CloudSpaceSession, action: suspend (ShoppingRepository) -> Unit) {
        require(session.writable)
        withContext(Dispatchers.IO) { session.mutex.withLock { action(session.repository) } }
        requestSync(session)
    }

    private suspend fun sync(session: CloudSpaceSession) {
        if (!session.mutex.tryLock()) return
        val previous = session.status.value
        session.status.value = previous.copy(syncing = true, issue = null)
        try {
            val invitation = session.invitation
            val identity = CloudDeviceIdentity.keyPair()
            val key = invitation.open(CloudDeviceIdentity.fingerprint(), CloudDeviceIdentity.encryptionPair().private)
            val journals = CloudJournalStore(context)
            journals.ensureInitialized(session.space, invitation.signed, invitation.admin)
            val directory = File(context.noBackupFilesDir, "cloud-git-v1/" + session.space.key.replace(':', '-'))
            FileRepositoryBuilder().setGitDir(directory).setBare().build().use { git ->
                if (!git.objectDatabase.exists()) git.create(true)
                val engine = CloudSyncEngine(journals, CloudOutbox(context), CloudGitEvents(git, CloudGitSsh.REMOTE, CloudGitSsh.configure), identity)
                // Pull durable events first. Merge the current local state, never replace it.
                val remote = engine.pull(session.space, invitation.signed, key)
                if (session.writable) {
                    val merged = SyncMerge.merge(remote.state.snapshot, session.repository.exportSnapshot())
                    val delta = CloudSnapshotDelta.between(remote.state.snapshot, merged)
                    val result = engine.publish(session.space, delta, invitation.signed, key)
                    session.repository.applyRemote(result.state.snapshot)
                } else {
                    session.repository.applyRemote(remote.state.snapshot)
                }
            }
            session.status.value = CloudSpaceStatus(lastSuccess = System.currentTimeMillis())
        } catch (e: CancellationException) { throw e }
        catch (e: LinkageError) { session.status.value = previous.copy(syncing = false, issue = "Облачный транспорт несовместим с этой сборкой. Данные сохранены.") }
        catch (e: Exception) {
            session.status.value = previous.copy(syncing = false, issue = "Нет синхронизации. Покупки сохранены на телефоне.")
        } finally {
            if (session.status.value.syncing) session.status.value = previous.copy(syncing = false)
            session.mutex.unlock()
        }
    }

    companion object {
        fun readBounded(input: java.io.InputStream): ByteArray = input.use {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                require(out.size() + count <= CloudInvitation.MAX_FILE)
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
    }
}
