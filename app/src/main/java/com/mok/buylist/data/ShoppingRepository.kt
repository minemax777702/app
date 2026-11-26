package com.mok.buylist.data

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.room.withTransaction
import androidx.room.InvalidationTracker
import android.util.Log
import com.mok.buylist.lentaCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.mok.buylist.sync.BackupCodec
import com.mok.buylist.sync.BackupContents
import com.mok.buylist.sync.Snapshot
import com.mok.buylist.sync.SyncCodec
import com.mok.buylist.sync.SyncFormatException
import com.mok.buylist.sync.SyncArchive
import com.mok.buylist.sync.SyncItem
import com.mok.buylist.sync.SyncMerge
import com.mok.buylist.sync.SyncTomb
import com.mok.buylist.sync.SyncUser
import com.mok.buylist.sync.TombKind
import com.mok.buylist.widget.ShoppingWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Сколько хранится «надгробие»: телефон, не бывший на обмене дольше, может вернуть удалённое — это допустимо. */
private const val TOMBSTONE_TTL_MILLIS = 400L * 24 * 60 * 60 * 1000

/** Как долго после обмена его можно отменить. */
const val UNDO_WINDOW_MILLIS = 10L * 60 * 1000

/** Что изменилось после обмена, для сообщения пользователю. */
data class SyncSummary(val items: Int, val archives: Int) {
    operator fun plus(other: SyncSummary) =
        SyncSummary(items + other.items, archives + other.archives)
}

data class ScannedPurchaseResult(val item: ShoppingItem, val impulsive: Boolean)

class ShoppingRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val profile: UserProfile,
) {
    private val dao = db.shoppingDao()
    private val previewScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private fun fetchPreview(id: String, barcode: String) {
        if (barcode.isNotBlank()) previewScope.launch {
            com.mok.buylist.ui.CustomProductPreviews.fetch(context, id, barcode)
        }
    }
    fun observeItems(): Flow<List<ShoppingItem>> = dao.observeItems()
    fun observeCustomProducts(): Flow<List<CustomProduct>> = dao.observeCustomProducts()
    fun observeStores(): Flow<List<ShoppingStore>> = dao.observeStores().map { rows ->
        val builtin = ShoppingStore(lentaStoreId(), "Лента", updatedAt = 0L)
        if (rows.any { it.id == builtin.id && !it.deleted }) rows
        else rows.filterNot { it.id == builtin.id } + builtin
    }

    suspend fun renameStore(id: String, rawName: String): String? = db.withTransaction {
        if (id == lentaStoreId()) return@withTransaction "Магазин Лента нельзя переименовать"
        val name = normalizeName(rawName)
        if (name.isBlank() || name.length > 100) return@withTransaction "Название должно содержать от 1 до 100 символов"
        val stores = dao.getStores()
        val store = stores.firstOrNull { it.id == id && !it.deleted }
            ?: return@withTransaction "Магазин не найден"
        if (stores.any { it.id != id && !it.deleted && it.nameKey == nameKeyOf(name) } ||
            nameKeyOf(name) == nameKeyOf("Лента"))
            return@withTransaction "Такой магазин уже существует"
        dao.upsertStore(store.copy(name = name, nameKey = nameKeyOf(name),
            updatedAt = maxOf(System.currentTimeMillis(), store.updatedAt + 1)))
        null
    }

    suspend fun deleteStore(id: String): String? = db.withTransaction {
        if (id == lentaStoreId()) return@withTransaction "Магазин Лента нельзя удалить"
        val store = dao.getStores().firstOrNull { it.id == id && !it.deleted }
            ?: return@withTransaction "Магазин не найден"
        // Keep name and UUID for archived purchase history and sync tombstones.
        dao.upsertStore(store.copy(deleted = true,
            updatedAt = maxOf(System.currentTimeMillis(), store.updatedAt + 1)))
        null
    }

    /** A store is created explicitly, never as a side effect of typing in checkout. */
    suspend fun createStore(rawName: String): ShoppingStore? = db.withTransaction {
        val name = normalizeName(rawName)
        if (name.isBlank() || name.length > 100) return@withTransaction null
        if (nameKeyOf(name) == nameKeyOf("Лента"))
            return@withTransaction ShoppingStore(lentaStoreId(), "Лента", updatedAt = 0L)
        dao.getStores().firstOrNull { !it.deleted && it.nameKey == nameKeyOf(name) }
            ?.let { return@withTransaction it }
        val now = System.currentTimeMillis()
        ShoppingStore(stableStoreId(name), name, updatedAt = now).also { dao.upsertStore(it) }
    }

    /** Read from live purchases and archived receipts; no catalog or estimated prices. */
    suspend fun latestStorePrice(productKey: String, storeId: String,
        excludedEventId: String? = null): Long? {
        if (productKey.isBlank() || storeId.isBlank()) return null
        val aliases = sharedCatalogIdentities(
            dao.getLentaProducts().map { it.toProduct() }, dao.getCustomProducts(),
            dao.getProductBarcodes(), dao.getCustomProductBarcodes())
        val canonical = aliases[productKey] ?: productKey
        val configured = dao.getStoreProducts().filter { !it.deleted &&
            it.storeId == storeId && it.priceKopecks != null &&
            (aliases[it.productKey] ?: it.productKey) == canonical }
            .maxByOrNull { it.updatedAt }?.priceKopecks
        if (configured != null) return configured
        if (storeId == lentaStoreId()) {
            dao.getLentaProducts().map { it.toProduct() }.firstOrNull {
                (aliases["lenta:${it.id}"] ?: "lenta:${it.id}") == canonical &&
                    it.address == context.lentaCatalog.address.value && it.address.isNotBlank() && it.channel == "locc"
            }?.price?.let(::parsePriceKopecks)?.let { return it }
        }
        val receipts = dao.getBought().mapNotNull { decodePurchaseEvent(it.purchaseEvent) } +
            dao.getArchives().flatMap { archive -> archive.events.mapNotNull(::decodePurchaseEvent) }
        return lastStorePurchasePrice(receipts, productKey, storeId, aliases, excludedEventId)
    }


    fun observeStoreProducts(): Flow<List<StoreProduct>> = dao.observeStoreProducts()

    /**
     * Create a catalog entry directly inside a store. It must not add a planned
     * shopping-list item; catalog management and shopping are separate actions.
     */
    suspend fun createCustomForStore(
        storeId: String, rawName: String, rawBarcode: String,
        storePrice: Long?, isWeighted: Boolean,
    ): String? = db.withTransaction {
        val name = normalizeName(rawName)
        val barcode = rawBarcode.trim()
        if (name.isBlank() || name.length > MAX_CUSTOM_PRODUCT_NAME_LENGTH)
            return@withTransaction null
        if (barcode.isNotEmpty() && !validProductBarcode(barcode)) return@withTransaction null
        if (storePrice != null && storePrice < 0) return@withTransaction null
        if (storeId != lentaStoreId() &&
            dao.getStores().none { it.id == storeId && !it.deleted })
            return@withTransaction null
        val matching = if (barcode.isBlank()) emptyList() else {
            val aliases = dao.getCustomProductBarcodes()
                .filter { !it.deleted && it.code == barcode }.mapTo(HashSet()) { it.productId }
            dao.getCustomProducts().filter {
                !it.deleted && (it.id in aliases ||
                    (it.barcode == barcode && dao.getCustomProductBarcodes().none { row ->
                        row.productId == it.id && row.code == barcode && row.deleted
                    }))
            }
        }
        if (matching.size > 1) return@withTransaction null
        val now = System.currentTimeMillis()
        val product = matching.singleOrNull() ?: CustomProduct(
            id = UUID.randomUUID().toString(), name = name, barcode = barcode,
            updatedAt = now, isWeighted = isWeighted)
                .also { dao.upsertCustomProduct(it) }
        if (barcode.isNotBlank() && matching.isEmpty())
            dao.upsertCustomProductBarcode(CustomProductBarcode(product.id, barcode, now))
        val earlier = dao.getStoreProducts().firstOrNull {
            it.storeId == storeId && it.productKey == customKey(product.id) }
        dao.upsertStoreProduct(StoreProduct(storeId, customKey(product.id), storePrice,
            maxOf(now, (earlier?.updatedAt ?: 0L) + 1)))
        product.id
    }.also { id -> if (id != null) fetchPreview(id, rawBarcode.trim()) }

    suspend fun saveStoreProduct(storeId: String, productKey: String, price: Long?): String? =
        db.withTransaction {
            if (storeId.isBlank() || productKey.isBlank()) return@withTransaction "Выберите магазин и товар"
            if (price != null && price < 0) return@withTransaction "Некорректная цена"
            val available = storeId == lentaStoreId() ||
                dao.getStores().any { it.id == storeId && !it.deleted }
            if (!available) return@withTransaction "Магазин не найден"
            val valid = if (productKey.startsWith("custom:")) {
                dao.getCustomProducts().any { it.id == productKey.removePrefix("custom:") && !it.deleted }
            } else if (productKey.startsWith("lenta:")) {
                dao.getLentaProducts().any { it.id.toString() == productKey.removePrefix("lenta:") }
            } else false
            if (!valid) return@withTransaction "Товар не найден"
            val aliases = sharedCatalogIdentities(dao.getLentaProducts().map { it.toProduct() }, dao.getCustomProducts(),
                dao.getProductBarcodes(), dao.getCustomProductBarcodes())
            val canonical = aliases[productKey] ?: productKey
            val matching = dao.getStoreProducts().filter { it.storeId == storeId &&
                (aliases[it.productKey] ?: it.productKey) == canonical }
            val now = maxOf(System.currentTimeMillis(), (matching.maxOfOrNull { it.updatedAt } ?: 0L) + 1)
            // Keep availability links; update all active aliases consistently.
            matching.filterNot { it.deleted }.forEach {
                dao.upsertStoreProduct(it.copy(priceKopecks = price, updatedAt = now))
            }
            dao.upsertStoreProduct(StoreProduct(storeId, productKey, price, now))
            null
        }

    /**
     * A confirmed purchase makes the product available at its chosen store.
     * Store catalog prices are edited separately and MUST NOT be overwritten
     * by the price of a single receipt.
     * Called inside the purchase / amendment Room transaction.
     */
    private suspend fun attachBoughtProductToStore(
        storeId: String, productKey: String, at: Long,
    ) {
        if (storeId.isBlank() || productKey.isBlank()) return
        val existing = dao.getStoreProducts().firstOrNull {
            it.storeId == storeId && it.productKey == productKey
        }
        linkForConfirmedPurchase(storeId, productKey, at, existing)
            ?.let { dao.upsertStoreProduct(it) }
    }

    suspend fun removeStoreProduct(storeId: String, productKey: String): String? = db.withTransaction {
        if (storeId == lentaStoreId() && productKey.startsWith("lenta:")) return@withTransaction "Для товара каталога сбросьте ручную цену"
        val existing = dao.getStoreProducts().firstOrNull {
            it.storeId == storeId && it.productKey == productKey && !it.deleted
        } ?: return@withTransaction "Товар не найден"
        dao.upsertStoreProduct(existing.copy(deleted = true,
            updatedAt = maxOf(System.currentTimeMillis(), existing.updatedAt + 1)))
        null
    }

    fun observeCustomBarcodes(): Flow<List<CustomProductBarcode>> = dao.observeCustomProductBarcodes()

    suspend fun editCustomProduct(id: String, rawName: String): String? = db.withTransaction {
        val name = normalizeName(rawName).take(MAX_CUSTOM_PRODUCT_NAME_LENGTH)
        if (name.isEmpty()) return@withTransaction "Введите название"
        val row = dao.getCustomProducts().firstOrNull { it.id == id && !it.deleted }
            ?: return@withTransaction "Товар не найден"
        val now = maxOf(System.currentTimeMillis(), row.updatedAt + 1)
        dao.upsertCustomProduct(row.copy(name = name, updatedAt = now))
        // Keep the planned shopping list label consistent with the catalog.
        dao.getItems().filter { it.customProductId == id }.forEach { item ->
            dao.updateItem(item.copy(name = name, updatedAt = now))
        }
        null
    }

    /** Estimated price is catalog metadata, never a purchase event. */
    suspend fun updateCustomEstimatedPrice(id: String, priceKopecks: Long?): String? =
        db.withTransaction {
            if (priceKopecks != null && priceKopecks < 0) return@withTransaction "Некорректная цена"
            val product = dao.getCustomProducts().firstOrNull { it.id == id && !it.deleted }
                ?: return@withTransaction "Товар не найден"
            dao.upsertCustomProduct(product.copy(
                estimatedPriceKopecks = priceKopecks,
                updatedAt = maxOf(System.currentTimeMillis(), product.updatedAt + 1),
            ))
            null
        }

    /**
     * Edit a custom product in one Room transaction so name and estimated price
     * are saved atomically and synced using the same updatedAt revision.
     */
    suspend fun updateCustomProductDetails(
        id: String, rawName: String, estimatedPriceKopecks: Long?, isWeighted: Boolean,
    ): String? = db.withTransaction {
        val name = normalizeName(rawName)
        if (name.isEmpty()) return@withTransaction "Введите название"
        if (name.length > MAX_CUSTOM_PRODUCT_NAME_LENGTH)
            return@withTransaction "Максимум $MAX_CUSTOM_PRODUCT_NAME_LENGTH символов"
        if (estimatedPriceKopecks != null && estimatedPriceKopecks < 0)
            return@withTransaction "Некорректная цена"
        val row = dao.getCustomProducts().firstOrNull { it.id == id && !it.deleted }
            ?: return@withTransaction "Товар не найден"
        if (row.name == name && row.estimatedPriceKopecks == estimatedPriceKopecks && row.isWeighted == isWeighted)
            return@withTransaction null
        val now = maxOf(System.currentTimeMillis(), row.updatedAt + 1)
        dao.upsertCustomProduct(row.copy(name = name,
            estimatedPriceKopecks = estimatedPriceKopecks, isWeighted = isWeighted, updatedAt = now))
        if (row.isWeighted != isWeighted) {
            dao.getItems().filter { it.customProductId == id && !it.deleted }.forEach { item ->
                dao.updateItem(item.copy(weightGrams = if (isWeighted) item.weightGrams ?: 1000 else null,
                    updatedAt = now))
            }
        }
        if (row.name != name) {
            dao.getItems().filter { it.customProductId == id && !it.deleted }.forEach { item ->
                dao.updateItem(item.copy(name = name, updatedAt = now))
            }
        }
        null
    }

    suspend fun deleteCustomProduct(id: String): Boolean = db.withTransaction {
        val row = dao.getCustomProducts().firstOrNull { it.id == id && !it.deleted }
            ?: return@withTransaction false
        dao.upsertCustomProduct(row.copy(deleted = true,
            updatedAt = maxOf(System.currentTimeMillis(), row.updatedAt + 1)))
        true
    }

    suspend fun changeCustomBarcode(id: String, previous: String?, rawCode: String?): String? = db.withTransaction {
        val product = dao.getCustomProducts().firstOrNull { it.id == id && !it.deleted }
            ?: return@withTransaction "Товар не найден"
        val code = rawCode?.trim().orEmpty()
        if (code.isNotEmpty() && !validProductBarcode(code))
            return@withTransaction "Некорректный штрихкод"
        val rows = dao.getCustomProductBarcodes()
        val old = previous?.let { key -> rows.firstOrNull {
            it.productId == id && it.code == key && !it.deleted
        } }
        if (previous != null && old == null) return@withTransaction "Штрихкод не найден"
        if (previous == code) return@withTransaction null
        if (code.isNotEmpty()) {
            val used = rows.any { !it.deleted && it.code == code && it.productId != id }
            // Cross-source duplicates are allowed and become an alias for one
            // shared product. Within one catalog the same code must be unique.
            if (used) return@withTransaction "Штрихкод уже занят другим своим товаром"
            if (rows.any { !it.deleted && it.code == code && it.productId == id })
                return@withTransaction "Штрихкод уже добавлен"
        }
        val now = System.currentTimeMillis()
        if (old != null) dao.upsertCustomProductBarcode(old.copy(
            updatedAt = maxOf(now, old.updatedAt + 1), deleted = true))
        if (code.isNotEmpty()) {
            val earlier = rows.firstOrNull { it.productId == id && it.code == code }
            dao.upsertCustomProductBarcode(CustomProductBarcode(id, code,
                maxOf(now, (earlier?.updatedAt ?: 0) + 1)))
        }
        // Legacy barcode stays immutable for old synced devices, except when
        // it is explicitly removed: the alias tombstone wins for v10 peers.
        if (code.isNotEmpty() && product.barcode.isBlank())
            dao.upsertCustomProduct(product.copy(barcode = code,
                updatedAt = maxOf(now, product.updatedAt + 1)))
        null
    }


    /**
     * A product with the same barcode from Lenta is reused rather than copied.
     * Ambiguous barcode matches are not automatically merged.
     */
    suspend fun addManualProduct(
        rawName: String,
        rawBarcode: String = "",
        estimatedPriceKopecks: Long? = null,
        isWeighted: Boolean = false,
    ): Boolean {
        if (estimatedPriceKopecks != null && estimatedPriceKopecks < 0) return false
        val name = normalizeName(rawName).take(MAX_CUSTOM_PRODUCT_NAME_LENGTH)
        val code = rawBarcode.trim()
        if (name.isEmpty() || (code.isNotEmpty() && !validProductBarcode(code))) return false
        if (code.isNotEmpty()) {
            val lentaIds = dao.getProductBarcodes().asSequence()
                .filter { !it.deleted && it.code == code }.map { it.productId }.toSet()
            val matchedLenta = dao.getLentaProducts().filter { it.id in lentaIds || it.ean == code }
            val customIds = dao.getCustomProductBarcodes().filter { !it.deleted && it.code == code }
                .mapTo(HashSet()) { it.productId }
            val matchedCustom = dao.getCustomProducts().filter { !it.deleted &&
                (it.id in customIds || (it.barcode == code && dao.getCustomProductBarcodes()
                    .none { alias -> alias.productId == it.id && alias.code == code && alias.deleted })) }
            if (matchedCustom.size == 1) {
                val saved = matchedCustom.single()
                if ((estimatedPriceKopecks != null && estimatedPriceKopecks != saved.estimatedPriceKopecks) ||
                    (isWeighted && !saved.isWeighted))
                    dao.upsertCustomProduct(saved.copy(
                        estimatedPriceKopecks = estimatedPriceKopecks ?: saved.estimatedPriceKopecks,
                        isWeighted = saved.isWeighted || isWeighted,
                        updatedAt = maxOf(System.currentTimeMillis(), saved.updatedAt + 1)))
                return addCustomToList(if (isWeighted && !saved.isWeighted)
                    saved.copy(isWeighted = true) else saved)
            }
            if (matchedCustom.size > 1 || matchedLenta.size > 1) return false
            // Without a user-specific price reuse the known Lenta product.
            // With one, create a personal alias so the estimate has a durable home;
            // its barcode still resolves to the same shared product identity.
            if (matchedLenta.size == 1 && estimatedPriceKopecks == null && !isWeighted)
                return addProduct(matchedLenta.single().toProduct())
        }
        val existing = dao.getCustomProducts().filter { !it.deleted && nameKeyOf(it.name) == nameKeyOf(name) }
        if (existing.size == 1) {
            val saved = existing.single()
            if (code.isNotEmpty() && saved.barcode.isNotEmpty() && saved.barcode != code) return false
            if (code.isNotEmpty() && saved.barcode.isEmpty()) {
                val updated = saved.copy(barcode = code, updatedAt = System.currentTimeMillis(),
                    estimatedPriceKopecks = estimatedPriceKopecks ?: saved.estimatedPriceKopecks,
                    isWeighted = saved.isWeighted || isWeighted)
                dao.upsertCustomProduct(updated)
                dao.upsertCustomProductBarcode(CustomProductBarcode(updated.id, code, updated.updatedAt))
                fetchPreview(updated.id, code)
                return addCustomToList(updated)
            }
            if ((estimatedPriceKopecks != null && estimatedPriceKopecks != saved.estimatedPriceKopecks) ||
                (isWeighted && !saved.isWeighted)) {
                dao.upsertCustomProduct(saved.copy(
                    estimatedPriceKopecks = estimatedPriceKopecks ?: saved.estimatedPriceKopecks,
                    isWeighted = saved.isWeighted || isWeighted,
                    updatedAt = maxOf(System.currentTimeMillis(), saved.updatedAt + 1)))
            }
            return addCustomToList(if (isWeighted && !saved.isWeighted)
                saved.copy(isWeighted = true) else saved)
        }
        val product = CustomProduct(UUID.randomUUID().toString(), name, code,
            System.currentTimeMillis(), estimatedPriceKopecks = estimatedPriceKopecks,
            isWeighted = isWeighted)
        return db.withTransaction {
            dao.upsertCustomProduct(product)
            if (code.isNotEmpty()) dao.upsertCustomProductBarcode(CustomProductBarcode(product.id, code, product.updatedAt))
            addCustomToListInTransaction(product)
        }.also {
            if (it) { refreshWidget(); fetchPreview(product.id, code) }
        }
    }

    /** Find an active item by shared product identity, regardless of its source catalog. */
    private suspend fun findActiveIdentity(key: String): ShoppingItem? {
        val identities = sharedCatalogIdentities(
            dao.getLentaProducts().map { it.toProduct() }, dao.getCustomProducts(),
            dao.getProductBarcodes(), dao.getCustomProductBarcodes())
        val wanted = identities[key] ?: key
        return dao.getItems().firstOrNull { item ->
            val itemKey = when {
                item.customProductId.isNotBlank() -> customKey(item.customProductId)
                item.productId > 0 -> "lenta:${item.productId}"
                else -> item.nameKey
            }
            (identities[itemKey] ?: itemKey) == wanted
        }
    }

    private suspend fun hasActiveIdentity(key: String): Boolean =
        findActiveIdentity(key) != null

    suspend fun addCustomToList(product: CustomProduct, quantity: Int = 1): Boolean {
        if (product.deleted) return false
        val added = db.withTransaction { addCustomToListInTransaction(product, quantity) }
        if (added) refreshWidget()
        return added
    }

    private suspend fun addCustomToListInTransaction(product: CustomProduct, quantity: Int = 1): Boolean {
        archiveFinishedTrip()
        if (hasActiveIdentity(customKey(product.id))) return false
        val key = customKey(product.id)
        var existing = dao.findByKey(key)
        if (existing?.deleted == true) {
            archiveFinishedTrip(allowRemaining = true)
            existing = dao.findByKey(key)
        }
        val now = System.currentTimeMillis()
        when {
            existing == null -> dao.insertItem(ShoppingItem(name = product.name, nameKey = key,
                customProductId = product.id, createdAt = now, authorId = profile.id,
                quantity = quantity.coerceIn(1, 999),
                weightGrams = if (product.isWeighted) 1000 else null))
            existing.deleted -> {
                dao.revive(existing.id, product.name, now, profile.id, quantity.coerceIn(1, 999))
                if (product.isWeighted) dao.setWeight(existing.id, 1000, now, profile.id)
            }
            else -> return false
        }
        return true
    }

    fun observeAllItems(): Flow<List<ShoppingItem>> = dao.observeAllItems()

    suspend fun getItems(): List<ShoppingItem> = dao.getItems()
    suspend fun actionItem(id: String): ShoppingItem? = dao.findById(id)
    suspend fun actionItemByKey(key: String): ShoppingItem? = dao.findByKey(key)

    /** Сколько товаров куплено в текущем списке — для полосы прогресса. */
    fun observeBoughtCount(): Flow<Int> = dao.observeBoughtCount()

    fun observeUsers(): Flow<List<AppUser>> = dao.observeUsers()

    /** Идентификатор текущего пользователя; пустая строка до регистрации. */
    val currentUserId: String get() = profile.id

    /**
     * Регистрация и смена имени. На первой регистрации привязываются локальные
     * товары без автора. Архивные покупки без подтверждённого автора не переписываются.
     */
    suspend fun saveProfile(rawName: String) {
        val name = normalizeName(rawName).take(MAX_NAME_LENGTH)
        if (name.isEmpty()) return
        val firstRegistration = profile.id.isEmpty()
        val saved = profile.save(name)
        db.withTransaction {
            dao.upsertUser(AppUser(saved.id, saved.name, System.currentTimeMillis()))
            // Старые локальные товары привязываем только при ПЕРВОЙ регистрации.
            // При смене имени нельзя присваивать себе чужие записи без автора.
            if (firstRegistration) claimLegacyData(saved.id)
        }
    }

    /**
     * На старте нельзя назначать текущего пользователя автором чужих записей,
     * которые пришли через синхронизацию без информации о покупателе.
     * Первичная привязка локальных данных выполняется только в saveProfile().
     */
    suspend fun claimLegacyData() = Unit

    private suspend fun claimLegacyData(me: String) {
        for (item in dao.getAllItems()) {
            val event = reassignPurchaseEvent(item.purchaseEvent, "", me)
            if (event != item.purchaseEvent) dao.updateItem(item.copy(purchaseEvent = event))
        }
        dao.claimItems(me)
        // Не назначаем автора архивным событиям с неизвестным покупателем:
        // импортированная запись без автора должна остаться неизвестной,
        // иначе вся история чужого телефона становится покупками владельца.

    }

    suspend fun addProduct(product: LentaProduct, quantity: Int = 1): Boolean {
        if (product.id <= 0) return false
        val name = normalizeName(product.name)
        if (name.isEmpty()) return false
        val key = "lenta:${product.id}"
        val now = System.currentTimeMillis()
        val me = profile.id
        val added = db.withTransaction {
            // Все товары куплены: прошлый список уходит в архив, счётчик купленного обнуляется.
            archiveFinishedTrip()
            if (hasActiveIdentity(key)) return@withTransaction false
            var existing = dao.findByKey(key)
            if (existing?.deleted == true) {
                // Не теряем событие покупки при повторном добавлении до завершения похода.
                archiveFinishedTrip(allowRemaining = true)
                existing = dao.findByKey(key)
            }
            when {
                existing == null -> dao.insertItem(ShoppingItem(name = name, nameKey = key, productId = product.id, createdAt = now, authorId = me, quantity = quantity.coerceIn(1, 999)))
                existing.deleted -> dao.revive(existing.id, name, now, me, quantity.coerceIn(1, 999))
                else -> return@withTransaction false
            }
            true
        }
        if (added) refreshWidget()
        return added
    }

    suspend fun buyScannedCustom(product: CustomProduct, priceOverride: Long? = null,
        storeName: String = "", weightGrams: Int? = null): ScannedPurchaseResult? {
        if (product.isWeighted && (weightGrams == null || weightGrams !in 1..999000)) return null
        if (product.deleted) return null
        val normalizedStore = normalizeName(storeName).take(100)
        val result = db.withTransaction {
            val now = System.currentTimeMillis()
            val storeId = if (normalizedStore.isBlank()) "" else {
                val store = dao.getStores().firstOrNull { !it.deleted && it.nameKey == nameKeyOf(normalizedStore) }
                    ?: ShoppingStore(stableStoreId(normalizedStore), normalizedStore, updatedAt = now)
                        .also { dao.upsertStore(it) }
                store.id
            }
            attachBoughtProductToStore(storeId, customKey(product.id), now)
            val planned = findActiveIdentity(customKey(product.id))
            if (planned != null) {
                val weighed = if (product.isWeighted) planned.copy(weightGrams = weightGrams) else planned
                dao.updateItem(weighed.copy(deleted = true, updatedAt = now, authorId = profile.id,
                    purchaseEvent = purchaseEvent(weighed, null, now, profile.id, priceOverride,
                        storeId = storeId)))
                return@withTransaction ScannedPurchaseResult(weighed, false)
            }
            val base = ShoppingItem(name = product.name, customProductId = product.id,
                createdAt = now, updatedAt = now, authorId = profile.id, deleted = true,
                weightGrams = weightGrams)
            val event = purchaseEvent(base, null, now, profile.id, priceOverride,
                impulsive = true, storeId = storeId)
            val eventId = checkNotNull(decodePurchaseEvent(event)).id
            val bought = base.copy(nameKey = "impulse:$eventId", purchaseEvent = event)
                .also { dao.insertItem(it) }
            ScannedPurchaseResult(bought, true)
        }
        refreshWidget()
        return result
    }

    private suspend fun ensureLentaStore(now: Long) {
        if (dao.getStores().none { it.id == lentaStoreId() && !it.deleted })
            dao.upsertStore(ShoppingStore(lentaStoreId(), "Лента", updatedAt = now))
    }

    suspend fun buyScanned(product: LentaProduct, priceOverride: Long? = null): ScannedPurchaseResult? {
        val name = normalizeName(product.name)
        if (product.id <= 0 || name.isEmpty()) return null
        val item = db.withTransaction {
            val now = System.currentTimeMillis()
            ensureLentaStore(now)
            val address = context.lentaCatalog.address.value
            val current = dao.getLentaProducts().firstOrNull { it.id == product.id }?.toProduct()?.takeIf {
                address.isNotBlank() && it.address == address && it.channel == "locc"
            }
            val effectivePrice = priceOverride ?: latestStorePrice("lenta:${product.id}", lentaStoreId())
            attachBoughtProductToStore(lentaStoreId(), "lenta:${product.id}", now)
            val planned = findActiveIdentity("lenta:${product.id}")
            if (planned != null) {
                dao.updateItem(planned.copy(deleted = true, updatedAt = now, authorId = profile.id,
                    purchaseEvent = purchaseEvent(planned, current, now, profile.id, effectivePrice,
                        storeId = lentaStoreId())))
                return@withTransaction ScannedPurchaseResult(planned, impulsive = false)
            }
            val base = ShoppingItem(name = name, productId = product.id, createdAt = now, updatedAt = now,
                authorId = profile.id, deleted = true)
            val event = purchaseEvent(base, current, now, profile.id, effectivePrice,
                impulsive = true, storeId = lentaStoreId())
            val eventId = checkNotNull(decodePurchaseEvent(event)).id
            val bought = base.copy(nameKey = "impulse:$eventId", purchaseEvent = event).also { dao.insertItem(it) }
            ScannedPurchaseResult(bought, impulsive = true)
        }
        refreshWidget()
        return item
    }

    /** Товар куплен — убираем из списка (в историю он уже записан при добавлении). */
    suspend fun buy(id: String, priceOverride: Long? = null, impulsive: Boolean = false,
        storeName: String = "", weightGrams: Int? = null) {
        if (weightGrams != null) require(weightGrams in 1..999000)
        db.withTransaction {
            val item = dao.findById(id) ?: return@withTransaction
            if (item.deleted) return@withTransaction
            val now = System.currentTimeMillis()
            val catalog = context.lentaCatalog
            val product = dao.getLentaProducts().firstOrNull { it.id == item.productId }?.toProduct()?.takeIf {
                catalog.address.value.isNotBlank() && it.address == catalog.address.value && it.channel == "locc"
            }
            val normalizedStore = if (item.productId > 0) "Лента" else normalizeName(storeName).take(100)
            val storeId = if (item.productId > 0) {
                ensureLentaStore(now)
                lentaStoreId()
            } else if (normalizedStore.isEmpty()) "" else {
                val store = dao.getStores().firstOrNull { !it.deleted && it.nameKey == nameKeyOf(normalizedStore) }
                    ?: ShoppingStore(stableStoreId(normalizedStore), normalizedStore, updatedAt = now)
                        .also { dao.upsertStore(it) }
                store.id
            }
            val productKey = when {
                item.customProductId.isNotBlank() -> customKey(item.customProductId)
                item.productId > 0 -> "lenta:${item.productId}"
                else -> ""
            }
            val effectivePrice = priceOverride ?: if (item.productId > 0) latestStorePrice(productKey, storeId) else null
            attachBoughtProductToStore(storeId, productKey, now)
            val boughtItem = if (weightGrams != null) item.copy(weightGrams = weightGrams) else item
            dao.updateItem(boughtItem.copy(deleted = true, updatedAt = now, authorId = profile.id,
                purchaseEvent = purchaseEvent(boughtItem, product,
                    now, profile.id, effectivePrice, impulsive, storeId)))
        }
        refreshWidget()
    }

    /**
     * Correct an existing purchase in-place without changing event ID or boughtAt.
     * The revision timestamp makes the corrected version win when QR snapshots merge.
     */
    suspend fun amendPurchase(eventId: String, unitPrice: Long?, storeName: String): String? {
        if (unitPrice != null && unitPrice < 0) return "Неверная цена"
        val normalizedStore = normalizeName(storeName).take(100)
        return db.withTransaction {
            val now = System.currentTimeMillis()
            val storeId = if (normalizedStore.isEmpty()) "" else {
                val store = dao.getStores().firstOrNull { !it.deleted &&
                    it.nameKey == nameKeyOf(normalizedStore) }
                    ?: ShoppingStore(stableStoreId(normalizedStore), normalizedStore, updatedAt = now)
                        .also { dao.upsertStore(it) }
                store.id
            }
            val bought = dao.getBought().firstOrNull {
                decodePurchaseEvent(it.purchaseEvent)?.id == eventId
            }
            if (bought != null) {
                val amended = amendPurchaseEvent(bought.purchaseEvent, unitPrice, storeId, now)
                    ?: return@withTransaction "Событие покупки повреждено"
                dao.updateItem(bought.copy(purchaseEvent = amended, updatedAt = now))
                decodePurchaseEvent(amended)?.let { event ->
                    attachBoughtProductToStore(storeId, when {
                        event.customProductId.isNotBlank() -> customKey(event.customProductId)
                        event.productId != null -> "lenta:${event.productId}"
                        else -> ""
                    }, now)
                }
                return@withTransaction null
            }
            val archive = dao.getArchives().firstOrNull { list ->
                list.events.any { decodePurchaseEvent(it)?.id == eventId }
            } ?: return@withTransaction "Покупка не найдена"
            val updatedEvents = archive.events.map { encoded ->
                if (decodePurchaseEvent(encoded)?.id != eventId) encoded
                else amendPurchaseEvent(encoded, unitPrice, storeId, now) ?: encoded
            }
            dao.updateArchived(archive.copy(purchaseEvents = updatedEvents.joinToString("\n")))
            updatedEvents.firstNotNullOfOrNull { encoded ->
                decodePurchaseEvent(encoded)?.takeIf { it.id == eventId }
            }?.let { event ->
                attachBoughtProductToStore(storeId, when {
                    event.customProductId.isNotBlank() -> customKey(event.customProductId)
                    event.productId != null -> "lenta:${event.productId}"
                    else -> ""
                }, now)
            }
            null
        }
    }

    suspend fun setQuantity(id: String, quantity: Int) {
        dao.setQuantity(id, quantity.coerceIn(1, 999), System.currentTimeMillis(), profile.id)
        refreshWidget()
    }

    suspend fun setWeight(id: String, grams: Int?) {
        require(grams == null || grams in 1..999000)
        dao.setWeight(id, grams, System.currentTimeMillis(), profile.id)
        refreshWidget()
    }

    /** Удаление из списка без покупки: строка стирается, в историю подсказок товар остаётся. */
    suspend fun remove(id: String): Pair<ShoppingItem, Long>? {
        val removed = db.withTransaction {
            val item = dao.findById(id)?.takeUnless { it.deleted } ?: return@withTransaction null
            val at = maxOf(System.currentTimeMillis(), item.updatedAt + 1)
            dao.deleteById(id)
            dao.upsertTombstone(tomb(TombKind.ITEM, item.nameKey, at))
            item to at
        }
        if (removed != null) refreshWidget()
        return removed
    }

    fun observeUndoTombstones() = dao.observeTombstones()

    /** Read-only eligibility check; the same guard is used within the undo transaction. */
    suspend fun canUndoListAction(before: ShoppingItem?, after: ShoppingItem?, removedAt: Long?): Boolean =
        db.withTransaction { matchesUndoRevision(before, after, removedAt) }

    private suspend fun matchesUndoRevision(before: ShoppingItem?, after: ShoppingItem?, removedAt: Long?): Boolean {
        val snapshot = before ?: after ?: return false
        val current = dao.findById(snapshot.id)
        val marker = dao.getTombstones().firstOrNull { it.kind == TombKind.ITEM.code && it.target == snapshot.nameKey }
        if (after != null) {
            if (current != after || (marker != null && marker.deletedAt >= after.updatedAt)) return false
        } else if (before == null || current != null || removedAt == null || marker?.deletedAt != removedAt) return false
        if (before != null) {
            val existing = dao.findByKey(before.nameKey)
            if (existing != null && existing.id != before.id) return false
            if (current == null && hasActiveIdentity(before.nameKey)) return false
        }
        return true
    }

    /** Exact revision guard prevents undo from changing a newer or archived purchase. */
    suspend fun undoListAction(before: ShoppingItem?, after: ShoppingItem?, removedAt: Long?): Boolean {
        val snapshot = before ?: after ?: return false
        val changed = db.withTransaction {
            if (!matchesUndoRevision(before, after, removedAt)) return@withTransaction false
            val current = dao.findById(snapshot.id)
            val marker = dao.getTombstones().firstOrNull { it.kind == TombKind.ITEM.code && it.target == snapshot.nameKey }
            val now = maxOf(System.currentTimeMillis(), snapshot.updatedAt + 1,
                (after?.updatedAt ?: 0L) + 1, (marker?.deletedAt ?: 0L) + 1)
            if (before == null) {
                dao.deleteById(snapshot.id)
                dao.upsertTombstone(tomb(TombKind.ITEM, snapshot.nameKey, now))
            } else {
                val restored = before.copy(updatedAt = now, authorId = profile.id)
                if (current == null) dao.insertItem(restored) else dao.updateItem(restored)
            }
            true
        }
        if (changed) refreshWidget()
        return changed
    }

    /** Отмена покупки или удаления: товар возвращается на прежнее место. */
    suspend fun restore(item: ShoppingItem) {
        val now = System.currentTimeMillis()
        val me = profile.id
        db.withTransaction {
            if (decodePurchaseEvent(item.purchaseEvent)?.impulsive == true) {
                dao.deleteById(item.id)
                dao.upsertTombstone(tomb(TombKind.ITEM, item.nameKey, now))
                return@withTransaction
            }
            // Строка могла исчезнуть, если после покупки начался новый список, — тогда вставляем заново.
            if (dao.markRestored(item.id, now, me) == 0 && dao.findByKey(item.nameKey) == null) {
                dao.insertItem(item.copy(deleted = false, updatedAt = now, authorId = me, purchaseEvent = ""))
            }
        }
        refreshWidget()
    }

    /**
     * Если в списке не осталось непокупленного, а купленное есть, — переносит его в архив с датой
     * и обнуляет прогресс. Ничего не делает, пока в списке есть что покупать.
     */
    suspend fun finishTripIfDone() {
        db.withTransaction { archiveFinishedTrip() }
    }

    /** Завершает поход с купленными товарами; непокупленные остаются в следующем списке. */
    suspend fun finishTrip() {
        val finished = db.withTransaction { archiveFinishedTrip(allowRemaining = true) }
        if (finished) refreshWidget()
    }

    fun observeArchive(): Flow<List<ArchivedList>> = dao.observeArchive()

    fun observeBought(): Flow<List<ShoppingItem>> = dao.observeBought()

    suspend fun deleteArchived(id: Long) {
        db.withTransaction {
            val uid = dao.archiveUid(id) ?: return@withTransaction
            // Помечаем удаление раньше физического удаления строки. Не допускаем
            // повторного появления архивного списка от отставшего устройства.
            dao.upsertTombstone(tomb(TombKind.ARCHIVE, uid, System.currentTimeMillis()))
            dao.deleteArchived(id)
        }
    }

    private fun tomb(kind: TombKind, key: String, at: Long) = Tombstone(kind.code, key, at)

    suspend fun repeatItems(names: List<String>, quantities: List<Int>, events: List<String> = emptyList()) {
        val products = dao.getLentaProducts().associateBy { it.id }
        val custom = dao.getCustomProducts().associateBy { it.id }
        names.forEachIndexed { index, name ->
            val event = decodePurchaseEvent(events.getOrElse(index) { "" })
            val id = event?.productId
            val product = id?.let(products::get)
            if (event?.customProductId?.let(custom::get)?.takeUnless { it.deleted } != null) {
                if (addCustomToList(custom.getValue(event.customProductId),
                        quantities.getOrElse(index) { 1 }) && event.weightGrams != null)
                    dao.findByKey(customKey(event.customProductId))?.let { setWeight(it.id, event.weightGrams) }
            } else if (product != null) {
                if (addProduct(product.toProduct(), quantities.getOrElse(index) { 1 }) && event.weightGrams != null)
                    dao.findByKey("lenta:$id")?.let { setWeight(it.id, event.weightGrams) }
            }
            else if (id != null) {
                val restored = LentaProduct(id, name, "", "", "", "", "", "", channel = "locc")
                context.lentaCatalog.save(restored)
                if (addProduct(restored, quantities.getOrElse(index) { 1 }) && event.weightGrams != null)
                    dao.findByKey("lenta:$id")?.let { setWeight(it.id, event.weightGrams) }
            }
        }
    }

    /** Вызывать внутри транзакции. @return true, если список перенесён в архив. */
    private suspend fun archiveFinishedTrip(allowRemaining: Boolean = false): Boolean {
        if (!allowRemaining && dao.countActive() != 0) return false
        val bought = dao.getBought()
        if (bought.isEmpty()) return false
        // Время похода фиксируется один раз — в момент завершения списка.
        val trip = computeTripTime(bought.map { it.updatedAt })
        val lastPurchaseAt = bought.maxOf { it.updatedAt }
        val completedAt = if (allowRemaining) System.currentTimeMillis() else lastPurchaseAt
        val uid = archiveUidOf(lastPurchaseAt, bought.map { it.nameKey })
        // Тот же список мог уже прийти от второго телефона: заводить вторую запись не нужно.
        if (dao.archiveIdByUid(uid) == null) {
            val address = context.lentaCatalog.address.value
            val products = dao.getLentaProducts().associateBy { it.id }
            val remaining = if (allowRemaining) dao.getItems() else emptyList()
            val remainingSnapshots = remaining.map { item ->
                val product = products[item.productId]?.toProduct()?.takeIf {
                    address.isNotBlank() && it.address == address && it.channel == "locc"
                }
                purchaseEvent(item, product, System.currentTimeMillis(), "")
            }
            dao.insertArchived(
                ArchivedList(
                    completedAt = completedAt,
                    itemCount = bought.size,
                    items = bought.joinToString("\n") { it.name },
                    tripMillis = trip.millis,
                    visits = trip.visits,
                    uid = uid,
                    buyers = bought.joinToString("\n") { it.authorId },
                    quantities = bought.joinToString("\n") { it.quantity.toString() },
                    purchaseEvents = bought.joinToString("\n") { it.purchaseEvent },
                    remainingEvents = "v1|" + remainingSnapshots.joinToString("\n"),
                ),
            )
        }
        // Купленное стирается из списка; надгробие не даёт второму телефону вернуть его при обмене.
        dao.upsertTombstones(bought.map { tomb(TombKind.ITEM, it.nameKey, completedAt) })
        dao.purgeBought()
        return true
    }

    // --- Обмен между телефонами (QR) ---

    /** Всё, чем делимся со вторым телефоном, одним согласованным снимком. */
    suspend fun exportSnapshot(): Snapshot = db.withTransaction { buildSnapshot() }

    private suspend fun buildSnapshot(): Snapshot {
        return Snapshot(
            products = dao.getLentaProducts(),
            productBarcodes = dao.getProductBarcodes(),
            customProducts = dao.getCustomProducts(),
            stores = dao.getStores(),
            customProductBarcodes = dao.getCustomProductBarcodes(),
            storeProducts = dao.getStoreProducts(),
            users = dao.getUsers().map { SyncUser(it.id, it.name, it.updatedAt) },
            items = dao.getAllItems().map { SyncItem(it.name, it.createdAt, it.updatedAt, it.deleted, it.authorId, it.quantity, it.purchaseEvent, it.productId, it.weightGrams, it.customProductId) },
            archives = dao.getArchives().map { list ->
                SyncArchive(
                    uid = list.uid,
                    completedAt = list.completedAt,
                    names = list.names,
                    buyers = list.buyerIds,
                    tripMillis = list.tripMillis,
                    visits = list.visits,
                    quantities = list.counts,
                    purchaseEvents = list.events,
                    remainingEvents = list.remainingEvents,
                )
            },
            tombs = dao.getTombstones().mapNotNull { row ->
                TombKind.fromCode(row.kind)?.let { SyncTomb(it, row.target, row.deletedAt) }
            },
        )
    }

    /**
     * Сливает данные второго телефона с локальными и записывает результат. Слияние делается над свежим снимком
     * внутри транзакции, поэтому правки, сделанные пока шёл обмен, не теряются.
     */
    suspend fun applyRemote(remote: Snapshot): SyncSummary {
        val summary = db.withTransaction {
            val local = buildSnapshot()
            val merged = SyncMerge.merge(local, remote)
            write(merged)
            summarize(local, merged)
        }
        // НЕ завершаем поход автоматически во время сетевого обмена.
        // Второе устройство может ещё передавать незавершённые позиции;
        // преждевременная архивация создаёт надгробия и стирает текущий список.
        refreshWidget()
        return summary
    }

    // --- Откат обмена ---

    private val undoFile: File get() = File(context.filesDir, "undo-sync.bin")

    /** Запоминает состояние до обмена. Вызывается один раз перед первым применением чужих данных. */
    suspend fun saveUndoPoint() {
        val bytes = SyncCodec.encode(exportSnapshot())
        withContext(Dispatchers.IO) { undoFile.writeBytes(bytes) }
    }

    /** Сколько минут назад сделана точка отката; null — отменять нечего или время вышло. */
    fun undoAvailableMinutes(): Int? {
        val file = undoFile
        if (!file.exists()) return null
        val age = System.currentTimeMillis() - file.lastModified()
        if (age > UNDO_WINDOW_MILLIS || age < 0) {
            file.delete()
            return null
        }
        return (age / 60_000).toInt()
    }

    /**
     * Возвращает данные, какими они были до последнего обмена (или восстановления копии). Всё, что изменено после
     * обмена, пропадёт. @return false, если точки отката нет или она устарела.
     */
    suspend fun undoLastSync(): Boolean {
        if (undoAvailableMinutes() == null) return false
        val snapshot = withContext(Dispatchers.IO) {
            try {
                SyncCodec.decode(undoFile.readBytes())
            } catch (e: SyncFormatException) {
                null
            }
        } ?: return false
        db.withTransaction { write(snapshot) }
        withContext(Dispatchers.IO) { undoFile.delete() }
        finishTripIfDone()
        refreshWidget()
        return true
    }

    // --- Резервная копия ---

    /** Все данные одним файлом: владелец и снимок, тот же, что идёт по QR. */
    suspend fun exportBackup(): ByteArray {
        val me = profile.profile.value
        return BackupCodec.encode(BackupContents(me?.id.orEmpty(), me?.name.orEmpty(), exportSnapshot()))
    }

    /**
     * Сливает копию с текущими данными. [asMe] — копия сделана этим же человеком на другом телефоне: тогда прежний
     * идентификатор становится моим, и покупки из копии остаются «моими», а не чужого пользователя с тем же именем.
     */
    suspend fun restoreBackup(contents: BackupContents, asMe: Boolean): SyncSummary {
        if (asMe && contents.ownerId.isNotEmpty() && contents.ownerId != profile.id) {
            adoptIdentity(contents.ownerId, contents.ownerName)
        }
        return applyRemote(contents.snapshot)
    }

    private suspend fun adoptIdentity(oldId: String, name: String) {
        val temporary = profile.id
        profile.adopt(oldId, name)
        db.withTransaction {
            dao.deleteUser(temporary)
            dao.upsertUser(AppUser(oldId, name, System.currentTimeMillis()))
            dao.reassignItems(temporary, oldId)
            for (item in dao.getAllItems()) {
                val event = reassignPurchaseEvent(item.purchaseEvent, temporary, oldId)
                if (event != item.purchaseEvent) dao.updateItem(item.copy(purchaseEvent = event))
            }
            for (list in dao.getArchives()) {
                val replaced = list.buyerIds.map { if (it == temporary) oldId else it }
                val events = list.events.map { reassignPurchaseEvent(it, temporary, oldId) }
                if (temporary in list.buyerIds || events != list.events)
                    dao.updateArchived(list.copy(buyers = replaced.joinToString("\n"), purchaseEvents = events.joinToString("\n")))
            }
        }
    }

    private fun summarize(local: Snapshot, merged: Snapshot): SyncSummary {
        val localItems = local.items.associateBy { it.key }
        val localArchives = local.archives.mapTo(HashSet()) { it.uid }
        return SyncSummary(
            items = merged.items.count { localItems[it.key] != it } + local.items.count { old -> merged.items.none { it.key == old.key } },
            archives = merged.archives.count { it.uid !in localArchives },
        )
    }

    /** Вызывать внутри транзакции: приводит таблицы к слитому состоянию, сохраняя локальные id. */
    private suspend fun write(merged: Snapshot) {
        val cutoff = System.currentTimeMillis() - TOMBSTONE_TTL_MILLIS

        dao.clearProductBarcodes()
        dao.clearLentaProducts()
        dao.upsertLentaProducts(merged.products)
        dao.insertProductBarcodes(merged.productBarcodes)

        dao.clearCustomProducts()
        dao.upsertCustomProducts(merged.customProducts)
        dao.clearStores()
        dao.upsertStores(merged.stores)
        dao.clearCustomProductBarcodes()
        dao.upsertCustomProductBarcodes(merged.customProductBarcodes)
        dao.clearStoreProducts()
        dao.upsertStoreProducts(merged.storeProducts)

        dao.clearUsers()
        dao.insertUsers(merged.users.map { AppUser(it.id, it.name, it.at) })

        val existing = dao.getAllItems().associateBy { it.nameKey }
        val mergedKeys = HashSet<String>()
        for (item in merged.items) {
            mergedKeys += item.key
            val old = existing[item.key]
            val row = ShoppingItem(
                id = old?.id ?: UUID.randomUUID().toString(),
                name = item.name,
                nameKey = item.key,
                createdAt = item.createdAt,
                updatedAt = item.at,
                deleted = item.bought,
                authorId = item.author,
                quantity = item.quantity,
                purchaseEvent = item.purchaseEvent,
                productId = item.productId,
                weightGrams = item.weightGrams,
                customProductId = item.customProductId,
            )
            when {
                old == null -> dao.insertItem(row)
                old != row -> dao.updateItem(row)
            }
        }
        existing.values.filter { it.nameKey !in mergedKeys }.map { it.id }.chunked(400).forEach { dao.deleteItemsByIds(it) }

        val localArchives = dao.getArchives().associateBy { it.uid }
        val mergedUids = HashSet<String>()
        for (archive in merged.archives) {
            mergedUids += archive.uid
            val old = localArchives[archive.uid]
            val row = ArchivedList(
                id = old?.id ?: 0,
                completedAt = archive.completedAt,
                itemCount = archive.names.size,
                items = archive.names.joinToString("\n"),
                tripMillis = archive.tripMillis,
                visits = archive.visits,
                uid = archive.uid,
                buyers = archive.buyers.joinToString("\n"),
                quantities = archive.quantities.joinToString("\n"),
                purchaseEvents = archive.purchaseEvents.joinToString("\n"),
                remainingEvents = archive.remainingEvents,
            )
            val id = when {
                old == null -> dao.insertArchived(row)
                else -> {
                    if (old != row) dao.updateArchived(row)
                    old.id
                }
            }
        }
        for (old in localArchives.values) {
            if (old.uid in mergedUids) continue
            dao.deleteArchived(old.id)
        }

        dao.clearTombstones()
        dao.upsertTombstones(
            // Удаления архивов хранятся бессрочно: иначе старый телефон
            // восстановит удалённый список спустя 400 дней.
            merged.tombs.filter { it.kind == TombKind.ARCHIVE || it.at >= cutoff }
                .map { Tombstone(it.kind.code, it.key, it.at) },
        )
    }

    private suspend fun refreshWidget() = ShoppingWidget().updateAll(context)

    private companion object {
        const val MAX_NAME_LENGTH = 30
        const val MAX_CUSTOM_PRODUCT_NAME_LENGTH = 200
    }
}
