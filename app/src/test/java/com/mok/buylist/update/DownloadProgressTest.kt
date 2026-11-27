package com.mok.buylist.update

import org.junit.Assert.*
import org.junit.Test

class DownloadProgressTest {
    @Test fun actualBytesAndRemainingTime() {
        val meter = DownloadMeter(10_000_000)
        assertNull(meter.sample(0, 1000)!!.secondsRemaining)
        assertNull(meter.sample(100_000, 1100))
        val sample = meter.sample(2_000_000, 2000)!!
        assertEquals(20, sample.percent)
        assertEquals(4L, sample.secondsRemaining)
        assertEquals(2_000_000L, sample.bytes)
    }

    @Test fun completionAndUnknownRate() {
        assertEquals(100, DownloadProgress(100, 100, verifying = true).percent)
        assertEquals(0, DownloadProgress(0, 0).percent)
        assertNull(DownloadProgress(10, 100, 0.0).secondsRemaining)
        assertEquals("Оцениваем время…", downloadEta(null))
    }
}
