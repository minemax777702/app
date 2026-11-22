package com.mok.buylist.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import com.mok.buylist.data.StoredLentaProduct
import com.mok.buylist.data.StoredProductBarcode
import com.mok.buylist.data.CustomProductBarcode
import com.mok.buylist.data.CustomProduct
import com.mok.buylist.data.ShoppingStore

class SyncFormatException(message: String) : Exception(message)

internal class ByteWriter {
    private val out = ByteArrayOutputStream()

    fun byte(v: Int) = out.write(v)

    /** Целое переменной длины со знаком (zigzag): маленькие числа занимают один-два байта. */
    fun long(v: Long) {
        var z = (v shl 1) xor (v shr 63)
        while ((z and 0x7FL.inv()) != 0L) {
            out.write(((z and 0x7F) or 0x80).toInt())
            z = z ushr 7
        }
        out.write(z.toInt())
    }

    fun int(v: Int) = long(v.toLong())

    fun string(s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        int(bytes.size)
        out.write(bytes)
    }

    fun raw(bytes: ByteArray) = out.write(bytes)

    fun toByteArray(): ByteArray = out.toByteArray()
}

internal class ByteReader(private val data: ByteArray) {
    private var pos = 0
    private val remaining get() = data.size - pos

    fun byte(): Int {
        if (pos >= data.size) throw SyncFormatException("неожиданный конец данных")
        return data[pos++].toInt() and 0xFF
    }

    fun long(): Long {
        var shift = 0
        var result = 0L
        while (true) {
            val b = byte()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 63) throw SyncFormatException("слишком длинное число")
        }
        return (result ushr 1) xor -(result and 1)
    }

    fun int(): Int {
        val v = long()
        if (v < Int.MIN_VALUE || v > Int.MAX_VALUE) throw SyncFormatException("число вне диапазона")
        return v.toInt()
    }

    /** Размер списка: каждая запись занимает хотя бы байт, поэтому больше оставшегося быть не может. */
    fun count(): Int {
        val n = int()
        if (n < 0 || n > remaining) throw SyncFormatException("неверный размер списка")
        return n
    }

    fun string(): String {
        val n = int()
        if (n < 0 || n > remaining) throw SyncFormatException("неверная длина строки")
        val s = String(data, pos, n, Charsets.UTF_8)
        pos += n
        return s
    }

    fun double(): Double {
        var bits = 0L
        repeat(8) { bits = (bits shl 8) or byte().toLong() }
        return java.lang.Double.longBitsToDouble(bits)
    }

    fun raw(n: Int): ByteArray {
        if (n < 0 || n > remaining) throw SyncFormatException("неожиданный конец данных")
        return data.copyOfRange(pos, pos + n).also { pos += n }
    }

    /** Всё, что осталось непрочитанным. */
    fun rest(): ByteArray = raw(remaining)
}

/** Двоичный формат сообщений. Сообщение сжимается deflate: названия в архиве и истории часто повторяются. */
object SyncCodec {
    private const val VERSION = 11
    private const val MAX_UNPACKED_BYTES = 16 * 1024 * 1024

    fun encode(snapshot: Snapshot): ByteArray = deflate(write(snapshot))

    fun decode(bytes: ByteArray): Snapshot = read(inflate(bytes))

    private fun write(s: Snapshot): ByteArray {
        val w = ByteWriter()
        w.byte(VERSION)
        w.int(s.users.size)
        for (u in s.users) { w.string(u.id); w.string(u.name); w.long(u.at) }
        w.int(s.items.size)
        for (i in s.items) {
            w.string(i.name); w.long(i.createdAt); w.long(i.at); w.byte(if (i.bought) 1 else 0); w.string(i.author); w.int(i.quantity); w.string(i.purchaseEvent); w.long(i.productId); w.int(i.weightGrams ?: -1); w.string(i.customProductId)
        }
        w.int(s.history.size)
        for (h in s.history) { w.string(h.name); w.int(h.useCount); w.long(h.lastUsedAt) }
        w.int(0) // Пустой раздел цен в формате версии 1.
        w.int(s.hidden.size)
        for (h in s.hidden) { w.string(h.key); w.long(h.at) }
        w.int(s.archives.size)
        for (a in s.archives) {
            writeUid(w, a.uid)
            w.long(a.completedAt); w.long(a.tripMillis); w.int(a.visits); w.long(0)
            w.int(a.names.size)
            a.names.forEachIndexed { index, name -> w.string(name); w.string(a.buyers.getOrElse(index) { "" }); w.int(a.quantities.getOrElse(index) { 1 }); w.string(a.purchaseEvents.getOrElse(index) { "" }) }
            w.int(0)
            w.string(a.remainingEvents)
        }
        w.int(s.tombs.size)
        for (t in s.tombs) { w.byte(t.kind.code); w.string(t.key); w.long(t.at) }
        w.int(s.products.size)
        for (p in s.products) {
            w.long(p.id); w.string(p.name); w.string(p.image); w.string(p.pack)
            w.long(p.priceKopecks ?: -1); w.string(p.priceType); w.string(p.ean)
            w.string(p.address); w.long(p.storeId); w.string(p.channel); w.long(p.fetchedAt); w.long(p.updatedAt)
        }
        w.int(s.productBarcodes.size)
        for (barcode in s.productBarcodes) { w.long(barcode.productId); w.string(barcode.code); w.long(barcode.updatedAt); w.byte(if (barcode.deleted) 1 else 0) }
        w.int(s.customProducts.size)
        for (p in s.customProducts) {
            w.string(p.id); w.string(p.name); w.string(p.barcode); w.long(p.updatedAt); w.byte(if (p.deleted) 1 else 0); w.long(p.estimatedPriceKopecks ?: -1)
        }
        w.int(s.stores.size)
        for (store in s.stores) {
            w.string(store.id); w.string(store.name); w.string(store.nameKey); w.long(store.updatedAt); w.byte(if (store.deleted) 1 else 0)
        }
        w.int(s.customProductBarcodes.size)
        for (barcode in s.customProductBarcodes) {
            w.string(barcode.productId); w.string(barcode.code); w.long(barcode.updatedAt)
            w.byte(if (barcode.deleted) 1 else 0)
        }
        return w.toByteArray()
    }

    private fun read(data: ByteArray): Snapshot {
        val r = ByteReader(data)
        val version = r.byte()
        if (version !in 1..VERSION) throw SyncFormatException("другая версия протокола")
        val users = List(r.count()) { SyncUser(r.string(), r.string(), r.long()) }
        val items = List(r.count()) {
            SyncItem(r.string(), r.long(), r.long(), r.byte() != 0, r.string(), if (version >= 2) r.int().coerceIn(1, 999) else 1, if (version >= 3) r.string() else "", if (version >= 4) r.long() else 0,
                if (version >= 6) r.int().takeIf { it in 1..999000 } else null,
                if (version >= 9) r.string() else "")
        }
        val history = List(r.count()) { SyncHistory(r.string(), r.int(), r.long()) }
        repeat(r.count()) { r.string(); r.long(); r.string(); r.long() } // Старые цены пропускаются.
        val hidden = List(r.count()) { SyncHidden(r.string(), r.long()) }
        val archives = List(r.count()) {
            val uid = readUid(r)
            val completedAt = r.long()
            val tripMillis = r.long()
            val visits = r.int()
            r.long() // Время изменения цен в старом формате.
            val n = r.count()
            val names = ArrayList<String>(n)
            val buyers = ArrayList<String>(n)
            val quantities = ArrayList<Int>(n)
            val events = ArrayList<String>(n)
            repeat(n) { names += r.string(); buyers += r.string(); quantities += if (version >= 2) r.int().coerceIn(1, 999) else 1; events += if (version >= 3) r.string() else "" }
            repeat(r.count()) { r.string(); r.long(); r.double(); r.string() }
            val remainingEvents = if (version >= 5) r.string() else ""
            SyncArchive(uid, completedAt, names, buyers, tripMillis, visits, quantities, events, remainingEvents)
        }
        val tombs = buildList {
            repeat(r.count()) {
                val code = r.byte()
                val key = r.string()
                val at = r.long()
                if (code != 2) add(SyncTomb(TombKind.fromCode(code) ?: throw SyncFormatException("неизвестный вид удаления"), key, at))
            }
        }
        val products = if (version >= 4) List(r.count()) {
            StoredLentaProduct(r.long(), r.string(), r.string(), r.string(), r.long().takeIf { it >= 0 },
                r.string(), r.string(), r.string(), r.long(), r.string(), r.long(), r.long())
        } else emptyList()
        val barcodes = if (version >= 7) List(r.count()) {
            StoredProductBarcode(r.long(), r.string(), if (version >= 8) r.long() else 0, if (version >= 8) r.byte() != 0 else false)
        } else products.mapNotNull { p ->
            p.ean.takeIf { it.isNotBlank() }?.let { StoredProductBarcode(p.id, it) }
        }
        val customProducts = if (version >= 9) List(r.count()) {
            CustomProduct(r.string(), r.string(), r.string(), r.long(), r.byte() != 0,
                if (version >= 11) r.long().takeIf { it >= 0 } else null)
        } else emptyList()
        val stores = if (version >= 9) List(r.count()) {
            ShoppingStore(r.string(), r.string(), r.string(), r.long(), r.byte() != 0)
        } else emptyList()
        val customBarcodes = if (version >= 10) List(r.count()) {
            CustomProductBarcode(r.string(), r.string(), r.long(), r.byte() != 0)
        } else customProducts.mapNotNull { product ->
            product.barcode.takeIf { it.isNotBlank() }?.let {
                CustomProductBarcode(product.id, it, product.updatedAt)
            }
        }
        return Snapshot(users, items, history, hidden, archives, tombs, products, barcodes,
            customProducts, stores, customBarcodes)
    }

    /** uid — 32 шестнадцатеричных символа: храним 16 байтами; нестандартный — строкой. */
    private fun writeUid(w: ByteWriter, uid: String) {
        val bytes = hexToBytes(uid)
        if (bytes != null) {
            w.byte(1)
            w.raw(bytes)
        } else {
            w.byte(0)
            w.string(uid)
        }
    }

    private fun readUid(r: ByteReader): String =
        if (r.byte() == 1) r.raw(16).joinToString("") { "%02x".format(it.toInt() and 0xFF) } else r.string()

    private fun hexToBytes(s: String): ByteArray? {
        if (s.length != 32) return null
        val out = ByteArray(16)
        for (i in 0 until 16) {
            val hi = Character.digit(s[i * 2], 16)
            val lo = Character.digit(s[i * 2 + 1], 16)
            if (hi < 0 || lo < 0 || s[i * 2].isUpperCase() || s[i * 2 + 1].isUpperCase()) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out, java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION)).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun inflate(bytes: ByteArray): ByteArray {
        try {
            val out = ByteArrayOutputStream()
            InflaterInputStream(ByteArrayInputStream(bytes)).use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_UNPACKED_BYTES) throw SyncFormatException("слишком большое сообщение")
                }
            }
            return out.toByteArray()
        } catch (e: java.io.IOException) {
            throw SyncFormatException("повреждённое сообщение")
        }
    }
}
