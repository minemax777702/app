package com.mok.buylist.data

import android.content.Context
import android.util.AtomicFile
import com.mok.buylist.sync.Snapshot
import com.mok.buylist.sync.SyncCodec
import java.io.File
import java.util.UUID

/** Локальные снимки не заменяют копию вне устройства. */
data class LocalBackup(val id: String, val createdAt: Long, val items: Int, val archives: Int)

class LocalBackups(private val context: Context) {
    private val dir get() = File(context.filesDir, "local-backups").also { it.mkdirs() }
    private val prefs get() = context.getSharedPreferences("local-backups", Context.MODE_PRIVATE)
    var limit: Int
        get() = prefs.getInt("limit", 10).coerceIn(1, 20)
        set(value) { synchronized(this) { prefs.edit().putInt("limit", value.coerceIn(1, 20)).commit(); prune() } }

    private fun write(file: File, snapshot: Snapshot) {
        val atomic = AtomicFile(file)
        val bytes = SyncCodec.encode(snapshot)
        val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) } catch (e: Exception) { atomic.failWrite(output); throw e }
    }

    @Synchronized fun saveCurrent(snapshot: Snapshot) = write(File(dir, "current.bin"), snapshot)
    @Synchronized fun saveHistory(snapshot: Snapshot) {
        val file = File(dir, "${System.currentTimeMillis()}-${UUID.randomUUID()}.bin")
        write(file, snapshot)
        prune()
    }
    @Synchronized fun list(): List<LocalBackup> = historyFiles().mapNotNull { file ->
        runCatching {
            val snapshot = SyncCodec.decode(file.readBytes())
            LocalBackup(file.name, file.name.substringBefore('-').toLong(), snapshot.items.size, snapshot.archives.size)
        }.getOrNull()
    }
    @Synchronized fun read(id: String): Snapshot {
        require(id == "current.bin" || historyFiles().any { it.name == id })
        return SyncCodec.decode(File(dir, id).readBytes())
    }
    private fun historyFiles(): List<File> = dir.listFiles()?.filter { it.name.matches(Regex("[0-9]+-[0-9a-f-]+\\.bin")) }
        ?.sortedByDescending { it.name } ?: emptyList()
    private fun prune() { historyFiles().drop(limit).forEach { it.delete() } }
}
