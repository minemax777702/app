package com.mok.buylist.cloud

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.UUID

/** Stores the exact already encrypted/signed envelope before the first network attempt. */
internal class CloudOutbox(context: Context, testRun: UUID? = null, epochNamespace: Long = 1) {
    private val directory = File(context.applicationContext.noBackupFilesDir,
        if (testRun == null) (if (epochNamespace == 1L) "cloud-outbox-v1" else "cloud-outbox-v1/epoch-$epochNamespace") else "cloud-outbox-tests/$testRun")

    private fun file(space: CloudSpace, device: String): AtomicFile {
        require(device.matches(Regex("[0-9a-f]{64}")))
        check(directory.isDirectory || directory.mkdirs())
        return AtomicFile(File(directory, space.key.replace(':', '-') + "-" + device + ".bin"))
    }

    fun pending(space: CloudSpace, device: String): CloudSignedEvent? {
        val atomic = file(space, device)
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) {
            // A first write interrupted before finishWrite was never submitted to the network.
            check(!File(atomic.baseFile.path + ".new").exists()) { "Interrupted outbox creation; recovery required" }
            return null
        }
        val bytes = atomic.openRead().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                require(out.size() + n <= CloudSignedEvent.MAX_DELTA_BYTES + 1024)
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
        return CloudSignedEvent.decode(bytes).also {
            require(it.header.space == space && it.header.deviceId == device)
        }
    }

    fun stage(event: CloudSignedEvent) {
        val header = event.header
        val previous = pending(header.space, header.deviceId)
        if (previous != null) {
            require(previous.digest == event.digest) { "An unconfirmed event must be delivered first" }
            return
        }
        val atomic = file(header.space, header.deviceId)
        val output = atomic.startWrite()
        try { output.write(event.encode()); atomic.finishWrite(output) }
        catch (e: Exception) { atomic.failWrite(output); throw e }
    }

    fun acknowledge(space: CloudSpace, device: String, cursor: CloudCursor) {
        val event = pending(space, device) ?: return
        require(event.header.sequence == cursor.sequence && event.digest == cursor.digest)
        val atomic = file(space, device)
        atomic.delete()
        check(!atomic.baseFile.exists()) { "Could not clear confirmed outbox" }
    }
}
