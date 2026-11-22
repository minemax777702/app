package com.mok.buylist.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Дата завершения списка для архива: «Сегодня, 19:42», «Вчера, 08:15», «9 октября, 19:42»,
 * а для прошлых лет ещё и год: «9 октября 2025, 19:42».
 */
internal fun formatArchiveDate(
    completedAt: Long,
    now: Long,
    zone: ZoneId,
    today: String,
    yesterday: String,
    locale: Locale,
): String {
    val moment = Instant.ofEpochMilli(completedAt).atZone(zone)
    val current = Instant.ofEpochMilli(now).atZone(zone)
    val time = DateTimeFormatter.ofPattern("HH:mm", locale).format(moment)
    val day = moment.toLocalDate()
    val currentDay = current.toLocalDate()
    return when {
        day == currentDay -> "$today, $time"
        day == currentDay.minusDays(1) -> "$yesterday, $time"
        day.year == currentDay.year -> DateTimeFormatter.ofPattern("d MMMM, HH:mm", locale).format(moment)
        else -> DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", locale).format(moment)
    }
}
