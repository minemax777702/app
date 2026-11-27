package com.mok.buylist.cloud

import android.content.Context
import android.util.AtomicFile
import androidx.glance.appwidget.updateAll
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

internal data class CloudSpaceStatus(val syncing: Boolean = false, val lastSuccess: Long = 0, val issue: String? = null, val photoIssue: String? = null)

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
    private val ready = CompletableDeferred<Unit>()
    private var foregroundJob: Job? = null
    private val syncAllMutex = Mutex()
    val widgetKey = MutableStateFlow(prefs.getString("widget_workspace", null))
    val selectedWorkspace = MutableStateFlow(prefs.getString("selected_workspace", null))
    val connectionCode = MutableStateFlow(prefs.getString("connection_code", null)
        ?: prefs.getString("admin", null)?.let { CloudDeviceProtocol.fingerprint(it) })
    private val _connecting = MutableStateFlow(false)
    val connecting: StateFlow<Boolean> = _connecting

    fun selectWorkspace(session: CloudSpaceSession?) {
        val key = session?.localKey
        prefs.edit().putString("selected_workspace", key).apply()
        selectedWorkspace.value = key
    }

    suspend fun connect(code: String) = withContext(Dispatchers.IO) {
        awaitReady()
        val fingerprint = code.filterNot(Char::isWhitespace).lowercase(java.util.Locale.ROOT)
        require(fingerprint.matches(Regex("[0-9a-f]{64}"))) { "Некорректный код подключения" }
        val pinned = prefs.getString("admin", null)
        require(pinned == null || CloudDeviceProtocol.fingerprint(pinned) == fingerprint) {
            "Подключение к другому администратору запрещено"
        }
        check(prefs.edit().putString("connection_code", fingerprint).commit())
        connectionCode.value = fingerprint
        _issue.value = null
        CloudSyncWork.schedule(context)
        CloudSyncWork.enqueue(context)
    }

    fun refreshConnection() {
        if (connectionCode.value != null) CloudSyncWork.enqueue(context)
    }

    private suspend fun discoverAccess(): Boolean {
        val fingerprint = connectionCode.value ?: return true
        _connecting.value = true
        return try {
            val device = CloudDeviceIdentity.fingerprint()
            val directory = File(context.noBackupFilesDir, "cloud-access-git-v1")
            val grants = FileRepositoryBuilder().setGitDir(directory).setBare().build().use { git ->
                if (!git.objectDatabase.exists()) git.create(true)
                CloudGitEvents(git, CloudGitSsh.REMOTE, CloudGitSsh.configure).readAccess(device, fingerprint)
            }
            for (grant in grants) {
                val current = _sessions.value.firstOrNull { it.space == grant.space && it.epoch == grant.signed.manifest.epoch }
                if (current == null) importInvitation(grant.encode(), fingerprint)
            }
            _issue.value = if (grants.isEmpty() && _sessions.value.none { !it.historical }) "Нет доступных списков" else null
            true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val cause = generateSequence<Throwable>(e) { it.cause }.take(12).filterIsInstance<CloudAdminException>().firstOrNull()
            _issue.value = cause?.message ?: "Не удалось обновить подключение"
            false
        } catch (_: LinkageError) {
            _issue.value = "Облачный модуль недоступен"
            false
        } finally { _connecting.value = false }
    }

    fun selectWidget(session: CloudSpaceSession?) {
        val key = session?.localKey
        if (session != null) require(!session.historical)
        prefs.edit().putString("widget_workspace", key).apply()
        widgetKey.value = key
        scope.launch { refreshWidgets() }
    }

    private suspend fun refreshWidgets() {
        try { com.mok.buylist.widget.ShoppingWidget().updateAll(context) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Widget failure must not affect committed purchases. */ }
    }

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
            ready.complete(Unit)
            if (connectionCode.value != null || _sessions.value.any { !it.historical }) CloudSyncWork.schedule(context)
        }
    }

    suspend fun awaitReady() { ready.await() }

    @Synchronized fun foreground(active: Boolean) {
        if (!active) {
            foregroundJob?.cancel(); foregroundJob = null
            if (connectionCode.value != null || _sessions.value.any { !it.historical }) CloudSyncWork.enqueue(context)
            return
        }
        if (foregroundJob?.isActive == true) return
        foregroundJob = scope.launch(Dispatchers.IO) {
            awaitReady()
            while (isActive) { syncAll(); delay(45_000) }
        }
    }

    suspend fun syncAll(): Boolean = withContext(Dispatchers.IO) {
        awaitReady()
        syncAllMutex.withLock {
            var ok = discoverAccess()
            for (session in _sessions.value.filterNot { it.historical }) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                sync(session)
                val status = session.status.value
                if (!session.historical && (status.syncing || status.issue != null || status.photoIssue != null || status.lastSuccess == 0L)) ok = false
            }
            ok
        }
    }

    suspend fun importInvitation(bytes: ByteArray, confirmedAdmin: String) = withContext(Dispatchers.IO) {
        awaitReady()
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
            if (pinned == null) check(prefs.edit().putString("admin", invitation.admin).putString("connection_code", confirmedAdmin).commit())
            connectionCode.value = confirmedAdmin
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
        CloudSyncWork.schedule(context)
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
            val repo = ShoppingRepository(context, database, profile, isolatedWorkspace = true, workspaceCatalog = catalog,
                onWorkspaceMutation = {
                    if (_sessions.value.any { it.localKey == name && it.writable }) CloudSyncWork.enqueue(context)
                    if (widgetKey.value == name) scope.launch { refreshWidgets() }
                })
            if (needsProfile && grant.canWrite) repo.saveProfile(invitation.userName)
            if (invitation.checkpoint.isNotEmpty()) {
                val checkpoint = CloudEpochCheckpoint.open(invitation.checkpoint, invitation.signed.manifest,
                    invitation.open(id, CloudDeviceIdentity.encryptionPair().private))
                repo.applyRemote(checkpoint)
            }
            val session = CloudSpaceSession(invitation, repo, grant, database, profile, catalog)
            session.status.value = CloudSpaceStatus(lastSuccess = prefs.getLong("last_sync_" + name, 0L))
            session.historical = epoch < prefs.getLong("minimum_epoch_" + invitation.space.key, 1L)
            _sessions.value = _sessions.value + session
            markHistorical()
        } catch (e: Exception) { database.close(); throw e }
    }

    private fun markHistorical() {
        val latest = _sessions.value.groupBy { it.space }.mapValues { (_, sessions) -> sessions.maxOf { it.epoch } }
        _sessions.value.forEach { if (it.epoch < latest.getValue(it.space)) it.historical = true }
    }

    suspend fun latePlan(source: CloudSpaceSession, target: CloudSpaceSession): CloudLatePlan = withContext(Dispatchers.IO) {
        require(source.historical && target.writable && source.space == target.space && source.epoch < target.epoch)
        require(source.grant.canWrite && source.grant.userId == target.grant.userId)
        val baseline = try {
            CloudJournalStore(context, epochNamespace = source.epoch).read(source.space).state.snapshot
        } catch (e: java.io.FileNotFoundException) {
            // Before the first ever sync the authenticated invitation is the baseline.
            if (source.invitation.checkpoint.isEmpty()) com.mok.buylist.sync.Snapshot()
            else CloudEpochCheckpoint.open(source.invitation.checkpoint, source.invitation.signed.manifest,
                source.invitation.open(CloudDeviceIdentity.fingerprint(), CloudDeviceIdentity.encryptionPair().private))
        }
        CloudLateChanges.plan(baseline, source.repository.exportSnapshot(), target.repository.exportSnapshot())
    }

    suspend fun transferPhotos(source: CloudSpaceSession, target: CloudSpaceSession): Int = withContext(Dispatchers.IO) {
        require(source.historical && target.writable && source.space == target.space && source.epoch < target.epoch)
        require(source.grant.canWrite && source.grant.userId == target.grant.userId)
        var count = 0
        val products = target.repository.exportSnapshot().customProducts.filterNot { it.deleted }
        for (product in products) {
            val photo = com.mok.buylist.ui.CustomProductPreviews.cloudPhoto(context, product.id, source.localKey) ?: continue
            if (com.mok.buylist.ui.CustomProductPreviews.cloudPhoto(context, product.id, target.localKey) == null) {
                com.mok.buylist.ui.CustomProductPreviews.installCloudPhoto(context, photo, target.localKey)
                count++
            }
        }
        requestSync(target)
        count
    }

    suspend fun transferLate(source: CloudSpaceSession, target: CloudSpaceSession, plan: CloudLatePlan): Boolean {
        require(source.historical && target.writable && source.space == target.space && source.epoch < target.epoch)
        require(source.grant.canWrite && source.grant.userId == target.grant.userId)
        require(plan.conflicts == 0) { "Сначала разрешите конфликты" }
        val applied = withContext(Dispatchers.IO) {
            target.mutex.withLock {
                require(target.writable)
                target.repository.applyLateChanges(plan)
            }
        }
        if (applied) {
            requestSync(target)
            if (widgetKey.value == target.localKey) refreshWidgets()
        }
        return applied
    }

    fun requestSync(session: CloudSpaceSession) {
        if (!session.historical) CloudSyncWork.enqueue(context)
    }

    suspend fun edit(session: CloudSpaceSession, action: suspend (ShoppingRepository) -> Unit) {
        require(session.writable)
        // Room writes remain available offline; applyRemote merges in a transaction.
        withContext(Dispatchers.IO) { action(session.repository) }
        requestSync(session)
        if (widgetKey.value == session.localKey) refreshWidgets()
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
            var photoIssue: String? = null
            FileRepositoryBuilder().setGitDir(directory).setBare().build().use { git ->
                if (!git.objectDatabase.exists()) git.create(true)
                val transport = CloudGitEvents(git, CloudGitSsh.REMOTE, CloudGitSsh.configure)
                val control = transport.readControl(session.space)?.verify(invitation.admin, session.space)
                    ?: throw CloudAdminException("Доступ к списку недоступен.")
                if (!control.matches(invitation.signed.manifest)) {
                    require(control.epoch >= session.epoch) { "Обнаружена устаревшая версия доступа" }
                    if (control.epoch > session.epoch || control.retired) {
                        val minimum = if (control.retired) Math.addExact(control.epoch, 1L) else control.epoch
                        val keyName = "minimum_epoch_" + session.space.key
                        check(prefs.edit().putLong(keyName, maxOf(minimum, prefs.getLong(keyName, 1L))).commit())
                        session.historical = true
                        if (widgetKey.value == session.localKey) refreshWidgets()
                        session.status.value = previous.copy(syncing = false, issue = "Доступ к прежней версии закрыт.")
                        return
                    }
                    throw CloudAdminException("Доступ к списку изменился.")
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
                try { CloudPhotoSync.sync(context, session, transport, identity, key) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { photoIssue = "Не все фотографии обновлены. Повторите синхронизацию." }
            }
            val syncedAt = System.currentTimeMillis()
            prefs.edit().putLong("last_sync_" + session.localKey, syncedAt).apply()
            session.status.value = CloudSpaceStatus(lastSuccess = syncedAt, photoIssue = photoIssue)
            if (widgetKey.value == session.localKey) refreshWidgets()
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
