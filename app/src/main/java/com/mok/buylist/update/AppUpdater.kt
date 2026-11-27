package com.mok.buylist.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val cacheMutex = Mutex()

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

    private fun cacheDirectory(context: Context): File =
        File(context.cacheDir, "updates").apply { mkdirs() }

    private fun cacheFile(dir: File, release: AppRelease): File =
        File(dir, updateCacheFileName(release.version, release.sha256))

    private suspend fun validateCached(context: Context, apk: File, release: AppRelease): Boolean {
        if (!apk.isFile || apk.length() != release.size) return false
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            apk.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count == -1) break
                    digest.update(buffer, 0, count)
                }
            }
            if (digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } != release.sha256) {
                false
            } else {
                verifyPackage(context, apk, release.version)
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun pruneInstalled(context: Context, dir: File) {
        val installed = installedVersion(context).code
        dir.listFiles()?.forEach { file ->
            val code = cachedUpdateVersionCode(file.name)
            when {
                file.name.endsWith(".part") -> file.delete()
                code != null && code <= installed -> file.delete()
                code == null && file.name.startsWith("update-") && file.extension == "apk" -> {
                    // Legacy cache names carry no version. Read the APK metadata locally,
                    // including when the network is unavailable after a successful update.
                    val info = runCatching {
                        context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                    }.getOrNull()
                    if (info == null || info.packageName != context.packageName ||
                        info.longVersionCode <= installed) file.delete()
                }
            }
        }
    }

    /** Drop the previous APK as soon as a newer release is discovered, not after downloading it. */
    private suspend fun findCachedLocked(context: Context, dir: File, release: AppRelease): File? {
        val expected = cacheFile(dir, release)
        var found: File? = null
        if (release.version.isNewerThan(installedVersion(context))) {
            if (validateCached(context, expected, release)) found = expected
            else if (expected.exists()) expected.delete()
            // Migrate a verified download from versions that used randomly named APKs.
            if (found == null) {
                for (legacy in dir.listFiles().orEmpty().filter { it.name.startsWith("update-") && it.extension == "apk" }) {
                    coroutineContext.ensureActive()
                    if (validateCached(context, legacy, release)) {
                        if (expected.exists()) expected.delete()
                        found = if (legacy.renameTo(expected)) expected else legacy
                        break
                    }
                }
            }
        }
        val installed = installedVersion(context).code
        dir.listFiles()?.filter { it != found && it.extension == "apk" }?.forEach { file ->
            if (shouldRemoveCachedUpdate(file.name, installed, release.version.code, expected.name)) file.delete()
        }
        return found
    }

    /** Also run after app replacement, even when update notifications are turned off. */
    suspend fun hasPendingCache(context: Context): Boolean = withContext(Dispatchers.IO) {
        cacheMutex.withLock {
            val dir = cacheDirectory(context)
            pruneInstalled(context, dir)
            dir.listFiles().orEmpty().any { it.isFile && it.extension == "apk" }
        }
    }

    suspend fun cachedUpdate(context: Context, release: AppRelease?): File? = withContext(Dispatchers.IO) {
        cacheMutex.withLock {
            val dir = cacheDirectory(context)
            pruneInstalled(context, dir)
            if (release == null) null else findCachedLocked(context, dir, release)
        }
    }

    suspend fun download(context: Context, release: AppRelease, progress: suspend (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            cacheMutex.withLock {
                require(release.version.isNewerThan(installedVersion(context))) { "Установлена такая же или более новая версия." }
                val dir = cacheDirectory(context)
                pruneInstalled(context, dir)
                findCachedLocked(context, dir, release)?.let { cached ->
                    withContext(Dispatchers.Main.immediate) { progress(100) }
                    return@withLock cached
                }
                val temp = File.createTempFile("update-", ".part", dir)
                val apk = cacheFile(dir, release)
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
                    // Only one pending update needs to occupy disk space.
                    dir.listFiles()?.filter { it != apk && it.extension == "apk" }?.forEach { it.delete() }
                    apk
                } finally {
                    temp.delete()
                    if (!ready) apk.delete()
                }
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

    suspend fun install(context: Context, apk: File, release: AppRelease) {
        withContext(Dispatchers.IO) {
            cacheMutex.withLock {
                check(validateCached(context, apk, release)) { "APK отсутствует или повреждён. Загрузите обновление повторно." }
            }
        }
        coroutineContext.ensureActive()
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
