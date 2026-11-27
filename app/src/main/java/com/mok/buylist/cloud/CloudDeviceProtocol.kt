package com.mok.buylist.cloud

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

internal data class CloudDeviceRequest(val publicKey: String, val createdAt: Long, val signature: String, val encryptionPublicKey: String = "") {
    val fingerprint: String get() = CloudDeviceProtocol.fingerprint(publicKey)
}

internal object CloudDeviceProtocol {
    private const val PREFIX = "BLCLD1:"
    private const val PREFIX_V2 = "BLCLD2:"
    const val TTL_MILLIS = 10 * 60 * 1000L
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun publicKey(encoded: String): PublicKey {
        require(encoded.length in 1..256)
        val bytes = decoder.decode(encoded)
        require(encoder.encodeToString(bytes) == encoded)
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes)) as ECPublicKey
        // Compare the entire named-curve domain, not just a field size.
        val params = java.security.AlgorithmParameters.getInstance("EC").apply {
            init(java.security.spec.ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(java.security.spec.ECParameterSpec::class.java)
        require(key.params.curve == params.curve && key.params.generator == params.generator &&
            key.params.order == params.order && key.params.cofactor == params.cofactor)
        return key
    }

    fun fingerprint(encoded: String): String = MessageDigest.getInstance("SHA-256")
        .digest(publicKey(encoded).encoded).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun payload(key: String, time: Long, encryption: String): ByteArray = ByteArrayOutputStream().also { out ->
        DataOutputStream(out).use {
            it.writeUTF(if (encryption.isEmpty()) "BuyList|device-request|1|minemax777702/app-cloud" else "BuyList|device-request|2|minemax777702/app-cloud")
            it.writeUTF(key)
            it.writeLong(time)
            if (encryption.isNotEmpty()) it.writeUTF(encryption)
        }
    }.toByteArray()

    fun create(public: PublicKey, private: PrivateKey, now: Long, encryption: String = ""): String {
        val key = encoder.encodeToString(public.encoded)
        publicKey(key)
        if (encryption.isNotEmpty()) CloudInviteCrypto.encryptionKey(encryption)
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(private)
            update(payload(key, now, encryption))
        }.sign()
        return (if (encryption.isEmpty()) PREFIX else PREFIX_V2) + encoder.encodeToString(ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use {
                it.writeUTF(key)
                it.writeLong(now)
                it.writeUTF(encoder.encodeToString(signature))
                if (encryption.isNotEmpty()) it.writeUTF(encryption)
            }
        }.toByteArray())
    }

    fun parse(qr: String, now: Long): CloudDeviceRequest {
        val v2 = qr.startsWith(PREFIX_V2)
        require((v2 || qr.startsWith(PREFIX)) && qr.length <= 2048)
        val bytes = decoder.decode(qr.removePrefix(if (v2) PREFIX_V2 else PREFIX))
        val request = DataInputStream(ByteArrayInputStream(bytes)).use {
            val result = CloudDeviceRequest(it.readUTF(), it.readLong(), it.readUTF(), if (v2) it.readUTF() else "")
            require(it.read() == -1)
            result
        }
        requireFresh(request, now)
        return request
    }

    fun requireFresh(request: CloudDeviceRequest, now: Long) {
        require(now >= TTL_MILLIS && request.createdAt in (now - TTL_MILLIS)..(now + 60_000L)) {
            "Срок QR-кода истёк"
        }
        require(request.signature.length in 1..128)
        if (request.encryptionPublicKey.isNotEmpty()) CloudInviteCrypto.encryptionKey(request.encryptionPublicKey)
        val valid = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(publicKey(request.publicKey))
            update(payload(request.publicKey, request.createdAt, request.encryptionPublicKey))
        }.verify(decoder.decode(request.signature))
        require(valid) { "Подпись QR-кода неверна" }
    }
}
