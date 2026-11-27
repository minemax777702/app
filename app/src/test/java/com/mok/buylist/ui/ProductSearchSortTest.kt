package com.mok.buylist.ui

import org.junit.Assert.*
import org.junit.Test

class ProductSearchSortTest {
    @Test fun normalizedPriceMakesLargerPackComparable() {
        val half = unitPrice(10000, parsePackMeasure("Молоко 0,5 л"))!!
        val full = unitPrice(11000, parsePackMeasure("Молоко 1 л"))!!
        assertEquals("200,00 ₽/л", half.label())
        assertEquals("110,00 ₽/л", full.label())
        assertTrue(full.kopecks < half.kopecks)
    }

    @Test fun convertsGramsMillilitersAndMultipacks() {
        assertEquals("400,00 ₽/кг", unitPrice(10000, parsePackMeasure("250 г"))!!.label())
        assertEquals("200,00 ₽/л", unitPrice(12000, parsePackMeasure("3 x 200 мл"))!!.label())
        assertEquals("200,00 ₽/л", unitPrice(12000, parsePackMeasure("200 мл х 3"))!!.label())
        assertEquals("200,00 ₽/л", unitPrice(12000, parsePackMeasure("3x200мл"))!!.label())
        assertNull(parsePackMeasure("сыр 300 г + 50 г"))
        assertNull(parsePackMeasure("молоко 3,2%"))
        assertNull(parsePackMeasure("0 г"))
        assertEquals(MeasureUnit.VOLUME, productMeasure("1 шт.", "Молоко 500 мл")?.unit)
    }

    @Test fun sortingKeepsUnknownPricesLastAndComparesMassWithVolume() {
        fun row(key: String, price: Long?, amount: String) =
            ProductSearchRow(key, price, unitPrice(price, parsePackMeasure(amount)))
        val rows = listOf(row("half", 10000, "500 мл"), row("unknown", null, "1 л"),
            row("full", 11000, "1 л"), row("mass", 12600, "900 г"))
        assertEquals(listOf("half", "full", "mass", "unknown"), sortProductRows(rows, ProductSort.PRICE_ASC).map { it.key })
        assertEquals(listOf("mass", "full", "half", "unknown"), sortProductRows(rows, ProductSort.PRICE_DESC).map { it.key })
        assertEquals(listOf("full", "mass", "half", "unknown"), sortProductRows(rows, ProductSort.UNIT_ASC).map { it.key })
        assertEquals(listOf("half", "mass", "full", "unknown"), sortProductRows(rows, ProductSort.UNIT_DESC).map { it.key })
    }

    @Test fun equalMassAndVolumeUseSameRankingWithoutChangingLabels() {
        val mass = unitPrice(10000, parsePackMeasure("1 кг"))!!
        val volume = unitPrice(10000, parsePackMeasure("1 л"))!!
        assertEquals(0, mass.kopecks.compareTo(volume.kopecks))
        assertEquals("100,00 ₽/кг", mass.label())
        assertEquals("100,00 ₽/л", volume.label())
    }
}
