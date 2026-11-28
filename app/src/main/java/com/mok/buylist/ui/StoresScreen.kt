package com.mok.buylist.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mok.buylist.R
import com.mok.buylist.data.ArchivedList
import com.mok.buylist.data.ShoppingItem
import com.mok.buylist.data.ShoppingStore
import com.mok.buylist.data.lentaStoreId
import com.mok.buylist.data.parsePriceKopecks
import com.mok.buylist.ui.theme.LocalPalette
import com.mok.buylist.lentaCatalog
import androidx.compose.ui.platform.LocalContext
import java.math.BigDecimal

private data class StoreProductLine(
    val key: String,
    val name: String,
    val purchasedAt: Long?,
    val price: Long?,
    val weighted: Boolean,
)

/** Stores are a persistent directory. Prices are derived from immutable purchase events. */
@Composable
internal fun StoresScreen(
    viewModel: ShoppingViewModel,
    archive: List<ArchivedList>,
    bought: List<ShoppingItem>,
    onProductClick: (String) -> Unit,
    onDetailChange: (Boolean) -> Unit = {},
) {
    val p = LocalPalette.current
    val stores by viewModel.stores.collectAsStateWithLifecycle()
    val custom by viewModel.customProducts.collectAsStateWithLifecycle()
    val customCodes by viewModel.customBarcodes.collectAsStateWithLifecycle()
    val storeLinks by viewModel.storeProducts.collectAsStateWithLifecycle()
    val catalog = LocalContext.current.lentaCatalog
    val lenta by catalog.products.collectAsStateWithLifecycle()
    val address by catalog.address.collectAsStateWithLifecycle()
    val lentaCodes by catalog.barcodeRows.collectAsStateWithLifecycle()
    val products = remember(archive, bought, lenta, custom, lentaCodes, customCodes) {
        mergeCatalogPriceHistory(
            expensePurchases(archive, bought).map { receipt ->
                if (receipt.storeId.isBlank() && receipt.productKey.startsWith("lenta:"))
                    receipt.copy(storeId = lentaStoreId()) else receipt
            }, custom, lenta, lentaCodes, customCodes)
    }
    val identities = remember(lenta, custom, lentaCodes, customCodes) { com.mok.buylist.data.sharedCatalogIdentities(lenta, custom, lentaCodes, customCodes) }
    var openedStore by rememberSaveable { mutableStateOf<String?>(null) }
    var productQuery by remember(openedStore) { mutableStateOf("") }
    var shown by remember(openedStore, productQuery.trim()) { mutableIntStateOf(8) }
    val productListState = rememberLazyListState()
    LaunchedEffect(openedStore, productQuery.trim()) {
        productListState.scrollToItem(0)
    }
    var editing by remember { mutableStateOf<ShoppingStore?>(null) }
    var creating by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ShoppingStore?>(null) }
    var shopName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var choosingProduct by remember { mutableStateOf(false) }
    var creatingStoreProduct by remember { mutableStateOf(false) }
    var chosenProductKey by remember { mutableStateOf<String?>(null) }
    var choiceQuery by remember { mutableStateOf("") }
    var choiceShown by remember(choiceQuery, openedStore) { mutableIntStateOf(30) }
    var linkPrice by remember { mutableStateOf("") }
    var linkError by remember { mutableStateOf<String?>(null) }
    var removingLink by remember { mutableStateOf<String?>(null) }
    val active = stores.filterNot { it.deleted }.distinctBy { it.id }.sortedBy { it.nameKey }
    val store = active.firstOrNull { it.id == openedStore }
    val currentProducts = remember(lenta, custom, storeLinks, identities, active) {
        val lentaKeys = lenta.mapTo(HashSet()) { "lenta:${it.id}" }
        val activeKeys = lentaKeys + custom.filterNot { it.deleted }.map { "custom:${it.id}" }
        active.associate { shop ->
            shop.id to currentStoreProductKeys(shop.id, storeLinks, activeKeys, lentaKeys, identities)
        }
    }
    LaunchedEffect(store?.id) { onDetailChange(store != null) }
    BackHandler(enabled = store != null && !choosingProduct && !creatingStoreProduct) { openedStore = null }

    Column(Modifier.fillMaxSize().background(p.background)) {
        if (store != null) {
            SubScreenHeader(store.name, { openedStore = null })
            val storeCount = currentProducts[store.id].orEmpty().size
            Text(productCountLabel(storeCount),
                Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp), color = p.textSecondary, fontSize = 13.sp)
        }
        if (store == null) {
            if (active.isEmpty()) {
                FreshCutEmptyState(R.drawable.ic_store, stringResource(R.string.empty_stores_title),
                    modifier = Modifier.weight(1f), description = stringResource(R.string.empty_stores_description))
            } else LazyColumn(
                state = productListState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(active, key = { it.id }) { shop ->
                    val productCount = currentProducts[shop.id].orEmpty().size
                    var menu by remember { mutableStateOf(false) }
                    FreshCutListRow(R.drawable.ic_store, shop.name, productCountLabel(productCount),
                        onClick = { openedStore = shop.id }, trailing = {
                            if (shop.id != lentaStoreId()) androidx.compose.foundation.layout.Box {
                                IconButton(onClick = { menu = true }) {
                                    Icon(painterResource(R.drawable.ic_more), "Действия с магазином", tint = p.textSecondary)
                                }
                                AppDropdownMenu(menu, { menu = false }) {
                                    AppDropdownMenuItem(text = { Text("Переименовать") }, onClick = {
                                        menu = false; editing = shop; shopName = shop.name; error = null
                                    })
                                    AppDropdownMenuItem(text = { Text("Удалить", color = p.error) },
                                        onClick = { menu = false; pendingDelete = shop })
                                }
                            } else Icon(painterResource(R.drawable.ic_chevron_down), null, Modifier.size(20.dp)
                                .rotate(-90f), tint = p.textSecondary)
                        })
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                SheetActionButton(onClick = { shopName = ""; error = null; creating = true }) { Text("Добавить магазин") }
            }
        } else {
            val entries = remember(store.id, currentProducts, products, lenta, custom, storeLinks, address, identities, stores) {
                val history = products.filter { it.storeId == store.id }
                    .groupBy { identities[it.productKey] ?: it.productKey }
                val catalogByKey = lenta.associateBy { "lenta:${it.id}" }
                val customByKey = custom.filterNot { it.deleted }.associateBy { "custom:${it.id}" }
                currentProducts[store.id].orEmpty().mapNotNull { key ->
                    val own = customByKey[key]
                    val catalogItem = catalogByKey[key]
                    val name = own?.name ?: catalogItem?.name ?: return@mapNotNull null
                    val purchases = history[identities[key] ?: key].orEmpty()
                    val latest = purchases.maxByOrNull { it.at }
                    val price = productShopPrices(key, stores, storeLinks, products, identities, lenta, address)
                        .firstOrNull { it.storeId == store.id }?.priceKopecks
                    StoreProductLine(key, name, latest?.at, price,
                        own?.isWeighted ?: (latest?.weightGrams != null))
                }.sortedWith(compareByDescending<StoreProductLine> { it.purchasedAt != null }
                    .thenByDescending { it.purchasedAt ?: 0L }
                    .thenBy { it.name.lowercase() })
            }
            val visibleEntries = remember(entries, productQuery) {
                entries.filter { it.name.contains(productQuery.trim(), ignoreCase = true) }
            }
            ProductSearch(productQuery, { productQuery = it }, Modifier.padding(horizontal = 20.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                FreshCutSectionTitle("Товары", Modifier.weight(1f))
                FreshCutToolbarAction(R.drawable.ic_plus, "Добавить товар", {
                    choosingProduct = true; chosenProductKey = null; choiceQuery = ""; linkPrice = ""; linkError = null
                }, primary = true)
            }
            if (visibleEntries.isEmpty()) {
                FreshCutEmptyState(
                    icon = if (productQuery.isBlank()) R.drawable.ic_package else R.drawable.ic_search,
                    title = stringResource(if (productQuery.isBlank()) R.string.empty_catalog_title else R.string.empty_store_search_title),
                    modifier = Modifier.weight(1f),
                    description = stringResource(if (productQuery.isBlank()) R.string.empty_store_products_description else R.string.empty_store_search_description),
                )
            } else LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(visibleEntries.take(shown), key = { it.key }) { entry ->
                    val catalogItem = lenta.firstOrNull { "lenta:${it.id}" == entry.key }
                    val purchasedAt = entry.purchasedAt?.let {
                        java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                            .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))
                    }
                    var menu by remember { mutableStateOf(false) }
                    FreshCutListRow(R.drawable.ic_package, entry.name,
                        listOfNotNull(if (store.id == lentaStoreId() && storeLinks.any { !it.deleted && it.storeId == store.id &&
                            (identities[it.productKey] ?: it.productKey) == (identities[entry.key] ?: entry.key) && it.priceKopecks != null }) "Ручная цена" else null,
                            if (entry.weighted) "Цена за кг" else null).joinToString(" · "),
                        entry.price?.let { formatUiMoney(it) },
                        onClick = { onProductClick(entry.key) },
                        leading = {
                            if (entry.key.startsWith("custom:")) CustomProductImage(entry.key.removePrefix("custom:"), Modifier.size(44.dp))
                            else LentaImage(catalogItem?.image.orEmpty(), Modifier.size(44.dp))
                        },
                        trailing = {
                            val attached = storeLinks.any { !it.deleted && it.storeId == store.id &&
                                (identities[it.productKey] ?: it.productKey) == (identities[entry.key] ?: entry.key) }
                            androidx.compose.foundation.layout.Box {
                                IconButton(onClick = { menu = true }, modifier = Modifier.size(UiMetrics.touchTarget)) {
                                    Icon(painterResource(R.drawable.ic_more), "Действия с ценой", Modifier.size(20.dp), tint = p.textSecondary)
                                }
                                AppDropdownMenu(menu, { menu = false }) {
                                    AppDropdownMenuItem(text = { Text("Изменить цену") }, onClick = {
                                        menu = false; chosenProductKey = entry.key
                                        linkPrice = entry.price?.let { BigDecimal.valueOf(it, 2).stripTrailingZeros().toPlainString() }.orEmpty()
                                        choiceQuery = ""; linkError = null; choosingProduct = true
                                    })
                                    if (store.id == lentaStoreId() && storeLinks.any { !it.deleted && it.storeId == store.id && (identities[it.productKey] ?: it.productKey) == (identities[entry.key] ?: entry.key) && it.priceKopecks != null }) AppDropdownMenuItem(text = { Text("Сбросить ручную цену") }, onClick = {
                                        menu = false; viewModel.saveStoreProduct(store.id, entry.key, null) { issue -> linkError = issue }
                                    })
                                    if (attached && !(store.id == lentaStoreId() && entry.key.startsWith("lenta:"))) AppDropdownMenuItem(text = { Text("Убрать из магазина", color = p.error) },
                                        onClick = { menu = false; removingLink = entry.key })
                                }
                            }
                        })
                }
                if (shown < visibleEntries.size) item {
                    FreshCutSecondaryButton("Показать ещё", { shown += 8 })
                }
            }
        }
    }

    if (choosingProduct && store != null) {
        val chosen = chosenProductKey
        val options = remember(custom, lenta, choiceQuery, storeLinks, store.id) {
            (custom.filterNot { it.deleted }.map { "custom:${it.id}" to it.name } +
                lenta.map { "lenta:${it.id}" to it.name })
                .distinctBy { it.first }
                .filter { it.second.contains(choiceQuery.trim(), ignoreCase = true) }
                .sortedBy { it.second.lowercase() }
        }
        val amount = if (linkPrice.isBlank()) null else parsePriceKopecks(linkPrice)
        AppSheet(
            onDismissRequest = { choosingProduct = false },
            containerColor = p.sheet,
            title = { SheetHeader(if (chosen == null) "Товар магазина" else "Цена в магазине") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (chosen == null) {
                        SheetTextField(choiceQuery, { choiceQuery = it }, "Найти товар в базе")
                        FreshCutSecondaryButton("Создать свой товар", {
                            choosingProduct = false
                            creatingStoreProduct = true
                        })
                        if (options.isEmpty()) {
                            if (choiceQuery.isNotBlank()) {
                                FreshCutEmptyState(
                                    icon = R.drawable.ic_search,
                                    title = stringResource(R.string.empty_store_search_title),
                                    description = stringResource(R.string.empty_store_search_description),
                                    fillAvailable = false,
                                )
                            }
                        } else LazyColumn(
                            Modifier.fillMaxWidth().heightIn(max = 270.dp),
                            verticalArrangement = Arrangement.spacedBy(UiMetrics.gap),
                        ) {
                            items(options.take(choiceShown), key = { it.first }) { (key, name) ->
                                val existing = storeLinks.firstOrNull { !it.deleted && it.storeId == store.id && it.productKey == key }
                                    ?: storeLinks.firstOrNull { !it.deleted && it.storeId == store.id &&
                                        (identities[it.productKey] ?: it.productKey) == (identities[key] ?: key) }
                                FreshCutListRow(
                                    icon = R.drawable.ic_package,
                                    title = name,
                                    subtitle = if (existing != null) stringResource(R.string.store_product_linked) else "",
                                    value = existing?.priceKopecks?.let(::formatUiMoney),
                                    onClick = {
                                        chosenProductKey = key
                                        linkPrice = existing?.priceKopecks?.let { BigDecimal.valueOf(it, 2).stripTrailingZeros().toPlainString() }.orEmpty()
                                        linkError = null
                                    },
                                    showChevron = false,
                                    trailing = {
                                        Icon(painterResource(if (existing != null) R.drawable.ic_check else R.drawable.ic_plus),
                                            contentDescription = null, tint = p.text, modifier = Modifier.size(20.dp))
                                    },
                                )
                            }
                            if (options.size > choiceShown) item {
                                FreshCutSecondaryButton("Показать ещё", { choiceShown += 30 })
                            }
                        }
                    } else {
                        val label = (custom.firstOrNull { "custom:${it.id}" == chosen }?.name
                            ?: lenta.firstOrNull { "lenta:${it.id}" == chosen }?.name).orEmpty()
                        Text(label, color = p.text, fontWeight = FontWeight.Bold)
                        SheetTextField(linkPrice, { linkPrice = it; linkError = null },
                            "Цена, ₽ (необязательно)")
                        if (store.id == lentaStoreId()) Text("Ручная цена не меняется при обновлении каталога. Очистите поле, чтобы вернуть цену каталога.", color = p.textSecondary, fontSize = 12.sp)
                        FreshCutSecondaryButton("Выбрать другой товар", { chosenProductKey = null })
                    }
                    linkError?.let { Text(it, color = p.error) }
                }
            },
            confirmButton = if (chosen != null) { {
                SheetActionButton(
                    enabled = !saving && (linkPrice.isBlank() || amount != null),
                    onClick = {
                        saving = true
                        viewModel.saveStoreProduct(store.id, chosen, amount) { issue ->
                            saving = false
                            if (issue == null) choosingProduct = false
                            else linkError = issue
                        }
                    },
                ) { Text("Сохранить") }
            } } else null,
        )
    }
    if (creatingStoreProduct && store != null) {
        CustomProductCreationSheet(
            onCreate = { name, barcode, price, weighted, onResult ->
                viewModel.createCustomForStore(store.id, name, barcode, price, weighted, onResult)
            },
            onDismiss = { creatingStoreProduct = false },
            onCreated = { creatingStoreProduct = false },
        )
    }

    if (removingLink != null && store != null) {
        val key = removingLink!!
        AppSheet(
            onDismissRequest = { removingLink = null },
            containerColor = p.sheet,
            title = { SheetHeader("Убрать товар из магазина?") },
            text = { Text("Товар останется в общей базе и в истории покупок.", color = p.textSecondary) },
            confirmButton = {
                SheetActionButton(destructive = true, onClick = {
                    viewModel.removeStoreProduct(store.id, key) { removingLink = null }
                }) { Text("Убрать") }
            },
        )
    }

    if (creating || editing != null) {
        val current = editing
        AppSheet(
            onDismissRequest = { creating = false; editing = null },
            containerColor = p.sheet,
            title = { SheetHeader(if (current == null) "Новый магазин" else "Название магазина") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SheetTextField(shopName, { shopName = it; error = null }, "Название магазина", placeholder = "Введите название")
                    error?.let { Text(it, color = p.error, fontSize = 13.sp) }
                }
            },
            confirmButton = {
                SheetActionButton(enabled = !saving && shopName.isNotBlank() &&
                    shopName.length <= 100, onClick = {
                    saving = true
                    if (current == null) {
                        viewModel.createStore(shopName) { made ->
                            saving = false
                            if (made == null) error = "Не удалось сохранить магазин"
                            else { creating = false; openedStore = made.id }
                        }
                    } else {
                        viewModel.renameStore(current.id, shopName) { result ->
                            saving = false
                            if (result != null) error = result
                            else editing = null
                        }
                    }
                }) { Text("Сохранить") }
            },
        )
    }
    pendingDelete?.let { shop ->
        AppSheet(
            onDismissRequest = { pendingDelete = null },
            containerColor = p.sheet,
            title = { SheetHeader("Удалить магазин?") },
            text = {
                Text("«${shop.name}» исчезнет из выбора. История покупок сохранится.",
                    color = p.textSecondary)
            },
            confirmButton = {
                SheetActionButton(destructive = true, onClick = {
                    viewModel.deleteStore(shop.id) { message ->
                        pendingDelete = null
                        if (message != null) error = message
                        if (openedStore == shop.id) openedStore = null
                    }
                }) { Text("Удалить") }
            },
        )
    }
}
