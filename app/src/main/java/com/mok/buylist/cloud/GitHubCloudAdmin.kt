package com.mok.buylist.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal class CloudAdminException(message: String) : IOException(message)

internal data class CloudStorage(
    val exists: Boolean,
    val actionsEnabled: Boolean? = null,
    val sizeKiB: Long = 0,
)

/** A short-lived admin session. Never serialize the token, include it in URLs or log responses. */
internal class GitHubCloudAdmin(private val token: String) {
    private val repoPath = "/repos/${CloudAccessPolicy.FULL_NAME}"

    suspend fun inspect(): CloudStorage = withContext(Dispatchers.IO) {
        verifyOwner()
        inspectStorage()
    }

    suspend fun createPrivateStorage(): CloudStorage = withContext(Dispatchers.IO) {
        verifyOwner()
        val existing = inspectStorage()
        if (existing.exists) return@withContext existing
        val payload = JSONObject()
            .put("name", CloudAccessPolicy.REPOSITORY)
            .put("description", "Private storage for Shopping List")
            .put("private", true)
            .put("auto_init", true)
            .put("has_issues", false)
            .put("has_projects", false)
            .put("has_wiki", false)
        val response = request("POST", "/user/repos", payload)
        requireSuccess(response)
        inspectStorage().also {
            if (!it.exists) throw CloudAdminException("Хранилище создано, но токен не имеет к нему доступа. Проверьте разрешения.")
        }
    }

    suspend fun disableActions(): CloudStorage = withContext(Dispatchers.IO) {
        verifyOwner()
        val storage = inspectStorage()
        if (!storage.exists) throw CloudAdminException("Хранилище недоступно.")
        requireSuccess(request("PUT", "$repoPath/actions/permissions", JSONObject().put("enabled", false)))
        inspectStorage()
    }

    internal data class RegistryFile(val bytes: ByteArray, val sha: String)

    private fun requireRegistryStorage() {
        verifyOwner()
        val storage = inspectStorage()
        if (!storage.exists || storage.actionsEnabled != false)
            throw CloudAdminException("Проверьте приватное хранилище и отключите Actions.")
    }

    internal suspend fun readUserRegistry(): RegistryFile? = withContext(Dispatchers.IO) {
        requireRegistryStorage()
        val response = request("GET", "$repoPath/contents/system/admin-users.v1.enc")
        if (response.status == 404) {
            // Distinguish an absent file from a token without Contents access.
            requireSuccess(request("GET", "$repoPath/contents"))
            return@withContext null
        }
        requireSuccess(response)
        val file = JSONObject(response.body)
        if (file.optString("type") != "file" || file.optString("encoding") != "base64")
            throw CloudAdminException("Неизвестный формат реестра.")
        val sha = file.optString("sha")
        if (!sha.matches(Regex("[0-9a-f]{40}"))) throw CloudAdminException("Не удалось проверить версию реестра.")
        val bytes = Base64.decode(file.getString("content"), Base64.DEFAULT)
        if (bytes.size > CloudRegistryCodec.MAX_BYTES + 29) throw CloudAdminException("Реестр слишком большой.")
        RegistryFile(bytes, sha)
    }

    internal suspend fun writeUserRegistry(bytes: ByteArray, expectedSha: String?): String = withContext(Dispatchers.IO) {
        requireRegistryStorage()
        require(bytes.size <= CloudRegistryCodec.MAX_BYTES + 29)
        require(expectedSha == null || expectedSha.matches(Regex("[0-9a-f]{40}")))
        val payload = JSONObject()
            .put("message", "Update encrypted admin user drafts")
            .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
        if (expectedSha != null) payload.put("sha", expectedSha)
        val response = request("PUT", "$repoPath/contents/system/admin-users.v1.enc", payload)
        if (response.status == 409 || response.status == 422)
            throw CloudAdminException("Реестр изменён или запись отклонена. Обновите список перед повторной попыткой.")
        requireSuccess(response)
        val sha = JSONObject(response.body).getJSONObject("content").getString("sha")
        require(sha.matches(Regex("[0-9a-f]{40}")))
        sha
    }

    internal data class BackupFile(val id: String, val sha: String, val size: Long)

    private fun backupPath(id: String): String {
        require(UUID.fromString(id).toString() == id)
        return "$repoPath/contents/backups/manual/$id.enc"
    }

    suspend fun listBackups(): List<BackupFile> = withContext(Dispatchers.IO) {
        requireRegistryStorage()
        val response = request("GET", "$repoPath/contents/backups/manual", maxResponseBytes = 1_048_576)
        if (response.status == 404) {
            requireSuccess(request("GET", "$repoPath/contents"))
            return@withContext emptyList()
        }
        requireSuccess(response)
        val array = JSONArray(response.body)
        // Contents API returns at most 1000 entries. Do not silently truncate.
        if (array.length() >= 1000) throw CloudAdminException("Достигнут предел списка резервных копий.")
        buildList {
            repeat(array.length()) { index ->
                val entry = array.getJSONObject(index)
                val name = entry.optString("name")
                val id = name.removeSuffix(".enc")
                val validId = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
                val sha = entry.optString("sha")
                val size = entry.optLong("size", -1L)
                if (entry.optString("type") == "file" && name.endsWith(".enc") &&
                    validId && sha.matches(Regex("[0-9a-f]{40}")) && size in 1L..CloudPurchaseBackupCodec.MAX_FILE_BYTES.toLong()) {
                    add(BackupFile(id, sha, size))
                }
            }
        }
    }

    suspend fun uploadBackup(bytes: ByteArray): String = withContext(Dispatchers.IO) {
        requireRegistryStorage()
        require(bytes.size in 1..CloudPurchaseBackupCodec.MAX_FILE_BYTES)
        val id = UUID.randomUUID().toString()
        val payload = JSONObject().put("message", "Add encrypted manual backup")
            .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
        // Unique immutable path; no SHA and no overwrite.
        requireSuccess(request("PUT", backupPath(id), payload))
        id
    }

    suspend fun downloadBackup(file: BackupFile): ByteArray = withContext(Dispatchers.IO) {
        requireRegistryStorage()
        val response = request("GET", backupPath(file.id), maxResponseBytes = 1_048_576)
        requireSuccess(response)
        val data = JSONObject(response.body)
        if (data.optString("type") != "file" || data.optString("encoding") != "base64" || data.optString("sha") != file.sha)
            throw CloudAdminException("Файл изменился. Обновите список резервных копий.")
        val bytes = Base64.decode(data.getString("content"), Base64.DEFAULT)
        require(bytes.size.toLong() == file.size && bytes.size <= CloudPurchaseBackupCodec.MAX_FILE_BYTES)
        bytes
    }

    private fun verifyOwner() {
        val reply = request("GET", "/user")
        requireSuccess(reply)
        val user = JSONObject(reply.body)
        if (!CloudAccessPolicy.isOwner(user.optLong("id"), user.optString("login"))) {
            throw CloudAdminException("Этот аккаунт не является владельцем хранилища.")
        }
    }

    private fun inspectStorage(): CloudStorage {
        val reply = request("GET", repoPath)
        if (reply.status == 404) return CloudStorage(exists = false)
        requireSuccess(reply)
        val repo = JSONObject(reply.body)
        if (!CloudAccessPolicy.isPrivateStorage(
                repo.optJSONObject("owner")?.optLong("id") ?: 0L,
                repo.optString("full_name"), repo.optBoolean("private"), repo.optString("visibility"),
            )) {
            throw CloudAdminException("Репозиторий не является ожидаемым приватным хранилищем. Доступ запрещён.")
        }
        val actions = request("GET", "$repoPath/actions/permissions")
        val enabled = if (actions.status == 200) JSONObject(actions.body).let {
            if (it.has("enabled")) it.optBoolean("enabled") else null
        } else null
        return CloudStorage(exists = true, actionsEnabled = enabled, sizeKiB = repo.optLong("size"))
    }

    private data class Response(val status: Int, val body: String)

    private fun requireSuccess(response: Response) {
        if (response.status in 200..299) return
        val message = when (response.status) {
            401 -> "Токен недействителен или истёк."
            403 -> "Недостаточно прав или превышен лимит запросов GitHub."
            404 -> "Репозиторий не найден или недоступен этому токену."
            422 -> "GitHub отклонил операцию. Возможно, репозиторий уже существует."
            429 -> "Превышен лимит запросов GitHub."
            else -> "GitHub вернул ошибку HTTP ${response.status}."
        }
        throw CloudAdminException(message)
    }

    private fun request(method: String, path: String, body: JSONObject? = null, maxResponseBytes: Int = 262_144): Response {
        val connection = URL("https://api.github.com$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "ShoppingList-Cloud")
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            // Error bodies may contain sensitive details; do not surface them.
            if (status !in 200..299 || status == 204) return Response(status, "")
            val response = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > maxResponseBytes) throw CloudAdminException("Слишком большой ответ GitHub.")
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
            return Response(status, response)
        } finally {
            connection.disconnect()
        }
    }
}
