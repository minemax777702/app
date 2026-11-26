package com.mok.buylist.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mok.buylist.data.AppUser
import com.mok.buylist.data.ArchivedList
import com.mok.buylist.data.Profile
import com.mok.buylist.data.ShoppingItem
import com.mok.buylist.data.CustomProductBarcode
import com.mok.buylist.data.CustomProduct
import com.mok.buylist.data.ShoppingStore
import com.mok.buylist.data.nameKeyOf
import com.mok.buylist.repository
import com.mok.buylist.userProfile
import com.mok.buylist.lentaCatalog
import com.mok.buylist.data.PricedLine
import com.mok.buylist.data.ShoppingTotals
import com.mok.buylist.data.calculateShoppingTotals
import com.mok.buylist.data.decodePurchaseEvent
import com.mok.buylist.data.parsePriceKopecks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

enum class RowState {
    ACTIVE,

    /** Только что куплен: строка ещё на месте, на ней можно отменить покупку. */
    BOUGHT,

    /** Только что удалён свайпом: строка ещё на месте, на ней можно вернуть товар. */
    REMOVED,
}

data class ListRow(val item: ShoppingItem, val state: RowState)

/** Сколько куплено из всего текущего списка. */
data class Progress(val bought: Int, val total: Int)

data class BatchPriceRefresh(val completed: Int, val total: Int, val failed: Int, val running: Boolean)

/** Сколько миллисекунд после покупки или удаления на строке остаётся кнопка «Отменить». */
private const val UNDO_WINDOW_MS = 1_500L

/** Живые товары плюс только что купленные или удалённые, в том же порядке, что и в базе (новые сверху). */
internal fun mergeRows(active: List<ShoppingItem>, recent: List<ListRow>): List<ListRow> {
    val activeIds = active.mapTo(HashSet()) { it.id }
    val finished = recent.filter { it.item.id !in activeIds }
    return (active.map { ListRow(it, RowState.ACTIVE) } + finished)
        .sortedByDescending { it.item.createdAt }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ShoppingViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = application.repository
    private val lenta = application.lentaCatalog
    private val journal = ActionJournal.get(application)
    private val actionMutex = kotlinx.coroutines.sync.Mutex()
    internal val actions = journal.entries

    private val activeItems: StateFlow<List<ShoppingItem>?> = repository.observeItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Купленные и удалённые товары, чьи строки ещё показывают «Отменить». */
    private val recent = MutableStateFlow<List<ListRow>>(emptyList())
    private val undoTimers = HashMap<String, Job>()

    /** null — список ещё загружается. */
    val rows: StateFlow<List<ListRow>?> = combine(activeItems, recent) { active, recent ->
        active?.let { mergeRows(it, recent) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Сколько куплено из текущего списка. Когда всё куплено и окно отмены закрылось, список уходит в архив,
     * а прогресс пропадает (total = 0), вместо вечного «3 из 3».
     */
    val progress: StateFlow<Progress> = combine(activeItems, repository.observeBoughtCount(), recent) { active, bought, recent ->
        if (active.isNullOrEmpty() && bought == 0) {
            Progress(0, 0)
        } else {
            Progress(bought = bought, total = bought + (active?.size ?: 0))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Progress(0, 0))

    val customProducts: StateFlow<List<CustomProduct>> = repository.observeCustomProducts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val customBarcodes: StateFlow<List<CustomProductBarcode>> = repository.observeCustomBarcodes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val storeProducts: StateFlow<List<com.mok.buylist.data.StoreProduct>> =
        repository.observeStoreProducts()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val stores: StateFlow<List<ShoppingStore>> = repository.observeStores()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val archive: StateFlow<List<ArchivedList>> = repository.observeArchive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val bought: StateFlow<List<ShoppingItem>> = repository.observeBought()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val priceInputs = combine(lenta.products, lenta.address, customProducts, customBarcodes, lenta.barcodeRows) { products, address, custom, customCodes, lentaCodes ->
        Triple(products, address, com.mok.buylist.data.sharedCatalogIdentities(products, custom, lentaCodes, customCodes))
    }
    internal val totals: StateFlow<ShoppingTotals> = combine(repository.observeAllItems(), priceInputs, storeProducts, archive, customProducts) { items, input, links, history, custom ->
        val (products, address, aliases) = input
        val purchases = expensePurchases(history, items.filter { it.deleted })
        val lines = items.map { item ->
            val key = if (item.customProductId.isNotBlank()) "custom:${item.customProductId}" else "lenta:${item.productId}"
            val price = if (item.deleted) decodePurchaseEvent(item.purchaseEvent)?.unitPrice else
                displayShopPrice(key, productShopPrices(key, emptyList(), links, purchases, aliases, products, address))?.priceKopecks
                    ?: custom.firstOrNull { it.id == item.customProductId }?.estimatedPriceKopecks
            PricedLine(item.quantity, item.deleted, price, if (item.deleted) decodePurchaseEvent(item.purchaseEvent)?.weightGrams else item.weightGrams)
        }
        calculateShoppingTotals(lines)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), calculateShoppingTotals(emptyList()))

    /** Кнопка остаётся на месте, пока виден активный список. */
    val canFinish: StateFlow<Boolean> = combine(activeItems, bought, recent) { active, items, undo ->
        !active.isNullOrEmpty() || items.isNotEmpty() || undo.isNotEmpty()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val finishEnabled: StateFlow<Boolean> = combine(bought, recent) { items, undo ->
        items.isNotEmpty() && undo.isEmpty()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** null — приложение запущено впервые, имя ещё не спросили. */
    val profile: StateFlow<Profile?> = application.userProfile.profile

    /** Все известные пользователи (в том числе те, кого принесла синхронизация): по ним делится статистика. */
    val users: StateFlow<List<AppUser>> = repository.observeUsers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Приложение могли закрыть сразу после последней покупки (или купить последнее в виджете):
        // дооформляем список — переносим в архив.
        viewModelScope.launch {
            repository.claimLegacyData()
        }
    }

    val priceRefresh = MutableStateFlow<Pair<Long, String>?>(null)
    val batchPriceRefresh = MutableStateFlow<BatchPriceRefresh?>(null)

    fun refreshAllPrices() {
        if (batchPriceRefresh.value?.running == true) return
        val ids = lenta.products.value.map { it.id }
        if (ids.isEmpty()) return
        val address = lenta.address.value
        if (address.isBlank()) {
            batchPriceRefresh.value = BatchPriceRefresh(0, ids.size, ids.size, false)
            return
        }
        batchPriceRefresh.value = BatchPriceRefresh(0, ids.size, 0, true)
        viewModelScope.launch {
            var failed = 0
            var completed = 0
            for (id in ids) {
                if (lenta.address.value != address) { failed += ids.size - completed; break }
                try {
                    if (!lenta.hasManualPrice(id)) lenta.refreshPrice(id)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) { failed++ }
                completed++
                batchPriceRefresh.value = BatchPriceRefresh(completed, ids.size, failed, true)
            }
            batchPriceRefresh.value = BatchPriceRefresh(completed, ids.size, failed, false)
        }
    }

    fun refreshPrice(id: Long) {
        if (priceRefresh.value?.second == "Загрузка") return
        priceRefresh.value = id to "Загрузка"
        viewModelScope.launch {
            priceRefresh.value = try {
                if (lenta.hasManualPrice(id)) id to "Установлена ручная цена"
                else { lenta.refreshPrice(id); id to "Цена обновлена" }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                id to (e.message ?: "Не удалось обновить цену")
            }
        }
    }

    fun saveProfile(name: String) {
        viewModelScope.launch { repository.saveProfile(name) }
    }

    fun addProduct(product: com.mok.buylist.data.LentaProduct, onResult: (Boolean) -> Unit) {
        viewModelScope.launch { actionMutex.withLock {
            val added = repository.addProduct(product)
            if (added) repository.actionItemByKey("lenta:${product.id}")?.let { journal.record("Добавлен товар", it.name, after = it) }
            onResult(added)
        } }
    }

    fun editCustomProduct(id: String, name: String, onResult: (String?) -> Unit) {
        viewModelScope.launch { onResult(repository.editCustomProduct(id, name)) }
    }

    fun updateCustomEstimatedPrice(id: String, priceKopecks: Long?,
        onResult: (String?) -> Unit) {
        viewModelScope.launch {
            onResult(repository.updateCustomEstimatedPrice(id, priceKopecks))
        }
    }

    fun updateCustomProductDetails(
        id: String, name: String, estimatedPriceKopecks: Long?, isWeighted: Boolean,
        onResult: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            onResult(repository.updateCustomProductDetails(id, name, estimatedPriceKopecks, isWeighted))
        }
    }

    fun deleteCustomProduct(id: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch { onResult(repository.deleteCustomProduct(id)) }
    }

    fun changeCustomBarcode(id: String, previous: String?, value: String?,
        onResult: (String?) -> Unit) {
        viewModelScope.launch { onResult(repository.changeCustomBarcode(id, previous, value)) }
    }

    fun renameStore(id: String, name: String, onResult: (String?) -> Unit) {
        viewModelScope.launch { onResult(repository.renameStore(id, name)) }
    }

    fun deleteStore(id: String, onResult: (String?) -> Unit) {
        viewModelScope.launch { onResult(repository.deleteStore(id)) }
    }

    fun createCustomForStore(storeId: String, name: String, barcode: String,
        storePrice: Long?, isWeighted: Boolean, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            onResult(repository.createCustomForStore(storeId, name, barcode, storePrice, isWeighted) != null)
        }
    }

    fun saveStoreProduct(storeId: String, productKey: String, price: Long?,
        onResult: (String?) -> Unit) {
        viewModelScope.launch { onResult(repository.saveStoreProduct(storeId, productKey, price)) }
    }

    fun removeStoreProduct(storeId: String, productKey: String,
        onResult: (String?) -> Unit) {
        viewModelScope.launch { onResult(repository.removeStoreProduct(storeId, productKey)) }
    }

    fun createStore(name: String, onResult: (ShoppingStore?) -> Unit) {
        viewModelScope.launch { onResult(repository.createStore(name)) }
    }

    fun latestStorePrice(productKey: String, storeId: String,
        excludedEventId: String? = null, onResult: (Long?) -> Unit) {
        viewModelScope.launch {
            onResult(repository.latestStorePrice(productKey, storeId, excludedEventId))
        }
    }

    fun amendPurchase(eventId: String, unitPrice: Long?, storeName: String,
        onResult: (String?) -> Unit) {
        viewModelScope.launch { onResult(repository.amendPurchase(eventId, unitPrice, storeName)) }
    }

    fun addManualProduct(name: String, barcode: String, estimatedPriceKopecks: Long?, isWeighted: Boolean,
        onResult: (Boolean) -> Unit) {
        viewModelScope.launch { actionMutex.withLock {
            val existing = repository.getItems().map { it.id }.toSet()
            val added = repository.addManualProduct(name, barcode, estimatedPriceKopecks, isWeighted)
            if (added) {
                val item = repository.getItems().filter { it.id !in existing }.singleOrNull()
                journal.record("Создан товар", name, after = item)
            }
            onResult(added)
        } }
    }

    fun addCustomProduct(product: CustomProduct, onResult: (Boolean) -> Unit) {
        viewModelScope.launch { actionMutex.withLock {
            val added = repository.addCustomToList(product)
            if (added) repository.actionItemByKey("custom:${product.id}")?.let { journal.record("Добавлен товар", it.name, after = it) }
            onResult(added)
        } }
    }

    fun buyScannedCustom(product: CustomProduct, price: Long?, storeName: String, weightGrams: Int?,
        onResult: (Boolean) -> Unit) {
        viewModelScope.launch { actionMutex.withLock {
            val before = repository.getItems().associateBy { it.id }
            val result = repository.buyScannedCustom(product, price, storeName, weightGrams)
            if (result == null) { onResult(false); return@withLock }
            repository.actionItem(result.item.id)?.let { after ->
                journal.record("Покупка отмечена", after.name, before = before[after.id], after = after)
            }
            onResult(true)
        } }
    }

    fun buyScanned(product: com.mok.buylist.data.LentaProduct, price: Long?, onResult: (Boolean) -> Unit) {
        viewModelScope.launch { actionMutex.withLock {
            val before = repository.getItems().associateBy { it.id }
            val result = repository.buyScanned(product, price)
            if (result == null) { onResult(false); return@withLock }
            repository.actionItem(result.item.id)?.let { after ->
                journal.record("Покупка отмечена", after.name, before = before[after.id], after = after)
            }
            onResult(true)
        } }
    }

    fun buy(item: ShoppingItem, priceOverride: Long? = null, storeName: String = "", weightGrams: Int? = null) {
        viewModelScope.launch { actionMutex.withLock {
            val before = repository.actionItem(item.id)?.takeUnless { it.deleted } ?: return@withLock
            repository.buy(item.id, priceOverride, storeName = storeName, weightGrams = weightGrams)
            val after = repository.actionItem(item.id)
            if (after?.deleted == true) journal.record("Покупка отмечена", before.name, before, after)
        } }
    }

    fun setQuantity(item: ShoppingItem, quantity: Int) {
        viewModelScope.launch { actionMutex.withLock {
            val before = repository.actionItem(item.id)?.takeUnless { it.deleted } ?: return@withLock
            repository.setQuantity(item.id, quantity)
            val after = repository.actionItem(item.id)
            if (after != null && after.quantity != before.quantity)
                journal.record("Количество изменено", "${before.name} · ${before.quantity} → ${after.quantity}", before, after)
        } }
    }

    fun setWeight(item: ShoppingItem, grams: Int?) {
        viewModelScope.launch { actionMutex.withLock {
            val before = repository.actionItem(item.id)?.takeUnless { it.deleted } ?: return@withLock
            repository.setWeight(item.id, grams)
            val after = repository.actionItem(item.id)
            if (after != null && after != before) journal.record("Вес изменён", before.name, before, after)
        } }
    }

    fun finishTrip() {
        viewModelScope.launch { actionMutex.withLock {
            if (bought.value.isNotEmpty()) {
                repository.finishTrip()
                journal.record("Список завершён")
            }
        } }
    }

    fun remove(item: ShoppingItem) {
        viewModelScope.launch { actionMutex.withLock {
            val (before, at) = repository.remove(item.id) ?: return@withLock
            journal.record("Товар удалён", before.name, before = before, removedAt = at)
        } }
    }

    fun restore(item: ShoppingItem) {
        undoableRecentActions(actions.value).firstOrNull { it.before?.id == item.id || it.after?.id == item.id }
            ?.let { undoAction(it.id) }
    }

    internal fun undoAction(id: String) {
        viewModelScope.launch { actionMutex.withLock {
            val action = undoableRecentActions(actions.value).firstOrNull { it.id == id } ?: return@withLock
            val success = repository.undoListAction(action.before, action.after, action.removedAt)
            val restored = if (success) action.before?.let { repository.actionItem(it.id) } else null
            journal.completeUndo(id, success, restored)
        } }
    }

    fun deleteArchived(list: ArchivedList) {
        viewModelScope.launch { repository.deleteArchived(list.id) }
    }

    /** Возвращает в текущий список выбранные товары из архивного; те, что уже в списке, пропускаются. */
    fun repeatArchived(names: List<String>, quantities: List<Int>, events: List<String>, onDone: () -> Unit) {
        viewModelScope.launch {
            repository.repeatItems(names, quantities, events)
            onDone()
        }
    }
}
