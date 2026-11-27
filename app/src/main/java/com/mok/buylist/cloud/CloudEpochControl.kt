package com.mok.buylist.cloud

import com.mok.buylist.sync.Snapshot
import com.mok.buylist.sync.SyncCodec
import java.security.MessageDigest
import java.security.PrivateKey
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Signed current epoch pointer. Git itself is not an authorization provider. */
internal data class CloudEpochControl(
    val space: CloudSpace,
    val epoch: Long,
    val revision: Long,
    val manifestHash: String,
    val retired: Boolean,
    val signature: ByteArray,
) {
    init {
        require(epoch > 0 && revision > 0 && manifestHash.matches(Regex("[0-9a-f]{64}")))
    }
    fun body(): ByteArray = CloudWire.bytes {
        writeUTF("BuyList|epoch-control|1|minemax777702/app-cloud")
        CloudWire.writeSpace(this, space)
        writeLong(epoch); writeLong(revision); writeUTF(manifestHash); writeBoolean(retired)
    }
    fun verify(admin: String, expected: CloudSpace): CloudEpochControl {
        require(space == expected && CloudInviteCrypto.verify(admin, body(), signature)) { "Недостоверная версия списка" }
        return this
    }
    fun matches(manifest: CloudManifest): Boolean = !retired && space == manifest.space &&
        epoch == manifest.epoch && revision == manifest.revision && manifestHash == hash(manifest.canonical())
    fun encode(): ByteArray = CloudWire.bytes {
        CloudWire.writeBlob(this, body(), 1024)
        CloudWire.writeBlob(this, signature, 80)
    }
    companion object {
        const val MAX_BYTES = 1200
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        fun branch(space: CloudSpace) = "refs/heads/cloud-control-v1/" + space.key.replace(':', '/')
        fun create(provision: CloudProvision, signer: CloudAdminSigner): CloudEpochControl {
            val m = provision.signed().manifest
            val unsigned = CloudEpochControl(m.space, m.epoch, m.revision, hash(m.canonical()), provision.retired, byteArrayOf())
            return unsigned.copy(signature = CloudInviteCrypto.sign(signer.signingKey(), unsigned.body()))
        }
        fun decode(bytes: ByteArray): CloudEpochControl = CloudWire.read(bytes, MAX_BYTES) {
            val body = CloudWire.readBlob(this, 1024)
            val signature = CloudWire.readBlob(this, 80)
            CloudWire.read(body, 1024) {
                require(readUTF() == "BuyList|epoch-control|1|minemax777702/app-cloud")
                CloudEpochControl(CloudWire.readSpace(this), readLong(), readLong(), readUTF(), readBoolean(), signature)
            }
        }
    }
}

/** Snapshot authenticated under the NEW key and bound to its signed manifest. */
internal object CloudEpochCheckpoint {
    const val MAX_BYTES = 512 * 1024 + 29
    private fun aad(manifest: CloudManifest) = CloudWire.bytes {
        writeUTF("BuyList|epoch-checkpoint|1|minemax777702/app-cloud")
        CloudWire.writeBlob(this, manifest.canonical(), 32768)
    }
    fun seal(snapshot: Snapshot, manifest: CloudManifest, key: SecretKey): ByteArray {
        val bytes = SyncCodec.encode(snapshot)
        try {
            require(bytes.size <= MAX_BYTES - 29) { "История слишком большая для переноса одним файлом" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            require(cipher.iv.size == 12)
            cipher.updateAAD(aad(manifest))
            return byteArrayOf(1) + cipher.iv + cipher.doFinal(bytes)
        } finally { bytes.fill(0) }
    }
    fun open(bytes: ByteArray, manifest: CloudManifest, key: SecretKey): Snapshot {
        require(bytes.size in 29..MAX_BYTES && bytes[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD(aad(manifest))
        val plain = cipher.doFinal(bytes, 13, bytes.size - 13)
        return try { SyncCodec.decode(plain) } finally { plain.fill(0) }
    }
}
