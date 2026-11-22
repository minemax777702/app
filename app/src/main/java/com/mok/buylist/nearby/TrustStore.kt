package com.mok.buylist.nearby

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/** Отдельный ключ установки приложения; идентификатор пользователя не является идентификатором устройства. */
class TrustStore(context: Context) : IdentityKeys {
    private val prefs = context.getSharedPreferences("nearby_trust_v1", Context.MODE_PRIVATE)
    private val alias = "buylist_nearby_identity_v1"
    private val keyPair: KeyPair by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val old = store.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
        if (old != null) KeyPair(old.certificate.publicKey, old.privateKey)
        else KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false).build())
        }.generateKeyPair()
    }

    override val ownFingerprint: String get() = fingerprint(keyPair.public.encoded)
    val pairingCode: String get() = "BLPAIR1:" + Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP or Base64.URL_SAFE)

    /** Вторая сторона должна независимо отсканировать наш QR, прежде чем ей будет доверено. */
    fun trustQr(text: String): String? {
        if (!text.startsWith("BLPAIR1:") || text.length > 2000) return null
        return try {
            val encoded = Base64.decode(text.removePrefix("BLPAIR1:"), Base64.URL_SAFE or Base64.NO_WRAP)
            val key = decode(encoded)
            if (key.algorithm != "EC" || fingerprint(encoded) == ownFingerprint) null
            else fingerprint(encoded).also {
                prefs.edit().putString(it, Base64.encodeToString(encoded, Base64.NO_WRAP)).apply()
            }
        } catch (_: Exception) { null }
    }

    fun trusted(): List<String> = prefs.all.keys.sorted()
    fun remove(fingerprint: String) { prefs.edit().remove(fingerprint).apply() }
    override fun publicKey(fingerprint: String): PublicKey? = prefs.getString(fingerprint, null)?.let {
        runCatching { decode(Base64.decode(it, Base64.NO_WRAP)) }.getOrNull()
    }
    override fun sign(bytes: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(keyPair.private); update(bytes); sign()
    }

    companion object {
        fun fingerprint(encoded: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(encoded)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        fun decode(bytes: ByteArray): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
        fun verify(key: PublicKey, payload: ByteArray, signature: ByteArray): Boolean =
            runCatching { Signature.getInstance("SHA256withECDSA").run {
                initVerify(key); update(payload); verify(signature)
            } }.getOrDefault(false)
    }
}
