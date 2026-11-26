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
        if (rows.isEmpty() && ownRows.isEmpty()) Text(if (query.isBlank()) "Сохранённых товаров пока нет" else "Ничего не найдено", Modifier.fillMaxWidth().padding(32.dp), color = p.textSecondary, fontSize = 16.sp, textAlign = TextAlign.Center)
        CreateCustomProductAction(onClick = {
            customName = query.takeUnless { it.all(Char::isDigit) }.orEmpty()
            customBarcode = query.takeIf { it.all(Char::isDigit) }.orEmpty()
            createCustom = true
        }, modifier = Modifier.padding(horizontal = 16.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ownRows.take(shownCount), key = { "custom:${it.id}" }) { product ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.card)
                    .clickable(role = Role.Button) { onProductClick("custom:${product.id}") }
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    CustomProductImage(product.id, Modifier.size(42.dp))
                    androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                        Text(product.name, color = p.text, fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold)
                        if (product.isWeighted) Text("Весовой · ₽/кг",
                            color = p.textSecondary, fontSize = 12.sp)
                        val shopPrices = productShopPrices(
                            "custom:${product.id}", stores, storeLinks, knownPurchases, identities)
                        if (shopPrices.isNotEmpty()) Text(
                            shopPrices.joinToString(" · ") { it.storeName },
                            color = p.textSecondary, fontSize = 12.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val cheapest = cheapestShopPrice(shopPrices)
                        val bestPrice = cheapest?.priceKopecks ?: product.estimatedPriceKopecks
                        if (bestPrice != null) {
                            Text(
                                (if (cheapest != null) "От " else "Ориентир: ") +
                                    java.math.BigDecimal.valueOf(bestPrice, 2)
                                        .stripTrailingZeros().toPlainString() + " ₽" +
                                    if (cheapest != null) " · ${cheapest.storeName}" else "",
                                color = p.accent, fontSize = 13.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    IconButton(onClick = { viewModel.addCustomProduct(product) { added ->
                        activity?.notifyUser(if (added) "Добавлено в список" else "Уже в списке")
                    } }) {
                        Icon(painterResource(R.drawable.ic_plus), "Добавить в список", tint = p.accent,
                            modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { pendingCustomDelete = product }) {
                        Icon(painterResource(R.drawable.ic_trash), "Удалить свой товар",
                            tint = p.iconMuted, modifier = Modifier.size(20.dp))
                    }
                }
            }
            items(rows.take(shownCount), key = { "lenta:${it.id}" }) { product ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.card)
                    .clickable(role = Role.Button) { onProductClick("lenta:${product.id}") }
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    LentaImage(product.image, Modifier.size(42.dp))
                    androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                        Text(product.name, color = p.text, fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        val linked = storeLinks.filter { !it.deleted &&
                            it.productKey == "lenta:${product.id}" }
                        if (linked.isNotEmpty()) Text(linked.take(2).joinToString(" · ") { row ->
                            val shop = stores.firstOrNull { it.id == row.storeId }?.name ?: "Магазин"
                            shop + (row.priceKopecks?.let { price ->
                                ": " + java.math.BigDecimal.valueOf(price, 2)
                                    .stripTrailingZeros().toPlainString() + " ₽"
                            } ?: "")
                        }, color = p.textSecondary, fontSize = 12.sp)
                    }
                    IconButton(onClick = { onAdd(product) }) {
                        Icon(painterResource(R.drawable.ic_plus), "Добавить в список", tint = p.accent, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { pendingDelete = product }) {
                        Icon(painterResource(R.drawable.ic_trash), stringResource(R.string.products_delete), tint = p.iconMuted, modifier = Modifier.size(20.dp))
                    }
                }
            }
            if (shownCount < rows.size || shownCount < ownRows.size) item {
                Text("Показать ещё", Modifier.clickable { shownCount += 20 }.padding(12.dp), color = p.accent)
            }
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
    pendingCustomDelete?.let { product ->
        AppSheet(
            onDismissRequest = { pendingCustomDelete = null },
            containerColor = p.sheet,
            title = { SheetHeader("Удалить товар?") },
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
