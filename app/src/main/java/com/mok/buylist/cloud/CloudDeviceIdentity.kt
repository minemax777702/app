package com.mok.buylist.cloud

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.KeyPair
import java.security.spec.ECGenParameterSpec

/** Device identity. Its public key may be explicitly authorized by the administrator. */
internal object CloudDeviceIdentity {
    private const val ALIAS = "buylist.cloud.device.signing.v1"

    @Synchronized fun keyPair(): KeyPair {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build())
            }.generateKeyPair()
        }
        val public = store.getCertificate(ALIAS).publicKey
        val private = store.getKey(ALIAS, null) as PrivateKey
        return KeyPair(public, private)
    }
    @Synchronized fun encryptionPair(): KeyPair {
        val alias = "buylist.cloud.device.encryption.v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP).build())
            }.generateKeyPair()
        }
        return KeyPair(store.getCertificate(alias).publicKey, store.getKey(alias, null) as PrivateKey)
    }

    fun fingerprint(): String = CloudDeviceProtocol.fingerprint(CloudInviteCrypto.encode(keyPair().public.encoded))

    fun request(now: Long = System.currentTimeMillis()): String {
        val keys = keyPair()
        return CloudDeviceProtocol.create(keys.public, keys.private, now, CloudInviteCrypto.encode(encryptionPair().public.encoded))
    }
}
