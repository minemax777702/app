package com.mok.buylist

import com.mok.buylist.data.ArchivedList
import com.mok.buylist.data.ShoppingItem
import com.mok.buylist.ui.STATS_WEEKS
import com.mok.buylist.ui.computeStats
import com.mok.buylist.ui.averagePurchaseInterval
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatsTest {
    private val zone = ZoneId.of("UTC")

    // Пятница, 9 октября 2026.
    private val now = Instant.parse("2026-10-09T20:00:00Z").toEpochMilli()

    private fun list(iso: String, vararg names: String, tripMinutes: Int = 0) = ArchivedList(
        completedAt = Instant.parse(iso).toEpochMilli(),
        itemCount = names.size,
        items = names.joinToString("\n"),
        tripMillis = tripMinutes * 60_000L,
    )

    @Test
    fun emptyArchiveGivesZeros() {
        val stats = computeStats(emptyList(), now, zone)
        assertEquals(0, stats.lists)
        assertEquals(0, stats.items)
        assertEquals(0, stats.averageItems)
        assertEquals(emptyList<Any>(), stats.top)
        assertEquals(STATS_WEEKS, stats.weeks.size)
        assertNull(stats.busiestDay)
    }

    @Test
    fun countsListsItemsAndAverage() {
        val stats = computeStats(
            listOf(list("2026-10-08T10:00:00Z", "Молоко", "Хлеб", "Яйца"), list("2026-10-02T10:00:00Z", "Молоко", "Сыр")),
            now,
            zone,
        )
        assertEquals(2, stats.lists)
        assertEquals(5, stats.items)
        assertEquals(3, stats.averageItems) // 2,5 округляется вверх
    }

    @Test
    fun topShowsOnlyRepeatedProductsIgnoringCase() {
        val stats = computeStats(
            listOf(
                list("2026-10-08T10:00:00Z", "Молоко", "Хлеб", "Яйца"),
                list("2026-10-05T10:00:00Z", "молоко", "Хлеб"),
                list("2026-10-01T10:00:00Z", "Молоко", "Сыр"),
            ),
            now,
            zone,
        )
        assertEquals(listOf("Молоко" to 3, "Хлеб" to 2), stats.top.map { it.name to it.count })
    }

    @Test
    fun weeksGoFromOldestToCurrentAndCountLists() {
        val stats = computeStats(
            listOf(
                list("2026-10-08T10:00:00Z", "А"), // эта неделя (с 5 октября)
                list("2026-10-05T00:00:00Z", "Б"), // тоже эта: понедельник
                list("2026-10-04T23:59:00Z", "В"), // прошлая неделя
                list("2026-01-01T10:00:00Z", "Г"), // давно, в график не попадает
            ),
            now,
            zone,
        )
        assertEquals(LocalDate.of(2026, 10, 5), stats.weeks.last().start)
        assertEquals(LocalDate.of(2026, 8, 17), stats.weeks.first().start)
        assertEquals(2, stats.weeks.last().lists)
        assertEquals(1, stats.weeks[STATS_WEEKS - 2].lists)
        assertEquals(3, stats.weeks.sumOf { it.lists })
    }

    @Test
    fun busiestDayNeedsEnoughData() {
        val two = listOf(list("2026-10-08T10:00:00Z", "А"), list("2026-10-01T10:00:00Z", "Б"))
        assertNull(computeStats(two, now, zone).busiestDay)
        val three = two + list("2026-09-24T10:00:00Z", "В") + list("2026-09-25T10:00:00Z", "Г")
        assertEquals(DayOfWeek.THURSDAY, computeStats(three, now, zone).busiestDay)
    }

    @Test
    fun tripTimeAveragesOnlyListsWhereItIsKnown() {
        val stats = computeStats(
            listOf(
                list("2026-10-08T10:00:00Z", "А", tripMinutes = 20),
                list("2026-10-05T10:00:00Z", "Б", tripMinutes = 40),
                list("2026-10-01T10:00:00Z", "В"), // старая запись или одна покупка: времени нет
            ),
            now,
            zone,
        )
        assertEquals(30, stats.averageTripMinutes)
        assertEquals(60, stats.totalTripMinutes)
        assertEquals(0, computeStats(listOf(list("2026-10-01T10:00:00Z", "В")), now, zone).averageTripMinutes)
    }
    @Test
    fun monthlyBarsFollowCalendarMonths() {
        val stats = computeStats(listOf(list("2026-10-01T00:00:00Z", "А"), list("2026-09-30T23:59:00Z", "Б")), now, zone)
        assertEquals(LocalDate.of(2026, 10, 1), stats.months.last().start)
        assertEquals(1, stats.months.last().lists)
        assertEquals(1, stats.months[6].lists)
    }
}
