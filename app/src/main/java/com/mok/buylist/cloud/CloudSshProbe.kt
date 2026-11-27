package com.mok.buylist.cloud

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Identity
import com.jcraft.jsch.JSch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlin.coroutines.coroutineContext

/** Isolated, read-only Git transport diagnostic. No shell, clone, push, PAT or trust-on-first-use. */
internal object CloudSshProbe {
    private const val ALIAS = "buylist.cloud.ssh.test.v1"
    private const val HOST_KEY = "AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBEmKSENjQEezOmxkZMy7opKgwFB9nkt5YRrYMjNuG5N87uRgg6CLrbo5wAdT/y6v0mKV0U2w0WZ2YB/++Tpockg="
    private const val TIMEOUT = 12_000

    private data class Keys(val public: ECPublicKey, val private: PrivateKey)

    @Synchronized private fun keys(): Keys {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256).build())
            }.generateKeyPair()
        }
        return Keys(store.getCertificate(ALIAS).publicKey as ECPublicKey, store.getKey(ALIAS, null) as PrivateKey)
    }

    suspend fun publicKey(): String = withContext(Dispatchers.IO) { CloudSshWire.openSsh(keys().public) }

    private class KeyIdentity(private val keys: Keys) : Identity {
        override fun setPassphrase(passphrase: ByteArray?): Boolean = true
        override fun getPublicKeyBlob(): ByteArray = CloudSshWire.publicBlob(keys.public)
        override fun getSignature(data: ByteArray): ByteArray = CloudSshWire.sign(keys.private, data)
        override fun getSignature(data: ByteArray, alg: String): ByteArray? =
            if (alg == CloudSshWire.ALGORITHM) getSignature(data) else null
        override fun getAlgName(): String = CloudSshWire.ALGORITHM
        override fun getName(): String = ALIAS
        override fun isEncrypted(): Boolean = false
        override fun clear() = Unit
    }

    internal fun openSession(port: Int, pair: java.security.KeyPair? = null): com.jcraft.jsch.Session {
        require(port == 22 || port == 443)
        val host = if (port == 443) "ssh.github.com" else "github.com"
        val knownHost = if (port == 443) "[ssh.github.com]:443" else "github.com"
        val jsch = JSch()
        // GitHub official ECDSA key. On a legitimate rotation, fail closed until app update.
        jsch.setKnownHosts(ByteArrayInputStream("$knownHost ${CloudSshWire.ALGORITHM} $HOST_KEY\n".toByteArray(Charsets.US_ASCII)))
        jsch.addIdentity(KeyIdentity(if (pair == null) keys() else Keys(pair.public as ECPublicKey, pair.private)), null)
        val session = jsch.getSession("git", host, port)
        try {
            session.setConfig("StrictHostKeyChecking", "yes")
            session.setConfig("server_host_key", CloudSshWire.ALGORITHM)
            session.setConfig("kex", "ecdh-sha2-nistp256")
            session.setConfig("PreferredAuthentications", "publickey")
            session.setConfig("PubkeyAcceptedAlgorithms", CloudSshWire.ALGORITHM)
            session.setConfig("cipher.c2s", "aes128-ctr")
            session.setConfig("cipher.s2c", "aes128-ctr")
            session.setConfig("mac.c2s", "hmac-sha2-256")
            session.setConfig("mac.s2c", "hmac-sha2-256")
            session.setConfig("compression.c2s", "none")
            session.setConfig("compression.s2c", "none")
            session.setTimeout(TIMEOUT)
            try {
                session.connect(TIMEOUT)
            } catch (e: com.jcraft.jsch.JSchException) {
                val detail = e.message.orEmpty()
                val reason = when {
                    detail.contains("Auth fail", true) || detail.contains("Auth cancel", true) -> "Сервер доступен, но ключ не принят. Добавьте открытый ключ в Deploy keys репозитория app-cloud."
                    detail.contains("HostKey", true) || detail.contains("host key", true) -> "Ключ сервера не подтверждён. Не отключайте проверку подлинности."
                    detail.contains("negotiation", true) -> "Не удалось согласовать параметры SSH."
                    detail.contains("timeout", true) -> "Истекло время ожидания SSH."
                    else -> "Не удалось установить SSH-соединение."
                }
                throw CloudAdminException(reason)
            }
            return session
        } catch (e: Exception) {
            session.disconnect()
            throw e
        }
    }

    suspend fun check(port: Int, registeredDevice: Boolean = false): String = withContext(Dispatchers.IO) {
        val session = openSession(port, if (registeredDevice) CloudDeviceIdentity.keyPair() else null)
        try {
            val channel = session.openChannel("exec") as ChannelExec
            try {
                channel.setCommand("git-upload-pack 'minemax777702/app-cloud.git'")
                channel.setInputStream(null)
                val input = channel.inputStream
                channel.connect(TIMEOUT)
                // Read only the first Git pkt-line. Never download repository objects.
                val deadline = android.os.SystemClock.elapsedRealtime() + TIMEOUT
                suspend fun readExact(size: Int): ByteArray {
                    val bytes = ByteArray(size)
                    var count = 0
                    while (count < size) {
                        coroutineContext.ensureActive()
                        val available = input.available()
                        if (available > 0) {
                            val n = input.read(bytes, count, minOf(available, size - count))
                            if (n < 0) throw CloudAdminException("Git прервал передачу.")
                            count += n
                        } else {
                            if (channel.isClosed || android.os.SystemClock.elapsedRealtime() >= deadline)
                                throw CloudAdminException("Репозиторий недоступен или истёк срок ожидания.")
                            delay(25)
                        }
                    }
                    return bytes
                }
                val header = readExact(4)
                val length = header.toString(Charsets.US_ASCII).toIntOrNull(16)
                    ?: throw CloudAdminException("Некорректный ответ Git.")
                require(length == 0 || length in 4..65_520)
                val first = readExact(maxOf(0, length - 4))
                val line = first.toString(Charsets.UTF_8)
                if (length != 0 && (line.startsWith("ERR ") || !line.take(40).matches(Regex("[0-9a-f]{40}")))) {
                    throw CloudAdminException("Не удалось прочитать ссылки app-cloud.")
                }
                "SSH $port: ключ сервера подтверждён, репозиторий доступен для чтения."
            } finally { channel.disconnect() }
        } finally { session.disconnect() }
    }
}
