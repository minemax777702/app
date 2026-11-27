package com.mok.buylist.cloud

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec

/** Device identity only; it is NOT yet a GitHub deploy key or an access credential. */
internal object CloudDeviceIdentity {
    private const val ALIAS = "buylist.cloud.device.signing.v1"

    @Synchronized fun request(now: Long = System.currentTimeMillis()): String {
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
        return CloudDeviceProtocol.create(public, private, now)
    }
}
