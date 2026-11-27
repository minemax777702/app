package com.mok.buylist.data

import org.junit.Assert.*
import org.junit.Test

class LentaProductRefreshTest {
    private val old = LentaProduct(1L, "Старое название", "old.jpg", "400 г",
        "99.90", "card", "4607078115610", "Старый адрес", storeId = 7, channel = "locc", fetchedAt = 10)

    @Test fun detailsWithoutPriceStillUpdateBarcodeAndMetadata() {
        val fresh = old.copy(name = "Новое название", ean = "4607078115627",
            price = "", address = "Новый адрес", storeId = 8, fetchedAt = 20)
        val result = mergeLentaDetails(old, fresh, updatePrice = true)
        assertEquals(fresh.name, result.name)
        assertEquals(fresh.ean, result.ean)
        assertEquals(old.price, result.price)
        assertEquals(old.address, result.address)
        assertEquals(old.storeId, result.storeId)
        assertEquals(old.fetchedAt, result.fetchedAt)
    }

    @Test fun manualPriceDoesNotBlockMetadata() {
        val fresh = old.copy(price = "149.90", ean = "4607078115627", image = "new.jpg")
        val result = mergeLentaDetails(old, fresh, updatePrice = false)
        assertEquals(fresh.ean, result.ean)
        assertEquals(fresh.image, result.image)
        assertEquals(old.price, result.price)
    }

    @Test fun emptyFieldsDoNotEraseExistingData() {
        val fresh = old.copy(name = "", image = "null", pack = "", ean = "null", price = "149.90",
            address = "Новый адрес", storeId = 8, fetchedAt = 20)
        val result = mergeLentaDetails(old, fresh, updatePrice = true)
        assertEquals(old.name, result.name)
        assertEquals(old.image, result.image)
        assertEquals(old.pack, result.pack)
        assertEquals(old.ean, result.ean)
        assertEquals(fresh.price, result.price)
        assertEquals(fresh.address, result.address)
        assertEquals(fresh.fetchedAt, result.fetchedAt)
    }

    @Test fun barcodeNormalization() {
        assertEquals("4607078115627", catalogBarcode(" 4607078115627 "))
        assertNull(catalogBarcode("null"))
        assertNull(catalogBarcode("123"))
        assertNull(catalogBarcode("460707811562x"))
    }
}
