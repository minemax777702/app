package com.mok.buylist.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.coroutines.coroutineContext

internal data class LoadedCloudUsers(val registry: CloudUserRegistry, val sha: String?)

/** Non-exportable key for a preliminary admin-only registry. Recovery is not implemented. */
internal class CloudUserRegistryStore(context: Context) {
    private val app = context.applicationContext
    private val checkpoint = AtomicFile(File(app.noBackupFilesDir, "cloud-users-checkpoint-v1"))

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) throw CloudAdminException("Ключ реестра отсутствует. Откройте его на устройстве, где он был создан.")
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    private fun checkpointRevision(): Long =
        if (checkpoint.baseFile.exists()) checkpoint.openRead().use { it.bufferedReader().readText().toLong() } else -1L

    private fun rememberRevision(revision: Long) {
        val output = checkpoint.startWrite()
        try {
            output.write(revision.toString().toByteArray(Charsets.UTF_8))
            checkpoint.finishWrite(output)
        } catch (e: Exception) {
            checkpoint.failWrite(output)
            throw e
        }
    }

    suspend fun load(admin: GitHubCloudAdmin): LoadedCloudUsers = withContext(Dispatchers.IO) {
        val remote = admin.readUserRegistry()
        coroutineContext.ensureActive()
        if (remote == null) {
            if (checkpointRevision() >= 0) throw CloudAdminException("Реестр исчез из репозитория. Восстановите файл из истории GitHub; новый реестр не создан.")
            return@withContext LoadedCloudUsers(CloudUserRegistry(), null)
        }
        val registry = try { CloudRegistryCrypto.decrypt(key(false), remote.bytes) }
        catch (e: CloudAdminException) { throw e }
        catch (_: Exception) { throw CloudAdminException("Реестр не удалось проверить. Запись запрещена.") }
        if (registry.revision < checkpointRevision()) throw CloudAdminException("Обнаружена более старая версия реестра. Запись запрещена.")
        rememberRevision(registry.revision)
        LoadedCloudUsers(registry, remote.sha)
    }

    suspend fun save(admin: GitHubCloudAdmin, previous: LoadedCloudUsers, next: CloudUserRegistry): LoadedCloudUsers =
        withContext(Dispatchers.IO) {
            require(next.revision == Math.addExact(previous.registry.revision, 1))
            if (previous.sha == null && checkpointRevision() >= 0) throw CloudAdminException("Необходимо перечитать реестр.")
            val encrypted = CloudRegistryCrypto.encrypt(key(previous.sha == null), next)
            coroutineContext.ensureActive()
            val sha = admin.writeUserRegistry(encrypted, previous.sha)
            // If the network result is uncertain, the caller must reload before any subsequent write.
            rememberRevision(next.revision)
            LoadedCloudUsers(next, sha)
        }

    suspend fun exportRecovery(admin: GitHubCloudAdmin, password: CharArray): ByteArray =
        withContext(Dispatchers.IO) {
            val loaded = load(admin)
            val sha = loaded.sha ?: throw CloudAdminException("Сначала создайте реестр.")
            CloudRecoveryCodec.encode(CloudRegistryRecovery(loaded.registry, sha), password)
        }

    /**
     * Re-encrypt only the exact current remote registry on the new installation.
     * Never overwrite a newer remote version, or silently recreate a missing file.
     */
    suspend fun recover(admin: GitHubCloudAdmin, file: ByteArray, password: CharArray): LoadedCloudUsers =
        withContext(Dispatchers.IO) {
            val copy = try { CloudRecoveryCodec.decode(file, password) }
            catch (_: Exception) { throw CloudAdminException("Неверный пароль или повреждённый файл восстановления.") }
            val remote = admin.readUserRegistry()
                ?: throw CloudAdminException("Реестр не найден. Сначала восстановите соответствующий файл из истории GitHub.")
            if (remote.sha != copy.sha || copy.registry.revision < checkpointRevision())
                throw CloudAdminException("Копия не соответствует текущей версии реестра. Запись запрещена.")
            val next = copy.registry.copy(revision = Math.addExact(copy.registry.revision, 1))
            val encrypted = CloudRegistryCrypto.encrypt(key(true), next)
            coroutineContext.ensureActive()
            val sha = admin.writeUserRegistry(encrypted, remote.sha)
            rememberRevision(next.revision)
            LoadedCloudUsers(next, sha)
        }

    private companion object { const val KEY_ALIAS = "buylist.cloud.users.draft.v1" }
}
