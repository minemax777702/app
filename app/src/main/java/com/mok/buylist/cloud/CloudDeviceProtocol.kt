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

internal data class CloudDeviceRequest(val publicKey: String, val createdAt: Long, val signature: String) {
    val fingerprint: String get() = CloudDeviceProtocol.fingerprint(publicKey)
}

internal object CloudDeviceProtocol {
    private const val PREFIX = "BLCLD1:"
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

    private fun payload(key: String, time: Long): ByteArray = ByteArrayOutputStream().also { out ->
        DataOutputStream(out).use {
            it.writeUTF("BuyList|device-request|1|minemax777702/app-cloud")
            it.writeUTF(key)
            it.writeLong(time)
        }
    }.toByteArray()

    fun create(public: PublicKey, private: PrivateKey, now: Long): String {
        val key = encoder.encodeToString(public.encoded)
        publicKey(key)
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(private)
            update(payload(key, now))
        }.sign()
        return PREFIX + encoder.encodeToString(ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use {
                it.writeUTF(key)
                it.writeLong(now)
                it.writeUTF(encoder.encodeToString(signature))
            }
        }.toByteArray())
    }

    fun parse(qr: String, now: Long): CloudDeviceRequest {
        require(qr.startsWith(PREFIX) && qr.length <= 1024)
        val bytes = decoder.decode(qr.removePrefix(PREFIX))
        val request = DataInputStream(ByteArrayInputStream(bytes)).use {
            val result = CloudDeviceRequest(it.readUTF(), it.readLong(), it.readUTF())
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
        val valid = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(publicKey(request.publicKey))
            update(payload(request.publicKey, request.createdAt))
        }.verify(decoder.decode(request.signature))
        require(valid) { "Подпись QR-кода неверна" }
    }
}
