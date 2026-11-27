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
    fun request(now: Long = System.currentTimeMillis()): String {
        val keys = keyPair()
        return CloudDeviceProtocol.create(keys.public, keys.private, now)
    }
}
