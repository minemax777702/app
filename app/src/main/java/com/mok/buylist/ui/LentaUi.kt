package com.mok.buylist.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
fun LentaSuggestions(query: String, modifier: Modifier = Modifier,
    onAdd: (LentaProduct) -> Unit, onSettings: () -> Unit,
    onlineOnly: Boolean = false, hasOtherResults: Boolean = false) {
    val catalog = LocalContext.current.lentaCatalog
    val address by catalog.address.collectAsStateWithLifecycle()
    val saved by catalog.products.collectAsStateWithLifecycle()
    val barcodeRows by catalog.barcodeRows.collectAsStateWithLifecycle()
    val ready by catalog.ready.collectAsStateWithLifecycle()
    val p = LocalPalette.current
    val trimmed = query.trim()
    val scope = rememberCoroutineScope()
    val controller = remember(catalog, scope) { LentaSearchController(catalog::search, scope, catalog::barcodeAliases) }
    val state by controller.state.collectAsStateWithLifecycle()
    val activity = androidx.activity.compose.LocalActivity.current as? MainActivity
    LaunchedEffect(state.failed) {
        if (state.failed) activity?.notifyUser("Не удалось выполнить поиск в Ленте")
    }
    LaunchedEffect(trimmed, address, saved, barcodeRows, onlineOnly, ready) {
        if (ready) controller.update(trimmed, address, saved, onlineOnly)
    }
    Column(modifier.fillMaxWidth().padding(top = 12.dp)) {
        if (trimmed.length >= 2) {
            if (address.isBlank()) TextButton(onClick = onSettings) { Text("Указать адрес") }
            if (!hasOtherResults && !state.loading && state.started && !state.failed && state.local.isEmpty() && state.online.isEmpty())
                Text("Ничего не найдено", color = p.textSecondary, fontSize = 13.sp)
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.local, key = { "local:${it.id}" }) { product ->
                    LentaResult(product, address, onClick = { onAdd(product) })
                }
                if (!onlineOnly && state.local.isNotEmpty() && !state.started && address.isNotBlank()) {
                    item { Text("Найти в Ленте", Modifier.clickable { controller.searchOnline() }.padding(12.dp), color = p.accent) }
                }
                items(state.online, key = { "online:${it.id}" }) { product ->
                    LentaResult(product, address, onClick = { onAdd(product) })
                }
                if (state.loading) item {
                    val loadingSize = if (state.local.isEmpty() && state.online.isEmpty()) Modifier.fillParentMaxHeight() else Modifier.height(80.dp)
                    Box(Modifier.fillMaxWidth().then(loadingSize),
                        contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(42.dp), strokeWidth = 4.dp, color = p.accent)
                    }
                }
                if ((state.hasMore || state.failed) && !state.loading) {
                    item { Text(if (state.failed) "Повторить" else "Показать ещё",
                        Modifier.clickable { controller.searchOnline() }.padding(12.dp), color = p.accent) }
                }
            }
        }
    }
}

@Composable
private fun LentaResult(product: LentaProduct, address: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.card).clickable(onClick = onClick).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LentaImage(product.image, Modifier.size(52.dp))
            Column(Modifier.weight(1f)) {
                Text(product.name, color = p.text, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val price = product.price.takeIf { product.address == address && product.channel == "locc" && it.isNotBlank() && it != "null" }
                val details = listOfNotNull(price?.let { "$it ₽" }, product.pack.takeIf { it.isNotBlank() }).joinToString(" · ")
                if (details.isNotEmpty()) Text(details, color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                product.stock?.let { Text(if (it > 0) "В наличии: $it" else "Нет в наличии", color = p.textSecondary, fontSize = 11.sp) }
            }
        }
    }
}

fun lentaPrice(product: LentaProduct): String = if (product.price.isBlank() || product.price == "null") "Цена неизвестна" else
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
