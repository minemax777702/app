package com.mok.buylist.ui

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

/** Presentation only: stored kopecks/grams and editable numeric inputs remain unchanged. */
internal fun formatUiMoney(kopecks: Long): String =
    NumberFormat.getNumberInstance(Locale.forLanguageTag("ru-RU")).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }.format(BigDecimal.valueOf(kopecks, 2)) + " ₽"

internal fun formatUiWeight(grams: Long): String =
    NumberFormat.getNumberInstance(Locale.forLanguageTag("ru-RU")).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 3
    }.format(BigDecimal.valueOf(grams, 3)) + " кг"
