package com.mok.buylist.cloud

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** A portable encrypted copy, tied to the exact remote file SHA. No PAT or private device key. */
internal data class CloudRegistryRecovery(val registry: CloudUserRegistry, val sha: String)

internal object CloudRecoveryCodec {
    private const val MAGIC = 0x42435231
    private const val ITERATIONS = 600_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val HEADER_BYTES = 4 + SALT_BYTES + IV_BYTES
    const val MAX_FILE_BYTES = CloudRegistryCodec.MAX_BYTES + 256
    private val aad = "BuyList|registry-recovery|1|minemax777702/app-cloud".toByteArray(Charsets.UTF_8)

    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        require(password.size in 16..256) { "Пароль должен содержать от 16 до 256 символов" }
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return try { SecretKeySpec(bytes, "AES") } finally { bytes.fill(0) }
        } finally { spec.clearPassword() }
    }

    fun encode(copy: CloudRegistryRecovery, password: CharArray): ByteArray {
        require(copy.sha.matches(Regex("[0-9a-f]{40}")))
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val registry = CloudRegistryCodec.encode(copy.registry)
        val plain = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use { data ->
                data.writeUTF(copy.sha)
                data.write(registry)
            }
        }.toByteArray()
        registry.fill(0)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            val encrypted = cipher.doFinal(plain)
            return ByteArrayOutputStream().also { out ->
                DataOutputStream(out).use { it.writeInt(MAGIC); it.write(salt); it.write(iv); it.write(encrypted) }
            }.toByteArray().also { require(it.size <= MAX_FILE_BYTES) }
        } finally { plain.fill(0) }
    }

    fun decode(file: ByteArray, password: CharArray): CloudRegistryRecovery {
        require(file.size in HEADER_BYTES + 16..MAX_FILE_BYTES)
        return DataInputStream(ByteArrayInputStream(file)).use { input ->
            require(input.readInt() == MAGIC)
            val salt = ByteArray(SALT_BYTES).also(input::readFully)
            val iv = ByteArray(IV_BYTES).also(input::readFully)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            val plain = cipher.doFinal(file, HEADER_BYTES, file.size - HEADER_BYTES)
            try {
                DataInputStream(ByteArrayInputStream(plain)).use { data ->
                    val sha = data.readUTF()
                    require(sha.matches(Regex("[0-9a-f]{40}")))
                    val payload = ByteArray(data.available()).also(data::readFully)
                    try { CloudRegistryRecovery(CloudRegistryCodec.decode(payload), sha) }
                    finally { payload.fill(0) }
                }
            } finally { plain.fill(0) }
        }
    }
}
