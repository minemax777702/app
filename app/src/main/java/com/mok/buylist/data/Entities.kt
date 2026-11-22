package com.mok.buylist.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/** Убирает лишние пробелы по краям и внутри названия. */
fun normalizeName(raw: String): String = raw.trim().replace(Regex("\\s+"), " ")

/** Нормализованный ключ названия: по нему ищем дубликаты и подсказки. */
fun nameKeyOf(name: String): String = normalizeName(name).lowercase()

/** Сохранённая карточка витрины; цена хранится в копейках. */
@Entity(tableName = "lenta_products")
data class StoredLentaProduct(
    @PrimaryKey val id: Long,
    val name: String,
    val image: String,
    val pack: String,
    val priceKopecks: Long?,
    val priceType: String,
    val ean: String,
    val address: String,
    val storeId: Long,
    val channel: String,
    val fetchedAt: Long,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
)

/** Подтверждённый код товара. Несколько товаров могут иметь один код — выбор тогда неоднозначен. */
@Entity(tableName = "product_barcodes", primaryKeys = ["productId", "code"], indices = [Index(value = ["code"])])
data class StoredProductBarcode(
    val productId: Long,
    val code: String,
)

/**
 * Товар в списке. Купленный товар не удаляется сразу, а помечается [deleted] — так работает отмена покупки.
 * [authorId] — пользователь, сделавший последнее действие (добавил, купил, вернул); пусто — до регистрации.
 */
@Entity(tableName = "items", indices = [Index(value = ["nameKey"], unique = true)])
data class ShoppingItem(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val nameKey: String = nameKeyOf(name),
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = createdAt,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
    @ColumnInfo(defaultValue = "''") val authorId: String = "",
    @ColumnInfo(defaultValue = "1") val quantity: Int = 1,
    @ColumnInfo(defaultValue = "''") val purchaseEvent: String = "",
    @ColumnInfo(defaultValue = "0") val productId: Long = 0,
    val weightGrams: Int? = null,
)

/** Новый случайный идентификатор записи архива: 32 шестнадцатеричных символа. */
fun newArchiveUid(): String = UUID.randomUUID().toString().replace("-", "")

/**
 * Идентификатор завершённого списка, одинаковый на всех телефонах: из времени завершения и состава. Если после
 * обмена оба телефона увидели «всё куплено» и каждый завёл запись, при следующем обмене это одна и та же запись.
 */
fun archiveUidOf(completedAt: Long, nameKeys: Collection<String>): String =
    UUID.nameUUIDFromBytes("$completedAt|${nameKeys.sorted().joinToString("\n")}".toByteArray())
        .toString().replace("-", "")

/**
 * Завершённый список: все товары куплены. [items] — названия через перевод строки в порядке покупки
 * (в названиях переводов строки нет, их убирает [normalizeName]).
 */
@Entity(tableName = "archived_lists", indices = [Index(value = ["uid"], unique = true)])
data class ArchivedList(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Когда куплен последний товар. */
    val completedAt: Long,
    val itemCount: Int,
    val items: String,
    /** Сколько длился поход (сумма заходов, см. [computeTripTime]); 0 — неизвестно, например у записей до появления таймера. */
    @ColumnInfo(defaultValue = "0") val tripMillis: Long = 0,
    /** На сколько заходов разбился поход; 0 — неизвестно. */
    @ColumnInfo(defaultValue = "0") val visits: Int = 0,
    /** Глобальный идентификатор: числовой [id] у каждого телефона свой, по нему записи не сопоставить. */
    @ColumnInfo(defaultValue = "''") val uid: String = newArchiveUid(),
    /** Кто купил каждый товар: id пользователей через перевод строки, по порядку [items]; пусто — неизвестно. */
    @ColumnInfo(defaultValue = "''") val buyers: String = "",
    @ColumnInfo(defaultValue = "''") val quantities: String = "",
    @ColumnInfo(defaultValue = "''") val purchaseEvents: String = "",
    @ColumnInfo(defaultValue = "''") val remainingEvents: String = "",
) {
    val names: List<String> get() = items.split('\n').filter { it.isNotEmpty() }
    val events: List<String> get() = purchaseEvents.split('\n').let { parts -> List(names.size) { parts.getOrElse(it) { "" } } }
    val hasRemainingSnapshot: Boolean get() = remainingEvents.startsWith("v1|")
    internal val remaining: List<PurchaseEvent> get() =
        remainingEvents.removePrefix("v1|").split('\n').mapNotNull { decodePurchaseEvent(it) }
    val counts: List<Int> get() {
        val parts = quantities.split('\n')
        return List(names.size) { parts.getOrNull(it)?.toIntOrNull()?.coerceIn(1, 999) ?: 1 }
    }

    /** Покупатели по порядку [names]; для неизвестного — пустая строка. */
    val buyerIds: List<String>
        get() {
            val parts = if (buyers.isEmpty()) emptyList() else buyers.split('\n')
            return List(names.size) { parts.getOrElse(it) { "" } }
        }
}

/**
 * «Надгробие»: запись стёрта в момент [deletedAt]. Без него при обмене телефон, где запись ещё есть, вернул бы её
 * обратно. [kind] — код `TombKind`, [target] — nameKey (или uid для архива).
 */
@Entity(tableName = "tombstones", primaryKeys = ["kind", "target"])
data class Tombstone(val kind: Int, val target: String, val deletedAt: Long)

/** Пользователь приложения (в том числе чужой, пришедший при обмене): по нему статистика делится на людей. */
@Entity(tableName = "users")
data class AppUser(@PrimaryKey val id: String, val name: String, val updatedAt: Long)
