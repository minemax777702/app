package com.mok.buylist.ui

import com.mok.buylist.data.LentaProduct
import com.mok.buylist.data.nameKeyOf
import com.mok.buylist.data.matchesProductSearch
import com.mok.buylist.data.isBarcodeSearch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

internal data class LentaSearchState(
    val local: List<LentaProduct> = emptyList(),
    val online: List<LentaProduct> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
    val started: Boolean = false,
    val hasMore: Boolean = false,
    val complete: Boolean = false,
    val stalled: Boolean = false,
    val pagesLoaded: Int = 0,
)

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
    private var fullSearch = false
    private var onlineOnly = false
    private var initialized = false

    fun update(query: String, address: String, saved: List<LentaProduct>, onlineOnly: Boolean, fullSearch: Boolean = false) {
        val trimmed = query.trim()
        val newSavedIds = saved.mapTo(HashSet()) { it.id }
        val reset = !initialized || this.query != trimmed ||
            this.address != address || this.onlineOnly != onlineOnly || this.fullSearch != fullSearch
        initialized = true
        if (reset) job?.cancel()
        this.query = trimmed
        this.onlineOnly = onlineOnly
        this.fullSearch = fullSearch
        this.address = address
        savedIds = newSavedIds
        val key = nameKeyOf(trimmed)
        val local = if (onlineOnly || key.length < 2) emptyList() else saved.filter {
            matchesProductSearch(it.name, it.pack, this.query) ||
                barcodeAliases(it.id).any { alias -> com.mok.buylist.sync.barcodeSearchMatches(alias, key) }
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
        if (key.length >= 2 && address.isNotBlank() && (fullSearch || onlineOnly || local.isEmpty())) {
            job = scope.launch { delay(500); fetch() }
        }
    }

    fun setFullSearch(enabled: Boolean) {
        if (fullSearch == enabled) return
        fullSearch = enabled
        if (enabled && !state.value.complete && !state.value.loading) searchOnline()
    }

    fun searchOnline() {
        if (state.value.loading || state.value.complete || query.length < 2 || address.isBlank()) return
        job?.cancel()
        job = scope.launch { fetch() }
    }

    private suspend fun fetch() {
        state.value = state.value.copy(loading = true, failed = false, started = true, stalled = false, hasMore = true)
        var skipped = 0
        var repeatedPages = 0
        try {
            while (true) {
                coroutineContext.ensureActive()
                val page = nextPage
                val response = search(query, address, page, storeId)
                coroutineContext.ensureActive()
                if (response.isEmpty()) {
                    state.value = state.value.copy(loading = false, hasMore = false, complete = true, failed = false)
                    return
                }
                if (storeId == 0L) storeId = response.first().storeId
                val fresh = response.filter { it.id > 0 && it.id !in seenIds }.distinctBy { it.id }
                nextPage = page + 1
                seenIds.addAll(fresh.map { it.id })
                val relevant = fresh.filter { product ->
                    isBarcodeSearch(query) || matchesProductSearch(product.name, product.pack, query)
                }
                // Keep existing IDs excluded from the online add-to-catalog screen.
                // In combined search, refresh a saved match with the live store result.
                val current = state.value
                val replacements = if (onlineOnly) emptyMap() else relevant.associateBy { it.id }
                val local = current.local.map { replacements[it.id] ?: it }
                val added = relevant.filter { it.id !in savedIds }
                state.value = current.copy(
                    local = local,
                    online = current.online + added,
                    pagesLoaded = page,
                )
                if (fresh.isEmpty()) repeatedPages++ else repeatedPages = 0
                if (added.isEmpty()) skipped++ else skipped = 0
                // No total-page limit: even five consecutive irrelevant pages may
                // precede a relevant product. Only identical/overlapping pages with
                // no new IDs trigger a recoverable guard in full search.
                if (fullSearch && repeatedPages >= 5) {
                    state.value = state.value.copy(loading = false, stalled = true, hasMore = true)
                    return
                }
                if (!fullSearch && (added.isNotEmpty() || skipped >= 5)) {
                    state.value = state.value.copy(loading = false, hasMore = true)
                    return
                }
                if (fullSearch) delay(100)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            state.value = state.value.copy(loading = false, failed = true, hasMore = true)
        }
    }
}
