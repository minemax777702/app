package com.mok.buylist.ui

import com.mok.buylist.data.ArchivedList
import com.mok.buylist.data.ShoppingItem
import com.mok.buylist.data.nameKeyOf
import com.mok.buylist.data.tripMinutes
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class TopProduct(val name: String, val count: Int)

/** Сколько списков закончено за неделю, которая началась в [start] (понедельник). */
data class WeekBar(val start: LocalDate, val lists: Int)

data class ShoppingStats(
    val lists: Int,
    val items: Int,
    /** Сколько товаров в среднем в списке, округлено. */
    val averageItems: Int,
    /** Товары, купленные больше одного раза, от частых к редким. */
    val top: List<TopProduct>,
    /** Последние недели, от старой к текущей. */
    val weeks: List<WeekBar>,
    val months: List<WeekBar>,
    /** Средний поход в минутах по спискам, у которых время известно; 0 — данных нет. */
    val averageTripMinutes: Int,
    /** Сколько всего времени проведено в магазинах, в минутах. */
    val totalTripMinutes: Int,
    /** День недели, в который заканчивали больше всего списков; null, пока данных мало. */
    val busiestDay: DayOfWeek?,
)

private const val TOP_LIMIT = 5
internal const val STATS_WEEKS = 8
private const val MIN_LISTS_FOR_BUSIEST_DAY = 3

/** Считает статистику по архиву: в нём только купленное, поэтому это именно «что покупаете», а не «что добавляли». */
internal fun computeStats(archive: List<ArchivedList>, now: Long, zone: ZoneId): ShoppingStats {
    val counts = HashMap<String, Int>()
    val shownNames = HashMap<String, String>()
    for (list in archive) {
        for (name in list.names) {
            val key = nameKeyOf(name)
            counts[key] = (counts[key] ?: 0) + 1
            shownNames.putIfAbsent(key, name)
        }
    }
    val top = counts.entries
        .filter { it.value > 1 }
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(TOP_LIMIT)
        .map { TopProduct(shownNames.getValue(it.key), it.value) }

    val days = archive.map { Instant.ofEpochMilli(it.completedAt).atZone(zone).toLocalDate() }
    val thisWeek = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val weeks = (STATS_WEEKS - 1 downTo 0).map { back ->
        val start = thisWeek.minusWeeks(back.toLong())
        WeekBar(start, days.count { !it.isBefore(start) && it.isBefore(start.plusWeeks(1)) })
    }

    val thisMonth = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().withDayOfMonth(1)
    val months = (7 downTo 0).map { back ->
        val start = thisMonth.minusMonths(back.toLong())
        WeekBar(start, days.count { !it.isBefore(start) && it.isBefore(start.plusMonths(1)) })
    }

    val busiest = if (archive.size >= MIN_LISTS_FOR_BUSIEST_DAY) {
        days.groupingBy { it.dayOfWeek }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<DayOfWeek, Int>> { it.value }.thenBy { it.key })
            .first().key
    } else {
        null
    }

    // Старые записи (до появления таймера) и походы из одной покупки времени не имеют — в среднее не входят.
    val timed = archive.filter { tripMinutes(it.tripMillis) > 0 }
    val tripMillis = timed.sumOf { it.tripMillis }

    val items = archive.sumOf { it.counts.sum() }
    return ShoppingStats(
        lists = archive.size,
        items = items,
        averageItems = if (archive.isEmpty()) 0 else Math.round(items.toDouble() / archive.size).toInt(),
        top = top,
        weeks = weeks,
        months = months,
        averageTripMinutes = if (timed.isEmpty()) 0 else tripMinutes(tripMillis / timed.size),
        totalTripMinutes = tripMinutes(tripMillis),
        busiestDay = busiest,
    )
}

internal fun averagePurchaseInterval(days: List<LocalDate>): Long? {
    val ordered = days.distinct().sorted()
    if (ordered.size < 2) return null
    return Math.round(java.time.temporal.ChronoUnit.DAYS.between(ordered.first(), ordered.last()).toDouble() / (ordered.size - 1))
}
