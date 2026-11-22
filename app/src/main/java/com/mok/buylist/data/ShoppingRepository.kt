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
    fun observeItems(): Flow<List<ShoppingItem>> = dao.observeItems()
    fun observeCustomProducts(): Flow<List<CustomProduct>> = dao.observeCustomProducts()
    fun observeStores(): Flow<List<ShoppingStore>> = dao.observeStores()

    /**
     * A product with the same barcode from Lenta is reused rather than copied.
     * Ambiguous barcode matches are not automatically merged.
     */
    suspend fun addManualProduct(rawName: String, rawBarcode: String = ""): Boolean {
        val name = normalizeName(rawName).take(MAX_NAME_LENGTH)
        val code = rawBarcode.trim()
        if (name.isEmpty() || (code.isNotEmpty() && !validProductBarcode(code))) return false
        if (code.isNotEmpty()) {
            val lentaIds = dao.getProductBarcodes().asSequence()
                .filter { !it.deleted && it.code == code }.map { it.productId }.toSet()
            val matchedLenta = dao.getLentaProducts().filter { it.id in lentaIds || it.ean == code }
            val matchedCustom = dao.getCustomProducts().filter { !it.deleted && it.barcode == code }
            if (matchedLenta.size + matchedCustom.size == 1) {
                return matchedLenta.singleOrNull()?.let { addProduct(it.toProduct()) }
                    ?: addCustomToList(matchedCustom.single())
            }
            if (matchedLenta.isNotEmpty() || matchedCustom.isNotEmpty()) return false
        }
        val existing = dao.getCustomProducts().filter { !it.deleted && nameKeyOf(it.name) == nameKeyOf(name) }
        if (existing.size == 1) return addCustomToList(existing.single())
        val product = CustomProduct(UUID.randomUUID().toString(), name, code, System.currentTimeMillis())
        return db.withTransaction {
            dao.upsertCustomProduct(product)
            addCustomToListInTransaction(product)
        }.also { if (it) refreshWidget() }
    }

    suspend fun addCustomToList(product: CustomProduct): Boolean {
        if (product.deleted) return false
        val added = db.withTransaction { addCustomToListInTransaction(product) }
        if (added) refreshWidget()
        return added
    }

    private suspend fun addCustomToListInTransaction(product: CustomProduct): Boolean {
        archiveFinishedTrip()
        val key = customKey(product.id)
        var existing = dao.findByKey(key)
        if (existing?.deleted == true) {
            archiveFinishedTrip(allowRemaining = true)
            existing = dao.findByKey(key)
        }
        val now = System.currentTimeMillis()
        when {
            existing == null -> dao.insertItem(ShoppingItem(name = product.name, nameKey = key,
                customProductId = product.id, createdAt = now, authorId = profile.id))
            existing.deleted -> dao.revive(existing.id, product.name, now, profile.id, 1)
            else -> return false
        }
        return true
    }

    fun observeAllItems(): Flow<List<ShoppingItem>> = dao.observeAllItems()

    suspend fun getItems(): List<ShoppingItem> = dao.getItems()

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
        storeName: String = ""): ScannedPurchaseResult? {
        if (product.deleted) return null
        val normalizedStore = normalizeName(storeName).take(100)
        val result = db.withTransaction {
            val now = System.currentTimeMillis()
            val storeId = if (normalizedStore.isBlank()) "" else {
                val store = dao.getStores().firstOrNull { !it.deleted && it.nameKey == nameKeyOf(normalizedStore) }
                    ?: ShoppingStore(UUID.randomUUID().toString(), normalizedStore, updatedAt = now)
                        .also { dao.upsertStore(it) }
                store.id
            }
            val planned = dao.findByKey(customKey(product.id))?.takeUnless { it.deleted }
            if (planned != null) {
                dao.updateItem(planned.copy(deleted = true, updatedAt = now, authorId = profile.id,
                    purchaseEvent = purchaseEvent(planned, null, now, profile.id, priceOverride,
                        storeId = storeId)))
                return@withTransaction ScannedPurchaseResult(planned, false)
            }
            val base = ShoppingItem(name = product.name, customProductId = product.id,
                createdAt = now, updatedAt = now, authorId = profile.id, deleted = true)
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

    suspend fun buyScanned(product: LentaProduct, priceOverride: Long? = null): ScannedPurchaseResult? {
        val name = normalizeName(product.name)
        if (product.id <= 0 || name.isEmpty()) return null
        val item = db.withTransaction {
            val now = System.currentTimeMillis()
            val address = context.lentaCatalog.address.value
            val current = dao.getLentaProducts().firstOrNull { it.id == product.id }?.toProduct()?.takeIf {
                address.isNotBlank() && it.address == address && it.channel == "locc"
            }
            val planned = dao.getItems().firstOrNull { it.productId == product.id }
            if (planned != null) {
                dao.updateItem(planned.copy(deleted = true, updatedAt = now, authorId = profile.id,
                    purchaseEvent = purchaseEvent(planned, current, now, profile.id, priceOverride)))
                return@withTransaction ScannedPurchaseResult(planned, impulsive = false)
            }
            val base = ShoppingItem(name = name, productId = product.id, createdAt = now, updatedAt = now,
                authorId = profile.id, deleted = true)
            val event = purchaseEvent(base, current, now, profile.id, priceOverride, impulsive = true)
            val eventId = checkNotNull(decodePurchaseEvent(event)).id
            val bought = base.copy(nameKey = "impulse:$eventId", purchaseEvent = event).also { dao.insertItem(it) }
            ScannedPurchaseResult(bought, impulsive = true)
        }
        refreshWidget()
        return item
    }

    /** Товар куплен — убираем из списка (в историю он уже записан при добавлении). */
    suspend fun buy(id: String, priceOverride: Long? = null, impulsive: Boolean = false, storeName: String = "") {
        db.withTransaction {
            val item = dao.findById(id) ?: return@withTransaction
            if (item.deleted) return@withTransaction
            val now = System.currentTimeMillis()
            val catalog = context.lentaCatalog
            val product = dao.getLentaProducts().firstOrNull { it.id == item.productId }?.toProduct()?.takeIf {
                catalog.address.value.isNotBlank() && it.address == catalog.address.value && it.channel == "locc"
            }
            val normalizedStore = normalizeName(storeName).take(100)
            val storeId = if (normalizedStore.isEmpty()) "" else {
                val store = dao.getStores().firstOrNull { !it.deleted && it.nameKey == nameKeyOf(normalizedStore) }
                    ?: ShoppingStore(UUID.randomUUID().toString(), normalizedStore, updatedAt = now)
                        .also { dao.upsertStore(it) }
                store.id
            }
            dao.updateItem(item.copy(deleted = true, updatedAt = now, authorId = profile.id,
                purchaseEvent = purchaseEvent(item, if (storeId.isNotEmpty()) null else product,
                    now, profile.id, priceOverride, impulsive, storeId)))
        }
        refreshWidget()
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
    suspend fun remove(id: String) {
        db.withTransaction {
            val item = dao.findById(id)
            dao.deleteById(id)
            // Без надгробия второй телефон вернул бы товар при следующем обмене.
            if (item != null) dao.upsertTombstone(tomb(TombKind.ITEM, item.nameKey, System.currentTimeMillis()))
        }
        refreshWidget()
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
                addCustomToList(custom.getValue(event!!.customProductId))
            } else if (product != null) {
                if (addProduct(product.toProduct(), quantities.getOrElse(index) { 1 }) && event?.weightGrams != null)
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
    }
}
