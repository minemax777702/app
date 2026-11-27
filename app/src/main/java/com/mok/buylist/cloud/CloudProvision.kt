package com.mok.buylist.cloud

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.PrivateKey
import java.security.Signature
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.security.spec.ECGenParameterSpec
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

internal object CloudInviteCrypto {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    fun encode(bytes: ByteArray) = encoder.encodeToString(bytes)
    fun decode(text: String): ByteArray = decoder.decode(text).also { require(encode(it) == text) }
    fun encryptionKey(text: String): RSAPublicKey {
        require(text.length in 1..600)
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(decode(text))) as RSAPublicKey
        require(key.modulus.bitLength() == 2048 && key.publicExponent == java.math.BigInteger.valueOf(65537))
        return key
    }
    private val oaep = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
    fun wrap(key: PublicKey, bytes: ByteArray): ByteArray =
        Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").run {
            init(Cipher.ENCRYPT_MODE, key, oaep); doFinal(bytes)
        }
    fun unwrap(key: PrivateKey, bytes: ByteArray): ByteArray =
        Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").run {
            init(Cipher.DECRYPT_MODE, key, oaep); doFinal(bytes)
        }
    fun sign(key: PrivateKey, bytes: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(key); update(bytes); sign()
    }
    fun verify(key: String, bytes: ByteArray, signature: ByteArray): Boolean = Signature.getInstance("SHA256withECDSA").run {
        initVerify(CloudDeviceProtocol.publicKey(key)); update(bytes); verify(signature)
    }
}

/** Stored only inside the authenticated admin registry/recovery file. */
internal data class CloudAdminSigner(val publicKey: String, val privateKey: String) {
    init {
        CloudDeviceProtocol.publicKey(publicKey)
        require(privateKey.length in 1..512)
    }
    fun private(): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(CloudInviteCrypto.decode(privateKey)))
    companion object {
        fun create(): CloudAdminSigner {
            val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            return CloudAdminSigner(CloudInviteCrypto.encode(pair.public.encoded), CloudInviteCrypto.encode(pair.private.encoded))
        }
    }
}

internal data class CloudProvision(val manifest: String, val secret: String) {
    fun signed() = SignedCloudManifest.decode(CloudInviteCrypto.decode(manifest))
    val space get() = signed().manifest.space
    fun key() = CloudInviteCrypto.decode(secret).let {
        require(it.size == 32)
        try { SecretKeySpec(it, "AES") } finally { it.fill(0) }
    }
}

internal fun CloudUserRegistry.grantsFor(space: CloudSpace): List<CloudGrant> {
    val roles = when (space.kind) {
        CloudSpaceKind.PERSONAL -> {
            require(users.any { it.id == space.id && !it.archived }) { "Пользователь недоступен" }
            mapOf(space.id to CloudGroupRole.OWNER)
        }
        CloudSpaceKind.GROUP -> groups.singleOrNull { it.id == space.id && !it.archived }?.members
            ?: throw IllegalArgumentException("Группа недоступна")
    }
    return devices.filter { !it.revoked && it.userId in roles }.map {
        CloudGrant(it.userId, it.publicKey, roles.getValue(it.userId))
    }.sortedBy { it.deviceId }
}

internal fun CloudUserRegistry.canWriteDevice(id: String): Boolean =
    provisions.any { provision -> provision.signed().manifest.grants.any { it.deviceId == id && it.canWrite } }

internal fun CloudUserRegistry.prepareSpace(space: CloudSpace): CloudUserRegistry {
    if (provisions.any { it.space == space }) return this
    val signer = adminSigner ?: CloudAdminSigner.create()
    val grants = grantsFor(space)
    require(grants.isNotEmpty() && grants.any { it.role == CloudGroupRole.OWNER }) {
        "Сначала подключите устройство владельца"
    }
    if (space.kind == CloudSpaceKind.GROUP) {
        val group = groups.single { it.id == space.id }
        require(group.members.keys.all { id -> grants.any { it.userId == id } }) {
            "Сначала зарегистрируйте устройства всех участников группы"
        }
    }
    val manifest = SignedCloudManifest.sign(CloudManifest(space, 1, 1, grants), signer.private())
    val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
    val provision = try { CloudProvision(CloudInviteCrypto.encode(manifest.encode()), CloudInviteCrypto.encode(raw)) }
        finally { raw.fill(0) }
    return copy(revision = Math.addExact(revision, 1), adminSigner = signer, provisions = provisions + provision)
}

/** One signed, recipient-bound invitation. The workspace AES key is RSA-OAEP wrapped. */
internal data class CloudInvitation(
    val admin: String,
    val recipient: String,
    val userName: String,
    val label: String,
    val signed: SignedCloudManifest,
    val wrapped: ByteArray,
    val signature: ByteArray,
) {
    val space get() = signed.manifest.space
    val adminFingerprint get() = CloudDeviceProtocol.fingerprint(admin)
    private fun body(): ByteArray = CloudWire.bytes {
        writeUTF("BuyList|invitation|1|minemax777702/app-cloud")
        writeUTF(admin); writeUTF(recipient); writeUTF(userName); writeUTF(label)
        CloudWire.writeBlob(this, signed.encode(), 32768)
        CloudWire.writeBlob(this, wrapped, 256)
    }
    fun verify(deviceId: String): CloudGrant {
        require(recipient == deviceId && userName.length in 1..50 && label.length in 1..100)
        require(CloudInviteCrypto.verify(admin, body(), signature)) { "Подпись приглашения неверна" }
        val manifest = signed.verify(admin, space, 1)
        return manifest.grants.singleOrNull { it.deviceId == recipient }
            ?: throw IllegalArgumentException("Приглашение для другого устройства")
    }
    fun open(deviceId: String, privateKey: PrivateKey): SecretKeySpec {
        verify(deviceId)
        val bytes = CloudInviteCrypto.unwrap(privateKey, wrapped)
        return try { require(bytes.size == 32); SecretKeySpec(bytes, "AES") } finally { bytes.fill(0) }
    }
    fun encode(): ByteArray = CloudWire.bytes {
        writeInt(0x42495631)
        CloudWire.writeBlob(this, body(), MAX_FILE - 128)
        CloudWire.writeBlob(this, signature, 80)
    }
    companion object {
        const val MAX_FILE = 36_864
        fun decode(bytes: ByteArray): CloudInvitation = CloudWire.read(bytes, MAX_FILE) {
            require(readInt() == 0x42495631)
            val body = CloudWire.readBlob(this, MAX_FILE - 128)
            val signature = CloudWire.readBlob(this, 80)
            CloudWire.read(body, MAX_FILE - 128) {
                require(readUTF() == "BuyList|invitation|1|minemax777702/app-cloud")
                CloudInvitation(readUTF(), readUTF(), readUTF(), readUTF(),
                    SignedCloudManifest.decode(CloudWire.readBlob(this, 32768)),
                    CloudWire.readBlob(this, 256), signature)
            }
        }
        fun create(registry: CloudUserRegistry, space: CloudSpace, device: CloudDeviceDraft): CloudInvitation {
            val provision = registry.provisions.single { it.space == space }
            val signer = requireNotNull(registry.adminSigner)
            val user = registry.users.single { it.id == device.userId && !it.archived }
            val label = if (space.kind == CloudSpaceKind.PERSONAL) "Личный список" else registry.groups.single { it.id == space.id }.name
            require(!device.revoked)
            require(provision.signed().manifest.grants.any { it.deviceId == device.fingerprint })
            val rsa = CloudInviteCrypto.encryptionKey(device.encryptionPublicKey)
            val raw = CloudInviteCrypto.decode(provision.secret)
            val wrapped = try { CloudInviteCrypto.wrap(rsa, raw) } finally { raw.fill(0) }
            val unsigned = CloudInvitation(signer.publicKey, device.fingerprint, user.name, label, provision.signed(), wrapped, byteArrayOf())
            return unsigned.copy(signature = CloudInviteCrypto.sign(signer.private(), unsigned.body())).also { it.verify(device.fingerprint) }
        }
    }
}
