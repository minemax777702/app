package com.mok.buylist.update

import org.junit.Assert.*
import org.junit.Test

class UpdateNotificationPolicyTest {
    @Test fun onlyNewAndPreviouslyUnannouncedVersionsNotify() {
        assertTrue(shouldNotifyUpdate(1042, 1043, 0))
        assertFalse(shouldNotifyUpdate(1042, 1043, 1043))
        assertFalse(shouldNotifyUpdate(1044, 1043, 0))
        assertTrue(shouldNotifyUpdate(1042, 1045, 1043))
    }

    @Test fun foregroundChecksAreThrottledAndClockChangesRecover() {
        val now = 1_000_000L
        assertTrue(shouldCheckUpdates(now, 0))
        assertFalse(shouldCheckUpdates(now, now - 1000))
        assertTrue(shouldCheckUpdates(now, now - UPDATE_CHECK_INTERVAL_MS))
        assertTrue(shouldCheckUpdates(now, now + 1000))
    }
}
