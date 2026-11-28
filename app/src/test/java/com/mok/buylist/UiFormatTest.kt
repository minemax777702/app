package com.mok.buylist

import com.mok.buylist.ui.formatUiMoney
import com.mok.buylist.ui.formatUiWeight
import org.junit.Assert.assertEquals
import org.junit.Test

class UiFormatTest {
    @Test fun currencyUsesRussianDecimalAndTwoDigits() {
        assertEquals("129,99 ₽", formatUiMoney(12999))
        assertEquals("89,00 ₽", formatUiMoney(8900))
        assertEquals("0,00 ₽", formatUiMoney(0))
    }

    @Test fun weightUsesRussianDecimalWithoutTrailingZeros() {
        assertEquals("0,75 кг", formatUiWeight(750))
        assertEquals("1 кг", formatUiWeight(1000))
        assertEquals("0,001 кг", formatUiWeight(1))
    }
}
