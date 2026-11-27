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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

@Composable
fun LentaSettings(onDismiss: () -> Unit) {
    val catalog = LocalContext.current.lentaCatalog
    val current by catalog.address.collectAsStateWithLifecycle()
    var address by rememberSaveable { mutableStateOf(current) }
    val p = LocalPalette.current
    AppSheet(onDismissRequest = onDismiss, containerColor = p.sheet,
        title = { SheetHeader("Магазин Ленты") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SheetTextField(address, { address = it }, "Город, улица и дом")
        } },
        confirmButton = { SheetActionButton(onClick = { catalog.setAddress(address); onDismiss() }) { Text("Сохранить", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold) } },
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
    val state by controller.state.collectAsStateWithLifecycle()
    var sort by rememberSaveable { mutableStateOf(ProductSort.DEFAULT) }
    val resultRows = remember(state.local, state.online, address, sort, customResults) {
        sortProductRows(customResults + (state.local + state.online).distinctBy { it.id }.map {
            ProductSearchRow.lenta(it, searchCatalogPrice(it, address))
        }, sort)
    }
    val activity = androidx.activity.compose.LocalActivity.current as? MainActivity
    LaunchedEffect(state.failed) {
        if (state.failed) activity?.notifyUser("Не удалось выполнить поиск в Ленте")
    }
    LaunchedEffect(trimmed, address, saved, barcodeRows, onlineOnly, ready) {
        if (ready) controller.update(trimmed, address, saved, onlineOnly, fullSearch = sort != ProductSort.DEFAULT)
    }
    LaunchedEffect(sort, ready) {
        if (ready) controller.setFullSearch(sort != ProductSort.DEFAULT)
    }
    val fullSort = sort != ProductSort.DEFAULT && address.isNotBlank() && trimmed.length >= 2
    Column(modifier.fillMaxWidth().padding(top = 4.dp)) {
        if (trimmed.length >= 2 || hasOtherResults || footer != null) {
            if (trimmed.length >= 2) ProductSortMenu(sort, { selected ->
                sort = selected
                controller.setFullSearch(selected != ProductSort.DEFAULT)
                scrollCallback()
            })
            if (fullSort && state.loading) {
                Text("Загружаем все результаты · страниц: ${state.pagesLoaded}", color = p.textSecondary,
                    fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 8.dp), color = p.accent)
            }
            // Вспомогательные действия прокручиваются вместе с результатами.
            LazyColumn(
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
                if (address.isBlank() && trimmed.length >= 2) item { TextButton(onClick = onSettings) { Text("Указать адрес магазина") } }
                if (state.failed && !state.loading) item { Text("Не удалось выполнить поиск. Сохранённые товары доступны.", color = p.error, fontSize = 13.sp) }
                if (!hasOtherResults && !state.loading && state.started && !state.hasMore && !state.failed && state.local.isEmpty() && state.online.isEmpty())
                    item { Text("Ничего не найдено", color = p.textSecondary, fontSize = 14.sp, modifier = Modifier.padding(12.dp)) }
                leadingResults()
                // In ranked mode avoid jumping rows and showing a provisional winner.
                if (!fullSort || !state.loading) items(resultRows, key = { it.key }) { row ->
                    val product = row.lenta
                    if (product != null) LentaResult(product, row, onClick = { onAdd(product) })
                    else customContent(row)
                }
                if (!fullSort && !onlineOnly && state.local.isNotEmpty() && !state.started && address.isNotBlank()) {
                    item { Text("Найти в Ленте", Modifier.clickable { controller.searchOnline() }.padding(12.dp), color = p.accent) }
                }
                if (fullSort && !state.loading && !state.complete) item {
                    Text(if (state.stalled) "Лента возвращает повторяющиеся страницы. Результат неполный."
                        else if (state.failed) "Не все страницы загружены. Результат неполный."
                        else "Ожидаем загрузку всех страниц.",
                        color = p.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp))
                }
                if (state.loading && !fullSort) item {
                    val loadingSize = Modifier.height(56.dp)
                    Box(Modifier.fillMaxWidth().then(loadingSize),
                        contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = p.textSecondary)
                    }
                }
                if ((state.hasMore || state.failed) && !state.loading) {
                    item { Text(if (state.failed) "Повторить" else if (fullSort) "Продолжить загрузку" else "Показать ещё",
                        Modifier.clickable { controller.searchOnline() }.padding(12.dp), color = p.accent) }
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
                Text(product.name, color = p.text, fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
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

@Composable
fun LentaImage(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, url) {
        value = null
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (!url.startsWith("https://")) return@runCatching null
                val key = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
                val file = java.io.File(context.cacheDir, "lenta_$key")
                if (!file.exists()) {
                    val c = URL(url).openConnection() as HttpURLConnection
                    try {
                        c.connectTimeout = 8000; c.readTimeout = 8000
                        if (c.responseCode != 200) error("image")
                        val bytes = c.inputStream.use { it.readBytes() }
                        if (bytes.size <= 2_000_000) file.writeBytes(bytes) else error("image too large")
                    } finally { c.disconnect() }
                }
                BitmapFactory.decodeFile(file.path)
            }.getOrNull()
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
