package com.mok.buylist.ui

/**
 * Purchase statistics are computed from historical events only. A missing price
 * counts as an item, never as a zero-cost purchase.
 */
internal data class PurchaseCoverage(
    val purchases: Int,
    val priced: Int,
    val unpriced: Int,
    val amount: Long,
    val distinctProducts: Int,
    val units: Long,
)

internal fun purchaseCoverage(rows: List<ExpensePurchase>): PurchaseCoverage {
    val priced = rows.filter { it.total != null }
    return PurchaseCoverage(
        purchases = rows.size,
        priced = priced.size,
        unpriced = rows.size - priced.size,
        amount = expenseSum(priced),
        distinctProducts = rows.map { it.productKey }.distinct().size,
        units = rows.sumOf { it.quantity.toLong() },
    )
}

internal data class StoreExpenseSummary(
    val id: String,
    val events: Int,
    val trips: Int,
    val priced: Int,
    val total: Long,
)

internal fun expenseByStore(rows: List<ExpensePurchase>): List<StoreExpenseSummary> =
    rows.groupBy { it.storeId }.map { (id, purchases) ->
        StoreExpenseSummary(id, purchases.size, closedTripCount(purchases), purchases.count { it.total != null },
            expenseSum(purchases))
    }.sortedWith(compareByDescending<StoreExpenseSummary> { it.total }
        .thenByDescending { it.events }.thenBy { it.id })

internal data class FrequentProductSummary(
    val key: String,
    val name: String,
    val purchases: Int,
    val trips: Int,
    val units: Long,
    val knownTotal: Long,
)

internal fun frequentProducts(rows: List<ExpensePurchase>): List<FrequentProductSummary> =
    rows.groupBy { it.productKey }.map { (key, purchases) ->
        FrequentProductSummary(key, purchases.maxBy { it.at }.name, purchases.size, closedTripCount(purchases),
            purchases.sumOf { it.quantity.toLong() }, expenseSum(purchases))
    }.sortedWith(compareByDescending<FrequentProductSummary> { it.trips }
        .thenByDescending { it.units }.thenBy { it.name })
