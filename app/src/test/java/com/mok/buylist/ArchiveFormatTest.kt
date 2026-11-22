package com.mok.buylist

import com.mok.buylist.data.ArchivedList
import com.mok.buylist.ui.formatArchiveDate
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class ArchiveFormatTest {
    private val zone = ZoneId.of("UTC")
    private val ru = Locale.forLanguageTag("ru")
    private val now = Instant.parse("2026-10-09T20:00:00Z").toEpochMilli()

    private fun format(iso: String) =
        formatArchiveDate(Instant.parse(iso).toEpochMilli(), now, zone, today = "Сегодня", yesterday = "Вчера", locale = ru)

    @Test
    fun todayAndYesterdayAreNamed() {
        assertEquals("Сегодня, 19:42", format("2026-10-09T19:42:00Z"))
        assertEquals("Вчера, 08:15", format("2026-10-08T08:15:00Z"))
    }

    @Test
    fun olderDatesShowDayAndMonth() {
        assertEquals("5 октября, 12:00", format("2026-10-05T12:00:00Z"))
    }

    @Test
    fun otherYearShowsYear() {
        assertEquals("31 декабря 2025, 12:00", format("2025-12-31T12:00:00Z"))
    }

    @Test
    fun archivedListSplitsNames() {
        val list = ArchivedList(completedAt = 0, itemCount = 3, items = "Молоко\nХлеб\nЯйца")
        assertEquals(listOf("Молоко", "Хлеб", "Яйца"), list.names)
        assertEquals(emptyList<String>(), ArchivedList(completedAt = 0, itemCount = 0, items = "").names)
    }
}
