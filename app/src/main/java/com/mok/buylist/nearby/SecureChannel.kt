package com.mok.buylist.nearby

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import javax.crypto.KeyAgreement
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

interface IdentityKeys {
    val ownFingerprint: String
    fun publicKey(fingerprint: String): PublicKey?
    fun sign(bytes: ByteArray): ByteArray
}

/** Authenticated ephemeral ECDH (P-256) + HKDF-SHA256 + AES-256-GCM. All messages are length bounded. */
class SecureChannel(private val identity: IdentityKeys) {
    private val ephemeral: KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    private val nonce = ByteArray(32).also(SecureRandom()::nextBytes)
    private val fingerprint = identity.ownFingerprint
    private var sendKey: SecretKeySpec? = null
    private var receiveKey: SecretKeySpec? = null
    private var prefix: ByteArray? = null
    private var sendCounter = 0L
    private var receiveCounter = -1L

    /** Signed hello binds an ephemeral key, a random nonce and the long-term QR-pinned identity. */
    fun hello(): ByteArray {
        val body = pack { out ->
            out.writeUTF("BLN1")
            out.writeUTF(fingerprint)
            out.write(nonce)
            writeBytes(out, ephemeral.public.encoded)
        }
        return pack { out -> writeBytes(out, body); writeBytes(out, identity.sign(body)) }
    }

    fun acceptHello(message: ByteArray): String {
        check(sendKey == null) { "Сеанс уже установлен" }
        require(message.size <= 2048) { "Слишком длинное приветствие" }
        val stream = DataInputStream(ByteArrayInputStream(message))
        val signed = readBytes(stream, 1024)
        val signature = readBytes(stream, 512)
        require(stream.available() == 0) { "Дополнительные данные" }
        val input = DataInputStream(ByteArrayInputStream(signed))
        require(input.readUTF() == "BLN1") { "Версия протокола не поддерживается" }
        val peerId = input.readUTF()
        require(peerId.length == 64 && peerId != fingerprint) { "Неверное устройство" }
        val peerNonce = ByteArray(32).also(input::readFully)
        val remoteEphemeral = readBytes(input, 256)
        require(input.available() == 0 && !peerNonce.contentEquals(nonce)) { "Повторное сообщение" }
        val trustedKey = identity.publicKey(peerId) ?: error("Нет доверия к этому устройству")
        require(Signature.getInstance("SHA256withECDSA").run { initVerify(trustedKey); update(signed); verify(signature) }) { "Подпись устройства неверна" }
        val remoteKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(remoteEphemeral))
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(ephemeral.private)
        agreement.doPhase(remoteKey, true)
        val secret = agreement.generateSecret()
        val ids = listOf(fingerprint, peerId).sorted()
        val saltBytes = if (fingerprint == ids.first()) nonce + peerNonce else peerNonce + nonce
        val salt = sha256("BLN1-salt".toByteArray() + saltBytes)
        val info = ("BLN1-data:" + ids.joinToString(":")).toByteArray()
        // Разные ключи для каждого направления исключают отражение своих сообщений.
        val directional = hkdf(secret, salt, info, 64)
        val first = SecretKeySpec(directional.copyOfRange(0, 32), "AES")
        val second = SecretKeySpec(directional.copyOfRange(32, 64), "AES")
        sendKey = if (fingerprint == ids.first()) first else second
        receiveKey = if (fingerprint == ids.first()) second else first
        directional.fill(0)
        prefix = sha256("BLN1-aad".toByteArray() + salt + info)
        secret.fill(0)
        return peerId
    }

    fun encrypt(plaintext: ByteArray): ByteArray {
        val cipherKey = requireNotNull(sendKey)
        require(plaintext.size <= 64 * 1024) { "Слишком большой блок" }
        val sequence = sendCounter++
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, cipherKey, GCMParameterSpec(128, iv))
        cipher.updateAAD(requireNotNull(prefix) + longBytes(sequence))
        val encrypted = cipher.doFinal(plaintext)
        return pack { it.writeLong(sequence); it.write(iv); writeBytes(it, encrypted) }
    }

    fun decrypt(message: ByteArray): ByteArray {
        val cipherKey = requireNotNull(receiveKey)
        require(message.size <= 66 * 1024) { "Слишком большой блок" }
        val input = DataInputStream(ByteArrayInputStream(message))
        val sequence = input.readLong()
        require(sequence > receiveCounter && sequence >= 0) { "Повтор или неправильный порядок" }
        val iv = ByteArray(12).also(input::readFully)
        val bytes = readBytes(input, 65552)
        require(input.available() == 0) { "Лишние данные" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, cipherKey, GCMParameterSpec(128, iv))
        cipher.updateAAD(requireNotNull(prefix) + longBytes(sequence))
        val plaintext = cipher.doFinal(bytes)
        receiveCounter = sequence
        return plaintext
    }

    companion object {
        private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
        private fun longBytes(n: Long) = java.nio.ByteBuffer.allocate(8).putLong(n).array()
        private fun pack(action: (DataOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use(action)
        }.toByteArray()
        private fun writeBytes(out: DataOutputStream, bytes: ByteArray) { out.writeInt(bytes.size); out.write(bytes) }
        private fun readBytes(input: DataInputStream, max: Int): ByteArray {
            val n = input.readInt()
            require(n in 0..max && n <= input.available()) { "Неверная длина" }
            return ByteArray(n).also(input::readFully)
        }
        /** RFC 5869, HKDF-SHA256. */
        private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, size: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(salt, "HmacSHA256"))
            val prk = mac.doFinal(ikm)
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            val output = ByteArrayOutputStream()
            var previous = ByteArray(0)
            var counter = 1
            while (output.size() < size) {
                previous = mac.doFinal(previous + info + byteArrayOf(counter.toByte()))
                output.write(previous)
                counter++
            }
            previous.fill(0)
            prk.fill(0)
            return output.toByteArray().copyOf(size)
        }
    }
}
