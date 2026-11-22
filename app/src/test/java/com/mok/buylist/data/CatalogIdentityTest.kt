package com.mok.buylist.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogIdentityTest {
    private val a = "4601234567893"
    private val b = "4607078115610"
    private val c = "4607078115627"

    @Test fun sameBarcodeAcrossCatalogsProducesOneStableKey() {
        val lenta = listOf(LentaProduct(7, "Яйца", "", "", "", "", a, ""))
        val custom = listOf(CustomProduct("own", "Яйца 10 шт", a, 10))
        val identity = sharedCatalogIdentities(lenta, custom, emptyList(), emptyList())
        assertEquals("lenta:7", identity["lenta:7"])
        assertEquals("lenta:7", identity["custom:own"])
    }

    @Test fun multipleBarcodesJoinTransitivelyAndRemovalUnlinks() {
        val lenta = listOf(LentaProduct(7, "Яйца", "", "", "", "", a, ""))
        val custom = listOf(CustomProduct("own", "Яйца", "", 10))
        val aliases = listOf(CustomProductBarcode("own", b), CustomProductBarcode("own", a))
        val joined = sharedCatalogIdentities(lenta, custom, emptyList(), aliases)
        assertEquals("lenta:7", joined["custom:own"])
        val separated = sharedCatalogIdentities(lenta, custom, emptyList(),
            aliases.map { if (it.code == a) it.copy(updatedAt = 11, deleted = true) else it })
        assertEquals("custom:own", separated["custom:own"])
    }

    @Test fun conflictingLentaProductsAreNeverJoinedThroughCustomAliases() {
        val lenta = listOf(
            LentaProduct(11, "Яйца 10", "", "", "", "", a, ""),
            LentaProduct(12, "Яйца 20", "", "", "", "", c, ""))
        val custom = listOf(CustomProduct("own", "Яйца", "", 10))
        val aliases = listOf(CustomProductBarcode("own", a), CustomProductBarcode("own", c))
        val ids = sharedCatalogIdentities(lenta, custom, emptyList(), aliases)
        assertEquals("lenta:11", ids["lenta:11"])
        assertEquals("lenta:12", ids["lenta:12"])
        assertEquals("lenta:11", ids["custom:own"])
        assertEquals(ids, sharedCatalogIdentities(lenta.reversed(), custom, emptyList(), aliases.reversed()))
    }

    @Test fun duplicateLentaBarcodeDoesNotMergeCatalogEntries() {
        val lenta = listOf(LentaProduct(7, "Первый", "", "", "", "", a, ""),
            LentaProduct(8, "Второй", "", "", "", "", a, ""))
        val ids = sharedCatalogIdentities(lenta, emptyList(), emptyList(), emptyList())
        assertEquals("lenta:7", ids["lenta:7"])
        assertEquals("lenta:8", ids["lenta:8"])
    }
}
