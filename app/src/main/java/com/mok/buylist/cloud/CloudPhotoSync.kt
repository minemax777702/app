package com.mok.buylist.cloud

import android.content.Context
import com.mok.buylist.ui.CustomProductPreviews
import java.security.KeyPair
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.ensureActive
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class CloudPhoto(val product: String, val at: Long, val jpeg: ByteArray) {
    init {
        require(UUID.fromString(product).toString() == product)
        require(at > 0 && jpeg.size in 4..MAX_JPEG)
        require(jpeg[0] == 0xff.toByte() && jpeg[1] == 0xd8.toByte())
    }
    val digest: String get() = MessageDigest.getInstance("SHA-256").digest(jpeg)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    fun compare(other: CloudPhoto): Int = compareValuesBy(this, other, { it.at }, { it.digest })
    companion object { const val MAX_JPEG = 256 * 1024 }
}

internal object CloudPhotoWire {
    const val MAX_FILE = CloudPhoto.MAX_JPEG + 2048
    private fun header(manifest: CloudManifest, writer: String, product: String, at: Long) = CloudWire.bytes {
        writeUTF("BuyList|photo|1|minemax777702/app-cloud")
        CloudWire.writeSpace(this, manifest.space)
        writeLong(manifest.epoch); writeLong(manifest.revision); writeUTF(writer); writeUTF(product); writeLong(at)
    }
    fun seal(photo: CloudPhoto, manifest: CloudManifest, key: SecretKey, identity: KeyPair, writer: String): ByteArray {
        require(manifest.grants.any { it.deviceId == writer && it.canWrite })
        val header = header(manifest, writer, photo.product, photo.at)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        require(cipher.iv.size == 12)
        cipher.updateAAD(header)
        val body = CloudWire.bytes {
            CloudWire.writeBlob(this, header, 1024)
            write(cipher.iv)
            CloudWire.writeBlob(this, cipher.doFinal(photo.jpeg), CloudPhoto.MAX_JPEG + 16)
        }
        return CloudWire.bytes {
            CloudWire.writeBlob(this, body, MAX_FILE - 128)
            CloudWire.writeBlob(this, CloudInviteCrypto.sign(identity.private, body), 80)
        }
    }
    fun open(bytes: ByteArray, manifest: CloudManifest, key: SecretKey, writer: String, product: String): CloudPhoto =
        CloudWire.read(bytes, MAX_FILE) {
            val body = CloudWire.readBlob(this, MAX_FILE - 128)
            val signature = CloudWire.readBlob(this, 80)
            val grant = manifest.grants.single { it.deviceId == writer && it.canWrite }
            require(CloudInviteCrypto.verify(grant.publicKey, body, signature))
            CloudWire.read(body, MAX_FILE - 128) {
                val aad = CloudWire.readBlob(this, 1024)
                val at = CloudWire.read(aad, 1024) {
                    require(readUTF() == "BuyList|photo|1|minemax777702/app-cloud")
                    require(CloudWire.readSpace(this) == manifest.space && readLong() == manifest.epoch && readLong() == manifest.revision)
                    require(readUTF() == writer && readUTF() == product)
                    readLong()
                }
                val iv = ByteArray(12).also { readFully(it) }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                cipher.updateAAD(aad)
                CloudPhoto(product, at, cipher.doFinal(CloudWire.readBlob(this, CloudPhoto.MAX_JPEG + 16)))
            }
        }
}

internal object CloudPhotoSync {
    suspend fun sync(context: Context, session: CloudSpaceSession, transport: CloudGitEvents,
                     identity: KeyPair, key: SecretKey) {
        val manifest = session.invitation.signed.manifest
        val writer = CloudDeviceIdentity.fingerprint()
        val products = session.repository.exportSnapshot().customProducts.filterNot { it.deleted }
            .mapTo(HashSet()) { it.id }
        val remote = transport.readPhotos(manifest, products, key)
        for (id in products.sorted()) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val local = CustomProductPreviews.cloudPhoto(context, id, session.localKey)
            val received = remote[id]
            if (received != null && (local == null || received.compare(local) > 0)) {
                CustomProductPreviews.installCloudPhoto(context, received, session.localKey)
            } else if (session.writable && local != null && (received == null || local.compare(received) > 0)) {
                transport.publishPhoto(manifest, local, writer, key, identity)
            }
        }
    }
}
