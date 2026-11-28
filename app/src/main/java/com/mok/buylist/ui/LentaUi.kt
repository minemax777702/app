package com.mok.buylist.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mok.buylist.data.LentaProduct
import com.mok.buylist.R
import com.mok.buylist.data.nameKeyOf
import com.mok.buylist.lentaCatalog
import com.mok.buylist.ui.theme.LocalPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

@Composable
fun LentaSettings(onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as com.mok.buylist.ShoppingApp
    val selection by app.lentaAddressSettings.current.collectAsStateWithLifecycle()
    var address by rememberSaveable(selection.account) { mutableStateOf(selection.address) }
    val p = LocalPalette.current
    AppSheet(onDismissRequest = onDismiss, containerColor = p.sheet,
        title = { SheetHeader("Магазин Ленты") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SheetTextField(address, { address = it.take(500) }, "Город, улица и дом")
        } },
        confirmButton = { SheetActionButton(onClick = { app.lentaAddressSettings.save(selection.account,address); app.cloud.signal(); onDismiss() }) { Text("Сохранить", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold) } },
        dismissButton = { SheetActionButton(onClick = onDismiss) { Text("Отмена", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold) } })
}

@Composable
internal fun LentaSuggestions(query: String, modifier: Modifier = Modifier,
    onAdd: (LentaProduct) -> Unit, onSettings: () -> Unit,
    onlineOnly: Boolean = false, hasOtherResults: Boolean = false,
    leadingResults: LazyListScope.() -> Unit = {}, footer: (@Composable () -> Unit)? = null,
    onUserScroll: () -> Unit = {},
    customResults: List<ProductSearchRow> = emptyList(),
    customContent: @Composable (ProductSearchRow) -> Unit = {}) {
    val catalog = LocalContext.current.lentaCatalog
    val address by catalog.address.collectAsStateWithLifecycle()
    val saved by catalog.products.collectAsStateWithLifecycle()
    val barcodeRows by catalog.barcodeRows.collectAsStateWithLifecycle()
    val ready by catalog.ready.collectAsStateWithLifecycle()
    val p = LocalPalette.current
    val trimmed = query.trim()
    val listState = rememberLazyListState()
    val scrollCallback by rememberUpdatedState(onUserScroll)
    val scope = rememberCoroutineScope()
    val controller = remember(catalog, scope) { LentaSearchController(catalog::search, scope, catalog::barcodeAliases) }
    val observedState by controller.state.collectAsStateWithLifecycle()
    val state = if (observedState.requestQuery == trimmed && observedState.requestAddress == address) observedState
        else LentaSearchState(pending = trimmed.length >= 2 && address.isNotBlank())
    var sort by rememberSaveable { mutableStateOf(ProductSort.DEFAULT) }
    val resultRows = remember(state.local, state.online, address, sort, customResults) {
        sortProductRows(customResults + (state.local + state.online).distinctBy { it.id }.map {
            ProductSearchRow.lenta(it, searchCatalogPrice(it, address))
        }, sort)
    }
    LaunchedEffect(trimmed, address, saved, barcodeRows, onlineOnly, ready) {
        if (ready) controller.update(trimmed, address, saved, onlineOnly, fullSearch = sort != ProductSort.DEFAULT)
    }
    LaunchedEffect(sort, ready) {
        if (ready) controller.setFullSearch(sort != ProductSort.DEFAULT)
    }
    val fullSort = sort != ProductSort.DEFAULT && address.isNotBlank() && trimmed.length >= 2
    val searching = state.pending || state.loading || (!ready && trimmed.length >= 2)
    Column(modifier.fillMaxWidth().padding(top = 4.dp)) {
        if (trimmed.length >= 2 || hasOtherResults || footer != null) {
            if (trimmed.length >= 2) ProductSortMenu(sort, { selected ->
                sort = selected
                controller.setFullSearch(selected != ProductSort.DEFAULT)
                scrollCallback()
            })
            if (fullSort && searching) {
                Column(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Загружаем все результаты · страниц: ${state.pagesLoaded}",
                        color = p.textSecondary, fontSize = 12.sp)
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = p.accent)
                    FreshCutSecondaryButton("Остановить поиск", controller::stop, enabled = state.pending || state.loading)
                }
            }
            val emptyResult = trimmed.length >= 2 && !hasOtherResults &&
                resultRows.isEmpty() && state.complete && !searching && !state.failed
            if (emptyResult) {
                FreshCutEmptyState(
                    icon = R.drawable.ic_search,
                    title = stringResource(R.string.empty_search_title),
                    description = stringResource(R.string.empty_search_description),
                    modifier = Modifier.weight(1f),
                )
                footer?.invoke()
            } else LazyColumn(
                Modifier.fillMaxWidth().weight(1f).pointerInput(Unit) {
                    // Observe, never consume: the list retains its own scrolling and fling.
                    // Also fires at either edge and when results fit without scrolling.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var notified = false
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            val delta = change.position - down.position
                            if (!notified && abs(delta.y) > viewConfiguration.touchSlop && abs(delta.y) > abs(delta.x)) {
                                notified = true
                                scrollCallback()
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
                state = listState, overscrollEffect = null, verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (address.isBlank() && trimmed.length >= 2 && ready) item(key = "search-address") {
                    FreshCutStatusCard("Магазин не выбран", "Для поиска в Ленте укажите адрес магазина.", "Указать адрес", onSettings)
                }
                if (state.failed && !searching) item(key = "search-error") {
                    FreshCutStatusCard("Не удалось загрузить товары",
                        if (resultRows.isNotEmpty()) "Найденные товары сохранены. Проверьте подключение и повторите поиск."
                        else "Проверьте подключение к интернету и повторите поиск.",
                        "Повторить", controller::searchOnline, warning = true)
                }
                leadingResults()
                // In ranked mode avoid jumping rows and showing a provisional winner.
                if (!fullSort || !searching) items(resultRows, key = { it.key }) { row ->
                    val product = row.lenta
                    if (product != null) LentaResult(product, row, onClick = { onAdd(product) })
                    else customContent(row)
                }
                if (!fullSort && !onlineOnly && state.local.isNotEmpty() && !state.started && !searching && !state.paused && address.isNotBlank()) {
                    item { FreshCutSecondaryButton("Найти в Ленте", { controller.searchOnline() }) }
                }
                if (!searching && !state.complete && !state.failed && (state.stalled || state.paused)) item(key = "search-paused") {
                    FreshCutStatusCard("Поиск приостановлен",
                        if (state.stalled) "Лента возвращает повторяющиеся страницы. Результат неполный."
                        else "Результат неполный. Можно продолжить поиск.",
                        "Продолжить поиск", controller::searchOnline)
                }
                if (searching && !fullSort) item(key = "search-loading") {
                    val loadingSize = Modifier.heightIn(min = 56.dp)
                    Box(Modifier.fillMaxWidth().then(loadingSize),
                        contentAlignment = Alignment.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiMetrics.gap)) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = p.textSecondary)
                            Text(if (ready) "Ищем товары…" else "Загружаем каталог…", color = p.textSecondary, fontSize = 13.sp)
                        }
                    }
                }
                if (searching && !fullSort && ready) item(key = "search-stop") {
                    FreshCutSecondaryButton("Остановить поиск", controller::stop, enabled = state.pending || state.loading)
                }
                if (state.hasMore && !state.failed && !searching && !state.paused && !state.stalled) {
                    item { FreshCutSecondaryButton(
                        if (state.paused || fullSort) "Продолжить поиск" else "Показать ещё",
                        { controller.searchOnline() },
                    ) }
                }
                if (footer != null) item(key = "search-footer") { footer() }
            }
        }
    }
}

@Composable
private fun LentaResult(product: LentaProduct, row: ProductSearchRow, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.card).clickable(onClick = onClick).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LentaImage(product.image, Modifier.size(52.dp))
            Column(Modifier.weight(1f)) {
                Text(product.name, color = p.text, fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
                val price = row.price?.let { java.math.BigDecimal.valueOf(it, 2).stripTrailingZeros().toPlainString().replace('.', ',') + " ₽" }
                val details = listOfNotNull(price, product.pack.takeIf { it.isNotBlank() }).joinToString(" · ")
                if (details.isNotEmpty()) Text(details, color = p.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                row.normalized?.let { Text(it.label(), color = p.textSecondary, fontSize = 12.sp) }
            }
        }
    }
}

fun lentaPrice(product: LentaProduct): String = if (product.price.isBlank() || product.price == "null") "—" else
    "${product.price} ₽"

private val lentaImageLoads = Semaphore(4)

@Composable
fun LentaImage(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, url) {
        value = null
        value = withContext(Dispatchers.IO) {
            lentaImageLoads.withPermit {
            runCatching {
                currentCoroutineContext().ensureActive()
                if (!url.startsWith("https://")) return@runCatching null
                val key = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
                val file = java.io.File(context.cacheDir, "lenta_$key")
                if (!file.exists()) {
                    val c = URL(url).openConnection() as HttpURLConnection
                    try {
                        c.connectTimeout = 8000; c.readTimeout = 8000
                        if (c.responseCode != 200) error("image")
                        val limit = 2_000_000
                        if (c.contentLength > limit) error("image too large")
                        val temporary = java.io.File.createTempFile("lenta_", ".tmp", context.cacheDir)
                        try {
                            c.inputStream.use { input ->
                                temporary.outputStream().use { output ->
                                    val buffer = ByteArray(8192)
                                    var total = 0
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        total += count
                                        if (total > limit) error("image too large")
                                        output.write(buffer, 0, count)
                                    }
                                }
                            }
                            currentCoroutineContext().ensureActive()
                            if (!temporary.renameTo(file) && !file.exists()) error("image cache")
                        } finally { temporary.delete() }
                    } finally { c.disconnect() }
                }
                currentCoroutineContext().ensureActive()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    file.delete()
                    return@runCatching null
                }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            }.onFailure { if (it is CancellationException) throw it }.getOrNull()
            }
        }
    }
    val p = LocalPalette.current
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(p.field), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) {
            Image(image.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            Icon(painterResource(R.drawable.ic_product_placeholder), contentDescription = null,
                modifier = Modifier.fillMaxSize(0.5f), tint = p.iconMuted.copy(alpha = 0.75f))
        }
    }
}
