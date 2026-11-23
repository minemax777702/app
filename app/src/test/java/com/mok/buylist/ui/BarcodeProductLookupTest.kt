package com.mok.buylist.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class BarcodeProductLookupTest {
    @Test fun rejectsPlaceholderAndInvalidProductNames() {
        assertFalse(BarcodeProductLookup.usefulName("not found", "4601985011102"))
        assertFalse(BarcodeProductLookup.usefulName("<html>captcha</html>", "4601985011102"))
        assertFalse(BarcodeProductLookup.usefulName("4601985011102", "4601985011102"))
        assertFalse(BarcodeProductLookup.usefulName("Ошибка: товар не найден", "4601985011102"))
        assertTrue(BarcodeProductLookup.usefulName("Молоко пастеризованное 3,2% 1 л", "4601985011102"))
    }

    @Test fun disabledApiSourcesReturnNoCandidates() = runBlocking {
        val result = BarcodeProductLookup.lookup("4607078115610",
            force = true, sources = emptySet())
        assertTrue(result.isEmpty())
    }

    @Test fun prefersLocalizedDescriptiveProductNames() {
        val localized = BarcodeProductLookup.quality("Молоко пастеризованное 3,2% 1 л", true, true)
        val generic = BarcodeProductLookup.quality("Milk", false, false)
        assertTrue(localized > generic)
    }
}
