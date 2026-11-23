package com.mok.buylist.ui

import com.mok.buylist.data.CustomProduct
import com.mok.buylist.data.LentaProduct
import com.mok.buylist.data.ShoppingItem
import com.mok.buylist.data.decodePurchaseEvent
import com.mok.buylist.data.purchaseEvent
import com.mok.buylist.data.StoredProductBarcode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MultiStorePricesTest {
    @Test fun manualPurchaseRetainsStoreAndProductUuid() {
        val item = ShoppingItem(name = "Яйца", nameKey = "custom:abc", customProductId = "abc")
        val event = decodePurchaseEvent(purchaseEvent(item, null, 1234L, "buyer", 11900L, storeId = "shop"))!!
        assertEquals("abc", event.customProductId)
        assertEquals("shop", event.storeId)
        assertEquals(11900L, event.unitPrice)
        assertNull(event.productId)
    }

    @Test fun sharedBarcodeConnectsPriceHistoryButNotIndependentPurchases() {
        val barcode = "4601234567893"
        val own = CustomProduct("uuid", "Яйца", barcode, 100L)
        val lenta = LentaProduct(100L, "Яйца", "", "", "129.00", "card", barcode, "")
        val purchases = listOf(
            ExpensePurchase("event-1", "custom:uuid", "Яйца", "trip-a", "u", 1000, 1, 11900, storeId = "shop"),
            ExpensePurchase("event-2", "lenta:100", "Яйца", "trip-b", "u", 2000, 1, 12900))
        val grouped = mergeCatalogPriceHistory(purchases, listOf(own), listOf(lenta), emptyList())
        assertEquals(setOf("lenta:100"), grouped.map { it.productKey }.toSet())
        assertEquals(2, grouped.size)
        assertEquals(2, expenseProducts(grouped).single().trips)
    }

    @Test fun ambiguousBarcodeDoesNotSilentlyJoinDifferentCatalogItems() {
        val barcode = "4601234567893"
        val purchases = listOf(ExpensePurchase("event", "custom:uuid", "Яйца", "trip", "u", 1, 1, 11900))
        val merged = mergeCatalogPriceHistory(purchases,
            listOf(CustomProduct("uuid", "Яйца", barcode, 1)),
            listOf(LentaProduct(100, "Яйца A", "", "", "", "", barcode, ""),
                LentaProduct(101, "Яйца B", "", "", "", "", barcode, "")), emptyList())
        assertEquals("custom:uuid", merged.single().productKey)
    }

    @Test fun barcodeAliasWorksEvenIfCatalogEANIsEmpty() {
        val code = "4601234567893"
        val own = CustomProduct("uuid", "Яйца", code, 1)
        val lenta = LentaProduct(100, "Яйца", "", "", "", "", "", "")
        val merged = mergeCatalogPriceHistory(
            listOf(ExpensePurchase("event", "custom:uuid", "Яйца", "trip", "u", 1, 1, 11900)),
            listOf(own), listOf(lenta), listOf(StoredProductBarcode(100L, code)))
        assertEquals("lenta:100", merged.single().productKey)
    }
}
