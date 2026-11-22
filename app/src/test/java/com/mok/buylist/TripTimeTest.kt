package com.mok.buylist

import com.mok.buylist.data.TripTime
import com.mok.buylist.data.computeTripTime
import com.mok.buylist.data.tripMinutes
import org.junit.Assert.assertEquals
import org.junit.Test

class TripTimeTest {
    private val minute = 60_000L

    @Test
    fun noPurchasesMeansNoTime() {
        assertEquals(TripTime(0, 0), computeTripTime(emptyList()))
    }

    @Test
    fun singlePurchaseHasZeroDuration() {
        assertEquals(TripTime(0, 1), computeTripTime(listOf(1_000L)))
    }

    @Test
    fun oneStoreIsFirstToLastMark() {
        val times = listOf(0L, 5 * minute, 12 * minute, 20 * minute)
        assertEquals(TripTime(20 * minute, 1), computeTripTime(times))
    }

    @Test
    fun longPauseSplitsIntoVisitsAndIsNotCounted() {
        // Три товара за 10 минут, через три часа ещё два за 6 минут: в сумме 16 минут, а не 3 часа.
        val second = 190 * minute
        val times = listOf(0L, 5 * minute, 10 * minute, second, second + 6 * minute)
        assertEquals(TripTime(16 * minute, 2), computeTripTime(times))
    }

    @Test
    fun pauseOfExactlyThirtyMinutesStaysInTheSameVisit() {
        assertEquals(TripTime(30 * minute, 1), computeTripTime(listOf(0L, 30 * minute)))
        assertEquals(TripTime(0, 2), computeTripTime(listOf(0L, 30 * minute + 1)))
    }

    @Test
    fun orderOfInputDoesNotMatter() {
        assertEquals(computeTripTime(listOf(0L, 5 * minute, 9 * minute)), computeTripTime(listOf(9 * minute, 0L, 5 * minute)))
    }

    @Test
    fun minutesAreRoundedAndTinyTimesAreHidden() {
        assertEquals(0, tripMinutes(0))
        assertEquals(0, tripMinutes(29_000))
        assertEquals(1, tripMinutes(30_000))
        assertEquals(32, tripMinutes(32 * minute + 10_000))
    }
}
