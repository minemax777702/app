package com.mok.buylist.sync

import com.mok.buylist.data.nameKeyOf
import com.mok.buylist.data.StoredLentaProduct
import com.mok.buylist.data.StoredProductBarcode
import com.mok.buylist.data.CustomProductBarcode
import com.mok.buylist.data.CustomProduct
import com.mok.buylist.data.ShoppingStore
import com.mok.buylist.data.StoreProduct
import com.mok.buylist.data.decodePurchaseEvent

/*
 * Модель обмена между телефонами. Это не таблицы Room, а «снимок» состояния, который не зависит от локальных
 * числовых id: товары и продукты опознаются по названию (nameKey), записи архива — по uid.
 *
 * Правило слияния везде одно: побеждает запись с более поздним временем действия. Удаления хранятся «надгробиями»
 * ([SyncTomb]) — иначе телефон, где запись ещё есть, вернул бы удалённое обратно.
 */

/** Пользователь: [at] — когда менялось имя (побеждает позднее). */
data class SyncUser(val id: String, val name: String, val at: Long)

/** Товар текущего списка. [at] — время последнего изменения, [bought] — куплен, [author] — кто изменил. */
data class SyncItem(
    val name: String,
    val createdAt: Long,
    val at: Long,
    val bought: Boolean,
    val author: String,
    val quantity: Int = 1,
    val purchaseEvent: String = "",
    val productId: Long = 0,
    val weightGrams: Int? = null,
    val customProductId: String = "",
) {
    val key: String get() {
        val event = decodePurchaseEvent(purchaseEvent)
        return if (event?.impulsive == true) "impulse:${event.id}"
            else if (customProductId.isNotBlank()) "custom:$customProductId"
            else if (productId > 0) "lenta:$productId" else nameKeyOf(name)
    }
}

/** Запись истории подсказок. Счётчик и время при слиянии берутся по максимуму. */
data class SyncHistory(val name: String, val useCount: Int, val lastUsedAt: Long) {
    val key: String get() = nameKeyOf(name)
}

/** Продукт, удалённый из базы продуктов, и когда это случилось. */
data class SyncHidden(val key: String, val at: Long)

/**
 * Завершённый список. [names] и [buyers] идут параллельно (кто купил каждый товар; пустая строка — неизвестно).
 */
data class SyncArchive(
    val uid: String,
    val completedAt: Long,
    val names: List<String>,
    val buyers: List<String>,
    val tripMillis: Long,
    val visits: Int,
    val quantities: List<Int> = List(names.size) { 1 },
    val purchaseEvents: List<String> = List(names.size) { "" },
    val remainingEvents: String = "",
)

enum class TombKind(val code: Int) {
    ITEM(0),
    HISTORY(1),

    /** Продукт снова показан в базе (отмена «скрытия»). */
    HIDDEN(3),
    ARCHIVE(4),
    PRODUCT(5);

    companion object {
        fun fromCode(code: Int): TombKind? = entries.firstOrNull { it.code == code }
    }
}

/** Запись удалена в момент [at]. */
data class SyncTomb(val kind: TombKind, val key: String, val at: Long)

data class Snapshot(
    val users: List<SyncUser> = emptyList(),
    val items: List<SyncItem> = emptyList(),
    val history: List<SyncHistory> = emptyList(),
    val hidden: List<SyncHidden> = emptyList(),
    val archives: List<SyncArchive> = emptyList(),
    val tombs: List<SyncTomb> = emptyList(),
    val products: List<StoredLentaProduct> = emptyList(),
    val productBarcodes: List<StoredProductBarcode> = emptyList(),
    val customProducts: List<CustomProduct> = emptyList(),
    val stores: List<ShoppingStore> = emptyList(),
    val customProductBarcodes: List<CustomProductBarcode> = emptyList(),
    val storeProducts: List<StoreProduct> = emptyList(),
)

/** Короткий «отпечаток» записи архива: по нему второй телефон понимает, что у меня это уже есть. */
data class ArchiveStamp(val prefix: Long, val digest: String = "")

/** Что уже есть у телефона: по нему второй телефон не присылает архив повторно. */
data class Hello(val archives: List<ArchiveStamp>)

/** Разделы снимка: удаления других разделов не передаются. */
enum class SnapshotSection { LIST, ARCHIVE, ALL }

fun Snapshot.selectSection(section: SnapshotSection): Snapshot = when (section) {
    SnapshotSection.ALL -> this
    SnapshotSection.LIST -> Snapshot(users = users, items = items, products = products, productBarcodes = productBarcodes, customProducts = customProducts, stores = stores, customProductBarcodes = customProductBarcodes, storeProducts = storeProducts, tombs = tombs.filter { it.kind == TombKind.ITEM || it.kind == TombKind.PRODUCT })
    SnapshotSection.ARCHIVE -> Snapshot(users = users, archives = archives, customProducts = customProducts, stores = stores, customProductBarcodes = customProductBarcodes, storeProducts = storeProducts, tombs = tombs.filter { it.kind == TombKind.ARCHIVE })
}
