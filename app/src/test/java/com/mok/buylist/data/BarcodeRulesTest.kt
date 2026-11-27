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

    @Test fun validatesGtin14AndDerivesRetailCandidate() {
        assertTrue(validProductBarcode("14607958070869"))
        assertTrue(validProductBarcode("4607958070862"))
        assertFalse(validProductBarcode("14607958070862"))
        org.junit.Assert.assertEquals("4607958070862", retailEanCandidate("14607958070869"))
        org.junit.Assert.assertNull(retailEanCandidate("04607958070862"))
        val variablePayload = "9460795807086"
        org.junit.Assert.assertNull(retailEanCandidate(variablePayload + gtinCheckDigit(variablePayload)))
    }

    @Test fun acceptsEan8AndUpcA() {
        assertTrue(validProductBarcode("96385074"))
        assertTrue(validProductBarcode("036000291452"))
    }
}
