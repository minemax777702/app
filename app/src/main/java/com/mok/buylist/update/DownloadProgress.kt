package com.mok.buylist.update

import kotlin.math.ceil
import java.util.Locale

internal data class DownloadProgress(
    val bytes: Long,
    val total: Long,
    val bytesPerSecond: Double? = null,
    val verifying: Boolean = false,
) {
    val fraction: Float get() = if (total > 0) (bytes.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
    val percent: Int get() = if (bytes >= total && total > 0) 100 else (fraction * 100).toInt().coerceIn(0, 99)
    val secondsRemaining: Long? get() = bytesPerSecond?.takeIf { it.isFinite() && it > 0 && total > 0 }
        ?.let { ceil((total - bytes).coerceAtLeast(0L) / it).toLong() }
}

/** Monotonic clock supplied by the caller; smooth short network bursts, report at most four times/s. */
internal class DownloadMeter(private val total: Long) {
    private var lastAt: Long? = null
    private var lastBytes = 0L
    private var speed: Double? = null

    fun sample(bytes: Long, nowMillis: Long): DownloadProgress? {
        val previous = lastAt
        if (previous == null) {
            lastAt = nowMillis
            lastBytes = bytes
            return DownloadProgress(bytes, total)
        }
        val elapsed = nowMillis - previous
        if (elapsed < 250 && bytes < total) return null
        if (elapsed > 0) {
            val instantaneous = (bytes - lastBytes).coerceAtLeast(0L) * 1000.0 / elapsed
            val alpha = 1.0 - kotlin.math.exp(-elapsed / 1800.0)
            speed = speed?.let { it + alpha * (instantaneous - it) } ?: instantaneous
        }
        lastAt = nowMillis
        lastBytes = bytes
        return DownloadProgress(bytes, total, speed?.takeIf { it > 0 })
    }
}

internal fun downloadSize(bytes: Long): String =
    String.format(Locale.forLanguageTag("ru-RU"), "%.1f", bytes.coerceAtLeast(0L) / 1_000_000.0) + " МБ"

internal fun downloadEta(seconds: Long?): String = when {
    seconds == null -> "Оцениваем время…"
    seconds <= 0L -> "Загрузка завершена"
    seconds < 60L -> "Осталось ≈ $seconds с"
    seconds < 3600L -> "Осталось ≈ ${(seconds + 59) / 60} мин"
    else -> "Осталось ≈ ${seconds / 3600} ч ${(seconds % 3600 + 59) / 60 % 60} мин"
}
