package com.mok.buylist.update

internal enum class UpdateChannel(val key: String, val label: String) {
    BETA("beta", "Beta"), STABLE("stable", "Stable");

    companion object {
        fun defaultFor(versionName: String): UpdateChannel =
            if ("-beta." in versionName) BETA else STABLE
    }
}

internal data class InstalledVersion(val name: String, val code: Long)

internal data class ReleaseVersion(val channel: UpdateChannel, val base: String, val code: Long) {
    val name: String get() = if (channel == UpdateChannel.BETA) "$base-beta.$code" else base
    val tag: String get() = "${channel.key}-v$base-$code"

    fun isNewerThan(installed: InstalledVersion): Boolean = code > installed.code

    companion object {
        private val pattern = Regex("""(beta|stable)-v(\d+\.\d+\.\d+)-(\d+)""")

        fun parse(tag: String, prerelease: Boolean): ReleaseVersion? {
            val match = pattern.matchEntire(tag) ?: return null
            val (channelKey, base, rawCode) = match.destructured
            val channel = UpdateChannel.entries.first { it.key == channelKey }
            val code = rawCode.toLongOrNull()?.takeIf { it in 1..2_100_000_000L } ?: return null
            if (prerelease != (channel == UpdateChannel.BETA)) return null
            return ReleaseVersion(channel, base, code)
        }
    }
}
