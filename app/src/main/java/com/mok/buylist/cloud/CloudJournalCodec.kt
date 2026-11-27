package com.mok.buylist.cloud

import com.mok.buylist.sync.SyncCodec
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Persist a verified manifest binding and a snapshot/cursor pair as one authenticated record. */
internal data class CloudJournalRecord(
    val state: CloudWorkspaceState,
    val pinnedAdmin: String,
    val manifestHash: String,
) {
    init {
        CloudDeviceProtocol.publicKey(pinnedAdmin)
        require(manifestHash.matches(Regex("[0-9a-f]{64}")))
        require(state.epoch > 0 && state.manifestRevision > 0)
        require(state.cursors.size <= 1000 && state.cursors.keys.all { it.matches(Regex("[0-9a-f]{64}")) })
    }

    fun accept(event: CloudSignedEvent, signed: SignedCloudManifest, key: SecretKey): CloudJournalRecord {
        val manifest = signed.verify(pinnedAdmin, state.space, state.manifestRevision)
        val digest = hash(manifest.canonical())
        require(manifest.revision != state.manifestRevision || digest == manifestHash) { "Conflicting manifest revision" }
        val updated = state.accept(event, signed, pinnedAdmin, key)
        if (updated == state) return this
        return copy(state = updated, manifestHash = digest)
    }

    companion object {
        fun initialize(space: CloudSpace, signed: SignedCloudManifest, pinnedAdmin: String): CloudJournalRecord {
            val manifest = signed.verify(pinnedAdmin, space, 1)
            return CloudJournalRecord(CloudWorkspaceState(space, manifest.epoch, manifest.revision),
                pinnedAdmin, hash(manifest.canonical()))
        }

        private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

internal object CloudJournalCodec {
    private const val MAGIC = 0x424A5231
    private const val MAX_SNAPSHOT = 16 * 1024 * 1024
    private const val MAX_PLAIN = MAX_SNAPSHOT + 128 * 1024
    const val MAX_FILE = MAX_PLAIN + 29

    private fun aad(space: CloudSpace) = "BuyList|local-journal|1|minemax777702/app-cloud|${space.key}".toByteArray(Charsets.UTF_8)

    fun encrypt(record: CloudJournalRecord, key: SecretKey): ByteArray {
        val state = record.state
        val snapshot = SyncCodec.encode(state.snapshot)
        val plain = try { CloudWire.bytes {
            writeInt(MAGIC)
            CloudWire.writeSpace(this, state.space)
            writeLong(state.epoch)
            writeLong(state.manifestRevision)
            writeUTF(record.pinnedAdmin)
            writeUTF(record.manifestHash)
            writeInt(state.cursors.size)
            state.cursors.toSortedMap().forEach { (device, cursor) ->
                writeUTF(device)
                writeLong(cursor.sequence)
                writeUTF(cursor.digest)
            }
            CloudWire.writeBlob(this, snapshot, MAX_SNAPSHOT)
        } } finally { snapshot.fill(0) }
        try {
            require(plain.size <= MAX_PLAIN)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            require(cipher.iv.size == 12)
            cipher.updateAAD(aad(state.space))
            return byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)
        } finally { plain.fill(0) }
    }

    fun decrypt(expectedSpace: CloudSpace, bytes: ByteArray, key: SecretKey): CloudJournalRecord {
        require(bytes.size in 29..MAX_FILE && bytes[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD(aad(expectedSpace))
        val plain = cipher.doFinal(bytes, 13, bytes.size - 13)
        try {
            return CloudWire.read(plain, MAX_PLAIN) {
                require(readInt() == MAGIC)
                val space = CloudWire.readSpace(this)
                require(space == expectedSpace)
                val epoch = readLong()
                val revision = readLong()
                val admin = readUTF()
                val hash = readUTF()
                val count = readInt()
                require(count in 0..1000)
                val cursors = linkedMapOf<String, CloudCursor>()
                repeat(count) {
                    val device = readUTF()
                    val cursor = CloudCursor(readLong(), readUTF())
                    require(cursors.put(device, cursor) == null)
                }
                val snapshot = CloudWire.readBlob(this, MAX_SNAPSHOT)
                try { CloudJournalRecord(CloudWorkspaceState(space, epoch, revision, SyncCodec.decode(snapshot), cursors), admin, hash) }
                finally { snapshot.fill(0) }
            }
        } finally { plain.fill(0) }
    }
}
