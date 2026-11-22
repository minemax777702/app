package com.mok.buylist.sync

import java.util.zip.CRC32

/** Сколько байтов сообщения в одном кадре QR. Вместе с заголовком это QR версии 12 при коррекции M (65 модулей). */
const val FRAME_CHUNK_BYTES = 256

/** Как часто сменяется кадр на экране: быстрее камера на части телефонов не успевает. */
const val FRAME_INTERVAL_MS = 180L

/** Часть обмена: сначала маленький список с остальными данными, затем архив (основной объём). */
const val PART_LIST = 0
const val PART_ARCHIVE = 1

/**
 * Кадр = один QR-код. Заголовок самодостаточен: любой кадр можно принять в любом порядке, повторы безвредны.
 * [session] — случайное число сеанса показа: если отправитель начал заново, получатель узнаёт это по нему.
 * [part] — какое из двух сообщений ([PART_LIST], [PART_ARCHIVE]) собирает этот кадр.
 * [crc] — контрольная сумма всего сообщения: ей проверяется собранное.
 */
class Frame(
    val session: Int,
    val part: Int,
    val total: Int,
    val index: Int,
    val crc: Int,
    val chunk: ByteArray,
)

internal object FrameCodec {
    private const val MAGIC = 0xB3
    private const val HEADER_BYTES = 14

    fun encode(f: Frame): String {
        val w = ByteWriter()
        w.byte(MAGIC)
        putInt(w, f.session)
        w.byte(f.part)
        w.byte(f.total shr 8); w.byte(f.total and 0xFF)
        w.byte(f.index shr 8); w.byte(f.index and 0xFF)
        putInt(w, f.crc)
        w.raw(f.chunk)
        return Base45.encode(w.toByteArray())
    }

    /** @return null, если это не кадр нашего протокола (посторонний QR, другая версия, повреждение). */
    fun decode(text: String): Frame? {
        val bytes = Base45.decode(text) ?: return null
        if (bytes.size < HEADER_BYTES || (bytes[0].toInt() and 0xFF) != MAGIC) return null
        fun u8(i: Int) = bytes[i].toInt() and 0xFF
        fun i32(i: Int) = (u8(i) shl 24) or (u8(i + 1) shl 16) or (u8(i + 2) shl 8) or u8(i + 3)
        val part = u8(5)
        val total = (u8(6) shl 8) or u8(7)
        val index = (u8(8) shl 8) or u8(9)
        if ((part != PART_LIST && part != PART_ARCHIVE) || total == 0 || index >= total) return null
        return Frame(
            session = i32(1),
            part = part,
            total = total,
            index = index,
            crc = i32(10),
            chunk = bytes.copyOfRange(HEADER_BYTES, bytes.size),
        )
    }

    private fun putInt(w: ByteWriter, v: Int) {
        w.byte(v ushr 24); w.byte((v ushr 16) and 0xFF); w.byte((v ushr 8) and 0xFF); w.byte(v and 0xFF)
    }
}

internal fun crcOf(bytes: ByteArray): Int = CRC32().also { it.update(bytes) }.value.toInt()

/** Режет сообщение на куски по [size] байт; у пустого сообщения всё равно один (пустой) кусок. */
internal fun splitMessage(message: ByteArray, size: Int = FRAME_CHUNK_BYTES): List<ByteArray> {
    if (message.isEmpty()) return listOf(ByteArray(0))
    return (message.indices step size).map { message.copyOfRange(it, minOf(it + size, message.size)) }
}

/**
 * Собирает сообщение из кадров, пришедших в любом порядке и с повторами. Если пришёл кадр другого сообщения
 * (другой размер или контрольная сумма) — начинает заново; если собранное не сошлось с контрольной суммой — тоже.
 */
internal class Assembler {
    private var total = 0
    private var crc = 0
    private var chunks: Array<ByteArray?> = emptyArray()
    private var have = 0
    private var done = false

    val received: Int get() = have
    val expected: Int get() = total
    val isComplete: Boolean get() = done

    fun reset() {
        total = 0
        crc = 0
        chunks = emptyArray()
        have = 0
        done = false
    }

    /** @return готовое сообщение в тот момент, когда этот кадр его завершил; иначе null. */
    fun add(frame: Frame): ByteArray? {
        if (frame.total != total || frame.crc != crc) {
            reset()
            total = frame.total
            crc = frame.crc
            chunks = arrayOfNulls(total)
        }
        if (done) return null
        if (chunks[frame.index] == null) {
            chunks[frame.index] = frame.chunk
            have++
        }
        if (have < total) return null
        val message = ByteArray(chunks.sumOf { it!!.size })
        var offset = 0
        for (chunk in chunks) {
            chunk!!.copyInto(message, offset)
            offset += chunk.size
        }
        if (crcOf(message) != crc) {
            // Один из кадров прочитан с ошибкой: собираем заново со следующего круга.
            val t = total
            val c = crc
            reset()
            total = t
            crc = c
            chunks = arrayOfNulls(total)
            return null
        }
        done = true
        return message
    }
}
