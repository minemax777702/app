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

internal class LentaSearchController(
    private val search: suspend (String, String, Int, Long) -> List<LentaProduct>,
    private val scope: CoroutineScope,
) {
    val state = MutableStateFlow(LentaSearchState())
    private var job: Job? = null
    private var query = ""
    private var address = ""
    private var savedIds: Set<Long> = emptySet()
    private var nextPage = 1
    private var storeId = 0L
    private val seenIds = HashSet<Long>()

    fun update(query: String, address: String, saved: List<LentaProduct>, onlineOnly: Boolean) {
        job?.cancel()
        this.query = query.trim()
        this.address = address
        savedIds = saved.mapTo(HashSet()) { it.id }
        nextPage = 1
        storeId = 0
        seenIds.clear()
        val key = nameKeyOf(this.query)
        val local = if (onlineOnly || key.length < 2) emptyList() else saved.filter {
            nameKeyOf(it.name).contains(key) || nameKeyOf(it.pack).contains(key) || it.ean.contains(key) ||
                com.mok.buylist.sync.possibleVariableBarcodeMatch(it.ean, key)
        }.sortedWith(compareBy<LentaProduct> { nameKeyOf(it.name) }.thenBy { it.id })
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
            // Пропускаем страницы, целиком состоящие из уже сохранённых товаров.
            repeat(5) {
                val page = nextPage
                val response = search(query, address, page, storeId)
                if (page == 1 && response.isNotEmpty()) storeId = response.first().storeId
                val fresh = response.filter { it.id > 0 && it.id !in seenIds }.distinctBy { it.id }
                if (response.isEmpty() || fresh.isEmpty()) {
                    state.value = state.value.copy(loading = false, hasMore = false, failed = false)
                    return
                }
                seenIds.addAll(fresh.map { it.id })
                nextPage = page + 1
                val added = fresh.filter { it.id !in savedIds }
                if (added.isNotEmpty()) {
                    state.value = state.value.copy(online = state.value.online + added,
                        loading = false, hasMore = true, failed = false)
                    return
                }
            }
            state.value = state.value.copy(loading = false, hasMore = true, failed = false)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            state.value = state.value.copy(loading = false, failed = true)
        }
    }
}
