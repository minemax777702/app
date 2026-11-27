package com.mok.buylist.cloud

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Manual backup, deliberately distinct from the registry recovery format. */
internal object CloudPurchaseBackupCodec {
    private const val MAGIC = 0x424C5031
    private const val ITERATIONS = 600_000
    private const val HEADER_SIZE = 32
    const val MAX_PAYLOAD_BYTES = 512 * 1024
    const val MAX_FILE_BYTES = MAX_PAYLOAD_BYTES + HEADER_SIZE + 16
    private val aad = "BuyList|manual-purchase-backup|1|minemax777702/app-cloud".toByteArray(Charsets.UTF_8)

    private fun derive(password: CharArray, salt: ByteArray): SecretKeySpec {
        require(password.size in 16..256) { "Пароль должен содержать от 16 до 256 символов" }
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            try { SecretKeySpec(bytes, "AES") } finally { bytes.fill(0) }
        } finally { spec.clearPassword() }
    }

    fun encrypt(plain: ByteArray, password: CharArray): ByteArray {
        require(plain.size in 1..MAX_PAYLOAD_BYTES) { "Размер копии превышает 512 КиБ" }
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, derive(password, salt), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(plain)
        return ByteBuffer.allocate(HEADER_SIZE + encrypted.size).putInt(MAGIC).put(salt).put(iv).put(encrypted).array()
    }

    fun decrypt(file: ByteArray, password: CharArray): ByteArray {
        require(file.size in HEADER_SIZE + 17..MAX_FILE_BYTES)
        val header = ByteBuffer.wrap(file)
        require(header.int == MAGIC)
        val salt = ByteArray(16).also { header.get(it) }
        val iv = ByteArray(12).also { header.get(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, derive(password, salt), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(file, HEADER_SIZE, file.size - HEADER_SIZE).also { require(it.size in 1..MAX_PAYLOAD_BYTES) }
    }
}
