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
    val profile: UserProfile,
    val catalog: com.mok.buylist.data.LentaCatalog,
) {
    val localKey: String get() = space.key.replace(':', '-') + (if (epoch == 1L) "" else "-epoch-$epoch")
    val space get() = invitation.space
    val historicalState = MutableStateFlow(false)
    var historical: Boolean
        get() = historicalState.value
        set(value) { historicalState.value = value }
    val epoch get() = invitation.signed.manifest.epoch
    val writable get() = grant.canWrite && !historical
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
                for (session in _sessions.value.filterNot { it.historical }) sync(session)
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
            val existing = _sessions.value.filter { it.space == invitation.space }.maxByOrNull { it.epoch }
            val epoch = invitation.signed.manifest.epoch
            require(epoch >= prefs.getLong("minimum_epoch_" + invitation.space.key, 1L)) { "Приглашение устарело" }
            if (existing != null) require(epoch >= existing.epoch) { "Приглашение устарело" }
            if (existing != null && existing.epoch == epoch) {
                require(existing.invitation.signed.manifest.canonical().contentEquals(invitation.signed.manifest.canonical()))
                val privateKey = CloudDeviceIdentity.encryptionPair().private
                require(java.security.MessageDigest.isEqual(existing.invitation.open(id, privateKey).encoded, invitation.open(id, privateKey).encoded))
            }
            check(dir.isDirectory || dir.mkdirs())
            if (pinned == null) check(prefs.edit().putString("admin", invitation.admin).commit())
            val name = invitation.space.key.replace(':', '-')
            val file = AtomicFile(File(dir, name + (if (epoch == 1L) "" else "-epoch-$epoch") + ".blinvite"))
            if (file.baseFile.exists()) {
                val old = CloudInvitation.decode(readBounded(file.openRead()))
                require(old.signed.manifest.canonical().contentEquals(invitation.signed.manifest.canonical()))
                val privateKey = CloudDeviceIdentity.encryptionPair().private
                require(java.security.MessageDigest.isEqual(old.open(id, privateKey).encoded, invitation.open(id, privateKey).encoded))
            }
            val out = file.startWrite()
            try { out.write(bytes); file.finishWrite(out) }
            catch (e: Exception) { file.failWrite(out); throw e }
            if (existing == null || existing.epoch != epoch) openSession(invitation)
            markHistorical()
            _issue.value = null
        }
        _sessions.value.filter { it.space == CloudInvitation.decode(bytes).space && !it.historical }.maxByOrNull { it.epoch }?.let { requestSync(it) }
    }

    private suspend fun openSession(invitation: CloudInvitation) {
        val id = CloudDeviceIdentity.fingerprint()
        val grant = invitation.verify(id)
        invitation.open(id, CloudDeviceIdentity.encryptionPair().private)
        val epoch = invitation.signed.manifest.epoch
        if (_sessions.value.any { it.space == invitation.space && it.epoch == epoch }) return
        val name = invitation.space.key.replace(':', '-') + (if (epoch == 1L) "" else "-epoch-$epoch")
        val database = AppDatabase.create(context, "cloud-$name.db")
        try {
            val profile = UserProfile(context, "cloud-profile-$name")
            val needsProfile = profile.id != grant.userId || profile.profile.value?.name != invitation.userName
            if (needsProfile) profile.adopt(grant.userId, invitation.userName)
            val catalog = com.mok.buylist.data.LentaCatalog(context, database, scope, "cloud-lenta-$name")
            val repo = ShoppingRepository(context, database, profile, isolatedWorkspace = true, workspaceCatalog = catalog)
            if (needsProfile && grant.canWrite) repo.saveProfile(invitation.userName)
            if (invitation.checkpoint.isNotEmpty()) {
                val checkpoint = CloudEpochCheckpoint.open(invitation.checkpoint, invitation.signed.manifest,
                    invitation.open(id, CloudDeviceIdentity.encryptionPair().private))
                repo.applyRemote(checkpoint)
            }
            val session = CloudSpaceSession(invitation, repo, grant, database, profile, catalog)
            session.historical = epoch < prefs.getLong("minimum_epoch_" + invitation.space.key, 1L)
            _sessions.value = _sessions.value + session
            markHistorical()
        } catch (e: Exception) { database.close(); throw e }
    }

    private fun markHistorical() {
        val latest = _sessions.value.groupBy { it.space }.mapValues { (_, sessions) -> sessions.maxOf { it.epoch } }
        _sessions.value.forEach { if (it.epoch < latest.getValue(it.space)) it.historical = true }
    }

    fun requestSync(session: CloudSpaceSession) {
        scope.launch(Dispatchers.IO) { sync(session) }
    }

    suspend fun edit(session: CloudSpaceSession, action: suspend (ShoppingRepository) -> Unit) {
        require(session.writable)
        // Room writes remain available offline; applyRemote merges in a transaction.
        withContext(Dispatchers.IO) { action(session.repository) }
        requestSync(session)
    }

    private suspend fun sync(session: CloudSpaceSession) {
        if (session.historical || !session.mutex.tryLock()) return
        val previous = session.status.value
        session.status.value = previous.copy(syncing = true, issue = null)
        try {
            val invitation = session.invitation
            val identity = CloudDeviceIdentity.keyPair()
            val key = invitation.open(CloudDeviceIdentity.fingerprint(), CloudDeviceIdentity.encryptionPair().private)
            val journals = CloudJournalStore(context, epochNamespace = session.epoch)
            val checkpoint = if (invitation.checkpoint.isEmpty()) com.mok.buylist.sync.Snapshot()
                else CloudEpochCheckpoint.open(invitation.checkpoint, invitation.signed.manifest, key)
            journals.ensureInitialized(session.space, invitation.signed, invitation.admin, checkpoint)
            val directory = File(context.noBackupFilesDir, "cloud-git-v1/" + session.space.key.replace(':', '-'))
            FileRepositoryBuilder().setGitDir(directory).setBare().build().use { git ->
                if (!git.objectDatabase.exists()) git.create(true)
                val transport = CloudGitEvents(git, CloudGitSsh.REMOTE, CloudGitSsh.configure)
                val control = transport.readControl(session.space)?.verify(invitation.admin, session.space)
                    ?: throw CloudAdminException("Нужно обновить приглашение у администратора.")
                if (!control.matches(invitation.signed.manifest)) {
                    require(control.epoch >= session.epoch) { "Обнаружена устаревшая версия доступа" }
                    if (control.epoch > session.epoch || control.retired) {
                        val minimum = if (control.retired) Math.addExact(control.epoch, 1L) else control.epoch
                        val keyName = "minimum_epoch_" + session.space.key
                        check(prefs.edit().putLong(keyName, maxOf(minimum, prefs.getLong(keyName, 1L))).commit())
                        session.historical = true
                        session.status.value = previous.copy(syncing = false, issue = "Версия списка изменилась. Получите новое приглашение.")
                        return
                    }
                    throw CloudAdminException("Версия приглашения не совпадает с опубликованной.")
                }
                val engine = CloudSyncEngine(journals, CloudOutbox(context, epochNamespace = session.epoch), transport, identity)
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
        catch (e: AssertionError) { session.status.value = previous.copy(syncing = false, issue = "Ошибка облачного транспорта. Данные сохранены.") }
        catch (e: Exception) {
            session.status.value = previous.copy(syncing = false, issue = if (e is CloudAdminException) e.message else "Нет синхронизации. Покупки сохранены на телефоне.")
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
