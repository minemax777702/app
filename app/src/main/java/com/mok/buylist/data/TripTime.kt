package com.mok.buylist.data

/**
 * Пауза между отметками «куплено», после которой считаем, что человек пошёл в другое место
 * (другой магазин или другой день). Внутри одного магазина между соседними товарами редко проходит
 * больше получаса, а дорога между магазинами обычно длиннее.
 */
const val VISIT_GAP_MILLIS = 30 * 60_000L

/**
 * Время похода: [millis] — сумма длительностей всех заходов, [visits] — сколько их было.
 * Заход меряется от первой до последней отметки в нём, поэтому реальное время чуть больше: путь до
 * первого товара приложению не виден. Заход с одной покупкой длится 0.
 */
data class TripTime(val millis: Long, val visits: Int)

/** Делит моменты покупок на заходы по паузам длиннее [gapMillis] и складывает их длительности. */
fun computeTripTime(purchaseTimes: List<Long>, gapMillis: Long = VISIT_GAP_MILLIS): TripTime {
    if (purchaseTimes.isEmpty()) return TripTime(0, 0)
    val sorted = purchaseTimes.sorted()
    var total = 0L
    var visits = 1
    var visitStart = sorted.first()
    var previous = sorted.first()
    for (time in sorted.drop(1)) {
        if (time - previous > gapMillis) {
            total += previous - visitStart
            visits++
            visitStart = time
        }
        previous = time
    }
    total += previous - visitStart
    return TripTime(total, visits)
}

/** Сколько целых минут показывать; 0 — времени нет (старая запись, одна покупка или меньше полминуты). */
fun tripMinutes(tripMillis: Long): Int = ((tripMillis + 30_000L) / 60_000L).toInt()
