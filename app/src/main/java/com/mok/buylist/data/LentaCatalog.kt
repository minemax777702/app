package com.mok.buylist.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import com.mok.buylist.sync.TombKind
import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LentaProduct(
    val id: Long, val name: String, val image: String, val pack: String,
    val price: String, val priceType: String, val ean: String, val address: String,
    val stock: Int? = null, val storeId: Long = 0, val channel: String = "lo",
    val fetchedAt: Long = 0,
) {
    fun json() = JSONObject().put("id", id).put("name", name).put("image", image)
        .put("package", pack).put("price", price).put("priceType", priceType)
        .put("ean", ean).put("address", address).put("channel", channel)
        .put("storeId", storeId).put("savedAt", fetchedAt)

    companion object {
        fun parse(o: JSONObject, address: String, storeId: Long = 0, live: Boolean = false, channel: String = o.optString("channel", "lo")): LentaProduct = LentaProduct(
            id = o.optLong("id"), name = o.optString("name"), image = o.optString("image"),
            pack = o.optString("package"), price = o.optString("price"), priceType = o.optString("priceType"),
            ean = catalogBarcode(o.optString("ean")).orEmpty(), address = address,
            stock = if (live && o.has("stock") && !o.isNull("stock")) o.optInt("stock") else null,
            storeId = if (storeId != 0L) storeId else o.optLong("storeId"), channel = channel,
            fetchedAt = if (live) System.currentTimeMillis() else o.optLong("savedAt"),
        )
    }
}

class LentaCatalog(context: Context, private val db: AppDatabase, scope: CoroutineScope) {
    private val dao = db.shoppingDao()
    private val refreshMutex = Mutex()
    private val prefs = context.getSharedPreferences("lenta_catalog", Context.MODE_PRIVATE)
    private val _address = MutableStateFlow(prefs.getString("address", "").orEmpty())
    val address = _address.asStateFlow()
    private val _ready = MutableStateFlow(false)
    val ready = _ready.asStateFlow()
    val barcodeRows = dao.observeProductBarcodes()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    val products = combine(dao.observeLentaProducts(), dao.observeProductBarcodes()) { rows, _ ->
        rows.map { it.toProduct() }
    }.onEach { _ready.value = true }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun barcodeAliases(id: Long): Set<String> =
        barcodeRows.value.asSequence().filter { it.productId == id && !it.deleted }.map { it.code }.toSet()

    fun matchesBarcode(product: LentaProduct, scanned: String): Boolean =
        barcodeAliases(product.id).any { com.mok.buylist.sync.barcodeMatches(it, scanned) }

    private suspend fun rememberBarcode(id: Long, code: String?) {
        val barcode = catalogBarcode(code) ?: return
        dao.insertProductBarcode(StoredProductBarcode(id, barcode))
    }

    /** Возвращает текст ошибки или null после успешного сохранения. */
    suspend fun addBarcode(id: Long, raw: String): String? = db.withTransaction {
        val code = raw.trim()
        if (!validProductBarcode(code)) return@withTransaction "Некорректный штрихкод (EAN-8, UPC-A или EAN-13)"
        if (dao.getLentaProducts().none { it.id == id }) return@withTransaction "Товар удалён"
        val records = dao.getProductBarcodes()
        if (records.any { it.productId != id && !it.deleted &&
                com.mok.buylist.sync.barcodeMatches(it.code, code) }) {
            return@withTransaction "Штрихкод уже привязан к другому товару"
        }
        if (records.any { it.productId == id && !it.deleted && it.code == code }) {
            return@withTransaction "Этот штрихкод уже добавлен"
        }
        val previous = records.firstOrNull { it.productId == id && it.code == code }
        val at = maxOf(System.currentTimeMillis(), (previous?.updatedAt ?: -1L) + 1)
        dao.upsertProductBarcode(StoredProductBarcode(id, code, at, false))
        null
    }

    suspend fun removeBarcode(id: Long, code: String): String? = db.withTransaction {
        val current = dao.getProductBarcodes().firstOrNull {
            it.productId == id && it.code == code && !it.deleted
        } ?: return@withTransaction "Штрихкод не найден"
        dao.upsertProductBarcode(current.copy(updatedAt = maxOf(System.currentTimeMillis(), current.updatedAt + 1), deleted = true))
        null
    }

    suspend fun editBarcode(id: Long, previous: String, replacement: String): String? = db.withTransaction {
        val next = replacement.trim()
        if (!validProductBarcode(next)) return@withTransaction "Некорректный штрихкод (EAN-8, UPC-A или EAN-13)"
        val records = dao.getProductBarcodes()
        val current = records.firstOrNull { it.productId == id && it.code == previous && !it.deleted }
            ?: return@withTransaction "Исходный штрихкод не найден"
        if (previous == next) return@withTransaction null
        if (records.any { !it.deleted && (it.productId != id || it.code != previous) &&
                com.mok.buylist.sync.barcodeMatches(it.code, next) }) {
            return@withTransaction "Этот штрихкод уже используется"
        }
        val now = System.currentTimeMillis()
        dao.upsertProductBarcode(current.copy(updatedAt = maxOf(now, current.updatedAt + 1), deleted = true))
        val previouslyUsed = records.firstOrNull { it.productId == id && it.code == next }
        val restoredAt = maxOf(now, (previouslyUsed?.updatedAt ?: -1L) + 1)
        dao.upsertProductBarcode(StoredProductBarcode(id, next, restoredAt, false))
        null
    }

    fun setAddress(value: String) {
        val address = value.trim()
        prefs.edit().putString("address", address).apply()
        _address.value = address
    }

    suspend fun save(product: LentaProduct, scannedBarcode: String? = null) {
        if (product.id <= 0 || product.name.isBlank()) return
        // Поисковая выдача не всегда содержит EAN. Запрашиваем карточку только при отсутствии известных кодов.
        val stored = dao.getLentaProducts().firstOrNull { it.id == product.id }
        val known = catalogBarcode(product.ean) != null || catalogBarcode(scannedBarcode) != null ||
            catalogBarcode(stored?.ean) != null || dao.getProductBarcodes().any { it.productId == product.id && !it.deleted }
        val enriched = if (!known && product.address.isNotBlank() && product.address == address.value) {
            try {
                val fresh = details(product)
                if (fresh.id == product.id && address.value == product.address)
                    mergeLentaDetails(product, fresh, updatePrice = false) else product
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) { product }
        } else product
        db.withTransaction {
            val old = dao.getLentaProducts().firstOrNull { it.id == product.id }?.toProduct()
            val saved = enriched.copy(ean = catalogBarcode(enriched.ean) ?: catalogBarcode(old?.ean).orEmpty(), stock = null)
            val deletedAt = dao.getTombstones().firstOrNull { it.kind == TombKind.PRODUCT.code && it.target == product.id.toString() }?.deletedAt ?: 0
            dao.upsertLentaProduct(saved.toStored().copy(updatedAt = maxOf(System.currentTimeMillis(), deletedAt + 1)))
            // Если Лента заменила EAN у того же ID, старый код остаётся доступным.
            rememberBarcode(product.id, old?.ean)
            rememberBarcode(product.id, saved.ean)
            rememberBarcode(product.id, scannedBarcode)
        }
    }

    suspend fun removeById(id: Long) {
        db.withTransaction {
            dao.deleteLentaProduct(id)
            dao.deleteProductBarcodes(id)
            dao.upsertTombstone(Tombstone(TombKind.PRODUCT.code, id.toString(), System.currentTimeMillis()))
        }
    }

    suspend fun search(query: String, address: String, page: Int = 1, storeId: Long = 0): List<LentaProduct> = withContext(Dispatchers.IO) {
        require(page >= 1)
        val args = JSONObject().put("query", query).put("page", page).put("channel", "locc")
        if (storeId > 0) args.put("storeId", storeId) else args.put("address", address)
        val data = call("storefront_products_search", args)
        val items = data.optJSONArray("items") ?: return@withContext emptyList()
        val responseStoreId = data.optLong("storeId")
        check(storeId == 0L || storeId == responseStoreId) { "Магазин в ответе изменился" }
        (0 until items.length()).map { LentaProduct.parse(items.getJSONObject(it), address, responseStoreId, live = true, channel = "locc") }
    }

    suspend fun details(product: LentaProduct): LentaProduct = withContext(Dispatchers.IO) {
        val args = JSONObject().put("id", product.id).put("channel", "locc")
        if (product.storeId != 0L) args.put("storeId", product.storeId) else args.put("address", product.address)
        val item = call("storefront_product_details", args).getJSONObject("item")
        LentaProduct.parse(item, product.address, product.storeId, live = true, channel = "locc")
    }

    /** A manually configured store price is synced in store_products, not in API metadata. */
    suspend fun hasManualPrice(id: Long): Boolean {
        val identities = sharedCatalogIdentities(dao.getLentaProducts().map { it.toProduct() },
            dao.getCustomProducts(), dao.getProductBarcodes(), dao.getCustomProductBarcodes())
        val key = "lenta:$id"
        return dao.getStoreProducts().any { !it.deleted && it.storeId == lentaStoreId() &&
            it.priceKopecks != null && (identities[it.productKey] ?: it.productKey) == (identities[key] ?: key) }
    }

    /** Обновляет карточку независимо от доступности цены; ручная цена хранится отдельно. */
    internal suspend fun refreshProduct(id: Long): ProductRefreshResult = refreshMutex.withLock {
        val currentAddress = address.value
        require(currentAddress.isNotBlank()) { "Укажите адрес магазина" }
        val product = dao.getLentaProducts().firstOrNull { it.id == id }?.toProduct()
            ?: error("Товар не найден в базе")
        val fresh = details(product.copy(address = currentAddress, storeId = 0))
        require(fresh.id == id && fresh.address == currentAddress && fresh.channel == "locc") {
            "Лента вернула данные другого товара"
        }
        db.withTransaction {
            check(address.value == currentAddress) { "Адрес магазина изменился" }
            val existing = dao.getLentaProducts().firstOrNull { it.id == id }?.toProduct()
                ?: error("Товар удалён из базы")
            val manual = hasManualPrice(id)
            val priceAvailable = parsePriceKopecks(fresh.price) != null
            val saved = mergeLentaDetails(existing, fresh, updatePrice = !manual && priceAvailable)
            dao.upsertLentaProduct(saved.toStored())
            rememberBarcode(id, existing.ean)
            rememberBarcode(id, fresh.ean)
            val hasBarcode = dao.getProductBarcodes().any { it.productId == id && !it.deleted }
            ProductRefreshResult(hasBarcode, manual, priceAvailable)
        }
    }

    private fun call(name: String, args: JSONObject): JSONObject {
        val request = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call")
            .put("params", JSONObject().put("name", name).put("arguments", args))
        val c = URL("https://mcp.lenta.com/mcp").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.connectTimeout = 12000; c.readTimeout = 20000; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/json, text/event-stream")
            c.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            val response = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            response.optJSONObject("error")?.let { error(it.optString("message")) }
            val result = response.getJSONObject("result")
            if (result.optBoolean("isError")) error("Ошибка сервиса")
            val body = JSONObject(result.getJSONArray("content").getJSONObject(0).getString("text"))
            if (!body.optBoolean("ok")) error("Не удалось получить товары")
            return body.getJSONObject("data")
        } finally { c.disconnect() }
    }
}

internal fun LentaProduct.toStored() = StoredLentaProduct(
    id, name, image, pack, parsePriceKopecks(price), priceType, ean, address, storeId, channel, fetchedAt, System.currentTimeMillis(),
)

internal fun StoredLentaProduct.toProduct() = LentaProduct(
    id, name, image, pack, priceKopecks?.let { java.math.BigDecimal.valueOf(it, 2).toPlainString() }.orEmpty(),
    priceType, ean, address, storeId = storeId, channel = channel, fetchedAt = fetchedAt,
)


internal data class ProductRefreshResult(
    val hasBarcode: Boolean,
    val manualPrice: Boolean,
    val priceAvailable: Boolean,
)

/** Допускаем те же форматы EAN/UPC, что и при сохранении алиасов каталога. */
internal fun catalogBarcode(raw: String?): String? =
    raw?.trim()?.takeIf { it.length in setOf(8, 12, 13) && it.all { c -> c in '0'..'9' } }

/** Пустые поля ответа не затирают сведения, а цена сохраняет контекст своего магазина. */
internal fun mergeLentaDetails(existing: LentaProduct, fresh: LentaProduct, updatePrice: Boolean): LentaProduct {
    require(existing.id == fresh.id)
    fun String.usableOr(old: String) = takeUnless { it.isBlank() || it == "null" } ?: old
    val metadata = existing.copy(
        name = fresh.name.usableOr(existing.name),
        image = fresh.image.usableOr(existing.image),
        pack = fresh.pack.usableOr(existing.pack),
        ean = catalogBarcode(fresh.ean) ?: catalogBarcode(existing.ean).orEmpty(),
    )
    if (!updatePrice || parsePriceKopecks(fresh.price) == null) return metadata
    return metadata.copy(price = fresh.price, priceType = fresh.priceType,
        address = fresh.address, storeId = fresh.storeId, channel = fresh.channel, fetchedAt = fresh.fetchedAt)
}
