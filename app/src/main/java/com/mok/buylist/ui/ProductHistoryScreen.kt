package com.mok.buylist.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mok.buylist.R
import com.mok.buylist.data.AppUser
import com.mok.buylist.data.nameKeyOf
import com.mok.buylist.ui.theme.LocalPalette
import com.mok.buylist.lentaCatalog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.math.BigDecimal
import java.math.BigInteger
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

private fun historyMoney(value: Long): String = NumberFormat.getNumberInstance(Locale.forLanguageTag("ru-RU"))
    .apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }.format(BigDecimal.valueOf(value, 2)) + " ₽"

internal data class PriceSummary(val type: String, val min: Long, val max: Long, val average: Long)

internal enum class PricePeriod(val title: String) {
    MONTH("Месяц"), HALF_YEAR("6 месяцев"), YEAR("Год"), ALL("Всё время")
}

internal fun pricesForPeriod(rows: List<ExpensePurchase>, period: PricePeriod, today: LocalDate, zone: ZoneId): List<ExpensePurchase> {
    val start = when (period) {
        PricePeriod.MONTH -> today.minusMonths(1).plusDays(1)
        PricePeriod.HALF_YEAR -> today.minusMonths(6).plusDays(1)
        PricePeriod.YEAR -> today.minusYears(1).plusDays(1)
        PricePeriod.ALL -> null
    }
    return rows.filter { row ->
        val day = expenseDate(row, zone)
        !day.isAfter(today) && (start == null || !day.isBefore(start))
    }
}

internal fun priceSummaries(rows: List<ExpensePurchase>): List<PriceSummary> =
    rows.filter { it.unitPrice != null }.groupBy { it.priceType }.map { (type, group) ->
        val values = group.map { it.unitPrice!! }
        val sum = values.fold(BigInteger.ZERO) { acc, value -> acc + BigInteger.valueOf(value) }
        PriceSummary(type, values.min(), values.max(), sum.divide(BigInteger.valueOf(values.size.toLong())).longValueExact())
    }.sortedBy { it.type }

@Composable
internal fun ProductHistoryScreen(purchases: List<ExpensePurchase>, onProductClick: (String) -> Unit, onBack: () -> Unit) {
    val p = LocalPalette.current
    val zone = ZoneId.systemDefault()
    val formatter = remember { DateTimeFormatter.ofPattern("dd.MM.yyyy") }
    var query by rememberSaveable { mutableStateOf("") }
    var shownCount by rememberSaveable(query) { mutableIntStateOf(5) }
    val products = remember(purchases, query) {
        purchases.filter { it.productKey.startsWith("lenta:") || it.productKey.startsWith("custom:") }
            .groupBy { it.productKey }.map { (_, rows) -> rows.maxBy { it.at } }
            .filter { nameKeyOf(it.name).contains(nameKeyOf(query)) }
            .sortedBy { nameKeyOf(it.name) }
    }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(p.background)) {
        SubScreenHeader("История продуктов", onBack)
        ProductSearch(query, { query = it }, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp,
            bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (products.isEmpty()) item { Text("Товары не найдены", Modifier.padding(16.dp), color = p.textSecondary) }
            items(products.take(shownCount), key = { it.productKey }) { product ->
                HistoryCard(Modifier.clickable(role = Role.Button) { onProductClick(product.productKey) }) {
                    Text(product.name, color = p.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("Последняя покупка: ${formatter.format(expenseDate(product, zone))}", color = p.textSecondary)
                }
            }
            if (shownCount < products.size) item {
                Text("Показать ещё", Modifier.clickable { shownCount += 5 }.padding(12.dp), color = p.accent)
            }
        }
    }
}

@Composable
internal fun ProductDetailsScreen(productKey: String, purchases: List<ExpensePurchase>, users: List<AppUser>,
    viewModel: ShoppingViewModel, onBack: () -> Unit) {
    val p = LocalPalette.current
    val zone = ZoneId.systemDefault()
    val catalog = LocalContext.current.lentaCatalog
    val products by catalog.products.collectAsStateWithLifecycle()
    val barcodeRows by catalog.barcodeRows.collectAsStateWithLifecycle()
    val address by catalog.address.collectAsStateWithLifecycle()
    val refresh by viewModel.priceRefresh.collectAsStateWithLifecycle()
    val product = products.firstOrNull { "lenta:${it.id}" == productKey }
    val customProducts by viewModel.customProducts.collectAsStateWithLifecycle()
    val stores by viewModel.stores.collectAsStateWithLifecycle()
    val storeLinks by viewModel.storeProducts.collectAsStateWithLifecycle()
    val customBarcodeRows by viewModel.customBarcodes.collectAsStateWithLifecycle()
    val ownProduct = customProducts.firstOrNull { "custom:${it.id}" == productKey }
    val identities = remember(products, customProducts, barcodeRows, customBarcodeRows) {
        com.mok.buylist.data.sharedCatalogIdentities(
            products, customProducts, barcodeRows, customBarcodeRows)
    }
    val sharedKey = identities[productKey] ?: productKey
    val rows = remember(purchases, sharedKey, identities) {
        purchases.filter { (identities[it.productKey] ?: it.productKey) == sharedKey }
            .sortedByDescending { it.at }
    }
    val shopPrices = remember(sharedKey, stores, storeLinks, rows, identities) {
        productShopPrices(productKey, stores, storeLinks, rows, identities)
    }
    val barcodes = if (product != null) barcodeRows.filter { it.productId == product.id && !it.deleted }
        .map { it.code }.sorted() else customBarcodeRows.filter {
            it.productId == ownProduct?.id && !it.deleted
        }.map { it.code }.sorted()
    val activity = androidx.activity.compose.LocalActivity.current as? MainActivity
    val scope = rememberCoroutineScope()
    var editingBarcode by remember(productKey) { mutableStateOf<String?>(null) }
    var barcodeInput by remember(productKey) { mutableStateOf("") }
    var barcodeError by remember(productKey) { mutableStateOf<String?>(null) }
    var deletingBarcode by remember(productKey) { mutableStateOf<String?>(null) }
    var barcodeEditorOpen by remember(productKey) { mutableStateOf(false) }
    var scanningBarcode by remember(productKey) { mutableStateOf(false) }
    var barcodeSaving by remember(productKey) { mutableStateOf(false) }
    var editingOwnDetails by remember(productKey) { mutableStateOf(false) }
    var ownDetailsMenuOpen by remember(productKey) { mutableStateOf(false) }
    var ownNameInput by remember(productKey) { mutableStateOf("") }
    var ownPriceInput by remember(productKey) { mutableStateOf("") }
    var ownWeighted by remember(productKey) { mutableStateOf(false) }
    var ownDetailsError by remember(productKey) { mutableStateOf<String?>(null) }
    var savingOwnDetails by remember(productKey) { mutableStateOf(false) }
    var deleteOwnPending by remember(productKey) { mutableStateOf(false) }
    var editingStoreLink by remember(productKey) { mutableStateOf(false) }
    var linkedStore by remember(productKey) { mutableStateOf<com.mok.buylist.data.ShoppingStore?>(null) }
    var linkedPrice by remember(productKey) { mutableStateOf("") }
    var linkError by remember(productKey) { mutableStateOf<String?>(null) }
    var savingLink by remember(productKey) { mutableStateOf(false) }
    var pendingLinkRemoval by remember(productKey) { mutableStateOf<String?>(null) }
    var editingPurchase by remember(productKey) { mutableStateOf<ExpensePurchase?>(null) }
    var purchasePrice by remember(productKey) { mutableStateOf("") }
    var purchaseStore by remember(productKey) { mutableStateOf<com.mok.buylist.data.ShoppingStore?>(null) }
    var purchasePriceRevision by remember(productKey) { mutableIntStateOf(0) }
    var purchaseError by remember(productKey) { mutableStateOf<String?>(null) }
    var historyCount by rememberSaveable(productKey) { mutableIntStateOf(5) }
    var tripCount by rememberSaveable(productKey) { mutableIntStateOf(5) }
    var openedTrip by rememberSaveable(productKey) { mutableStateOf<String?>(null) }
    val archive by viewModel.archive.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val days = remember(rows, zone) { rows.map { expenseDate(it, zone) }.distinct() }
    val interval = remember(days) { averagePurchaseInterval(days) }
    var pricePeriod by rememberSaveable { mutableStateOf(PricePeriod.MONTH) }
    var priceStoreFilter by rememberSaveable(productKey) { mutableStateOf<String?>(null) }
    val today = LocalDate.now(zone)
    val pricedRows = remember(rows, priceStoreFilter) {
        if (priceStoreFilter == null) rows else rows.filter { it.storeId == priceStoreFilter }
    }
    val periodRows = remember(pricedRows, pricePeriod, today, zone) {
        pricesForPeriod(pricedRows, pricePeriod, today, zone)
    }
    val summaries = remember(periodRows) { priceSummaries(periodRows) }
    val points = remember(periodRows) { periodRows.filter { it.unitPrice != null }.sortedBy { it.at } }
    val formatter = remember { DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm") }
    val buyers = remember(users) { users.associate { it.id to it.name } }
    val trip = archive.firstOrNull { it.uid == openedTrip }
    if (trip != null) {
        ArchiveScreen(listOf(trip), onBack = { openedTrip = null }, onOpenStats = null,
            onRepeat = { names, quantities, events -> viewModel.repeatArchived(names, quantities, events) { openedTrip = null } },
            onDelete = { viewModel.deleteArchived(it); openedTrip = null }, users = users,
            meId = profile?.id.orEmpty(), initiallyExpandedId = trip.id, viewModel = viewModel)
        return
    }
    if (scanningBarcode) {
        BarcodeScannerSheet(
            onDismiss = {
                scanningBarcode = false
                barcodeEditorOpen = true
            },
            onCode = { code ->
                scanningBarcode = false
                barcodeEditorOpen = true
                barcodeInput = code
                barcodeError = null
            },
        )
    }
    if (barcodeEditorOpen && !scanningBarcode && (product != null || ownProduct != null)) {
        AppSheet(
            onDismissRequest = { if (!barcodeSaving) barcodeEditorOpen = false },
            containerColor = p.sheet,
            title = { SheetHeader(if (editingBarcode == null) "Добавить штрихкод" else "Изменить штрихкод") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SheetTextField(
                        value = barcodeInput,
                        onValueChange = { barcodeInput = it.filter(Char::isDigit).take(13); barcodeError = null },
                        hint = "EAN-8, UPC-A или EAN-13",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = barcodeError != null,
                        trailingAction = {
                            IconButton(
                                onClick = {
                                    barcodeEditorOpen = false
                                    scanningBarcode = true
                                },
                                enabled = !barcodeSaving,
                            ) {
                                Icon(painterResource(R.drawable.ic_camera),
                                    contentDescription = "Сканировать камерой", tint = p.accent)
                            }
                        },
                    )
                    barcodeError?.let { Text(it, color = p.error, fontSize = 13.sp) }
                }
            },
            confirmButton = {
                SheetActionButton(enabled = !barcodeSaving && barcodeInput.isNotBlank(), onClick = {
                    barcodeSaving = true
                    scope.launch {
                        try {
                            val previous = editingBarcode
                            val error = if (product != null) {
                                if (previous == null) catalog.addBarcode(product.id, barcodeInput)
                                else catalog.editBarcode(product.id, previous, barcodeInput)
                            } else {
                                val customId = ownProduct?.id
                                if (customId == null) "Товар не найден" else {
                                    // Repository methods are exposed as callbacks through the ViewModel.
                                    // The callback owns editor state rather than waiting in a coroutine.
                                    viewModel.changeCustomBarcode(customId, previous, barcodeInput) { outcome ->
                                        barcodeError = outcome
                                        if (outcome == null) barcodeEditorOpen = false
                                        barcodeSaving = false
                                    }
                                    return@launch
                                }
                            }
                            if (error == null) barcodeEditorOpen = false else barcodeError = error
                        } catch (_: Exception) {
                            barcodeError = "Не удалось сохранить штрихкод"
                        } finally {
                            barcodeSaving = false
                        }
                    }
                }) { Text("Сохранить") }
            },
        )
    }
    deletingBarcode?.let { code ->
        AppSheet(
            onDismissRequest = { deletingBarcode = null },
            containerColor = p.sheet,
            title = { SheetHeader("Удалить штрихкод?") },
            text = { Text("Код $code перестанет находить этот товар. Удаление передастся при синхронизации.", color = p.textSecondary) },
            confirmButton = {
                SheetActionButton(destructive = true, onClick = {
                    if (product != null) {
                        scope.launch {
                            val error = runCatching { catalog.removeBarcode(product.id, code) }
                                .getOrElse { "Не удалось удалить штрихкод" }
                            deletingBarcode = null
                            if (error != null) activity?.notifyUser(error)
                        }
                    } else {
                        val id = ownProduct?.id ?: return@SheetActionButton
                        viewModel.changeCustomBarcode(id, code, null) { error ->
                            deletingBarcode = null
                            if (error != null) activity?.notifyUser(error)
                        }
                    }
                }) { Text("Удалить") }
            },
        )
    }
    if (editingOwnDetails && ownProduct != null) {
        val parsedPrice = if (ownPriceInput.isBlank()) null
            else com.mok.buylist.data.parsePriceKopecks(ownPriceInput)
        AppSheet(
            onDismissRequest = { if (!savingOwnDetails) editingOwnDetails = false },
            containerColor = p.sheet,
            title = { SheetHeader("Редактировать товар") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Название", color = p.textSecondary, fontSize = 13.sp)
                    SheetTextField(ownNameInput,
                        { ownNameInput = it; ownDetailsError = null }, "Название товара")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !ownWeighted, onClick = { ownWeighted = false },
                            label = { Text("Штучный") })
                        FilterChip(selected = ownWeighted, onClick = { ownWeighted = true },
                            label = { Text("Весовой") })
                    }
                    Text(if (ownWeighted) "Ориентировочная цена за кг" else "Ориентировочная цена",
                        color = p.textSecondary, fontSize = 13.sp)
                    SheetTextField(ownPriceInput,
                        { ownPriceInput = it; ownDetailsError = null }, "Цена, ₽",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    ownDetailsError?.let { Text(it, color = p.error, fontSize = 13.sp) }
                }
            },
            confirmButton = {
                SheetActionButton(
                    enabled = !savingOwnDetails && ownNameInput.isNotBlank() &&
                        ownNameInput.length <= 200 &&
                        (ownPriceInput.isBlank() || parsedPrice != null),
                    onClick = {
                        savingOwnDetails = true
                        viewModel.updateCustomProductDetails(
                            ownProduct.id, ownNameInput, parsedPrice, ownWeighted
                        ) { error ->
                            savingOwnDetails = false
                            if (error == null) editingOwnDetails = false
                            else ownDetailsError = error
                        }
                    },
                ) { Text(if (savingOwnDetails) "Сохранение…" else "Сохранить") }
            },
        )
    }
    if (editingStoreLink) {
        val price = if (linkedPrice.isBlank()) null
            else com.mok.buylist.data.parsePriceKopecks(linkedPrice)
        AppSheet(
            onDismissRequest = { if (!savingLink) editingStoreLink = false },
            containerColor = p.sheet,
            title = { SheetHeader("Товар в магазине") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StoreSelectField(
                        stores.filterNot { it.id == com.mok.buylist.data.lentaStoreId() },
                        linkedStore,
                        onSelected = { linkedStore = it; linkError = null },
                        onCreate = viewModel::createStore,
                    )
                    SheetTextField(linkedPrice, { linkedPrice = it; linkError = null },
                        "Цена, ₽")
                    linkError?.let { Text(it, color = p.error, fontSize = 13.sp) }
                }
            },
            confirmButton = {
                SheetActionButton(
                    enabled = !savingLink && linkedStore != null &&
                        (linkedPrice.isBlank() || price != null),
                    onClick = {
                        val shop = linkedStore ?: return@SheetActionButton
                        savingLink = true
                        viewModel.saveStoreProduct(shop.id, productKey, price) { message ->
                            savingLink = false
                            if (message == null) editingStoreLink = false
                            else linkError = message
                        }
                    },
                ) { Text("Сохранить") }
            },
        )
    }
    pendingLinkRemoval?.let { shopId ->
        AppSheet(
            onDismissRequest = { pendingLinkRemoval = null },
            containerColor = p.sheet,
            title = { SheetHeader("Убрать товар из магазина?") },
            text = { Text("Покупки останутся в истории.", color = p.textSecondary) },
            confirmButton = {
                SheetActionButton(destructive = true, onClick = {
                    val actualKey = storeLinks.firstOrNull { link ->
                        link.storeId == shopId && !link.deleted &&
                            (identities[link.productKey] ?: link.productKey) == sharedKey
                    }?.productKey ?: productKey
                    viewModel.removeStoreProduct(shopId, actualKey) { result ->
                        pendingLinkRemoval = null
                        if (result != null) activity?.notifyUser(result)
                    }
                }) { Text("Убрать") }
            },
        )
    }
    if (deleteOwnPending && ownProduct != null) {
        AppSheet(onDismissRequest = { deleteOwnPending = false }, containerColor = p.sheet,
            title = { SheetHeader("Удалить свой товар?") },
            text = { Text("Карточка исчезнет из каталога. Уже купленные товары и их цены сохранятся.", color = p.textSecondary) },
            confirmButton = {
                SheetActionButton(destructive = true, onClick = {
                    viewModel.deleteCustomProduct(ownProduct.id) { deleted ->
                        if (deleted) { deleteOwnPending = false; onBack() }
                    }
                }) { Text("Удалить") }
            })
    }
    editingPurchase?.let { row ->
        val parsed = com.mok.buylist.data.parsePriceKopecks(purchasePrice)
        AppSheet(onDismissRequest = { editingPurchase = null }, containerColor = p.sheet,
            title = { SheetHeader("Изменить покупку") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(row.name, color = p.text)
                    SheetTextField(purchasePrice,
                        { purchasePrice = it; purchaseError = null; purchasePriceRevision++ },
                        "Цена за единицу, ₽ (необязательно)",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    if ((row.productKey.startsWith("lenta:") && row.storeId == com.mok.buylist.data.lentaStoreId())) {
                        Text("Магазин: Лента", color = p.textSecondary)
                    } else StoreSelectField(stores, purchaseStore, onSelected = { store ->
                        purchaseStore = store
                        purchaseError = null
                        val revision = ++purchasePriceRevision
                        if (store != null) viewModel.latestStorePrice(
                            row.productKey, store.id, row.id
                        ) { known ->
                            if (purchaseStore?.id == store.id && purchasePriceRevision == revision && known != null)
                                purchasePrice = BigDecimal.valueOf(known, 2)
                                    .stripTrailingZeros().toPlainString()
                        }
                    }, onCreate = viewModel::createStore)
                    purchaseError?.let { Text(it, color = p.error) }
                }
            },
            confirmButton = {
                SheetActionButton(enabled = purchasePrice.isBlank() || parsed != null, onClick = {
                    viewModel.amendPurchase(row.id, parsed,
                        if ((row.productKey.startsWith("lenta:") && row.storeId == com.mok.buylist.data.lentaStoreId())) "Лента"
                        else purchaseStore?.name.orEmpty()) { error ->
                        if (error == null) editingPurchase = null else purchaseError = error
                    }
                }) { Text("Сохранить") }
            })
    }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(p.background)) {
        SubScreenHeader("Информация о товаре", onBack)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp,
            bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                HistoryCard {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            product?.name ?: ownProduct?.name ?: rows.firstOrNull()?.name ?: "Товар не найден",
                            modifier = Modifier.weight(1f),
                            color = p.text, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        )
                        if (ownProduct != null) {
                            Box {
                                IconButton(
                                    onClick = { ownDetailsMenuOpen = true },
                                    modifier = Modifier.semantics { contentDescription = "Действия с товаром" },
                                ) {
                                    Text("⋮", color = p.iconMuted, fontSize = 24.sp)
                                }
                                DropdownMenu(
                                    expanded = ownDetailsMenuOpen,
                                    onDismissRequest = { ownDetailsMenuOpen = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Редактировать товар") },
                                        leadingIcon = {
                                            Icon(painterResource(R.drawable.ic_edit),
                                                contentDescription = null)
                                        },
                                        onClick = {
                                            ownDetailsMenuOpen = false
                                            ownNameInput = ownProduct.name
                                            ownWeighted = ownProduct.isWeighted
                                            ownPriceInput = ownProduct.estimatedPriceKopecks?.let {
                                                BigDecimal.valueOf(it, 2).stripTrailingZeros().toPlainString()
                                            }.orEmpty()
                                            ownDetailsError = null
                                            editingOwnDetails = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Удалить товар", color = p.error) },
                                        leadingIcon = {
                                            Icon(painterResource(R.drawable.ic_trash),
                                                contentDescription = null, tint = p.error)
                                        },
                                        onClick = {
                                            ownDetailsMenuOpen = false
                                            deleteOwnPending = true
                                        },
                                    )
                                }
                            }
                        }
                    }
                    if (ownProduct != null) {
                        Text(if (ownProduct.isWeighted) "Ориентировочная цена за кг" else "Ориентировочная цена",
                            color = p.textSecondary, fontSize = 13.sp)
                        Text(
                            ownProduct.estimatedPriceKopecks?.let(::historyMoney) ?: "Не указана",
                            color = p.text, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        )
                        SheetActionButton(onClick = {
                            viewModel.addCustomProduct(ownProduct) { added ->
                                activity?.notifyUser(if (added) "Добавлено в список" else "Уже в списке")
                            }
                        }) { Text("Добавить в список") }
                    }
                }
            }
            if (product != null) item {
                HistoryCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        LentaImage(product.image, Modifier.size(96.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val current = product.takeIf { it.address == address && address.isNotBlank() && it.channel == "locc" }
                        current?.price?.takeIf { it.isNotBlank() && it != "null" }?.let {
                            Text("$it ₽", color = p.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                        if (product.pack.isNotBlank()) Text("Упаковка: ${product.pack}", color = p.textSecondary)
                        if (product.ean.isNotBlank()) Text("Штрихкод: ${product.ean}", color = p.textSecondary)
                        }
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))
                        val message = refresh?.takeIf { it.first == product.id }?.second
                        SheetActionButton(onClick = {
                            viewModel.addProduct(product) { added -> activity?.notifyUser(if (added) "Добавлено в список" else "Уже в списке") }
                        }) { Text("Добавить в список") }
                        androidx.compose.material3.TextButton(onClick = { viewModel.refreshPrice(product.id) }, enabled = message != "Загрузка") {
                            Text(if (message == "Загрузка") "Обновление…" else "Обновить цену")
                        }
                }
            }
            if (product != null || ownProduct != null) item {
                HistoryCard {
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("Магазины и цены", color = p.text,
                            fontSize = 18.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f))
                        IconButton(onClick = {
                            linkedStore = null
                            linkedPrice = ""
                            linkError = null
                            editingStoreLink = true
                        }) {
                            Icon(painterResource(R.drawable.ic_plus),
                                contentDescription = "Добавить магазин к товару",
                                tint = p.accent)
                        }
                    }
                    if (shopPrices.isEmpty()) Text("Магазины пока не добавлены",
                        color = p.textSecondary, fontSize = 13.sp)
                    shopPrices.forEach { shopPrice ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                .background(p.field)
                                .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(shopPrice.storeName, color = p.text,
                                    fontWeight = FontWeight.SemiBold)
                                Text(shopPrice.priceKopecks?.let(::historyMoney) ?: "Цена не указана",
                                    color = p.textSecondary, fontSize = 13.sp)
                            }
                            if (shopPrice.storeId != com.mok.buylist.data.lentaStoreId()) {
                                IconButton(onClick = {
                                    linkedStore = stores.firstOrNull { it.id == shopPrice.storeId }
                                    linkedPrice = shopPrice.priceKopecks?.let {
                                        BigDecimal.valueOf(it, 2).stripTrailingZeros().toPlainString()
                                    }.orEmpty()
                                    linkError = null
                                    editingStoreLink = true
                                }) {
                                    Icon(painterResource(R.drawable.ic_edit),
                                        contentDescription = "Изменить цену магазина",
                                        tint = p.accent, modifier = Modifier.size(19.dp))
                                }
                                if (storeLinks.any { !it.deleted && it.storeId == shopPrice.storeId &&
                                    (identities[it.productKey] ?: it.productKey) == sharedKey })
                                    IconButton(onClick = { pendingLinkRemoval = shopPrice.storeId }) {
                                        Icon(painterResource(R.drawable.ic_trash),
                                            contentDescription = "Убрать привязку к магазину",
                                            tint = p.iconMuted, modifier = Modifier.size(19.dp))
                                    }
                            }
                        }
                    }
                }
            }
            if (product != null || ownProduct != null) item {
                HistoryCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Штрихкоды товара", color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        IconButton(onClick = {
                            editingBarcode = null
                            barcodeInput = ""
                            barcodeError = null
                            barcodeEditorOpen = true
                        }, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(p.chipBg)) {
                            Icon(painterResource(R.drawable.ic_plus), contentDescription = "Добавить штрихкод",
                                tint = p.accent, modifier = Modifier.size(22.dp))
                        }
                    }
                    if (barcodes.isEmpty()) Text("Штрихкоды ещё не добавлены", color = p.textSecondary, fontSize = 14.sp)
                    barcodes.forEach { code ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.field)
                                .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(code, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                if (code == product?.ean) Text("Код из Ленты", color = p.textSecondary, fontSize = 11.sp)
                            }
                            IconButton(onClick = {
                                editingBarcode = code
                                barcodeInput = code
                                barcodeError = null
                                barcodeEditorOpen = true
                            }) {
                                Icon(painterResource(R.drawable.ic_edit),
                                    contentDescription = "Изменить штрихкод $code", tint = p.accent,
                                    modifier = Modifier.size(20.dp))
                            }
                            IconButton(onClick = { deletingBarcode = code }) {
                                Icon(painterResource(R.drawable.ic_trash),
                                    contentDescription = "Удалить штрихкод $code", tint = p.iconMuted,
                                    modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
            if (interval != null) item {
                HistoryCard {
                    Text("Интервал покупок: $interval дн.", color = p.text)
                }
            }
            item {
                HistoryCard {
                    Text("Динамика цен", color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PricePeriod.entries.forEach { option ->
                            HistoryChoice(option.title, option == pricePeriod) { pricePeriod = option }
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HistoryChoice("Все магазины", priceStoreFilter == null) { priceStoreFilter = null }
                        rows.map { it.storeId }.distinct().forEach { storeId ->
                            val storeLabel = stores.firstOrNull { it.id == storeId }?.name
                                ?: if (storeId.isBlank()) "Без магазина" else "Другой магазин"
                            HistoryChoice(storeLabel, priceStoreFilter == storeId) { priceStoreFilter = storeId }
                        }
                    }
                    if (summaries.isEmpty()) Text("Известных цен за период нет", color = p.textSecondary)
                    if (summaries.isNotEmpty()) {
                        PriceChart(points, zone)
                    }
                }
            }
            val storePrices = rows.filter { it.unitPrice != null }.groupBy { it.storeId }.toList()
                .sortedBy { (storeId, _) -> stores.firstOrNull { it.id == storeId }?.name ?: storeId }
            if (storePrices.isNotEmpty()) item {
                HistoryCard {
                    Text("Последние цены по магазинам", color = p.text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    storePrices.forEach { (storeId, purchasesInStore) ->
                        val latest = purchasesInStore.maxBy { it.at }
                        val storeName = stores.firstOrNull { it.id == storeId }?.name
                            ?: if (storeId.isBlank()) "Магазин не указан" else "Другой магазин"
                        val prices = purchasesInStore.mapNotNull { it.unitPrice }
                        val average = prices.fold(BigInteger.ZERO) { total, value ->
                            total + BigInteger.valueOf(value)
                        }.divide(BigInteger.valueOf(prices.size.toLong())).longValueExact()
                        Text("$storeName · ${historyMoney(latest.unitPrice!!)}",
                            color = p.text, fontSize = 14.sp)
                        Text("Мин.: ${historyMoney(prices.min())} · Средняя: ${historyMoney(average)}",
                            color = p.textSecondary, fontSize = 12.sp)
                    }
                }
            }
            item { Text("История покупок", Modifier.padding(top = 8.dp), color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            items(rows.take(historyCount), key = { "event:${it.id}" }) { row ->
                HistoryCard {
                    Text(formatter.format(Instant.ofEpochMilli(row.at).atZone(zone)), color = p.text, fontWeight = FontWeight.Bold)
                    if (row.impulsive) Text("Импульсивная покупка", color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    row.unitPrice?.let { Text("Цена: ${historyMoney(it)}", color = p.text) }
                    stores.firstOrNull { it.id == row.storeId }?.let { Text("Магазин: ${it.name}", color = p.textSecondary) }
                    val amount = row.weightGrams?.let { "${java.math.BigDecimal.valueOf(it.toLong(), 3).stripTrailingZeros().toPlainString()} кг" } ?: "Количество: ${row.quantity}"
                    Text(listOfNotNull(amount, row.total?.let { "Стоимость: ${historyMoney(it)}" }).joinToString(" · "), color = p.textSecondary)
                    if (row.buyer.isNotBlank()) Text("Покупатель: ${buyers[row.buyer] ?: row.buyer}", color = p.textSecondary)
                    TextButton(onClick = {
                        editingPurchase = row
                        purchasePrice = row.unitPrice?.let {
                            BigDecimal.valueOf(it, 2).stripTrailingZeros().toPlainString()
                        }.orEmpty()
                        purchaseStore = stores.firstOrNull { it.id == row.storeId }
                        purchaseError = null
                    }) { Text("Изменить цену и магазин", color = p.accent) }
                }
            }
            if (historyCount < rows.size) item {
                Text("Показать ещё", Modifier.clickable { historyCount += 5 }.padding(12.dp), color = p.accent)
            }
            val trips = rows.filter { it.trip != "active" }.distinctBy { it.trip }
            if (trips.isNotEmpty()) item {
                Text("Списки с этим товаром", Modifier.padding(top = 8.dp), color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            items(trips.take(tripCount), key = { "trip:${it.trip}" }) { row ->
                HistoryCard(Modifier.clickable(role = Role.Button) { openedTrip = row.trip }) {
                    Text("Архив: ${formatter.format(Instant.ofEpochMilli(row.at).atZone(zone))}", color = p.text)
                }
            }
            if (tripCount < trips.size) item {
                Text("Показать ещё", Modifier.clickable { tripCount += 5 }.padding(12.dp), color = p.accent)
            }
        }
    }
}

@Composable
private fun HistoryChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Text(label, Modifier.clip(RoundedCornerShape(20.dp)).background(if (selected) p.accent else p.chipBg)
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        color = if (selected) p.onAccent else p.chipText, fontSize = 13.sp)
}

@Composable
private fun PriceChart(points: List<ExpensePurchase>, zone: ZoneId) {
    if (points.isEmpty()) return
    val p = LocalPalette.current
    val minPrice = points.minOf { it.unitPrice!! }
    val maxPrice = points.maxOf { it.unitPrice!! }
    val first = points.first().at
    val last = points.last().at
    val date = remember { DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm") }
    var selected by remember(points) { mutableIntStateOf(points.lastIndex) }
    val current = points[selected]
    val description = points.joinToString("; ") {
        "${date.format(Instant.ofEpochMilli(it.at).atZone(zone))}: ${historyMoney(it.unitPrice!!)}"
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        androidx.compose.material3.IconButton(onClick = { selected-- }, enabled = selected > 0) {
            Text("‹", color = if (selected > 0) p.text else p.textSecondary, fontSize = 26.sp,
                modifier = Modifier.semantics { contentDescription = "Предыдущая цена" })
        }
        Column(Modifier.weight(1f), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            Text(historyMoney(current.unitPrice!!), color = p.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(date.format(Instant.ofEpochMilli(current.at).atZone(zone)), color = p.textSecondary, fontSize = 12.sp)
        }
        androidx.compose.material3.IconButton(onClick = { selected++ }, enabled = selected < points.lastIndex) {
            Text("›", color = if (selected < points.lastIndex) p.text else p.textSecondary, fontSize = 26.sp,
                modifier = Modifier.semantics { contentDescription = "Следующая цена" })
        }
    }
    Text(historyMoney(maxPrice), color = p.textSecondary, fontSize = 12.sp)
    Canvas(Modifier.fillMaxWidth().height(160.dp)
        .pointerInput(points) {
            detectTapGestures { tap ->
                val pad = 8.dp.toPx()
                val width = (size.width - 2 * pad).coerceAtLeast(1f)
                val height = (size.height - 2 * pad).coerceAtLeast(1f)
                val span = last.toDouble() - first.toDouble()
                val range = maxPrice.toDouble() - minPrice.toDouble()
                selected = points.indices.minBy { index ->
                    val point = points[index]
                    val x = if (span == 0.0) 0.5 else (point.at.toDouble() - first.toDouble()) / span
                    val y = if (range == 0.0) 0.5 else (point.unitPrice!!.toDouble() - minPrice.toDouble()) / range
                    val dx = tap.x - (pad + width * x.toFloat())
                    val dy = tap.y - (pad + height * (1f - y.toFloat()))
                    dx * dx + dy * dy
                }
            }
        }.semantics { contentDescription = "График цен: $description" }) {
        val pad = 8.dp.toPx()
        val width = (size.width - 2 * pad).coerceAtLeast(1f)
        val height = (size.height - 2 * pad).coerceAtLeast(1f)
        drawLine(p.textSecondary, Offset(pad, pad), Offset(pad, size.height - pad), strokeWidth = 1.dp.toPx())
        drawLine(p.textSecondary, Offset(pad, size.height - pad), Offset(size.width - pad, size.height - pad), strokeWidth = 1.dp.toPx())
        val span = last.toDouble() - first.toDouble()
        val range = maxPrice.toDouble() - minPrice.toDouble()
        val coords = points.map { point ->
            val x = if (span == 0.0) 0.5 else (point.at.toDouble() - first.toDouble()) / span
            val y = if (range == 0.0) 0.5 else (point.unitPrice!!.toDouble() - minPrice.toDouble()) / range
            Offset(pad + width * x.toFloat(), pad + height * (1f - y.toFloat()))
        }
        coords.zipWithNext().forEach { (a, b) -> drawLine(p.accent, a, b, strokeWidth = 2.dp.toPx()) }
        coords.forEachIndexed { index, point -> drawCircle(p.accent, radius = if (index == selected) 7.dp.toPx() else 4.dp.toPx(), center = point) }
    }
    Text(historyMoney(minPrice), color = p.textSecondary, fontSize = 12.sp)
    Text("Дата: ${date.format(Instant.ofEpochMilli(first).atZone(zone))}" +
        if (first == last) "" else " — ${date.format(Instant.ofEpochMilli(last).atZone(zone))}",
        color = p.textSecondary, fontSize = 12.sp)
}

@Composable
private fun HistoryCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(LocalPalette.current.card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
}
