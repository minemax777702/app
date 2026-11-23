package com.mok.buylist.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class StoreProductLinkTest {
    @Test fun firstPurchaseCreatesShopAssociationWithoutInventingPrice() {
        val row = linkForConfirmedPurchase("store", "custom:milk", 100L, null)!!
        assertEquals("store", row.storeId)
        assertEquals("custom:milk", row.productKey)
        assertNull(row.priceKopecks)
        assertFalse(row.deleted)
    }

    @Test fun secondPurchaseNeverOverwritesManualShopPrice() {
        val configured = StoreProduct("store", "custom:milk", 10990L, updatedAt = 250L)
        assertNull(linkForConfirmedPurchase("store", "custom:milk", 300L, configured))
        assertEquals(10990L, configured.priceKopecks)
    }

    @Test fun purchaseCanRestoreRemovedLinkWithoutLosingConfiguredPrice() {
        val removed = StoreProduct("store", "custom:milk", 10990L,
            updatedAt = 500L, deleted = true)
        val restored = linkForConfirmedPurchase("store", "custom:milk", 400L, removed)!!
        assertEquals(10990L, restored.priceKopecks)
        assertEquals(501L, restored.updatedAt)
        assertFalse(restored.deleted)
    }

    @Test fun emptyShopNeverCreatesAssociation() {
        assertNull(linkForConfirmedPurchase("", "custom:milk", 100L, null))
        assertNull(linkForConfirmedPurchase("store", "", 100L, null))
    }
}
