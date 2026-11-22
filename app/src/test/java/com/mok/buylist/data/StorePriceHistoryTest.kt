package com.mok.buylist.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StorePriceHistoryTest {
    private fun purchase(
        id: String, store: String, price: Long?, at: Long,
        productId: Long? = null, customId: String = "",
        revisedAt: Long = 0,
        grams: Int? = null,
    ) = PurchaseEvent(
        id = id, productId = productId, name = "Яйца", unitPrice = price,
        priceType = "", quantity = 1, boughtAt = at, buyer = "buyer",
        address = "", channel = "", fetchedAt = 0,
        customProductId = customId, storeId = store,
        revisedAt = revisedAt, weightGrams = grams,
    )

    @Test fun picksLatestKnownPriceForTheSelectedShopOnly() {
        val events = listOf(
            purchase("a", "lenta", 12900, 100, customId = "eggs"),
            purchase("b", "kb", 11900, 200, customId = "eggs"),
            purchase("c", "lenta", 12500, 300, customId = "eggs"),
            purchase("d", "kb", null, 400, customId = "eggs"),
        )
        assertEquals(12500L, lastStorePurchasePrice(events, "custom:eggs", "lenta"))
        assertEquals(11900L, lastStorePurchasePrice(events, "custom:eggs", "kb"))
        assertNull(lastStorePurchasePrice(events, "custom:eggs", "unknown"))
        assertNull(lastStorePurchasePrice(events, "custom:eggs", ""))
    }

    @Test fun barcodeLinkedCustomAndLentaPurchasesUseSamePriceHistory() {
        val events = listOf(
            purchase("a", "kb", 9900, 100, customId = "eggs"),
            purchase("b", "kb", 10500, 200, productId = 100),
        )
        val aliases = mapOf("custom:eggs" to "lenta:100", "lenta:100" to "lenta:100")
        assertEquals(10500L,
            lastStorePurchasePrice(events, "custom:eggs", "kb", aliases))
    }

    @Test fun editsExcludeTheCurrentPurchaseFromPriceSuggestions() {
        val events = listOf(
            purchase("old", "kb", 9900, 100, customId = "eggs"),
            purchase("current", "kb", 12500, 200, customId = "eggs"),
        )
        assertEquals(9900L, lastStorePurchasePrice(
            events, "custom:eggs", "kb", excludedEventId = "current"))
    }

    @Test fun weightedPriceIsPerKilogramNotTotalAndOtherProductsDoNotLeak() {
        val events = listOf(
            purchase("a", "kb", 32999, 100, customId = "apples", grams = 750),
            purchase("b", "kb", 59900, 200, customId = "bananas", grams = 500),
        )
        assertEquals(32999L, lastStorePurchasePrice(events, "custom:apples", "kb"))
        assertEquals(24749L, priceForWeight(32999L, 750))
    }
}
