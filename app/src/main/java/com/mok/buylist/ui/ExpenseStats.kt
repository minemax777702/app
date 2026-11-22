package com.mok.buylist.ui

import com.mok.buylist.data.ArchivedList
import com.mok.buylist.data.ShoppingItem
import com.mok.buylist.data.decodePurchaseEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal data class ExpensePurchase(
    val id: String, val productKey: String, val name: String, val trip: String,
    val buyer: String, val at: Long, val quantity: Int, val unitPrice: Long?, val priceType: String = "", val impulsive: Boolean = false,
    val weightGrams: Int? = null,
) {
    val total: Long? get() = unitPrice?.let { price ->
        if (weightGrams != null) com.mok.buylist.data.priceForWeight(price, weightGrams)
        else runCatching { Math.multiplyExact(price, quantity.toLong()) }.getOrNull()
    }
}

internal data class ExpenseProduct(val key: String, val name: String, val trips: Int, val units: Long, val total: Long)

/** Снимки архива и текущего похода объединяются по ID события, а не по названию. */
internal fun expensePurchases(archive: List<ArchivedList>, bought: List<ShoppingItem>): List<ExpensePurchase> {
    val purchases = mutableListOf<ExpensePurchase>()
    fun add(encoded: String, name: String, trip: String, fallbackId: String, at: Long, quantity: Int, buyer: String) {
        val event = decodePurchaseEvent(encoded)
        val eventId = event?.id?.takeIf { it.isNotBlank() } ?: fallbackId
        purchases += ExpensePurchase(eventId,
            event?.productId?.let { "lenta:$it" } ?: "event:$eventId",
            event?.name ?: name, trip, event?.buyer?.ifBlank { buyer } ?: buyer,
            event?.boughtAt ?: at, event?.quantity ?: quantity, event?.unitPrice, event?.priceType.orEmpty(), event?.impulsive == true, event?.weightGrams)
    }
    archive.forEach { list ->
        list.names.forEachIndexed { index, name ->
            add(list.events[index], name, list.uid, "${list.uid}:$index", list.completedAt, list.counts[index], list.buyerIds[index])
        }
    }
    bought.filter { it.deleted }.forEach { item ->
        add(item.purchaseEvent, item.name, "active", "active:${item.id}", item.updatedAt, item.quantity, item.authorId)
    }
    return purchases.distinctBy { it.id }
}

internal enum class ExpensePeriod(val title: String) { WEEK("Неделя"), MONTH("Месяц"), YEAR("Год"), ALL("Всё время") }
internal enum class ExpenseInterval(val title: String) { DAY("Дни"), WEEK("Недели"), MONTH("Месяцы") }

internal fun periodStart(period: ExpensePeriod, today: LocalDate): LocalDate? = when (period) {
    ExpensePeriod.WEEK -> today.minusDays(6)
    ExpensePeriod.MONTH -> today.minusMonths(1).plusDays(1)
    ExpensePeriod.YEAR -> today.minusYears(1).plusDays(1)
    ExpensePeriod.ALL -> null
}

internal fun expenseDate(purchase: ExpensePurchase, zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(purchase.at).atZone(zone).toLocalDate()

internal fun expenseSum(purchases: List<ExpensePurchase>): Long = purchases.mapNotNull { it.total }
    .fold(0L) { sum, value -> runCatching { Math.addExact(sum, value) }.getOrDefault(Long.MAX_VALUE) }

internal fun exactExpenseSum(purchases: List<ExpensePurchase>): Long? {
    if (purchases.isEmpty()) return null
    var total = 0L
    for (purchase in purchases) {
        val amount = purchase.total ?: return null
        total = try { Math.addExact(total, amount) } catch (_: ArithmeticException) { return null }
    }
    return total
}

internal fun expenseProducts(purchases: List<ExpensePurchase>): List<ExpenseProduct> =
    purchases.groupBy { it.productKey }.map { (key, rows) ->
        ExpenseProduct(key, rows.maxBy { it.at }.name, rows.map { it.trip }.distinct().size,
            rows.sumOf { it.quantity.toLong() }, expenseSum(rows))
    }.sortedWith(compareByDescending<ExpenseProduct> { it.trips }.thenBy { it.name })

internal fun archiveForPeriod(archive: List<ArchivedList>, start: LocalDate?, today: LocalDate, zone: ZoneId): List<ArchivedList> =
    archive.mapNotNull { list ->
        val indices = list.names.indices.filter { index ->
            val time = decodePurchaseEvent(list.events[index])?.boughtAt ?: list.completedAt
            val day = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
            !day.isAfter(today) && (start == null || !day.isBefore(start))
        }
        if (indices.isEmpty()) null else list.copy(
            itemCount = indices.size,
            items = indices.joinToString("\n") { list.names[it] },
            buyers = indices.joinToString("\n") { list.buyerIds[it] },
            quantities = indices.joinToString("\n") { list.counts[it].toString() },
            purchaseEvents = indices.joinToString("\n") { list.events[it] },
        )
    }
