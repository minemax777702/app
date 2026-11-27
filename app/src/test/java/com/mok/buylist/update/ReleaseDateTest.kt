package com.mok.buylist.update

import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ReleaseDateTest {
    @Test fun betaUsesLocalTimeAndStableUsesOnlyDate() {
        val zone = ZoneId.of("Europe/Amsterdam")
        val published = "2026-11-27T23:30:00Z"
        assertEquals("28.11.2026 · 00:30", formatReleaseDate(published, UpdateChannel.BETA, zone))
        assertEquals("28.11.2026", formatReleaseDate(published, UpdateChannel.STABLE, zone))
    }

    @Test fun invalidPublicationTimeIsNotInvented() {
        assertEquals("", formatReleaseDate("", UpdateChannel.BETA))
        assertEquals("", formatReleaseDate("null", UpdateChannel.STABLE))
    }

    @Test fun releaseTagMatchesInstalledVersion() {
        assertEquals("beta-v1.2.0-1104", InstalledVersion("1.2.0-beta.1104",1104).releaseVersion()?.tag)
        assertEquals("stable-v1.2.0-1063", InstalledVersion("1.2.0",1063).releaseVersion()?.tag)
        assertNull(InstalledVersion("1.2.0-beta.local",3).releaseVersion())
    }
}
