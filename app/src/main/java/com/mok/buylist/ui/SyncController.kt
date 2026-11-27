package com.mok.buylist.ui

import com.mok.buylist.data.ShoppingRepository
import com.mok.buylist.data.SyncSummary
import com.mok.buylist.sync.BackupContents
import com.mok.buylist.sync.BackupCodec
import com.mok.buylist.sync.SyncFormatException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SyncController(
    private val repository: ShoppingRepository,
    private val scope: CoroutineScope,
) {
    enum class Notice { BACKUP_SAVED, BACKUP_FAILED, RESTORED, RESTORE_FAILED, UNDONE, UNDO_FAILED, LOCAL_BACKUP_FAILED }
    data class State(
        val undoMinutes: Int? = null,
        val pendingRestoreOwner: String? = null,
        val notice: Notice? = null,
    )
    private val _state = MutableStateFlow(State(undoMinutes = repository.undoAvailableMinutes()))
    val state: StateFlow<State> = _state
    private val applyMutex = Mutex()
    private var pendingRestore: BackupContents? = null

    // --- Откат ---

    fun undo() {
        scope.launch {
            val ok = withContext(NonCancellable) { repository.undoLastSync() }
            _state.update {
                State(
                    undoMinutes = repository.undoAvailableMinutes(),
                    notice = if (ok) Notice.UNDONE else Notice.UNDO_FAILED,
                )
            }
        }
    }

    // --- Резервная копия ---

    suspend fun backupBytes(): ByteArray = repository.exportBackup()

    fun onBackupWritten(ok: Boolean) {
        _state.update { it.copy(notice = if (ok) Notice.BACKUP_SAVED else Notice.BACKUP_FAILED) }
    }

    /** Прочитан файл копии ([bytes] == null — не удалось прочитать). */
    fun onBackupRead(bytes: ByteArray?) {
        val contents = try {
            bytes?.let { BackupCodec.decode(it) }
        } catch (e: SyncFormatException) {
            null
        }
        if (contents == null) {
            _state.update { it.copy(notice = Notice.RESTORE_FAILED) }
            return
        }
        // Копия, сделанная под другим идентификатором: спрашиваем, это тот же человек (новый телефон) или другой.
        val foreign = contents.ownerId.isNotEmpty() && contents.ownerId != repository.currentUserId
        if (foreign) {
            pendingRestore = contents
            _state.update { it.copy(pendingRestoreOwner = contents.ownerName.ifEmpty { "?" }) }
        } else {
            restore(contents, asMe = false)
        }
    }

    fun confirmRestore(asMe: Boolean) {
        val contents = pendingRestore ?: return
        pendingRestore = null
        _state.update { it.copy(pendingRestoreOwner = null) }
        restore(contents, asMe)
    }

    fun cancelRestore() {
        pendingRestore = null
        _state.update { it.copy(pendingRestoreOwner = null) }
    }

    private fun restore(contents: BackupContents, asMe: Boolean) {
        scope.launch {
            withContext(NonCancellable) {
                applyMutex.withLock {
                    repository.saveUndoPoint()
                    repository.restoreBackup(contents, asMe)
                }
            }
            _state.update {
                it.copy(notice = Notice.RESTORED, undoMinutes = repository.undoAvailableMinutes())
            }
        }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }
}
