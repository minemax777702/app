package com.mok.buylist.update

private val cacheNamePattern = Regex("""(?:beta|stable)-v\d+\.\d+\.\d+-(\d+)-[a-f0-9]{64}\.apk""")

internal fun updateCacheFileName(version: ReleaseVersion, sha256: String): String {
    require(sha256.matches(Regex("[a-f0-9]{64}")))
    return "${version.tag}-$sha256.apk"
}

internal fun cachedUpdateVersionCode(name: String): Long? =
    cacheNamePattern.matchEntire(name)?.groupValues?.get(1)?.toLongOrNull()

/** Do not delete a valid newer cached version when viewing an older channel. */
internal fun shouldRemoveCachedUpdate(name: String, installed: Long, available: Long, expected: String): Boolean {
    val code = cachedUpdateVersionCode(name) ?: return name.startsWith("update-") && name.endsWith(".apk")
    return code <= installed || code < available || (code == available && name != expected)
}
