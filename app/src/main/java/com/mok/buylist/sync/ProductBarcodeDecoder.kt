package com.mok.buylist.sync

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer

internal fun barcodeMatches(saved: String, scanned: String): Boolean {
    val a = saved.trim()
    val b = scanned.trim()
    if (a.isEmpty() || b.isEmpty()) return false
    return a == b || (a.length == 13 && a.startsWith("0") && a.drop(1) == b) ||
        (b.length == 13 && b.startsWith("0") && b.drop(1) == a)
}

/** Весовой код магазина содержит переменную часть; его нельзя сравнивать как постоянный GTIN. */
internal fun isVariableMeasureBarcode(code: String): Boolean =
    code.length == 13 && code.all(Char::isDigit) && code.substring(0, 2).toInt() in 20..29

/** Кандидат на выбор человеком; состав кодов может различаться у магазинов. */
internal fun possibleVariableBarcodeMatch(saved: String, scanned: String): Boolean =
    isVariableMeasureBarcode(saved) && isVariableMeasureBarcode(scanned) && saved.take(7) == scanned.take(7)

/** На предоставленных этикетках Ленты префикс 24 содержит массу в граммах в позициях 8–12. */
internal fun lentaWeightGrams(code: String): Int? =
    code.takeIf { isVariableMeasureBarcode(it) && it.startsWith("24") &&
        it.take(12).mapIndexed { index, digit -> digit.digitToInt() * if (index % 2 == 0) 1 else 3 }.sum()
            .let { sum -> (10 - sum % 10) % 10 == it.last().digitToInt() } }
        ?.substring(7, 12)?.toIntOrNull()?.takeIf { it > 0 }

/** Читает товарные штрихкоды, включая повёрнутые кадры камеры. */
class ProductBarcodeDecoder {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(
            BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E),
            DecodeHintType.TRY_HARDER to true))
    }

    fun decode(luma: ByteArray, rowStride: Int, width: Int, height: Int): String? {
        fun read(data: ByteArray, stride: Int, w: Int, h: Int): String? = try {
            val source = PlanarYUVLuminanceSource(data, stride, h, 0, 0, w, h, false)
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: ReaderException) { null } finally { reader.reset() }

        read(luma, rowStride, width, height)?.let { return it }
        val rotated = ByteArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            rotated[x * height + (height - 1 - y)] = luma[y * rowStride + x]
        }
        return read(rotated, height, height, width)
    }
}
