package com.mok.buylist.cloud

import com.mok.buylist.sync.Snapshot
import com.mok.buylist.sync.SyncCodec
import com.mok.buylist.sync.SyncMerge
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Protocol primitives only. No network, Room writes, or credentials are issued here. */
internal enum class CloudSpaceKind { PERSONAL, GROUP }

internal data class CloudSpace(val kind: CloudSpaceKind, val id: String) {
    init { require(UUID.fromString(id).toString() == id) }
    val key: String get() = "${kind.name.lowercase(java.util.Locale.ROOT)}:$id"
}

internal data class CloudGrant(val userId: String, val publicKey: String, val role: CloudGroupRole) {
    init {
        require(UUID.fromString(userId).toString() == userId)
        CloudDeviceProtocol.publicKey(publicKey)
    }
    val deviceId: String get() = CloudDeviceProtocol.fingerprint(publicKey)
    val canWrite: Boolean get() = role != CloudGroupRole.VIEWER
}

internal data class CloudManifest(
    val space: CloudSpace,
    val epoch: Long,
    val revision: Long,
    val grants: List<CloudGrant>,
) {
    init {
        require(epoch > 0 && revision > 0)
        require(grants.size in 1..CloudUserRegistry.MAX_DEVICES)
        require(grants.map { it.deviceId }.distinct().size == grants.size)
        require(grants.groupBy { it.userId }.values.all { rows -> rows.map { it.role }.distinct().size == 1 })
        require(grants.filter { it.role == CloudGroupRole.OWNER }.map { it.userId }.distinct().size == 1)
        require(space.kind != CloudSpaceKind.PERSONAL || grants.all { it.userId == space.id && it.role == CloudGroupRole.OWNER })
    }

    fun canonical(): ByteArray = cloudBytes {
        writeUTF("BuyList|manifest|1|minemax777702/app-cloud")
        writeUTF(space.key)
        writeLong(epoch)
        writeLong(revision)
        writeInt(grants.size)
        grants.sortedBy { it.deviceId }.forEach {
            writeUTF(it.userId)
            writeUTF(it.publicKey)
            writeByte(it.role.wire)
        }
    }
}

internal class SignedCloudManifest private constructor(
    val manifest: CloudManifest,
    private val signature: ByteArray,
) {
    fun verify(adminPublicKey: String, expectedSpace: CloudSpace, minRevision: Long): CloudManifest {
        require(manifest.space == expectedSpace && manifest.revision >= minRevision) { "Wrong workspace or manifest rollback" }
        require(cloudVerify(adminPublicKey, manifest.canonical(), signature)) { "Invalid admin signature" }
        return manifest
    }

    companion object {
        fun sign(manifest: CloudManifest, adminKey: PrivateKey): SignedCloudManifest {
            // Defensive copies: callers must not mutate a previously signed grant list.
            val immutable = manifest.copy(grants = manifest.grants.toList())
            return SignedCloudManifest(immutable, cloudSign(adminKey, immutable.canonical()))
        }
    }
}

internal data class CloudEventHeader(
    val space: CloudSpace,
    val epoch: Long,
    val manifestRevision: Long,
    val deviceId: String,
    val sequence: Long,
    val previousHash: String,
) {
    init {
        require(epoch > 0 && manifestRevision > 0 && sequence > 0)
        require(deviceId.matches(HEX_64))
        require(if (sequence == 1L) previousHash.isEmpty() else previousHash.matches(HEX_64))
    }
    fun canonical(): ByteArray = cloudBytes {
        writeUTF("BuyList|delta|1|minemax777702/app-cloud")
        writeUTF(space.key)
        writeLong(epoch)
        writeLong(manifestRevision)
        writeUTF(deviceId)
        writeLong(sequence)
        writeUTF(previousHash)
    }
}

/** Immutable authenticated delta. The signature also covers the GCM nonce and ciphertext. */
internal class CloudSignedEvent private constructor(
    val header: CloudEventHeader,
    private val iv: ByteArray,
    private val encrypted: ByteArray,
    private val signature: ByteArray,
) {
    private fun signedBytes(): ByteArray = cloudBytes {
        val aad = header.canonical()
        writeInt(aad.size)
        write(aad)
        write(iv)
        writeInt(encrypted.size)
        write(encrypted)
    }

    val digest: String get() = MessageDigest.getInstance("SHA-256")
        .digest(cloudBytes { write(signedBytes()); writeInt(signature.size); write(signature) })
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun open(manifest: CloudManifest, key: SecretKey): Snapshot {
        require(header.space == manifest.space && header.epoch == manifest.epoch &&
            header.manifestRevision == manifest.revision) { "Workspace, key epoch or manifest changed" }
        val grant = manifest.grants.singleOrNull { it.deviceId == header.deviceId && it.canWrite }
            ?: error("Device is not allowed to write")
        require(cloudVerify(grant.publicKey, signedBytes(), signature)) { "Invalid event signature" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(header.canonical())
        val plain = cipher.doFinal(encrypted)
        return try {
            require(plain.size <= MAX_DELTA_BYTES)
            SyncCodec.decode(plain)
        } finally { plain.fill(0) }
    }

    companion object {
        const val MAX_DELTA_BYTES = 512 * 1024

        fun seal(header: CloudEventHeader, delta: Snapshot, key: SecretKey, devicePrivateKey: PrivateKey): CloudSignedEvent {
            val plain = SyncCodec.encode(delta)
            try {
                require(plain.size <= MAX_DELTA_BYTES)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key)
                require(cipher.iv.size == 12)
                cipher.updateAAD(header.canonical())
                val encrypted = cipher.doFinal(plain)
                val unsigned = CloudSignedEvent(header, cipher.iv, encrypted, byteArrayOf())
                return CloudSignedEvent(header, cipher.iv, encrypted, cloudSign(devicePrivateKey, unsigned.signedBytes()))
            } finally { plain.fill(0) }
        }
    }
}

internal data class CloudCursor(val sequence: Long = 0, val digest: String = "") {
    init {
        require(sequence >= 0)
        require(if (sequence == 0L) digest.isEmpty() else digest.matches(HEX_64))
    }
}

/** State for ONE workspace. Persistence of snapshot and cursor must be atomic before use in production. */
internal data class CloudWorkspaceState(
    val space: CloudSpace,
    val epoch: Long,
    val manifestRevision: Long,
    val snapshot: Snapshot = Snapshot(),
    val cursors: Map<String, CloudCursor> = emptyMap(),
) {
    fun accept(event: CloudSignedEvent, signed: SignedCloudManifest, pinnedAdmin: String, key: SecretKey): CloudWorkspaceState {
        val manifest = signed.verify(pinnedAdmin, space, manifestRevision)
        require(manifest.epoch == epoch) { "Key epoch change requires a verified checkpoint" }
        require(event.header.space == space && event.header.epoch == epoch)
        require(event.header.manifestRevision == manifest.revision)
        val cursor = cursors[event.header.deviceId] ?: CloudCursor()
        if (event.header.sequence == cursor.sequence && event.digest == cursor.digest) {
            // Exact replay is a no-op, but the writer and manifest still need to be valid.
            require(manifest.grants.any { it.deviceId == event.header.deviceId && it.canWrite })
            return this
        }
        require(event.header.sequence == Math.addExact(cursor.sequence, 1L) && event.header.previousHash == cursor.digest) {
            "Replay, missing event or divergent device history"
        }
        val delta = event.open(manifest, key)
        return copy(manifestRevision = manifest.revision,
            snapshot = SyncMerge.merge(snapshot, delta),
            cursors = cursors + (event.header.deviceId to CloudCursor(event.header.sequence, event.digest)))
    }
}

private val HEX_64 = Regex("[0-9a-f]{64}")

private fun cloudBytes(block: DataOutputStream.() -> Unit): ByteArray =
    ByteArrayOutputStream().also { out -> DataOutputStream(out).use { block(it) } }.toByteArray()

private fun cloudSign(key: PrivateKey, message: ByteArray): ByteArray =
    Signature.getInstance("SHA256withECDSA").apply { initSign(key); update(message) }.sign()

private fun cloudVerify(key: String, message: ByteArray, signature: ByteArray): Boolean =
    Signature.getInstance("SHA256withECDSA").apply {
        initVerify(CloudDeviceProtocol.publicKey(key)); update(message)
    }.verify(signature)
