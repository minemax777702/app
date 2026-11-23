package com.mok.buylist.ui

import com.mok.buylist.data.LentaProduct
import com.mok.buylist.data.nameKeyOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

internal data class LentaSearchState(
    val local: List<LentaProduct> = emptyList(),
    val online: List<LentaProduct> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
    val started: Boolean = false,
    val hasMore: Boolean = false,
)

private const val MAX_PAGES_PER_FETCH = 12
private const val MAX_IDENTICAL_PAGES = 8

internal class LentaSearchController(
    private val search: suspend (String, String, Int, Long) -> List<LentaProduct>,
    private val scope: CoroutineScope,
    private val barcodeAliases: (Long) -> Set<String> = { emptySet() },
) {
    val state = MutableStateFlow(LentaSearchState())
    private var job: Job? = null
    private var query = ""
    private var address = ""
    private var savedIds: Set<Long> = emptySet()
    private var nextPage = 1
    private var storeId = 0L
    private val seenIds = HashSet<Long>()
    private var onlineOnly = false
    private var initialized = false

    fun update(query: String, address: String, saved: List<LentaProduct>, onlineOnly: Boolean) {
        val trimmed = query.trim()
        val newSavedIds = saved.mapTo(HashSet()) { it.id }
        val reset = !initialized || this.query != trimmed ||
            this.address != address || this.onlineOnly != onlineOnly
        initialized = true
        if (reset) job?.cancel()
        this.query = trimmed
        this.address = address
        this.onlineOnly = onlineOnly
        savedIds = newSavedIds
        val key = nameKeyOf(trimmed)
        val local = if (onlineOnly || key.length < 2) emptyList() else saved.filter {
            nameKeyOf(it.name).contains(key) || nameKeyOf(it.pack).contains(key) ||
                barcodeAliases(it.id).any { alias -> alias.contains(key) ||
                    com.mok.buylist.sync.possibleVariableBarcodeMatch(alias, key) }
        }.sortedWith(compareBy<LentaProduct> { nameKeyOf(it.name) }.thenBy { it.id })
        if (!reset) {
            // Saved products and barcode aliases may change while paging.
            // Do not discard the current server cursor or cancel an in-flight request.
            state.value = state.value.copy(
                local = local,
                online = state.value.online.filterNot { it.id in newSavedIds },
            )
            return
        }
        nextPage = 1
        storeId = 0
        seenIds.clear()
        state.value = LentaSearchState(local = local)
        if (key.length >= 2 && address.isNotBlank() && (onlineOnly || local.isEmpty())) {
            job = scope.launch { delay(500); fetch() }
        }
    }

    fun searchOnline() {
        if (state.value.loading || query.length < 2 || address.isBlank()) return
        job?.cancel()
        job = scope.launch { fetch() }
    }

    private suspend fun fetch() {
        state.value = state.value.copy(loading = true, failed = false, started = true)
        try {
            var previousPageIds: Set<Long>? = null
            var identicalPages = 0
            // Пропускаем сохранённые и уже показанные товары, включая страницы после пятой.
            // Лимиты ограничивают нагрузку на сервис, но не объявляют выдачу законченной.
            repeat(MAX_PAGES_PER_FETCH) {
                val page = nextPage
                val response = search(query, address, page, storeId)
                if (response.isEmpty()) {
                    state.value = state.value.copy(loading = false, hasMore = false, failed = false)
                    return
                }
                if (page == 1) storeId = response.first().storeId
                nextPage = page + 1

                val pageIds = response.mapNotNull { it.id.takeIf { id -> id > 0 } }.toSet()
                identicalPages = if (pageIds == previousPageIds) identicalPages + 1 else 1
                previousPageIds = pageIds

                val fresh = response.filter { it.id > 0 && seenIds.add(it.id) }.distinctBy { it.id }
                val added = fresh.filter { it.id !in savedIds }
                if (added.isNotEmpty()) {
                    state.value = state.value.copy(
                        online = state.value.online + added,
                        loading = false, hasMore = true, failed = false,
                    )
                    return
                }
                if (identicalPages >= MAX_IDENTICAL_PAGES) {
                    // Сервер повторяет одинаковую страницу. Оставляем ручное
                    // продолжение с nextPage вместо бесконечных запросов.
                    state.value = state.value.copy(loading = false, hasMore = true, failed = false)
                    return
                }
            }
            // Если новых товаров пока нет, кнопка «Показать ещё» продолжит
            // поиск с сохранённой позиции, не возвращаясь на первую страницу.
            state.value = state.value.copy(loading = false, hasMore = true, failed = false)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            state.value = state.value.copy(loading = false, failed = true)
        }
    }
}
