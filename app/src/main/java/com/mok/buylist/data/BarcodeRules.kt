package com.mok.buylist.data

/** EAN-8, UPC-A и EAN-13: контрольная цифра GS1 Mod-10. */
internal fun validProductBarcode(raw: String): Boolean {
    val code = raw.trim()
    if (code.length !in setOf(8, 12, 13) || !code.all { it in '0'..'9' }) return false
    val weightedSum = code.dropLast(1).reversed().mapIndexed { index, digit ->
        digit.digitToInt() * if (index % 2 == 0) 3 else 1
    }.sum()
    return (10 - weightedSum % 10) % 10 == code.last().digitToInt()
}
