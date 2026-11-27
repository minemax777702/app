package com.mok.buylist.data

/** GS1 Mod-10 check digit, calculated from the payload without its check digit. */
internal fun gtinCheckDigit(payload: String): Int {
    require(payload.isNotEmpty() && payload.all { it in '0'..'9' })
    val sum = payload.reversed().mapIndexed { index, digit ->
        digit.digitToInt() * if (index % 2 == 0) 3 else 1
    }.sum()
    return (10 - sum % 10) % 10
}

/** EAN-8, UPC-A, EAN-13 and GTIN-14. */
internal fun validProductBarcode(raw: String): Boolean {
    val code = raw.trim()
    if (code.length !in setOf(8, 12, 13, 14) || !code.all { it in '0'..'9' }) return false
    return gtinCheckDigit(code.dropLast(1)) == code.last().digitToInt()
}

/**
 * A nonzero indicator 1–8 denotes a packaging level. Its related EAN-13
 * is only a candidate: the two trade items are not necessarily identical.
 * Indicator 9 (variable measure) is deliberately excluded.
 */
internal fun retailEanCandidate(raw: String): String? {
    val code = raw.trim()
    if (code.length != 14 || code.first() !in '1'..'8' || !validProductBarcode(code)) return null
    val payload = code.substring(1, 13)
    return payload + gtinCheckDigit(payload)
}
