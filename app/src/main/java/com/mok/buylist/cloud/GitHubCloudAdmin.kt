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
        if (file.optString("type") != "file")
            throw CloudAdminException("REG-05A: Путь реестра не является файлом. Запись запрещена.")
        val sha = file.optString("sha")
        if (!sha.matches(Regex("[0-9a-f]{40}"))) throw CloudAdminException("REG-05A: Некорректный SHA реестра. Запись запрещена.")
        val declaredSize = file.optLong("size", -1)
        if (declaredSize > CloudRegistryCodec.MAX_BYTES + 29) throw CloudAdminException("Реестр слишком большой.")

        // Size is advisory; a matching Git object ID verifies the exact byte sequence.
        // If the inline Contents response cannot be verified, obtain the SAME object by SHA.
        val inline = if (file.optString("encoding") == "base64") {
            runCatching { CloudRegistryBlob.decodeBase64(file.getString("content")) }.getOrNull()
        } else null
        if (inline != null && CloudRegistryBlob.matches(inline, sha))
            return@withContext RegistryFile(inline, sha)

        val blobPath = "$repoPath/git/blobs/$sha"
        val raw = request("GET", blobPath, maxResponseBytes = 262_144, accept = "application/vnd.github.raw+json")
        if (raw.status == 200 && CloudRegistryBlob.matches(raw.bytes, sha))
            return@withContext RegistryFile(raw.bytes, sha)

        // Some clients/proxies do not honor the raw media type. Try the JSON blob form.
        val blob = request("GET", blobPath)
        val decoded = if (blob.status == 200) runCatching {
            val json = JSONObject(blob.body)
            require(json.optString("sha") == sha && json.optString("encoding") == "base64")
            CloudRegistryBlob.decodeBase64(json.getString("content"))
        }.getOrNull() else null
        if (decoded != null && CloudRegistryBlob.matches(decoded, sha))
            return@withContext RegistryFile(decoded, sha)

        val details = "SHA=${sha.take(12)}; размер GitHub=$declaredSize; Contents=${inline?.size ?: -1}; " +
            "Blob raw HTTP=${raw.status}, байт=${raw.bytes.size}; Blob JSON HTTP=${blob.status}, байт=${decoded?.size ?: -1}"
        val code = if (inline == null) "REG-05B" else "REG-05C"
        throw CloudAdminException("$code: Не удалось получить файл с подтверждённым SHA. $details. Запись запрещена.")
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

    private fun checkedSha(value: String): String = value.also { require(it.matches(Regex("[0-9a-f]{40}"))) }

    private fun readJson(path: String): JSONObject {
        val response = request("GET", "$repoPath/$path")
        requireSuccess(response)
        return JSONObject(response.body)
    }

    private fun branchTip(branch: String): String? {
        require(branch.startsWith("refs/heads/cloud-"))
        val response = request("GET", "$repoPath/git/ref/" + branch.removePrefix("refs/"))
        if (response.status == 404) return null
        requireSuccess(response)
        val obj = JSONObject(response.body).getJSONObject("object")
        require(obj.getString("type") == "commit")
        return checkedSha(obj.getString("sha"))
    }

    private fun readBlob(sha: String, max: Int): ByteArray {
        checkedSha(sha)
        val response = request("GET", "$repoPath/git/blobs/$sha", maxResponseBytes = max * 2 + 8192)
        requireSuccess(response)
        val json = JSONObject(response.body)
        require(json.getString("sha") == sha && json.getString("encoding") == "base64")
        val bytes = Base64.decode(json.getString("content"), Base64.DEFAULT)
        require(bytes.size in 1..max && CloudRegistryBlob.sha(bytes) == sha)
        return bytes
    }

    private fun writeBlob(bytes: ByteArray): String {
        val response = request("POST", "$repoPath/git/blobs", JSONObject()
            .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP)).put("encoding", "base64"))
        requireSuccess(response)
        return checkedSha(JSONObject(response.body).getString("sha")).also {
            require(it == CloudRegistryBlob.sha(bytes))
        }
    }

    private data class CommitFile(val bytes: ByteArray, val parents: List<String>)

    private fun readCommitFile(sha: String, name: String, max: Int): CommitFile {
        val commit = readJson("git/commits/" + checkedSha(sha))
        require(commit.getString("sha") == sha)
        val tree = readJson("git/trees/" + checkedSha(commit.getJSONObject("tree").getString("sha")))
        require(!tree.optBoolean("truncated"))
        val entries = tree.getJSONArray("tree")
        require(entries.length() == 1)
        val file = entries.getJSONObject(0)
        require(file.getString("path") == name && file.getString("type") == "blob" && file.getString("mode") == "100644")
        val parents = commit.getJSONArray("parents")
        require(parents.length() <= 1)
        return CommitFile(readBlob(file.getString("sha"), max),
            (0 until parents.length()).map { checkedSha(parents.getJSONObject(it).getString("sha")) })
    }

    internal suspend fun snapshotForRotation(provision: CloudProvision, signer: CloudAdminSigner): com.mok.buylist.sync.Snapshot =
        withContext(Dispatchers.IO) {
            requireRegistryStorage()
            val manifest = provision.signed().verify(signer.publicKey, provision.space, 1)
            val key = provision.key()
            var snapshot = if (provision.checkpointSha.isEmpty()) com.mok.buylist.sync.Snapshot()
                else CloudEpochCheckpoint.open(readBlob(provision.checkpointSha, CloudEpochCheckpoint.MAX_BYTES), manifest, key)
            val tips = linkedMapOf<String, String?>()
            for (grant in manifest.grants.filter { it.canWrite }) {
                val branch = "refs/heads/cloud-v1/" + manifest.space.key.replace(':', '/') + "/${manifest.epoch}/${grant.deviceId}"
                val tip = branchTip(branch)
                tips[branch] = tip
                var sha = tip
                val reverse = arrayListOf<CloudSignedEvent>()
                while (sha != null) {
                    require(reverse.size < 500) { "История требует отдельного переноса: слишком много событий" }
                    val file = readCommitFile(sha, "event.bin", CloudSignedEvent.MAX_DELTA_BYTES + 1024)
                    val event = CloudSignedEvent.decode(file.bytes)
                    require(event.header.deviceId == grant.deviceId)
                    require(event.header.space == manifest.space && event.header.epoch == manifest.epoch)
                    reverse.add(event)
                    sha = file.parents.singleOrNull()
                }
                var cursor = CloudCursor()
                for (event in reverse.asReversed()) {
                    require(event.header.sequence == cursor.sequence + 1 && event.header.previousHash == cursor.digest)
                    snapshot = com.mok.buylist.sync.SyncMerge.merge(snapshot, event.open(manifest, key))
                    cursor = CloudCursor(event.header.sequence, event.digest)
                }
            }
            require(tips.all { (branch, tip) -> branchTip(branch) == tip }) {
                "Покупки изменились во время переноса. Повторите операцию."
            }
            snapshot
        }

    internal suspend fun storeCheckpoint(bytes: ByteArray): String = withContext(Dispatchers.IO) {
        requireRegistryStorage()
        require(bytes.size in 29..CloudEpochCheckpoint.MAX_BYTES)
        // Keep the blob reachable through an immutable commit/ref, not just loose object storage.
        val blob = writeBlob(bytes)
        val branch = "refs/heads/cloud-checkpoints-v1/$blob"
        val old = branchTip(branch)
        if (old == null) {
            val commit = createFileCommit("checkpoint.bin", blob, null, "Store encrypted workspace checkpoint")
            requireSuccess(request("POST", "$repoPath/git/refs", JSONObject().put("ref", branch).put("sha", commit)))
        } else {
            require(readCommitFile(old, "checkpoint.bin", CloudEpochCheckpoint.MAX_BYTES).bytes.contentEquals(bytes))
        }
        blob
    }

    internal suspend fun invitationCheckpoint(provision: CloudProvision): ByteArray = withContext(Dispatchers.IO) {
        requireRegistryStorage()
        if (provision.checkpointSha.isEmpty()) byteArrayOf()
        else readBlob(provision.checkpointSha, CloudEpochCheckpoint.MAX_BYTES)
    }

    private fun createFileCommit(name: String, blob: String, oldTip: String?, message: String): String {
        val entry = JSONObject().put("path", name).put("mode", "100644").put("type", "blob").put("sha", blob)
        val treeResponse = request("POST", "$repoPath/git/trees", JSONObject().put("tree", JSONArray().put(entry)))
        requireSuccess(treeResponse)
        val tree = checkedSha(JSONObject(treeResponse.body).getString("sha"))
        val parents = JSONArray()
        if (oldTip != null) parents.put(oldTip)
        val commitResponse = request("POST", "$repoPath/git/commits", JSONObject()
            .put("message", message).put("tree", tree).put("parents", parents))
        requireSuccess(commitResponse)
        return checkedSha(JSONObject(commitResponse.body).getString("sha"))
    }

    internal suspend fun publishEpoch(provision: CloudProvision, signer: CloudAdminSigner) = withContext(Dispatchers.IO) {
        requireRegistryStorage()
        val next = CloudEpochControl.create(provision, signer)
        val branch = CloudEpochControl.branch(provision.space)
        val oldTip = branchTip(branch)
        if (oldTip != null) {
            val old = CloudEpochControl.decode(readCommitFile(oldTip, "control.bin", CloudEpochControl.MAX_BYTES).bytes)
                .verify(signer.publicKey, provision.space)
            if (old.body().contentEquals(next.body())) return@withContext
            require(next.epoch > old.epoch || (next.epoch == old.epoch && next.retired && !old.retired)) {
                "Нельзя заменить более новую версию списка"
            }
        }
        val commit = createFileCommit("control.bin", writeBlob(next.encode()), oldTip, "Update signed workspace version")
        if (oldTip == null) requireSuccess(request("POST", "$repoPath/git/refs", JSONObject().put("ref", branch).put("sha", commit)))
        else requireSuccess(request("PATCH", "$repoPath/git/refs/" + branch.removePrefix("refs/"),
            JSONObject().put("sha", commit).put("force", false)))
        require(branchTip(branch) == commit) { "Не удалось подтвердить новую версию списка" }
    }

    private fun deviceTitle(device: CloudDeviceDraft) = "buylist-device-${device.fingerprint}"
    private fun deviceSshKey(device: CloudDeviceDraft): String = CloudSshWire.openSsh(
        CloudDeviceProtocol.publicKey(device.publicKey) as java.security.interfaces.ECPublicKey
    ).split(' ').take(2).joinToString(" ")

    private fun readDeployKeys(): List<JSONObject> {
        val response = request("GET", "$repoPath/keys?per_page=100", maxResponseBytes = 1_048_576)
        requireSuccess(response)
        val keys = org.json.JSONArray(response.body)
        require(keys.length() < 100) { "Too many deploy keys to verify safely" }
        return (0 until keys.length()).map { keys.getJSONObject(it) }
    }

    private fun findDeviceKey(device: CloudDeviceDraft, keys: List<JSONObject> = readDeployKeys()): JSONObject? {
        val matching = keys.filter {
            it.optString("title") == deviceTitle(device) ||
                it.optString("key").split(' ').take(2).joinToString(" ") == deviceSshKey(device)
        }
        require(matching.size <= 1) { "Conflicting device keys" }
        return matching.singleOrNull()?.also {
            require(it.optString("title") == deviceTitle(device) &&
                it.optString("key").split(' ').take(2).joinToString(" ") == deviceSshKey(device)) {
                "Existing key is not managed by this application"
            }
        }
    }

    suspend fun deviceAccesses(devices: List<CloudDeviceDraft>): Map<String, Boolean> = withContext(Dispatchers.IO) {
        requireRegistryStorage()
        val keys = readDeployKeys()
        devices.associate { it.fingerprint to (findDeviceKey(it, keys) != null) }
    }

    suspend fun deviceAccess(device: CloudDeviceDraft): Boolean = withContext(Dispatchers.IO) {
        requireRegistryStorage()
        findDeviceKey(device) != null
    }

    suspend fun activateDeviceReadOnly(device: CloudDeviceDraft) = activateDevice(device, false)

    suspend fun activateDevice(device: CloudDeviceDraft, writable: Boolean) = withContext(Dispatchers.IO) {
        require(!device.revoked)
        requireRegistryStorage()
        var existing = findDeviceKey(device)
        if (existing != null && existing.optBoolean("read_only") == writable) {
            deactivateDevice(device)
            existing = null
        }
        if (existing == null) {
            val payload = JSONObject().put("title", deviceTitle(device))
                .put("key", deviceSshKey(device)).put("read_only", !writable)
            requireSuccess(request("POST", "$repoPath/keys", payload))
        }
        val verified = findDeviceKey(device) ?: throw CloudAdminException("Ключ не подтверждён GitHub.")
        if (verified.optBoolean("read_only") == writable) throw CloudAdminException("Права устройства не подтверждены.")
    }

    suspend fun deactivateDevice(device: CloudDeviceDraft) = withContext(Dispatchers.IO) {
        requireRegistryStorage()
        val existing = findDeviceKey(device) ?: return@withContext
        val id = existing.getLong("id")
        require(id > 0)
        requireSuccess(request("DELETE", "$repoPath/keys/$id"))
        if (findDeviceKey(device) != null) throw CloudAdminException("GitHub не подтвердил отзыв ключа.")
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

    private data class Response(val status: Int, val bytes: ByteArray) {
        val body: String get() = bytes.toString(Charsets.UTF_8)
    }

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

    private fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        maxResponseBytes: Int = 262_144,
        accept: String = "application/vnd.github+json",
    ): Response {
        val connection = URL("https://api.github.com$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.useCaches = false
            connection.setRequestProperty("Accept", accept)
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
            if (status !in 200..299 || status == 204) return Response(status, ByteArray(0))
            val response = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > maxResponseBytes) throw CloudAdminException("Слишком большой ответ GitHub.")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return Response(status, response)
        } finally {
            connection.disconnect()
        }
    }
}
