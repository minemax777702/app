package com.mok.buylist.cloud

import org.junit.Assert.*
import org.junit.Test

class CloudAdminGestureTest {
    @Test fun sevenConsecutiveTapsOpenAndReset() {
        val gesture = CloudAdminGesture()
        repeat(6) { assertFalse(gesture.tap(it * 100L)) }
        assertTrue(gesture.tap(600))
        assertFalse(gesture.tap(700))
    }

    @Test fun slowOrInterruptedTapsDoNotOpen() {
        val gesture = CloudAdminGesture()
        repeat(6) { assertFalse(gesture.tap(it * 100L)) }
        assertFalse(gesture.tap(3000))
        gesture.reset()
        repeat(6) { assertFalse(gesture.tap(4000L + it * 100L)) }
        assertTrue(gesture.tap(4600))
    }
}
