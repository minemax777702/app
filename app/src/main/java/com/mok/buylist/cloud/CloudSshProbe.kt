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

    private class KeyIdentity(private val keys: Keys, private val trace: CloudSshTrace) : Identity {
        override fun setPassphrase(passphrase: ByteArray?): Boolean = true
        override fun getPublicKeyBlob(): ByteArray = CloudSshWire.publicBlob(keys.public)
        override fun getSignature(data: ByteArray): ByteArray = CloudSshWire.sign(keys.private, data).also {
            trace.step("Подпись SSH создана в Android Keystore")
        }
        override fun getSignature(data: ByteArray, alg: String): ByteArray? =
            if (alg == CloudSshWire.ALGORITHM) getSignature(data) else null
        override fun getAlgName(): String = CloudSshWire.ALGORITHM
        override fun getName(): String = ALIAS
        override fun isEncrypted(): Boolean = false
        override fun clear() = Unit
    }

    internal fun openSession(port: Int, pair: java.security.KeyPair? = null): com.jcraft.jsch.Session =
        connect(port, pair, CloudSshTrace())

    private fun connect(port: Int, pair: java.security.KeyPair?, trace: CloudSshTrace): com.jcraft.jsch.Session {
        require(port == 22 || port == 443)
        val host = if (port == 443) "ssh.github.com" else "github.com"
        val knownHost = if (port == 443) "[ssh.github.com]:443" else "github.com"
        val jsch = JSch().apply { setInstanceLogger(trace.logger()) }
        // GitHub official ECDSA key. On a legitimate rotation, fail closed until app update.
        jsch.setKnownHosts(ByteArrayInputStream("$knownHost ${CloudSshWire.ALGORITHM} $HOST_KEY\n".toByteArray(Charsets.US_ASCII)))
        val identity = if (pair == null) keys() else Keys(pair.public as ECPublicKey, pair.private)
        trace.step("Открытый ключ загружен")
        val challenge = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val signature = try {
            java.security.Signature.getInstance("SHA256withECDSA").apply { initSign(identity.private); update(challenge) }.sign()
        } catch (e: Exception) {
            throw CloudAdminException(trace.failure(port, "SSH-01", "Не удалось подписать данные ключом Android Keystore.", e))
        }
        val valid = java.security.Signature.getInstance("SHA256withECDSA").apply {
            initVerify(identity.public); update(challenge)
        }.verify(signature)
        if (!valid) throw CloudAdminException(trace.failure(port, "SSH-01", "Закрытый и открытый ключи не совпадают."))
        trace.step("Локальная проверка подписи пройдена")
        jsch.addIdentity(KeyIdentity(identity, trace), null)
        val session = jsch.getSession("git", host, port)
        try {
            session.setConfig("StrictHostKeyChecking", "yes")
            session.setConfig("server_host_key", CloudSshWire.ALGORITHM)
            session.setConfig("kex", "ecdh-sha2-nistp256")
            // Probe only algorithms actually negotiated; avoid optional desktop algorithms.
            session.setConfig("CheckCiphers", "aes128-ctr")
            session.setConfig("CheckKexes", "ecdh-sha2-nistp256")
            session.setConfig("CheckSignatures", CloudSshWire.ALGORITHM)
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
                val causes = generateSequence<Throwable>(e) { it.cause }.take(5).toList()
                val (code, reason) = when {
                    causes.any { it is java.net.UnknownHostException } -> "SSH-02" to "Не удалось разрешить DNS-имя сервера."
                    causes.any { it is java.net.ConnectException || it is java.net.NoRouteToHostException } -> "SSH-03" to "TCP-порт недоступен."
                    detail.contains("Auth fail", true) || detail.contains("Auth cancel", true) -> "SSH-06" to "Авторизация ключа не прошла. Сверьте отпечаток в Deploy keys app-cloud."
                    detail.contains("HostKey", true) || detail.contains("host key", true) -> "SSH-05" to "Ключ сервера не подтверждён. Проверка не отключена."
                    detail.contains("negotiation", true) || causes.any { it is java.security.GeneralSecurityException } -> "SSH-04" to "Не удалось согласовать или выполнить криптографические параметры SSH."
                    detail.contains("timeout", true) || causes.any { it is java.net.SocketTimeoutException } -> "SSH-03" to "Истекло время ожидания; последний успешный этап указан выше."
                    else -> "SSH-04" to "Не удалось завершить установление SSH-сеанса."
                }
                throw CloudAdminException(trace.failure(port, code, reason, e))
            }
            trace.step("Авторизация SSH подтверждена")
            return session
        } catch (e: Exception) {
            session.disconnect()
            throw e
        }
    }

    suspend fun check(port: Int, registeredDevice: Boolean = false): String = withContext(Dispatchers.IO) {
        val trace = CloudSshTrace()
        val session = try {
            connect(port, if (registeredDevice) CloudDeviceIdentity.keyPair() else null, trace)
        } catch (e: CloudAdminException) { throw e }
        catch (e: Exception) { throw CloudAdminException(trace.failure(port, "SSH-01", "Не удалось подготовить ключ или SSH-клиент.", e)) }
        catch (e: LinkageError) { throw CloudAdminException(trace.failure(port, "SSH-04", "SSH-клиент несовместим с этой сборкой Android.", e)) }
        try {
            val channel = session.openChannel("exec") as ChannelExec
            try {
                channel.setCommand("git-upload-pack 'minemax777702/app-cloud.git'")
                channel.setInputStream(null)
                // Drain stderr without retaining arbitrary remote messages.
                channel.setErrStream(object : java.io.OutputStream() {
                    override fun write(value: Int) = Unit
                    override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
                })
                val input = channel.inputStream
                channel.connect(TIMEOUT)
                trace.step("Открыт канал чтения Git")
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
                            if (n < 0) throw CloudAdminException(trace.failure(port, "SSH-07", "Git прервал передачу."))
                            count += n
                        } else {
                            if (channel.isClosed || android.os.SystemClock.elapsedRealtime() >= deadline)
                                throw CloudAdminException(trace.failure(port, "SSH-07", if (channel.isClosed) "GitHub закрыл канал чтения репозитория (exit=${channel.exitStatus})." else "Нет ответа Git до истечения срока ожидания."))
                            delay(25)
                        }
                    }
                    return bytes
                }
                val header = readExact(4)
                val length = header.toString(Charsets.US_ASCII).toIntOrNull(16)
                    ?: throw CloudAdminException(trace.failure(port, "SSH-08", "Некорректный заголовок ответа Git."))
                require(length == 0 || length in 4..65_520)
                val first = readExact(maxOf(0, length - 4))
                val line = first.toString(Charsets.UTF_8)
                if (length != 0 && (line.startsWith("ERR ") || !line.take(40).matches(Regex("[0-9a-f]{40}")))) {
                    throw CloudAdminException(trace.failure(port, "SSH-08", "Git не подтвердил чтение ссылок app-cloud."))
                }
                trace.step("Репозиторий app-cloud доступен для чтения")
                "SSH $port · Успешно\n" + trace.report()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: CloudAdminException) { throw e }
            catch (e: Exception) { throw CloudAdminException(trace.failure(port, "SSH-07", "Ошибка чтения Git.", e)) }
            finally { channel.disconnect() }
        } finally { session.disconnect() }
    }
}
