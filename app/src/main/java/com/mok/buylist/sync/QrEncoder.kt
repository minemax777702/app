package com.mok.buylist.sync

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Квадратная матрица модулей QR без «тихой зоны»: поля рисует экран. */
class QrMatrix(val size: Int, private val dark: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = dark[y * size + x]
}

object QrEncoder {
    private val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 0,
    )

    fun encode(text: String): QrMatrix {
        val bits = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val size = bits.width
        val dark = BooleanArray(size * size)
        for (y in 0 until size) for (x in 0 until size) dark[y * size + x] = bits[x, y]
        return QrMatrix(size, dark)
    }
}
