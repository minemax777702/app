package com.mok.buylist.ui

import com.mok.buylist.data.StoreProduct
import com.mok.buylist.data.lentaStoreId
import org.junit.Assert.assertEquals
import org.junit.Test

class StoreCatalogMembershipTest {
    private val active = setOf("custom:milk", "lenta:1", "custom:bread")
    private val lenta = setOf("lenta:1")
    private val aliases = mapOf("custom:milk" to "lenta:1")

    @Test fun onlyLiveLinksToExistingProductsCount() {
        val links = listOf(
            StoreProduct("a", "custom:milk", deleted = true),
            StoreProduct("a", "custom:missing"),
            StoreProduct("b", "custom:bread"),
        )
        assertEquals(emptyList<String>(), currentStoreProductKeys("a", links, active, lenta, aliases))
        assertEquals(listOf("custom:bread"), currentStoreProductKeys("b", links, active, lenta, aliases))
    }

    @Test fun catalogAliasesCountOnceAndLentaRemainsImplicitlyLinked() {
        val links = listOf(StoreProduct("a", "custom:milk", updatedAt = 10),
            StoreProduct("a", "lenta:1", updatedAt = 20),
            StoreProduct(lentaStoreId(), "custom:milk"))
        assertEquals(listOf("lenta:1"), currentStoreProductKeys("a", links, active, lenta, aliases))
        assertEquals(listOf("lenta:1"), currentStoreProductKeys(lentaStoreId(), links, active, lenta, aliases))
    }

    @Test fun russianProductCountForms() {
        mapOf(0 to "0 товаров", 1 to "1 товар", 2 to "2 товара", 4 to "4 товара",
            5 to "5 товаров", 11 to "11 товаров", 14 to "14 товаров",
            21 to "21 товар", 22 to "22 товара", 111 to "111 товаров").forEach { (count, label) ->
            assertEquals(label, productCountLabel(count))
        }
    }
}
