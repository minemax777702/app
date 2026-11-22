package com.mok.buylist

import com.mok.buylist.data.CustomProductBarcode
import com.mok.buylist.data.CustomProduct
import com.mok.buylist.data.ShoppingStore
import com.mok.buylist.sync.Snapshot
import com.mok.buylist.sync.SyncCodec
import com.mok.buylist.sync.SyncItem
import com.mok.buylist.sync.SyncMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiCatalogSyncTest {
    private val product = CustomProduct("product-1", "Яйца С1 10 шт", "4601234567893", 100L)
    private val store = ShoppingStore("store-1", "Красное и Белое", updatedAt = 100L)

    @Test fun customCatalogAndStoresSurviveV10WireFormat() {
        val snapshot = Snapshot(
            customProducts = listOf(product),
            stores = listOf(store),
            items = listOf(SyncItem("Яйца С1 10 шт", 10, 100, false, "buyer",
                customProductId = product.id)),
        )
        assertEquals(snapshot, SyncCodec.decode(SyncCodec.encode(snapshot)))
        assertEquals("custom:product-1", snapshot.items.single().key)
    }

    @Test fun concurrentEditsPickNewerAndDoNotResurrectDeleted() {
        val local = Snapshot(customProducts = listOf(product), stores = listOf(store))
        val remote = Snapshot(customProducts = listOf(product.copy(updatedAt = 200L, deleted = true)),
            stores = listOf(store.copy(name = "К&Б", nameKey = "к&б", updatedAt = 300L)))
        val merged = SyncMerge.merge(local, remote)
        assertTrue(merged.customProducts.single().deleted)
        assertEquals("К&Б", merged.stores.single().name)
        assertEquals(merged, SyncMerge.merge(merged, local))
        assertEquals(merged, SyncMerge.merge(local, merged))
    }

    @Test fun weightedCustomProductSurvivesV12Sync() {
        val weighted = product.copy(isWeighted = true, estimatedPriceKopecks = 32999L)
        val snapshot = Snapshot(customProducts = listOf(weighted))
        val decoded = SyncCodec.decode(SyncCodec.encode(snapshot))
        assertEquals(weighted, decoded.customProducts.single())
    }

    @Test fun optionalEstimatedPriceSurvivesV12Sync() {
        val withPrice = product.copy(estimatedPriceKopecks = 11990L)
        val snapshot = Snapshot(customProducts = listOf(withPrice))
        val decoded = SyncCodec.decode(SyncCodec.encode(snapshot))
        assertEquals(11990L, decoded.customProducts.single().estimatedPriceKopecks)
        val noPrice = Snapshot(customProducts = listOf(product))
        assertEquals(null, SyncCodec.decode(SyncCodec.encode(noPrice))
            .customProducts.single().estimatedPriceKopecks)
    }

    @Test fun unrelatedCatalogsAreNotMergedBySimilarName() {
        val a = CustomProduct("uuid-a", "Молоко 1 л", "", 10L)
        val b = CustomProduct("uuid-b", "Молоко 1 литр", "", 20L)
        val merged = SyncMerge.merge(Snapshot(customProducts = listOf(a)),
            Snapshot(customProducts = listOf(b)))
        assertEquals(2, merged.customProducts.size)
        assertFalse(merged.customProducts.any { it.deleted })
    }
    @Test fun multipleAliasesSurviveSyncAndDeletionTombstonesWin() {
        val barcode = CustomProductBarcode(product.id, "4607078115610", 100)
        val snapshot = Snapshot(customProducts = listOf(product), customProductBarcodes = listOf(barcode))
        assertEquals(snapshot, SyncCodec.decode(SyncCodec.encode(snapshot)))
        val deleted = snapshot.copy(customProductBarcodes = listOf(barcode.copy(deleted = true, updatedAt = 200)))
        val merged = SyncMerge.merge(snapshot, deleted)
        assertTrue(merged.customProductBarcodes.single().deleted)
        assertEquals(merged, SyncMerge.merge(merged, snapshot))
        assertEquals(merged, SyncMerge.merge(snapshot, merged))
    }
}

