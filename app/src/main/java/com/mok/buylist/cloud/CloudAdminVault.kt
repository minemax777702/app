package com.mok.buylist.cloud

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.annotation.RequiresApi
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only ciphertext is stored, outside Android backup and device transfer. */
internal class CloudAdminVault(context: Context) {
    private val app = context.applicationContext
    private val file = AtomicFile(File(app.noBackupFilesDir, "cloud-admin-v1.bin"))

    val supported: Boolean
        get() = Build.VERSION.SDK_INT >= 30 &&
            app.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    val exists: Boolean get() = file.baseFile.exists()

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @RequiresApi(30)
    fun prepareSave(): Cipher {
        check(supported)
        val store = keyStore()
        val key = (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                    .setRandomizedEncryptionRequired(true)
                    .build())
            }.generateKey()
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    fun save(cipher: Cipher, token: String) {
        require(token.isNotBlank() && token.length <= 512)
        val plain = token.toByteArray(Charsets.UTF_8)
        val encrypted = try {
            cipher.updateAAD(AAD)
            cipher.doFinal(plain)
        } finally { plain.fill(0) }
        val iv = cipher.iv
        require(iv.size == 12)
        val output = file.startWrite()
        try {
            output.write(byteArrayOf(1))
            output.write(iv)
            output.write(encrypted)
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
    }

    internal data class Unlock(val cipher: Cipher, val encrypted: ByteArray)

    @RequiresApi(30)
    fun prepareUnlock(): Unlock {
        check(supported)
        val bytes = file.openRead().use { input ->
            val buffer = ByteArray(4097)
            var size = 0
            while (size < buffer.size) {
                val count = input.read(buffer, size, buffer.size - size)
                if (count < 0) break
                size += count
            }
            buffer.copyOf(size)
        }
        require(bytes.size in 30..4096 && bytes[0] == 1.toByte()) { "Invalid vault" }
        val key = keyStore().getKey(ALIAS, null) as? SecretKey ?: error("Key unavailable")
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        }
        return Unlock(cipher, bytes.copyOfRange(13, bytes.size))
    }

    fun unlock(cipher: Cipher, encrypted: ByteArray): String {
        cipher.updateAAD(AAD)
        val plain = cipher.doFinal(encrypted)
        return try {
            plain.toString(Charsets.UTF_8).also { require(it.isNotBlank() && it.length <= 512) }
        } finally { plain.fill(0) }
    }

    fun forget() {
        // Destroy the key as well as the encrypted record.
        keyStore().deleteEntry(ALIAS)
        file.delete()
        check(!exists)
    }

    private companion object {
        const val ALIAS = "buylist.cloud.admin.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val AAD = "BuyList Cloud Admin v1".toByteArray(Charsets.UTF_8)
    }
}
