package com.mok.buylist.sync

/**
 * Base45 (RFC 9285): два байта превращаются в три символа алфавита, который QR-код хранит в самом плотном
 * текстовом режиме (alphanumeric, 5,5 бита на символ). Двоичные данные в QR занимали бы заметно больше места.
 */
internal object Base45 {
    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"
    private val index = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { i, c -> table[c.code] = i }
    }

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 3 / 2 + 3)
        var i = 0
        while (i + 1 < bytes.size) {
            val v = (bytes[i].toInt() and 0xFF) * 256 + (bytes[i + 1].toInt() and 0xFF)
            sb.append(ALPHABET[v % 45]).append(ALPHABET[v / 45 % 45]).append(ALPHABET[v / (45 * 45)])
            i += 2
        }
        if (i < bytes.size) {
            val v = bytes[i].toInt() and 0xFF
            sb.append(ALPHABET[v % 45]).append(ALPHABET[v / 45])
        }
        return sb.toString()
    }

    /** @return null, если в тексте посторонние символы или неверная длина. */
    fun decode(text: String): ByteArray? {
        if (text.length % 3 == 1) return null
        val out = ByteArray(text.length / 3 * 2 + if (text.length % 3 == 2) 1 else 0)
        var o = 0
        var i = 0
        while (i + 2 < text.length) {
            val a = digit(text[i])
            val b = digit(text[i + 1])
            val c = digit(text[i + 2])
            if (a < 0 || b < 0 || c < 0) return null
            val v = a + b * 45 + c * 45 * 45
            if (v > 0xFFFF) return null
            out[o++] = (v shr 8).toByte()
            out[o++] = (v and 0xFF).toByte()
            i += 3
        }
        if (i < text.length) {
            val a = digit(text[i])
            val b = digit(text[i + 1])
            if (a < 0 || b < 0) return null
            val v = a + b * 45
            if (v > 0xFF) return null
            out[o] = v.toByte()
        }
        return out
    }

    private fun digit(c: Char): Int = if (c.code < index.size) index[c.code] else -1
}
