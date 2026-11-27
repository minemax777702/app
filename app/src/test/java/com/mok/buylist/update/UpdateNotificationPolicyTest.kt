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

    @Test fun dismissedNotificationCanReappearOnNextEntryButNotWhileActive() {
        assertTrue(shouldNotifyUpdate(1042, 1043, 1043, allowRepeat = true))
        assertFalse(shouldNotifyUpdate(1042, 1043, 1043, active = true, allowRepeat = true))
        assertFalse(shouldNotifyUpdate(1043, 1043, 1043, allowRepeat = true))
    }

    @Test fun aFailedPostDoesNotConsumeTheVersion() {
        // The stored code changes only after the posted notification is confirmed.
        val lastConfirmed = 1042L
        assertTrue(shouldNotifyUpdate(1042, 1043, lastConfirmed))
        assertTrue(shouldNotifyUpdate(1042, 1043, lastConfirmed))
        assertFalse(shouldNotifyUpdate(1042, 1043, 1043))
    }
}
