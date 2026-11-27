package com.mok.buylist.update

/** Display a bounded, readable subset of Markdown without a WebView or executable HTML. */
internal fun releaseChangelogLines(raw: String): List<String> =
    raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
        .filterNot { it.startsWith("<!--") || it.startsWith("**Full Changelog**") || it.startsWith("Full Changelog:") }
        .map { line ->
            line.replace(Regex("""^#{1,6}\s+"""), "")
                .replace(Regex("""^[-*]\s+"""), "• ")
                .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
                .replace("**", "").replace("`", "")
        }.take(80).map { it.take(1000) }.toList()
