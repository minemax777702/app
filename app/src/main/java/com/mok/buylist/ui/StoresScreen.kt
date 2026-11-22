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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
) {
    val p = LocalPalette.current
    val stores by viewModel.stores.collectAsStateWithLifecycle()
    val custom by viewModel.customProducts.collectAsStateWithLifecycle()
    val customCodes by viewModel.customBarcodes.collectAsStateWithLifecycle()
    val storeLinks by viewModel.storeProducts.collectAsStateWithLifecycle()
    val catalog = LocalContext.current.lentaCatalog
    val lenta by catalog.products.collectAsStateWithLifecycle()
    val lentaCodes by catalog.barcodeRows.collectAsStateWithLifecycle()
    val products = remember(archive, bought, lenta, custom, lentaCodes, customCodes) {
        mergeCatalogPriceHistory(
            expensePurchases(archive, bought).map { receipt ->
                if (receipt.storeId.isBlank() && receipt.productKey.startsWith("lenta:"))
                    receipt.copy(storeId = lentaStoreId()) else receipt
            }, custom, lenta, lentaCodes, customCodes)
    }
    var openedStore by remember { mutableStateOf<String?>(null) }
    var shown by remember(openedStore) { mutableIntStateOf(8) }
    var productQuery by remember(openedStore) { mutableStateOf("") }
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
    BackHandler(enabled = store != null && !choosingProduct && !creatingStoreProduct) { openedStore = null }

    Column(Modifier.fillMaxSize().background(p.background)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(if (store == null) "Магазины" else store.name,
                Modifier.weight(1f), color = p.text, fontSize = 20.sp,
                fontWeight = FontWeight.Bold)
            if (store != null) {
                if (store.id != lentaStoreId()) IconButton(onClick = {
                    choosingProduct = true
                    chosenProductKey = null
                    choiceQuery = ""
                    linkPrice = ""
                    linkError = null
                }) {
                    Icon(painterResource(R.drawable.ic_plus), "Добавить товар в магазин",
                        tint = p.accent, modifier = Modifier.size(24.dp))
                }
                IconButton(onClick = { openedStore = null }) {
                    Icon(painterResource(R.drawable.ic_close), "Назад к магазинам",
                        tint = p.iconMuted, modifier = Modifier.size(21.dp))
                }
            } else {
                IconButton(onClick = { shopName = ""; error = null; creating = true }) {
                    Icon(painterResource(R.drawable.ic_plus), "Добавить магазин",
                        tint = p.accent, modifier = Modifier.size(24.dp))
                }
            }
        }
        if (store == null) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(active, key = { it.id }) { shop ->
                    val entries = products.filter { it.storeId == shop.id }
                    val linked = storeLinks.filter { it.storeId == shop.id && !it.deleted }
                    val productCount = (entries.map { it.productKey } + linked.map { it.productKey } +
                        if (shop.id == lentaStoreId()) lenta.map { "lenta:${it.id}" } else emptyList())
                        .distinct().size
                    Row(
                        Modifier.fillMaxWidth()
                            .background(p.card, RoundedCornerShape(18.dp))
                            .clickable { openedStore = shop.id }
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(painterResource(R.drawable.ic_store), null,
                            tint = p.accent, modifier = Modifier.size(28.dp))
                        Column(Modifier.weight(1f)) {
                            Text(shop.name, color = p.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text("Товаров: $productCount · Покупок: ${entries.size}",
                                color = p.textSecondary, fontSize = 12.sp)
                        }
                        if (shop.id != lentaStoreId()) {
                            IconButton(onClick = { editing = shop; shopName = shop.name; error = null }) {
                                Icon(painterResource(R.drawable.ic_edit), "Переименовать магазин",
                                    tint = p.iconMuted, modifier = Modifier.size(20.dp))
                            }
                            IconButton(onClick = { pendingDelete = shop }) {
                                Icon(painterResource(R.drawable.ic_trash), "Удалить магазин",
                                    tint = p.iconMuted, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
                if (active.isEmpty()) item {
                    Text("Добавьте первый магазин", color = p.textSecondary,
                        modifier = Modifier.padding(16.dp))
                }
            }
        } else {
            val entries = remember(store.id, products, lenta, custom, storeLinks) {
                val history = products.filter { it.storeId == store.id }
                    .groupBy { it.productKey }
                    .map { (key, purchases) ->
                        val latest = purchases.maxBy { it.at }
                        val known = purchases.filter { it.unitPrice != null }.maxByOrNull { it.at }
                        StoreProductLine(key, latest.name, latest.at, known?.unitPrice,
                            known?.weightGrams != null)
                    }
                val historyKeys = history.mapTo(HashSet()) { it.key }
                val fromLenta = if (store.id == lentaStoreId()) lenta.filter { item ->
                    "lenta:${item.id}" !in historyKeys
                }.map { item ->
                    StoreProductLine("lenta:${item.id}", item.name, null,
                        parsePriceKopecks(item.price), false)
                } else emptyList()
                val knownKeys = (history + fromLenta).mapTo(HashSet()) { it.key }
                val linked = storeLinks.filter { !it.deleted && it.storeId == store.id }
                    .mapNotNull { link ->
                        val match = custom.firstOrNull { "custom:${it.id}" == link.productKey && !it.deleted }
                        val catalogProduct = lenta.firstOrNull { "lenta:${it.id}" == link.productKey }
                        if (match == null && catalogProduct == null) null
                        else StoreProductLine(link.productKey, match?.name ?: catalogProduct!!.name,
                            null, link.priceKopecks, match?.isWeighted == true)
                    }
                val additions = linked.filter { it.key !in knownKeys }
                val updatedHistory = history.map { line ->
                    val manual = linked.firstOrNull { it.key == line.key }
                    if (manual != null && manual.price != null) line.copy(price = manual.price)
                    else line
                }
                (updatedHistory + fromLenta + additions).sortedWith(
                    compareByDescending<StoreProductLine> { it.purchasedAt != null }
                        .thenByDescending { it.purchasedAt ?: 0L }
                        .thenBy { it.name.lowercase() })
            }
            val visibleEntries = remember(entries, productQuery) {
                entries.filter { it.name.contains(productQuery.trim(), ignoreCase = true) }
            }
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                SheetTextField(productQuery, { productQuery = it }, "Поиск товаров")
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (visibleEntries.isEmpty()) item {
                    Text("Товары не найдены", color = p.textSecondary,
                        modifier = Modifier.padding(16.dp))
                }
                items(visibleEntries.take(shown), key = { it.key }) { entry ->
                    Column(
                        Modifier.fillMaxWidth()
                            .background(p.card, RoundedCornerShape(18.dp))
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().clickable { onProductClick(entry.key) },
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(entry.name, color = p.text, fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text(entry.purchasedAt?.let { at ->
                                    "Покупка: " +
                                        java.time.Instant.ofEpochMilli(at).atZone(
                                            java.time.ZoneId.systemDefault()).toLocalDate()
                                            .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))
                                } ?: "", color = p.textSecondary, fontSize = 12.sp)
                            }
                            Text(entry.price?.let {
                                BigDecimal.valueOf(it, 2).stripTrailingZeros().toPlainString() + " ₽" +
                                    if (entry.weighted) "/кг" else ""
                            } ?: "—", color = p.accent,
                                fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                maxLines = 2)
                        }
                        if (store.id != lentaStoreId()) {
                            val attached = storeLinks.any {
                                !it.deleted && it.storeId == store.id && it.productKey == entry.key
                            }
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(onClick = {
                                    chosenProductKey = entry.key
                                    linkPrice = entry.price?.let {
                                        BigDecimal.valueOf(it, 2).stripTrailingZeros().toPlainString()
                                    }.orEmpty()
                                    choiceQuery = ""
                                    linkError = null
                                    choosingProduct = true
                                }) {
                                    Icon(painterResource(R.drawable.ic_edit),
                                        contentDescription = null, tint = p.accent,
                                        modifier = Modifier.size(17.dp))
                                    androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
                                    Text(if (attached) "Изменить цену" else "Добавить в магазин",
                                        color = p.accent, fontSize = 13.sp)
                                }
                                if (attached) {
                                    IconButton(onClick = { removingLink = entry.key }) {
                                        Icon(painterResource(R.drawable.ic_trash),
                                            contentDescription = "Убрать из магазина",
                                            tint = p.iconMuted, modifier = Modifier.size(19.dp))
                                    }
                                }
                            }
                        }
                    }
                }
                if (shown < visibleEntries.size) item {
                    TextButton(onClick = { shown += 8 }) {
                        Text("Показать ещё", color = p.accent)
                    }
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
                        TextButton(onClick = {
                            choosingProduct = false
                            creatingStoreProduct = true
                        }) {
                            Icon(painterResource(R.drawable.ic_plus), contentDescription = null,
                                tint = p.accent, modifier = Modifier.size(18.dp))
                            Text("Создать новый товар", color = p.accent)
                        }
                        androidx.compose.foundation.lazy.LazyColumn(
                            Modifier.fillMaxWidth().heightIn(max = 270.dp),
                        ) {
                            items(options.take(choiceShown), key = { it.first }) { (key, name) ->
                                Row(Modifier.fillMaxWidth().clickable {
                                    chosenProductKey = key
                                    linkPrice = ""
                                }.padding(12.dp)) {
                                    Text(name, color = p.text, modifier = Modifier.weight(1f))
                                    Icon(painterResource(R.drawable.ic_plus), null,
                                        tint = p.accent, modifier = Modifier.size(20.dp))
                                }
                            }
                            if (options.size > choiceShown) item {
                                TextButton(onClick = { choiceShown += 30 }) {
                                    Text("Показать ещё", color = p.accent)
                                }
                            }
                        }
                    } else {
                        val label = (custom.firstOrNull { "custom:${it.id}" == chosen }?.name
                            ?: lenta.firstOrNull { "lenta:${it.id}" == chosen }?.name).orEmpty()
                        Text(label, color = p.text, fontWeight = FontWeight.Bold)
                        SheetTextField(linkPrice, { linkPrice = it; linkError = null },
                            "Цена, ₽ (необязательно)")
                        TextButton(onClick = { chosenProductKey = null }) {
                            Text("Выбрать другой товар", color = p.accent)
                        }
                    }
                    linkError?.let { Text(it, color = p.error) }
                }
            },
            confirmButton = {
                if (chosen != null) SheetActionButton(
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
            },
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
            title = { SheetHeader(if (current == null) "Добавить магазин" else "Название магазина") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SheetTextField(shopName, { shopName = it; error = null }, "Название")
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
