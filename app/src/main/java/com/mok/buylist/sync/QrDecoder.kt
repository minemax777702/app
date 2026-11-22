package com.mok.buylist.sync

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer

/**
 * Читает QR из кадра камеры. Нужна только яркость (плоскость Y камеры): цвет коду не нужен.
 * Не потокобезопасен — держите по одному на поток анализа.
 */
class QrDecoder {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }

    /**
     * @param luma яркость построчно; между строками может быть отступ, поэтому [rowStride] ≥ [width]
     * @return текст кода или null, если в кадре нет читаемого QR
     */
    fun decode(luma: ByteArray, rowStride: Int, width: Int, height: Int): String? = try {
        val source = PlanarYUVLuminanceSource(luma, rowStride, height, 0, 0, width, height, false)
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    } catch (e: ReaderException) {
        null
    } finally {
        reader.reset()
    }
}
