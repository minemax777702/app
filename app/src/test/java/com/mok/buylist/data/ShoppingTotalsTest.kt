package com.mok.buylist.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Test
import com.mok.buylist.sync.Snapshot
import com.mok.buylist.sync.SyncArchive
import com.mok.buylist.sync.SyncCodec
import com.mok.buylist.sync.SyncItem
import com.mok.buylist.sync.SyncMerge

class ShoppingTotalsTest {
    @Test fun weightedPriceAndSnapshotPreserveWeight() {
        assertEquals(1000, parseWeightGrams(""))
        assertEquals(2540, parseWeightGrams("2,54"))
        assertEquals(1, parseWeightGrams("0.001"))
        assertNull(parseWeightGrams("0"))
        assertNull(parseWeightGrams("0.0001"))
        assertEquals(7620L, priceForWeight(3000, 2540))
        assertEquals(1L, priceForWeight(1, 500))
        assertNull(priceForWeight(Long.MAX_VALUE, 999000))
        val item = ShoppingItem(name = "Картофель", productId = 42, weightGrams = 2540)
        val product = LentaProduct(42, item.name, "", "", "30", "card", "", "Адрес")
        val event = purchaseEvent(item, product, 100, "buyer")
        assertEquals(2540, decodePurchaseEvent(event)!!.weightGrams)
        assertEquals(7620, calculateShoppingTotals(listOf(PricedLine(1, false, 3000, 2540))).total.kopecks)
        val snapshot = Snapshot(items = listOf(SyncItem(item.name, 1, 2, false, "buyer", productId = 42, weightGrams = 2540)))
        assertEquals(snapshot, SyncCodec.decode(SyncCodec.encode(snapshot)))
    }
    @Test fun purchaseSnapshotSurvivesExchangeAndKeepsOriginalPrice() {
        val product = LentaProduct(42, "Молоко", "", "", "100,00", "card", "", "Адрес", storeId = 3, channel = "locc", fetchedAt = 50)
        val item = ShoppingItem(name = "Молоко", quantity = 3, productId = 42)
        val event = purchaseEvent(item, product, 100, "buyer")
        val decoded = decodePurchaseEvent(event)!!
        assertEquals(42L, decoded.productId)
        assertEquals(10_000L, decoded.unitPrice)
        assertEquals(3, decoded.quantity)
        assertEquals("card", decoded.priceType)
        assertEquals("Адрес", decoded.address)
        assertEquals(50L, decoded.fetchedAt)
        val reassigned = decodePurchaseEvent(reassignPurchaseEvent(event, "buyer", "new-buyer"))!!
        assertEquals(decoded.copy(buyer = "new-buyer"), reassigned)
        assertNotEquals(event, purchaseEvent(item, product, 100, "buyer"))
        val snapshot = Snapshot(items = listOf(SyncItem(item.name, 1, 100, true, "buyer", 3, event, 42)),
            archives = listOf(SyncArchive("archive", 100, listOf(item.name), listOf("buyer"), 0, 0, listOf(3), listOf(event))))
        assertEquals(snapshot, SyncCodec.decode(SyncCodec.encode(snapshot)))
        assertEquals(snapshot, SyncMerge.merge(snapshot, snapshot))
    }

    @Test fun syncKeepsEqualNamesWithDifferentIds() {
        val first = SyncItem("Молоко", 1, 2, false, "buyer", productId = 41)
        val second = first.copy(productId = 42)
        val merged = SyncMerge.merge(Snapshot(items = listOf(first)), Snapshot(items = listOf(second)))
        assertEquals(setOf(41L, 42L), merged.items.map { it.productId }.toSet())
        assertEquals(merged, SyncCodec.decode(SyncCodec.encode(merged)))
    }

    @Test fun remainingSnapshotDoesNotChangeWhenCatalogPriceChanges() {
        val item = ShoppingItem(name = "Молоко", productId = 42, quantity = 2)
        val product = LentaProduct(42, "Молоко", "", "", "69.99", "card", "", "Адрес")
        val saved = purchaseEvent(item, product, 100, "")
        val archived = ArchivedList(completedAt = 100, itemCount = 1, items = "Хлеб", remainingEvents = "v1|$saved")
        assertEquals(6999L, archived.remaining.single().unitPrice)
        assertEquals(2, archived.remaining.single().quantity)
        val updatedCatalogPrice = product.copy(price = "100.00")
        assertEquals(10000L, parsePriceKopecks(updatedCatalogPrice.price))
        assertEquals(6999L, archived.remaining.single().unitPrice)
        val unknown = ArchivedList(completedAt = 100, itemCount = 1, items = "Хлеб")
        assertEquals(false, unknown.hasRemainingSnapshot)
    }

    @Test fun purchaseAndRemovalChangeCorrectAmounts() {
        val first = PricedLine(3, false, 10_000)
        val second = PricedLine(2, false, 5_000)
        assertEquals(ShoppingTotals(PartialAmount(40_000, 2, 0), PartialAmount(40_000, 2, 0)), calculateShoppingTotals(listOf(first, second)))
        assertEquals(ShoppingTotals(PartialAmount(40_000, 2, 0), PartialAmount(10_000, 1, 0)), calculateShoppingTotals(listOf(first.copy(bought = true), second)))
        assertEquals(30_000, calculateShoppingTotals(listOf(first.copy(bought = true))).total.kopecks)
    }

    @Test fun unknownAndZeroAreDifferent() {
        val result = calculateShoppingTotals(listOf(PricedLine(1, false, null), PricedLine(1, false, 0)))
        assertEquals(PartialAmount(0, 1, 1), result.total)
        assertEquals(PartialAmount(0, 1, 1), result.remaining)
    }

    @Test fun priceParsingIsExact() {
        assertEquals(12345L, parsePriceKopecks("123,45"))
        assertEquals(0L, parsePriceKopecks("0"))
        assertNull(parsePriceKopecks("1.001"))
        assertNull(parsePriceKopecks("-1"))
        assertNull(parsePriceKopecks("нет"))
    }

    @Test fun overflowIsUnknown() {
        assertEquals(PartialAmount(0, 0, 1), calculateShoppingTotals(listOf(PricedLine(999, false, Long.MAX_VALUE))).total)
    }
    @Test fun earlyFinishOnlyCountsBoughtProductsNotRemainingPlan() {
        val bought = ShoppingItem(name = "Кофе", quantity = 1)
        val event = purchaseEvent(bought, null, 100L, "buyer", priceOverride = 32_999L)
        val remaining = ShoppingItem(name = "Не куплено", quantity = 1)
        val estimated = purchaseEvent(remaining, null, 100L, "",
            priceOverride = 169_478L)
        val archive = ArchivedList(completedAt = 100L, itemCount = 1,
            items = "Кофе", quantities = "1", purchaseEvents = event,
            remainingEvents = "v1|$estimated")
        assertEquals(32_999L, archivedPurchaseTotal(archive).kopecks)
        assertEquals(1, archivedPurchaseTotal(archive).known)
        assertEquals(0, archivedPurchaseTotal(archive).unknown)
        // 329.99 + 1694.78 = 2024.77, but remaining stock is NOT an expense.
        assertEquals(202_477L,
            calculateShoppingTotals(listOf(
                PricedLine(1, true, 32_999L),
                PricedLine(1, false, 169_478L)
            )).total.kopecks)
    }

    @Test fun archiveUnpricedBoughtItemDoesNotCountRemainingEstimatedPrices() {
        val priced = purchaseEvent(ShoppingItem(name = "Кофе"), null,
            100L, "buyer", priceOverride = 32_999L)
        val withoutPrice = purchaseEvent(ShoppingItem(name = "Яйца"), null,
            101L, "buyer")
        val remaining = purchaseEvent(ShoppingItem(name = "Хлеб"), null,
            101L, "", priceOverride = 50_000L)
        val archive = ArchivedList(completedAt = 101L, itemCount = 2,
            items = listOf("Кофе", "Яйца").joinToString("\n"),
            quantities = listOf(1, 1).joinToString("\n"),
            purchaseEvents = listOf(priced, withoutPrice).joinToString("\n"),
            remainingEvents = "v1|$remaining")
        assertEquals(PartialAmount(32_999L, 1, 1), archivedPurchaseTotal(archive))
    }

    @Test fun weightedManualPriceRetainsWeightAndCalculatesExactTotal() {
        val item = ShoppingItem(name = "Яблоки", weightGrams = 750)
        val encoded = purchaseEvent(item, null, 1L, "buyer", priceOverride = 32999L)
        val decoded = decodePurchaseEvent(encoded)!!
        assertEquals(750, decoded.weightGrams)
        assertEquals(24749L, priceForWeight(decoded.unitPrice!!, decoded.weightGrams!!))
        assertEquals(PartialAmount(24749L, 1, 0),
            calculateShoppingTotals(listOf(PricedLine(decoded.quantity, true,
                decoded.unitPrice, decoded.weightGrams))).total)
    }

    @Test fun plannedCustomProductUsesCheapestKnownStorePrice() {
        val item = ShoppingItem(name = "Яйца", customProductId = "own")
        val custom = listOf(CustomProduct("own", "Яйца", updatedAt = 1,
            estimatedPriceKopecks = 15_000))
        val offers = listOf(
            StoreProduct("first", "custom:own", 12_500),
            StoreProduct("second", "custom:own", 11_900),
        )
        assertEquals(11_900L, plannedUnitPrice(item, emptyList(), "", custom, offers))
        assertEquals(15_000L, plannedUnitPrice(item, emptyList(), "", custom, emptyList()))
        assertNull(plannedUnitPrice(item, emptyList(), "", emptyList(), emptyList()))
    }

    @Test fun barcodeAliasesLinkStorePricesAndIgnoreDeletedOffers() {
        val item = ShoppingItem(name = "Яйца", productId = 42L)
        val offers = listOf(
            StoreProduct("first", "custom:own", 7_000),
            StoreProduct("second", "custom:own", 100, deleted = true),
        )
        assertEquals(7_000L, plannedUnitPrice(item, emptyList(), "",
            emptyList(), offers, mapOf("custom:own" to "lenta:42")))
    }

    @Test fun currentLentaPriceAndKnownStorePricesUseMinimum() {
        val item = ShoppingItem(name = "Молоко", productId = 42L)
        val product = LentaProduct(42L, "Молоко", "", "", "120.00", "", "", "address",
            channel = "locc")
        val offers = listOf(StoreProduct("shop", "lenta:42", 11_000))
        assertEquals(11_000L, plannedUnitPrice(item, listOf(product), "address",
            emptyList(), offers))
        assertEquals(12_000L, plannedUnitPrice(item, listOf(product), "address",
            emptyList(), emptyList()))
    }

}
