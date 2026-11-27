package com.mok.buylist.cloud

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudIntegrationProbeTest {
    @Test fun twoIsolatedRoomPeersExchangeSignedEvents() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val results = mutableListOf<CloudTestResult>()
        CloudIntegrationProbe.run(context) { results.add(it) }
        assertEquals(7, results.size)
        assertTrue(results.all { it.passed })
    }
}
