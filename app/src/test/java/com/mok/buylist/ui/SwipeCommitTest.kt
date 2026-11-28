package com.mok.buylist.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SwipeCommitTest {
    @Test fun shortDragDoesNotCommit() {
        for (width in listOf(280, 320, 371, 560)) {
            val short = width * 0.54f
            assertEquals(0, resolveSwipeCommit(short, width))
            assertEquals(0, resolveSwipeCommit(-short, width))
        }
    }

    @Test fun thresholdIsSymmetricAndInclusive() {
        for (width in listOf(280, 320, 371, 560)) {
            val threshold = width * SWIPE_REMOVE_FRACTION
            assertEquals(1, resolveSwipeCommit(threshold, width))
            assertEquals(-1, resolveSwipeCommit(-threshold, width))
        }
    }

    @Test fun invalidGeometryDoesNotCommit() {
        assertEquals(0, resolveSwipeCommit(100f, 0))
        assertEquals(0, resolveSwipeCommit(-100f, -1))
        assertEquals(0, resolveSwipeCommit(Float.NaN, 320))
        assertEquals(0, resolveSwipeCommit(Float.POSITIVE_INFINITY, 320))
    }
}
