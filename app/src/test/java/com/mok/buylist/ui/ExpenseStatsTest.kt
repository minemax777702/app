package com.mok.buylist.ui

import com.mok.buylist.data.ArchivedList
import com.mok.buylist.data.LentaProduct
import com.mok.buylist.data.ShoppingItem
import com.mok.buylist.data.purchaseEvent
import com.mok.buylist.data.decodePurchaseEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ExpenseStatsTest {
    @Test fun weightIsIncludedInArchiveAndExpenseTotal() {
        val item = ShoppingItem(name = "Картофель", productId = 42, weightGrams = 2540)
        val product = LentaProduct(42, item.name, "", "", "30", "card", "", "")
        val event = purchaseEvent(item, product, 1, "u")
        val list = ArchivedList(completedAt = 1, itemCount = 1, items = item.name, purchaseEvents = event)
        val row = expensePurchases(listOf(list), emptyList()).single()
        assertEquals(2540, row.weightGrams)
        assertEquals(7620L, row.total)
    }
    @Test fun archiveRetainsOrdinaryAndImpulsePurchasesInTheSameList() {
        val ordinary = ShoppingItem(name = "Хлеб", productId = 1)
        val impulse = ShoppingItem(name = "Сыр", productId = 2)
        val a = LentaProduct(1, ordinary.name, "", "", "20", "card", "", "")
        val b = LentaProduct(2, impulse.name, "", "", "30", "card", "", "")
        val events = listOf(purchaseEvent(ordinary, a, 1, "u"), purchaseEvent(impulse, b, 2, "u", impulsive = true))
        val archive = ArchivedList(completedAt = 2, itemCount = 2, items = "Хлеб\nСыр",
            quantities = "1\n1", purchaseEvents = events.joinToString("\n"))
        val rows = expensePurchases(listOf(archive), emptyList())
        assertEquals(2, rows.size)
        assertEquals(listOf(false, true), rows.map { it.impulsive })
        assertEquals(5000L, exactExpenseSum(rows))
        assertEquals(3000L, exactExpenseSum(rows.filter { it.impulsive }))
        assertEquals(1, rows.map { it.trip }.distinct().size)
    }
    @Test fun scannedPackagePriceIsStoredInPurchaseSnapshot() {
        val item = ShoppingItem(name = "Весовой товар", productId = 1, quantity = 2)
        val product = LentaProduct(1, item.name, "", "", "999", "card", "", "")
        val encoded = purchaseEvent(item, product, 1, "u", priceOverride = 12550, impulsive = true)
        val event = decodePurchaseEvent(encoded)!!
        assertEquals(12550L, event.unitPrice)
        assertEquals(2, event.quantity)
        org.junit.Assert.assertTrue(event.impulsive)
        // v9 appends two UTF strings after the legacy fields. Construct the actual
        // pre-impulsive format rather than trimming an arbitrary number of bytes.
        val legacy = java.io.ByteArrayOutputStream().also { bytes ->
            java.io.DataOutputStream(bytes).use { out ->
                out.writeUTF(event.id); out.writeLong(event.productId ?: 0)
                out.writeUTF(event.name); out.writeLong(event.unitPrice ?: -1)
                out.writeUTF(event.priceType); out.writeInt(event.quantity); out.writeLong(event.boughtAt)
                out.writeUTF(event.buyer); out.writeUTF(event.address); out.writeUTF(event.channel)
                out.writeLong(event.fetchedAt)
            }
        }.toByteArray()
        org.junit.Assert.assertFalse(decodePurchaseEvent(java.util.Base64.getEncoder().encodeToString(legacy))!!.impulsive)
        val saved = item.copy(deleted = true, purchaseEvent = encoded)
        val current = expensePurchases(emptyList(), listOf(saved)).single()
        org.junit.Assert.assertTrue(current.impulsive)
        assertEquals(25100L, current.total)
        val archive = ArchivedList(completedAt = 1, itemCount = 1, items = item.name,
            quantities = "2", purchaseEvents = encoded)
        val archived = expensePurchases(listOf(archive), emptyList()).single()
        org.junit.Assert.assertTrue(archived.impulsive)
        assertEquals(25100L, archived.total)
    }

    @Test fun repeatedImpulsePurchasesKeepSeparateSyncIdentities() {
        val item = ShoppingItem(name = "Товар", productId = 1)
        val product = LentaProduct(1, item.name, "", "", "10", "card", "", "")
        val first = purchaseEvent(item, product, 1, "u", impulsive = true)
        val second = purchaseEvent(item, product, 2, "u", impulsive = true)
        val a = com.mok.buylist.sync.SyncItem(item.name, 1, 1, true, "u", purchaseEvent = first, productId = 1)
        val b = a.copy(at = 2, purchaseEvent = second)
        org.junit.Assert.assertNotEquals(a.key, b.key)
        assertEquals("impulse:${decodePurchaseEvent(first)!!.id}", a.key)
    }
    @Test fun exactTotalsRequireEveryPriceAndDetectOverflow() {
        val row = ExpensePurchase("a", "lenta:1", "Товар", "trip", "u", 1, 2, 150)
        assertEquals(300L, exactExpenseSum(listOf(row)))
        assertEquals(0L, exactExpenseSum(listOf(row.copy(unitPrice = 0))))
        assertNull(exactExpenseSum(listOf(row, row.copy(unitPrice = null))))
        assertNull(exactExpenseSum(listOf(row.copy(unitPrice = Long.MAX_VALUE))))
        assertNull(exactExpenseSum(emptyList()))
    }
    @Test fun sameNamesRemainSeparateByProductId() {
        val first = ShoppingItem(name = "Молоко", productId = 101, deleted = true)
        val second = ShoppingItem(name = "Молоко", productId = 102, deleted = true)
        fun product(id: Long) = LentaProduct(id, "Молоко", "", "", "10", "card", "", "")
        val rows = expensePurchases(emptyList(), listOf(
            first.copy(purchaseEvent = purchaseEvent(first, product(101), 1, "u")),
            second.copy(purchaseEvent = purchaseEvent(second, product(102), 2, "u")),
        ))
        assertEquals(setOf("lenta:101", "lenta:102"), rows.map { it.productKey }.toSet())
        assertEquals(2, expenseProducts(rows).size)
    }

    @Test fun frequencyCountsTripsAndUnitsSeparately() {
        val purchases = listOf(2, 1, 5).mapIndexed { index, quantity ->
            ExpensePurchase("event$index", "lenta:1", "Молоко", "trip$index", "u", 1, quantity, 1000)
        }
        val product = expenseProducts(purchases).single()
        assertEquals(3, product.trips)
        assertEquals(8L, product.units)
        assertEquals(8000L, product.total)
    }

    @Test fun unknownPriceDoesNotBecomeAZeroPrice() {
        val unknown = ExpensePurchase("a", "lenta:1", "Товар", "trip", "u", 1, 2, null)
        val free = unknown.copy(id = "b", unitPrice = 0)
        assertNull(unknown.total)
        assertEquals(0L, free.total)
        assertEquals(0L, expenseSum(listOf(unknown, free)))
    }

    @Test fun userFilterKeepsPurchaseEventsAligned() {
        val list = ArchivedList(completedAt = 1, itemCount = 2, items = "Первый\nВторой", buyers = "a\nb", quantities = "2\n3", purchaseEvents = "event1\nevent2")
        val filtered = filterByUser(listOf(list), "b").single()
        assertEquals(listOf("Второй"), filtered.names)
        assertEquals(listOf("event2"), filtered.events)
        assertEquals(listOf(3), filtered.counts)
    }

    @Test fun calendarPeriodsUseLocalDates() {
        val now = LocalDate.of(2026, 11, 19)
        assertEquals(LocalDate.of(2026, 11, 13), periodStart(ExpensePeriod.WEEK, now))
        assertEquals(LocalDate.of(2026, 10, 20), periodStart(ExpensePeriod.MONTH, now))
        val list = ArchivedList(completedAt = now.atStartOfDay(ZoneId.of("Europe/Moscow")).toInstant().toEpochMilli(), itemCount = 1, items = "Товар")
        assertEquals(1, archiveForPeriod(listOf(list), now, now, ZoneId.of("Europe/Moscow")).size)
    }

    @Test fun historyUsesDistinctLocalDaysAndSeparatesPriceTypes() {
        val zone = ZoneId.of("Europe/Moscow")
        fun time(day: Int, hour: Int) = LocalDate.of(2026, 11, day).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        val row = ExpensePurchase("a", "lenta:1", "Товар", "trip", "u", time(1, 9), 2, 100, "card")
        val rows = listOf(row, row.copy(id = "b", at = time(1, 18), unitPrice = 300),
            row.copy(id = "c", at = time(5, 10), unitPrice = 50, priceType = "promo"),
            row.copy(id = "d", at = time(5, 11), unitPrice = null))
        assertEquals(4, rows.size)
        assertEquals(4L, averagePurchaseInterval(rows.map { expenseDate(it, zone) }))
        assertEquals(listOf(PriceSummary("card", 100, 300, 200), PriceSummary("promo", 50, 50, 50)), priceSummaries(rows))
        assertNull(averagePurchaseInterval(rows.take(2).map { expenseDate(it, zone) }))
    }

    @Test fun priceChartPeriodsUseLocalCalendarAndDoNotApplyStatsPeriod() {
        val zone = ZoneId.of("Europe/Moscow")
        val today = LocalDate.of(2026, 11, 20)
        fun purchase(id: String, day: LocalDate) = ExpensePurchase(id, "lenta:1", "Товар", "trip", "u",
            day.atStartOfDay(zone).toInstant().toEpochMilli(), 1, 100)
        val rows = listOf(purchase("month", today.minusMonths(1).plusDays(1)),
            purchase("half", today.minusMonths(5)), purchase("year", today.minusMonths(10)),
            purchase("older", today.minusYears(2)), purchase("future", today.plusDays(1)))
        fun ids(period: PricePeriod) = pricesForPeriod(rows, period, today, zone).map { it.id }
        assertEquals(listOf("month"), ids(PricePeriod.MONTH))
        assertEquals(listOf("month", "half"), ids(PricePeriod.HALF_YEAR))
        assertEquals(listOf("month", "half", "year"), ids(PricePeriod.YEAR))
        assertEquals(listOf("month", "half", "year", "older"), ids(PricePeriod.ALL))
    }
}
