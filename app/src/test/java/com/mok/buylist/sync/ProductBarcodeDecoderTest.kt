package com.mok.buylist.sync

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import org.junit.Assert.*
import org.junit.Test

class ProductBarcodeDecoderTest {
    @Test fun readsEanInBothOrientations() {
        val code = "4006381333931"
        val matrix = MultiFormatWriter().encode(code, BarcodeFormat.EAN_13, 320, 160)
        val frame = ByteArray(320 * 160) { index -> if (matrix[index % 320, index / 320]) 0 else 255.toByte() }
        val decoder = ProductBarcodeDecoder()
        assertEquals(code, decoder.decode(frame, 320, 320, 160))
        val rotated = ByteArray(frame.size)
        for (y in 0 until 160) for (x in 0 until 320) rotated[x * 160 + (159 - y)] = frame[y * 320 + x]
        assertEquals(code, decoder.decode(rotated, 160, 160, 320))
    }

    @Test fun matchesOnlyExactOrEquivalentUpc() {
        assertTrue(barcodeMatches("012345678905", "0012345678905"))
        assertFalse(barcodeMatches("12345678", "2345678"))
        assertFalse(barcodeMatches("", ""))
    }

    @Test fun detectsRestrictedVariableMeasureCodesWithoutTreatingOthersAsWeight() {
        assertTrue(isVariableMeasureBarcode("2101234001000"))
        assertTrue(isVariableMeasureBarcode("2901234001000"))
        assertFalse(isVariableMeasureBarcode("4006381333931"))
        assertFalse(isVariableMeasureBarcode("2101234"))
        assertFalse(isVariableMeasureBarcode("21A1234001000"))
        assertTrue(possibleVariableBarcodeMatch("2101234001000", "2101234002300"))
        assertFalse(possibleVariableBarcodeMatch("2101234001000", "2101235001000"))
        assertFalse(possibleVariableBarcodeMatch("4006381333931", "4006381333932"))
    }

    @Test fun readsWeightsFromProvidedLentaLabels() {
        assertEquals(338, lentaWeightGrams("2441111003388"))
        assertEquals(806, lentaWeightGrams("2441111008062"))
        assertTrue(possibleVariableBarcodeMatch("2441111003388", "2441111008062"))
        assertNull(lentaWeightGrams("2441111003389"))
        assertNull(lentaWeightGrams("2101234001000"))
    }
}
