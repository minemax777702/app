package com.mok.buylist.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Separate per-space encrypted state. Never imports the default Room database.
 * Process-local lock covers reading, verification, merge and AtomicFile commit.
 * No automatic initialization after corruption or missing key.
 */
internal class CloudJournalStore(context: Context, private val testRun: UUID? = null, private val epochNamespace: Long = 1) {
    private val directory = File(context.applicationContext.noBackupFilesDir,
        if (testRun == null) (if (epochNamespace == 1L) "cloud-journals-v1" else "cloud-journals-v1/epoch-$epochNamespace") else "cloud-journal-tests/$testRun")
    private fun alias(space: CloudSpace): String =
        if (testRun == null) "buylist.cloud.journal.v1." + (if (epochNamespace == 1L) "" else "epoch-$epochNamespace.") + space.key else "buylist.cloud.journal.test.$testRun." + space.key
    private fun file(space: CloudSpace): AtomicFile {
        check(directory.isDirectory || directory.mkdirs())
        return AtomicFile(File(directory, space.key.replace(':', '-') + ".bin"))
    }

    private fun key(space: CloudSpace, create: Boolean): SecretKey {
        val alias = alias(space)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "Workspace key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    private fun loadLocked(space: CloudSpace): CloudJournalRecord {
        val bytes = file(space).openRead().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(out.size() + count <= CloudJournalCodec.MAX_FILE)
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
        return CloudJournalCodec.decrypt(space, bytes, key(space, false))
    }

    private fun saveLocked(record: CloudJournalRecord, create: Boolean) {
        val space = record.state.space
        val bytes = CloudJournalCodec.encrypt(record, key(space, create))
        val atomic = file(space)
        val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) }
        catch (e: Exception) { atomic.failWrite(output); throw e }
    }

    /** Explicit provisioning only. Existing, interrupted or corrupt files must not be replaced. */
    suspend fun initialize(space: CloudSpace, manifest: SignedCloudManifest, pinnedAdmin: String): CloudJournalRecord =
        withContext(Dispatchers.IO) {
            synchronized(LOCK) {
                val atomic = file(space)
                check(!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists() &&
                    !File(atomic.baseFile.path + ".new").exists()) { "Workspace already initialized or recovery required" }
                val record = CloudJournalRecord.initialize(space, manifest, pinnedAdmin)
                saveLocked(record, true)
                record
            }
        }

    suspend fun ensureInitialized(space: CloudSpace, manifest: SignedCloudManifest, pinnedAdmin: String, checkpoint: com.mok.buylist.sync.Snapshot = com.mok.buylist.sync.Snapshot()): CloudJournalRecord =
        withContext(Dispatchers.IO) {
            synchronized(LOCK) {
                val atomic = file(space)
                val existing = atomic.baseFile.exists() || File(atomic.baseFile.path + ".bak").exists()
                val initial = CloudJournalRecord.initialize(space, manifest, pinnedAdmin)
                val binding = initial.copy(state = initial.state.copy(snapshot = checkpoint))
                if (existing) {
                    val record = loadLocked(space)
                    require(record.pinnedAdmin == pinnedAdmin && record.manifestHash == binding.manifestHash &&
                        record.state.epoch == binding.state.epoch && record.state.manifestRevision == binding.state.manifestRevision)
                    record
                } else {
                    check(!File(atomic.baseFile.path + ".new").exists()) { "Interrupted journal creation" }
                    saveLocked(binding, true)
                    binding
                }
            }
        }

    suspend fun read(space: CloudSpace): CloudJournalRecord = withContext(Dispatchers.IO) {
        synchronized(LOCK) { loadLocked(space) }
    }

    suspend fun accept(space: CloudSpace, eventBytes: ByteArray, manifestBytes: ByteArray, contentKey: SecretKey): CloudJournalRecord =
        withContext(Dispatchers.IO) {
            synchronized(LOCK) {
                val current = loadLocked(space)
                val next = current.accept(CloudSignedEvent.decode(eventBytes), SignedCloudManifest.decode(manifestBytes), contentKey)
                if (next != current) saveLocked(next, false)
                next
            }
        }

    /** The diagnostic can delete only its own random test namespace, never a real workspace. */
    suspend fun cleanTest(space: CloudSpace) = withContext(Dispatchers.IO) {
        check(testRun != null)
        synchronized(LOCK) {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            store.deleteEntry(alias(space))
            file(space).delete()
            directory.delete()
        }
    }

    private companion object { val LOCK = Any() }
}
