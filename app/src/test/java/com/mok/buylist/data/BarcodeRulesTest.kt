package com.mok.buylist.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BarcodeRulesTest {
    @Test fun acceptsBothMilkBarcodes() {
        assertTrue(validProductBarcode("4607078115610"))
        assertTrue(validProductBarcode("4607078115627"))
    }

    @Test fun rejectsIncorrectCheckDigitAndNonNumericCodes() {
        assertFalse(validProductBarcode("4607078115611"))
        assertFalse(validProductBarcode("460707811561x"))
        assertFalse(validProductBarcode("123456"))
        assertFalse(validProductBarcode(""))
    }

    @Test fun acceptsEan8AndUpcA() {
        assertTrue(validProductBarcode("96385074"))
        assertTrue(validProductBarcode("036000291452"))
    }
}
