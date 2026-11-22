package com.mok.buylist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mok.buylist.R
import com.mok.buylist.lentaCatalog
import com.mok.buylist.data.LentaProduct
import com.mok.buylist.data.nameKeyOf
import com.mok.buylist.ui.theme.LocalPalette
import com.mok.buylist.ui.theme.Manrope
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ProductsScreen(onBack: () -> Unit, onAdd: (LentaProduct) -> Unit, onAddressSettings: () -> Unit, viewModel: ShoppingViewModel,
    onProductClick: (Long) -> Unit) {
    val p = LocalPalette.current
    val catalog = LocalContext.current.lentaCatalog
    val activity = androidx.activity.compose.LocalActivity.current as? MainActivity
    val scope = rememberCoroutineScope()
    val products by catalog.products.collectAsStateWithLifecycle()
    val barcodeRows by catalog.barcodeRows.collectAsStateWithLifecycle()
    val batch by viewModel.batchPriceRefresh.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(batch) {
        val result = batch
        if (result != null && !result.running) {
            activity?.notifyUser(if (result.failed == 0) "Цены обновлены" else "Не обновлено: ${result.failed} из ${result.total}")
            viewModel.batchPriceRefresh.value = null
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var onlineQuery by rememberSaveable { mutableStateOf("") }
    var adding by rememberSaveable { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var scanningOnline by remember { mutableStateOf(false) }
    var scannedOnlineCode by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<LentaProduct?>(null) }
    var shownCount by rememberSaveable(query) { mutableStateOf(20) }
    val rows = remember(products, query, barcodeRows) {
        val key = nameKeyOf(query)
        products.filter { key.isEmpty() || nameKeyOf(it.name).contains(key) || nameKeyOf(it.pack).contains(key) ||
            catalog.barcodeAliases(it.id).any { alias -> alias.contains(key) ||
                com.mok.buylist.sync.possibleVariableBarcodeMatch(alias, key) } }
            .sortedWith(compareBy<LentaProduct> { nameKeyOf(it.name) }.thenBy { it.id })
    }
    Column(Modifier.fillMaxSize().background(p.background)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ProductSearch(query, { query = it }, Modifier.weight(1f))
            IconButton(onClick = { scanning = true }) {
                Icon(painterResource(R.drawable.ic_camera), "Поиск по штрихкоду", tint = p.accent)
            }
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(18.dp)).background(p.accent)
                .clickable(role = Role.Button) { onlineQuery = ""; adding = true }, contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_plus), stringResource(R.string.products_add), tint = p.onAccent)
            }
        }
        if (products.isNotEmpty()) TextButton(onClick = viewModel::refreshAllPrices, enabled = batch?.running != true,
            modifier = Modifier.padding(start = 8.dp)) {
            Text(if (batch?.running == true) "Обновление ${batch!!.completed}/${batch!!.total}" else "Обновить все цены")
        }
        if (rows.isEmpty()) Text(if (query.isBlank()) "Сохранённых товаров пока нет" else "Ничего не найдено", Modifier.fillMaxWidth().padding(32.dp), color = p.textSecondary, fontSize = 16.sp, textAlign = TextAlign.Center)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows.take(shownCount), key = { it.id }) { product ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.card)
                    .clickable(role = Role.Button) { onProductClick(product.id) }
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    LentaImage(product.image, Modifier.size(42.dp))
                    androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
                    Text(product.name, Modifier.weight(1f).padding(vertical = 12.dp), color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { onAdd(product) }) {
                        Icon(painterResource(R.drawable.ic_plus), "Добавить в список", tint = p.accent, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { pendingDelete = product }) {
                        Icon(painterResource(R.drawable.ic_trash), stringResource(R.string.products_delete), tint = p.iconMuted, modifier = Modifier.size(20.dp))
                    }
                }
            }
            if (shownCount < rows.size) item {
                Text("Показать ещё", Modifier.clickable { shownCount += 20 }.padding(12.dp), color = p.accent)
            }
        }
    }
    if (adding) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { adding = false }, containerColor = p.sheet,
            dragHandle = { SheetDragHandle { adding = false } }) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).imePadding().padding(horizontal = 20.dp)) {
                NoticeInSheet()
                SheetHeader("Добавить продукт")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProductSearch(onlineQuery, { onlineQuery = it; scannedOnlineCode = null }, Modifier.weight(1f))
                    IconButton(onClick = { scanningOnline = true }) {
                        Icon(painterResource(R.drawable.ic_camera), "Поиск по штрихкоду", tint = p.accent)
                    }
                }
                LentaSuggestions(onlineQuery, Modifier.weight(1f), onAdd = { product ->
                    scope.launch {
                        val chosen = if ((product.ean.isBlank() || product.ean == "null") && scannedOnlineCode == onlineQuery) product.copy(ean = onlineQuery) else product
                        catalog.save(chosen, scannedOnlineCode?.takeIf { it == onlineQuery })
                        adding = false
                        activity?.notifyUser("Товар сохранён")
                    }
                }, onSettings = { adding = false; onAddressSettings() }, onlineOnly = true)
            }
        }
    }
    if (scanning || scanningOnline) BarcodeScannerSheet(onDismiss = { scanning = false; scanningOnline = false }, onCode = { code ->
        if (scanningOnline) { onlineQuery = code; scannedOnlineCode = code } else query = code
        scanning = false; scanningOnline = false
    })
    pendingDelete?.let { product ->
        AppSheet(onDismissRequest = { pendingDelete = null }, containerColor = p.sheet,
            title = { Text(product.name, color = p.text, fontWeight = FontWeight.ExtraBold) },
            text = { Text(stringResource(R.string.products_delete_text), color = p.textSecondary) },
            confirmButton = { SheetActionButton(onClick = { scope.launch { catalog.removeById(product.id); pendingDelete = null } }, destructive = true) { Text(stringResource(R.string.products_delete), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold) } },
            dismissButton = { SheetActionButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold) } },
        )
    }
}

@Composable
internal fun ProductSearch(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = TextStyle(color = p.text, fontFamily = Manrope, fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
        cursorBrush = SolidColor(p.accent),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Search),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(p.field)
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(stringResource(R.string.products_search), color = p.textSecondary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                inner()
            }
        },
    )
}
