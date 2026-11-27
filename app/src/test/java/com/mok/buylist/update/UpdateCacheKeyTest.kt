package com.mok.buylist.update

import org.junit.Assert.*
import org.junit.Test

class UpdateCacheKeyTest {
    private val hash = "a".repeat(64)
    private fun name(code: Long, digest: String = hash) =
        updateCacheFileName(ReleaseVersion(UpdateChannel.BETA, "1.2.0", code), digest)

    @Test fun installedAndSupersededFilesAreRemoved() {
        val current = name(1046)
        assertTrue(shouldRemoveCachedUpdate(name(1044), 1044, 1046, current))
        assertTrue(shouldRemoveCachedUpdate(name(1045), 1044, 1046, current))
        assertFalse(shouldRemoveCachedUpdate(current, 1044, 1046, current))
        assertTrue(shouldRemoveCachedUpdate(current, 1046, 1046, current))
    }

    @Test fun changedHashIsNotReusedAndNewerOtherChannelCanRemain() {
        val current = name(1046)
        assertTrue(shouldRemoveCachedUpdate(name(1046, "b".repeat(64)), 1044, 1046, current))
        assertFalse(shouldRemoveCachedUpdate(name(1047), 1044, 1046, current))
        assertEquals(1046L, cachedUpdateVersionCode(current))
        assertNull(cachedUpdateVersionCode("update-random.apk"))
    }
}
