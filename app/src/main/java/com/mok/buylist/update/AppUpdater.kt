package com.mok.buylist.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

internal data class AppRelease(
    val version: ReleaseVersion,
    val url: String,
    val sha256: String,
    val size: Long,
)

internal object AppUpdater {
    private const val REPO = "minemax777702/app"
    private const val APK_NAME = "app-release.apk"
    private const val MAX_APK_BYTES = 200L * 1024 * 1024
    private const val MAX_JSON_BYTES = 8 * 1024 * 1024

    @Suppress("DEPRECATION")
    fun installedVersion(context: Context): InstalledVersion {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return InstalledVersion(info.versionName.orEmpty(), info.longVersionCode)
    }

    fun selectedChannel(context: Context): UpdateChannel {
        val stored = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
            .getString("channel", null)
        return UpdateChannel.entries.firstOrNull { it.key == stored }
            ?: UpdateChannel.defaultFor(installedVersion(context).name)
    }

    fun selectChannel(context: Context, channel: UpdateChannel) {
        context.getSharedPreferences("app_updates", Context.MODE_PRIVATE).edit()
            .putString("channel", channel.key).apply()
        UpdateNotifications.clearNotification(context)
    }

    suspend fun latest(channel: UpdateChannel): AppRelease? = withContext(Dispatchers.IO) {
        // GitHub returns public releases without credentials. Search all pages until a channel is found.
        for (page in 1..20) {
            coroutineContext.ensureActive()
            val url = "https://api.github.com/repos/$REPO/releases?per_page=50&page=$page"
            val releases = JSONArray(readJson(url))
            val matching = (0 until releases.length()).mapNotNull { index ->
                val entry = releases.getJSONObject(index)
                if (entry.optBoolean("draft")) return@mapNotNull null
                val version = ReleaseVersion.parse(entry.optString("tag_name"), entry.optBoolean("prerelease"))
                    ?: return@mapNotNull null
                if (version.channel == channel) version to entry else null
            }.maxByOrNull { it.first.code }
            if (matching != null) return@withContext parseAsset(matching.first, matching.second)
            if (releases.length() < 50) return@withContext null
        }
        error("Не удалось найти канал в пределах списка релизов.")
    }

    private fun parseAsset(version: ReleaseVersion, release: JSONObject): AppRelease {
        val assets = release.getJSONArray("assets")
        val asset = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name") == APK_NAME && it.optString("state") == "uploaded" }
            ?: error("APK ещё не опубликован.")
        val expected = "https://github.com/$REPO/releases/download/${version.tag}/$APK_NAME"
        require(asset.optString("browser_download_url") == expected) { "Недопустимый адрес APK." }
        val digest = asset.optString("digest").removePrefix("sha256:")
        require(digest.matches(Regex("[a-fA-F0-9]{64}"))) { "Контрольная сумма APK недоступна." }
        val size = asset.optLong("size")
        require(size in 1..MAX_APK_BYTES) { "Недопустимый размер APK." }
        return AppRelease(version, expected, digest.lowercase(), size)
    }

    suspend fun download(context: Context, release: AppRelease, progress: suspend (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            require(release.version.isNewerThan(installedVersion(context))) { "Установлена такая же или более новая версия." }
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }
                ?.forEach { it.delete() }
            val temp = File.createTempFile("update-", ".part", dir)
            val apk = File(dir, temp.nameWithoutExtension + ".apk")
            var ready = false
            try {
                val connection = openDownload(release.url)
                val digest = MessageDigest.getInstance("SHA-256")
                var bytes = 0L
                var previous = -1
                try {
                    connection.inputStream.use { input ->
                        temp.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val count = input.read(buffer)
                                if (count == -1) break
                                bytes += count
                                check(bytes <= release.size) { "Размер APK не совпадает." }
                                output.write(buffer, 0, count)
                                digest.update(buffer, 0, count)
                                val percent = (bytes * 100 / release.size).toInt()
                                if (percent != previous) {
                                    previous = percent
                                    withContext(Dispatchers.Main.immediate) { progress(percent) }
                                }
                            }
                        }
                    }
                } finally { connection.disconnect() }
                check(bytes == release.size) { "APK загружен не полностью." }
                val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                check(hash == release.sha256) { "Контрольная сумма APK не совпадает." }
                check(temp.renameTo(apk)) { "Не удалось сохранить APK." }
                verifyPackage(context, apk, release.version)
                coroutineContext.ensureActive()
                ready = true
                apk
            } finally {
                temp.delete()
                if (!ready) apk.delete()
            }
        }

    @Suppress("DEPRECATION")
    private fun verifyPackage(context: Context, apk: File, version: ReleaseVersion) {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val installed = pm.getPackageInfo(context.packageName, flags)
        val candidate = pm.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: error("Некорректный APK.")
        check(candidate.packageName == context.packageName) { "APK принадлежит другому приложению." }
        check(candidate.longVersionCode == version.code && candidate.versionName == version.name &&
            candidate.longVersionCode > installed.longVersionCode) { "Версия APK не совпадает с релизом." }
        val current = installed.signingInfo?.apkContentsSigners?.toSet().orEmpty()
        val incoming = candidate.signingInfo?.apkContentsSigners?.toSet().orEmpty()
        check(current.isNotEmpty() && current == incoming) { "Подпись APK не совпадает с установленным приложением." }
    }

    fun install(context: Context, apk: File) {
        check(context.packageManager.canRequestPackageInstalls()) { "Разрешите установку приложений из этого источника." }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun connection(url: URL): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "BuyList-Android-Updater")
        }

    private fun readJson(address: String): String {
        val c = connection(URL(address))
        try {
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            check(c.responseCode == 200) { "GitHub: HTTP ${c.responseCode}. Попробуйте позже." }
            return c.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    check(output.size() + count <= MAX_JSON_BYTES) { "Ответ GitHub слишком большой." }
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
        } finally { c.disconnect() }
    }

    private fun openDownload(address: String): HttpURLConnection {
        var url = URL(address)
        repeat(6) {
            check(url.protocol == "https" && url.userInfo == null &&
                (url.port == -1 || url.port == 443) &&
                url.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
            ) { "Недопустимый адрес загрузки." }
            val c = connection(url)
            try {
                val status = c.responseCode
                if (status == 200) return c
                check(status in setOf(301, 302, 303, 307, 308)) { "Ошибка загрузки APK: HTTP $status." }
                val location = c.getHeaderField("Location") ?: error("GitHub не вернул адрес загрузки.")
                url = URL(url, location)
            } catch (e: Exception) {
                c.disconnect()
                throw e
            }
            c.disconnect()
        }
        error("Слишком много перенаправлений GitHub.")
    }
}
