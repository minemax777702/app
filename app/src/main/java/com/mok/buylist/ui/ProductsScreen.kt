package com.mok.buylist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.mok.buylist.data.validProductBarcode
import com.mok.buylist.data.nameKeyOf
import com.mok.buylist.ui.theme.LocalPalette
import com.mok.buylist.ui.theme.Manrope
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ProductsScreen(onBack: () -> Unit, onAdd: (LentaProduct) -> Unit, onAddressSettings: () -> Unit, viewModel: ShoppingViewModel,
    onProductClick: (String) -> Unit) {
    val p = LocalPalette.current
    val catalog = LocalContext.current.lentaCatalog
    val activity = androidx.activity.compose.LocalActivity.current as? MainActivity
    val scope = rememberCoroutineScope()
    val products by catalog.products.collectAsStateWithLifecycle()
    val address by catalog.address.collectAsStateWithLifecycle()
    val customProducts by viewModel.customProducts.collectAsStateWithLifecycle()
    val stores by viewModel.stores.collectAsStateWithLifecycle()
    val storeLinks by viewModel.storeProducts.collectAsStateWithLifecycle()
    val archive by viewModel.archive.collectAsStateWithLifecycle()
    val bought by viewModel.bought.collectAsStateWithLifecycle()
    val knownPurchases = remember(archive, bought) { expensePurchases(archive, bought) }
    val barcodeRows by catalog.barcodeRows.collectAsStateWithLifecycle()
    val customBarcodeRows by viewModel.customBarcodes.collectAsStateWithLifecycle()
    val batch by viewModel.batchPriceRefresh.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(batch) {
        val result = batch
        if (result != null && !result.running) {
            activity?.notifyUser(if (result.failed == 0) "Цены обновлены" else "Не обновлено: ${result.failed} из ${result.total}")
            viewModel.batchPriceRefresh.value = null
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var catalogMenu by remember { mutableStateOf(false) }
    var onlineQuery by rememberSaveable { mutableStateOf("") }
    var adding by rememberSaveable { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var scanningOnline by remember { mutableStateOf(false) }
    var scannedOnlineCode by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<LentaProduct?>(null) }
    var pendingCustomDelete by remember { mutableStateOf<com.mok.buylist.data.CustomProduct?>(null) }
    var createCustom by remember { mutableStateOf(false) }
    var customName by remember { mutableStateOf("") }
    var customBarcode by remember { mutableStateOf("") }
    var shownCount by rememberSaveable(query) { mutableStateOf(20) }
    val rows = remember(products, query, barcodeRows) {
        val key = nameKeyOf(query)
        products.filter { key.isEmpty() || nameKeyOf(it.name).contains(key) || nameKeyOf(it.pack).contains(key) ||
            catalog.barcodeAliases(it.id).any { alias -> alias.contains(key) ||
                com.mok.buylist.sync.possibleVariableBarcodeMatch(alias, key) } }
            .sortedWith(compareBy<LentaProduct> { nameKeyOf(it.name) }.thenBy { it.id })
    }
    val identities = remember(products, customProducts, barcodeRows, customBarcodeRows) {
        com.mok.buylist.data.sharedCatalogIdentities(
            products, customProducts, barcodeRows, customBarcodeRows)
    }
    val ownRows = remember(customProducts, customBarcodeRows, query) {
        val key = nameKeyOf(query)
        customProducts.filter { !it.deleted &&
            (key.isBlank() || nameKeyOf(it.name).contains(key) || customBarcodeRows.any { row -> !row.deleted && row.productId == it.id && row.code.contains(key) }) }
            .sortedBy { nameKeyOf(it.name) }
    }
    Column(Modifier.fillMaxSize().background(p.background)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            ProductSearch(query, { query = it }, Modifier.weight(1f), trailing = {
                Box {
                    IconButton(onClick = { catalogMenu = true }) {
                        Icon(painterResource(R.drawable.ic_more), "Действия каталога", tint = p.text, modifier = Modifier.size(24.dp))
                    }
                    AppDropdownMenu(expanded = catalogMenu, onDismissRequest = { catalogMenu = false }) {
                        AppDropdownMenuItem(text = { Text("Сканировать штрихкод") }, onClick = { catalogMenu = false; scanning = true })
                        AppDropdownMenuItem(text = { Text("Найти в Ленте") }, onClick = { catalogMenu = false; onlineQuery = query; adding = true })
                        AppDropdownMenuItem(text = { Text("Создать свой товар") }, onClick = {
                            catalogMenu = false
                            customName = query.takeUnless { it.all(Char::isDigit) }.orEmpty()
                            customBarcode = query.takeIf { it.all(Char::isDigit) }.orEmpty()
                            createCustom = true
                        })
                    }
                }
            })
        }
        if (products.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${ownRows.size + rows.size} товаров", color = p.textSecondary, fontSize = 12.sp)
            TextButton(onClick = viewModel::refreshAllPrices, enabled = batch?.running != true) {
                Icon(painterResource(R.drawable.ic_sync), null, Modifier.size(18.dp), tint = p.text)
                Text(if (batch?.running == true) "  ${batch!!.completed}/${batch!!.total}" else "  Обновить цены", color = p.text)
            }
        }
        if (rows.isEmpty() && ownRows.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.ic_package), null, Modifier.size(40.dp), tint = p.textSecondary)
                Text(if (query.isBlank()) "Сохранённых товаров пока нет" else "Ничего не найдено",
                    color = p.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Text(if (query.isBlank()) "Добавьте товар, чтобы начать." else "Попробуйте другое название или штрихкод.",
                    color = p.textSecondary, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ownRows.take(shownCount), key = { "custom:${it.id}" }) { product ->
                val prices = productShopPrices("custom:${product.id}", stores, storeLinks, knownPurchases, identities, products, address)
                val best = cheapestShopPrice(prices)?.priceKopecks ?: product.estimatedPriceKopecks
                val subtitle = (listOfNotNull(if (product.isWeighted) "Весовой" else null) + prices.map { it.storeName })
                    .joinToString(" · ").ifBlank { "Свой товар" }
                var menu by remember { mutableStateOf(false) }
                FreshCutListRow(R.drawable.ic_package, product.name, subtitle,
                    value = best?.let { java.math.BigDecimal.valueOf(it, 2).stripTrailingZeros().toPlainString() + " ₽" },
                    onClick = { onProductClick("custom:${product.id}") },
                    leading = { CustomProductImage(product.id, Modifier.size(44.dp)) },
                    trailing = {
                        Box {
                            IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                                Icon(painterResource(R.drawable.ic_more), "Действия с товаром", Modifier.size(20.dp), tint = p.textSecondary)
                            }
                            AppDropdownMenu(menu, { menu = false }) {
                                AppDropdownMenuItem(text = { Text("Добавить в список") }, onClick = {
                                    menu = false
                                    viewModel.addCustomProduct(product) { added ->
                                        activity?.notifyUser(if (added) "Добавлено в список" else "Уже в списке")
                                    }
                                })
                                AppDropdownMenuItem(text = { Text("Удалить товар", color = p.error) },
                                    onClick = { menu = false; pendingCustomDelete = product })
                            }
                        }
                    })
            }
            items(rows.take(shownCount), key = { "lenta:${it.id}" }) { product ->
                val prices = productShopPrices("lenta:${product.id}", stores, storeLinks, knownPurchases, identities, products, address)
                val value = displayShopPrice("lenta:${product.id}", prices)?.priceKopecks ?: product.takeIf { address.isNotBlank() && it.address == address && it.channel == "locc" }?.let { com.mok.buylist.data.parsePriceKopecks(it.price) }
                val subtitle = (listOf("Лента") + prices.map { it.storeName }).distinct().joinToString(" · ")
                var menu by remember { mutableStateOf(false) }
                FreshCutListRow(R.drawable.ic_package, product.name, subtitle,
                    value = value?.let { java.math.BigDecimal.valueOf(it, 2).stripTrailingZeros().toPlainString() + " ₽" },
                    onClick = { onProductClick("lenta:${product.id}") },
                    leading = { LentaImage(product.image, Modifier.size(44.dp)) },
                    trailing = {
                        Box {
                            IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                                Icon(painterResource(R.drawable.ic_more), "Действия с товаром", Modifier.size(20.dp), tint = p.textSecondary)
                            }
                            AppDropdownMenu(menu, { menu = false }) {
                                AppDropdownMenuItem(text = { Text("Добавить в список") }, onClick = { menu = false; onAdd(product) })
                                AppDropdownMenuItem(text = { Text("Удалить товар", color = p.error) },
                                    onClick = { menu = false; pendingDelete = product })
                            }
                        }
                    })
            }
            if (shownCount < rows.size || shownCount < ownRows.size) item {
                FreshCutSecondaryButton("Показать ещё", { shownCount += 20 })
            }
        }
        if (rows.isEmpty() && ownRows.isEmpty()) Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            SheetActionButton(onClick = {
                customName = query.takeUnless { it.all(Char::isDigit) }.orEmpty()
                customBarcode = query.takeIf { it.all(Char::isDigit) }.orEmpty()
                createCustom = true
            }) { Text("Создать свой товар") }
        }
    }
    if (createCustom) {
        CustomProductCreationSheet(
            initialName = customName,
            initialBarcode = customBarcode,
            onCreate = viewModel::addManualProduct,
            onChooseLenta = { product, onResult ->
                viewModel.addProduct(product) { added -> onResult(added) }
            },
            onDismiss = { createCustom = false },
            onCreated = {
                createCustom = false
                activity?.notifyUser("Товар добавлен в базу и список")
            },
        )
    }
    if (adding) {
        AppModalSheet(onDismissRequest = { adding = false }, containerColor = p.sheet,
            dragHandle = { SheetDragHandle { adding = false } }) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).imePadding().padding(horizontal = 20.dp)) {
                NoticeInSheet()
                SheetHeader("Добавить продукт")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProductSearch(onlineQuery, { onlineQuery = it; scannedOnlineCode = null }, Modifier.weight(1f))
                    IconButton(onClick = { scanningOnline = true }) {
                        Icon(painterResource(R.drawable.ic_camera), "Поиск по штрихкоду", tint = p.text)
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
    pendingCustomDelete?.let { product ->
        AppSheet(
            onDismissRequest = { pendingCustomDelete = null },
            containerColor = p.sheet,
            title = { SheetHeader("Удалить товар?") }, minBodyHeight = 230.dp,
            text = {
                Text("«${product.name}» исчезнет из базы продуктов. История покупок сохранится.",
                    color = p.textSecondary)
            },
            confirmButton = {
                SheetActionButton(destructive = true, onClick = {
                    viewModel.deleteCustomProduct(product.id) { deleted ->
                        pendingCustomDelete = null
                        if (!deleted) activity?.notifyUser("Не удалось удалить товар")
                    }
                }) { Text("Удалить") }
            },
        )
    }
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
internal fun ProductSearch(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null) {
    val p = LocalPalette.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = TextStyle(color = p.text, fontFamily = Manrope, fontSize = 15.sp, fontWeight = FontWeight.Medium),
        cursorBrush = SolidColor(p.accent),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Search),
        decorationBox = { inner ->
            Row(Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(16.dp))
                .background(p.card).border(1.dp, p.line, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(R.drawable.ic_search), null, Modifier.size(24.dp), tint = p.textSecondary)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(stringResource(R.string.products_search), color = p.textSecondary, fontSize = 15.sp)
                    inner()
                }
                trailing?.invoke()
            }
        },
    )
}
