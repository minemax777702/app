package com.mok.buylist.data

import java.math.BigDecimal
import java.math.RoundingMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.UUID

/** Цена из витрины передаётся строкой; непонятное значение не превращаем в ноль. */
internal fun parsePriceKopecks(raw: String): Long? = try {
    val price = BigDecimal(raw.trim().replace(',', '.'))
    if (price.signum() < 0) null else price.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
} catch (_: NumberFormatException) {
    null
} catch (_: ArithmeticException) {
    null
}

internal fun parseWeightGrams(raw: String): Int? = try {
    if (raw.isBlank()) 1000 else BigDecimal(raw.trim().replace(',', '.')).movePointRight(3)
        .setScale(0, RoundingMode.UNNECESSARY).intValueExact().takeIf { it in 1..999000 }
} catch (_: NumberFormatException) { null } catch (_: ArithmeticException) { null }

internal fun priceForWeight(price: Long, grams: Int): Long? = try {
    if (price < 0 || grams !in 1..999000) null else BigDecimal.valueOf(price).multiply(BigDecimal.valueOf(grams.toLong()))
        .divide(BigDecimal.valueOf(1000), 0, RoundingMode.HALF_UP).longValueExact()
} catch (_: ArithmeticException) { null }

internal data class PricedLine(val quantity: Int, val bought: Boolean, val unitPriceKopecks: Long?, val weightGrams: Int? = null)
internal data class PartialAmount(val kopecks: Long, val known: Int, val unknown: Int)
internal data class ShoppingTotals(val total: PartialAmount, val remaining: PartialAmount)

/** Переходный снимок до появления идентификатора товара в строке списка на этапе 07. */
internal data class PurchaseEvent(
    val id: String, val productId: Long?, val name: String, val unitPrice: Long?,
    val priceType: String, val quantity: Int, val boughtAt: Long, val buyer: String,
    val address: String, val channel: String, val fetchedAt: Long, val impulsive: Boolean = false, val weightGrams: Int? = null,
    val customProductId: String = "", val storeId: String = "",
)

internal fun purchaseEvent(item: ShoppingItem, product: LentaProduct?, now: Long, buyer: String, priceOverride: Long? = null, impulsive: Boolean = false, storeId: String = ""): String {
    return encodePurchaseEvent(PurchaseEvent(UUID.randomUUID().toString(), item.productId.takeIf { it > 0 } ?: product?.id, item.name,
        priceOverride ?: product?.price?.let(::parsePriceKopecks), product?.priceType.orEmpty(), item.quantity,
        now, buyer, product?.address.orEmpty(), product?.channel.orEmpty(), product?.fetchedAt ?: 0, impulsive,
        if (priceOverride == null) item.weightGrams else null, item.customProductId, storeId))
}

private fun encodePurchaseEvent(event: PurchaseEvent): String {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { out ->
        out.writeUTF(event.id); out.writeLong(event.productId ?: 0)
        out.writeUTF(event.name); out.writeLong(event.unitPrice ?: -1)
        out.writeUTF(event.priceType); out.writeInt(event.quantity); out.writeLong(event.boughtAt)
        out.writeUTF(event.buyer); out.writeUTF(event.address); out.writeUTF(event.channel)
        out.writeLong(event.fetchedAt)
        out.writeBoolean(event.impulsive)
        out.writeInt(event.weightGrams ?: -1)
        out.writeUTF(event.customProductId)
        out.writeUTF(event.storeId)
    }
    return Base64.getEncoder().encodeToString(bytes.toByteArray())
}

internal fun decodePurchaseEvent(encoded: String): PurchaseEvent? = runCatching {
    DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(encoded))).use { input ->
        PurchaseEvent(input.readUTF(), input.readLong().takeIf { it > 0 }, input.readUTF(),
            input.readLong().takeIf { it >= 0 }, input.readUTF(), input.readInt(), input.readLong(),
            input.readUTF(), input.readUTF(), input.readUTF(), input.readLong(), if (input.available() > 0) input.readBoolean() else false,
            if (input.available() >= 4) input.readInt().takeIf { it in 1..999000 } else null,
            if (input.available() > 0) input.readUTF() else "",
            if (input.available() > 0) input.readUTF() else "")
    }
}.getOrNull()

internal fun reassignPurchaseEvent(encoded: String, old: String, new: String): String {
    val event = decodePurchaseEvent(encoded) ?: return encoded
    return if (event.buyer == old) encodePurchaseEvent(event.copy(buyer = new)) else encoded
}

/** Переполнение учитывается как неизвестная цена, а не как отрицательная сумма. */
internal fun calculateShoppingTotals(lines: List<PricedLine>): ShoppingTotals {
    fun amount(selected: List<PricedLine>): PartialAmount {
        var sum = 0L
        var known = 0
        var unknown = 0
        for (line in selected) {
            val value = try {
                val price = line.unitPriceKopecks
                if (price == null || price < 0 || line.quantity !in 1..999) null
                else if (line.weightGrams != null) priceForWeight(price, line.weightGrams)
                else Math.multiplyExact(price, line.quantity.toLong())
            } catch (_: ArithmeticException) { null }
            val next = try { value?.let { Math.addExact(sum, it) } } catch (_: ArithmeticException) { null }
            if (next == null) unknown++ else { sum = next; known++ }
        }
        return PartialAmount(sum, known, unknown)
    }
    return ShoppingTotals(amount(lines), amount(lines.filterNot { it.bought }))
}
