package com.mok.buylist.ui

import com.mok.buylist.data.ShoppingStore
import com.mok.buylist.data.StoreProduct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProductStorePriceSummaryTest {
    private val stores = listOf(
        ShoppingStore("store-a", "Лавка", updatedAt = 1),
        ShoppingStore("store-b", "Супермаркет", updatedAt = 1),
    )
    private fun receipt(
        key: String, shop: String, price: Long?, whenBought: Long,
    ) = ExpensePurchase(
        id = "$key:$shop:$whenBought", productKey = key, name = "Молоко",
        trip = "trip", buyer = "person", at = whenBought,
        quantity = 1, unitPrice = price, storeId = shop,
    )

    @Test fun cheapestShopUsesKnownPricesAndCorrectShopLabel() {
        val rows = productShopPrices(
            "custom:milk", stores,
            listOf(StoreProduct("store-a", "custom:milk", 13500L),
                StoreProduct("store-b", "custom:milk", 11900L)),
            emptyList(),
        )
        assertEquals("Супермаркет", cheapestShopPrice(rows)?.storeName)
        assertEquals(11900L, cheapestShopPrice(rows)?.priceKopecks)
    }

    @Test fun purchaseHistoryShowsShopsWithoutExplicitAssociation() {
        val receipts = listOf(
            receipt("custom:milk", "store-a", 13900L, 100L),
            receipt("custom:milk", "store-a", 12900L, 200L),
            receipt("custom:milk", "store-b", null, 300L),
        )
        val rows = productShopPrices("custom:milk", stores, emptyList(), receipts)
        assertEquals(12900L, rows.first { it.storeId == "store-a" }.priceKopecks)
        assertNull(rows.first { it.storeId == "store-b" }.priceKopecks)
    }

    @Test fun manualCatalogPriceDoesNotGetOverwrittenByLatestPurchase() {
        val rows = productShopPrices("custom:milk", stores,
            listOf(StoreProduct("store-a", "custom:milk", 9900L, updatedAt = 200)),
            listOf(receipt("custom:milk", "store-a", 11900L, 300)),
        )
        assertEquals(9900L, rows.single().priceKopecks)
    }

    @Test fun barcodeAliasesCombineCrossCatalogPricesWithoutCombiningOtherGoods() {
        val receipts = listOf(
            receipt("lenta:100", "store-a", 11500L, 200L),
            receipt("custom:other", "store-b", 100L, 300L),
        )
        val rows = productShopPrices("custom:milk", stores, emptyList(), receipts,
            mapOf("custom:milk" to "lenta:100"))
        assertEquals(1, rows.size)
        assertEquals(11500L, rows.single().priceKopecks)
    }

    @Test fun deletedAssignmentsAreNotVisibleWithoutPurchases() {
        val rows = productShopPrices("custom:milk", stores,
            listOf(StoreProduct("store-a", "custom:milk", 9900L, deleted = true)),
            emptyList())
        assertEquals(emptyList<ProductShopPrice>(), rows)
    }
}
