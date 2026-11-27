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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.border
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
internal fun ProductHistoryScreen(purchases: List<ExpensePurchase>, onProductClick: (String) -> Unit, onBack: () -> Unit,
    navigation: @Composable () -> Unit = {}) {
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
        SubScreenHeader("История покупок", onBack)
        ProductSearch(query, { query = it }, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp,
            bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (products.isEmpty()) item { Text("Товары не найдены", Modifier.padding(16.dp), color = p.textSecondary) }
            items(products.take(shownCount), key = { it.productKey }) { product ->
                FreshCutListRow(R.drawable.ic_package, product.name,
                    "Последняя покупка: ${formatter.format(expenseDate(product, zone))}",
                    onClick = { onProductClick(product.productKey) })
            }
            if (shownCount < products.size) item {
                Text("Показать ещё", Modifier.clickable { shownCount += 5 }.padding(12.dp), color = p.accent)
            }
        }
        navigation()
    }
}

@Composable
internal fun ProductDetailsScreen(productKey: String, purchases: List<ExpensePurchase>, users: List<AppUser>,
    viewModel: ShoppingViewModel, onBack: () -> Unit, navigation: @Composable () -> Unit = {}) {
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
    val shopPrices = remember(sharedKey, stores, storeLinks, rows, identities, products, address) {
        productShopPrices(productKey, stores, storeLinks, rows, identities, products, address)
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
    var detailTab by rememberSaveable(productKey) { mutableIntStateOf(0) }
    var historyStoresExpanded by rememberSaveable(productKey) { mutableStateOf(false) }
    var historyTripsExpanded by rememberSaveable(productKey) { mutableStateOf(false) }
    LaunchedEffect(detailTab) { if (detailTab > 1) { detailTab = 0 } }
    val historyOpen = detailTab == 1
    var barcodeExpanded by rememberSaveable(productKey) { mutableStateOf(false) }
    var priceFiltersOpen by rememberSaveable(productKey) { mutableStateOf(false) }
    var priceUnit by rememberSaveable(productKey) { mutableStateOf<String?>(null) }
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
    val effectiveUnit = priceUnit ?: if (ownProduct?.isWeighted == true || (rows.isNotEmpty() && rows.all { it.weightGrams != null })) "kg" else "piece"
    val pricedRows = remember(rows, priceStoreFilter, effectiveUnit) {
        rows.filter { (priceStoreFilter == null || it.storeId == priceStoreFilter) &&
            (if (it.weightGrams != null) "kg" else "piece") == effectiveUnit }
    }
    fun storeLabel(id: String?): String = if (id == null) "Все магазины" else
        stores.firstOrNull { it.id == id }?.name ?: if (id.isBlank()) "Без магазина" else "Другой магазин"
    if (priceFiltersOpen) {
        var draftPeriod by remember { mutableStateOf(pricePeriod) }
        var draftStore by remember { mutableStateOf(priceStoreFilter) }
        var draftUnit by remember { mutableStateOf(effectiveUnit) }
        AppSheet(onDismissRequest = { priceFiltersOpen = false }, containerColor = p.sheet,
            title = { SheetHeader("Фильтры графика") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterSelect("Период", draftPeriod.title, PricePeriod.entries.map { it.title }) { draftPeriod = PricePeriod.entries[it] }
                    val ids = listOf<String?>(null) + rows.map { it.storeId }.distinct()
                    FilterSelect("Магазин", storeLabel(draftStore), ids.map(::storeLabel)) { draftStore = ids[it] }
                    FilterSelect("Единица цены", if (draftUnit == "kg") "За кг" else "За штуку", listOf("За штуку", "За кг")) {
                        draftUnit = if (it == 1) "kg" else "piece"
                    }
                    TextButton(onClick = { draftPeriod = PricePeriod.MONTH; draftStore = null; draftUnit = if (ownProduct?.isWeighted == true) "kg" else "piece" }) {
                        Text("Сбросить фильтры", color = p.text)
                    }
                }
            }, confirmButton = { SheetActionButton(onClick = {
                pricePeriod = draftPeriod; priceStoreFilter = draftStore; priceUnit = draftUnit; priceFiltersOpen = false
            }) { Text("Применить") } })
    }
    val periodRows = remember(pricedRows, pricePeriod, today, zone) {
        pricesForPeriod(pricedRows, pricePeriod, today, zone)
    }
    val points = remember(periodRows) { periodRows.filter { it.unitPrice != null }.sortedBy { it.at } }
    val formatter = remember { DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm") }
    val buyers = remember(users) { users.associate { it.id to it.name } }
    val trip = archive.firstOrNull { it.uid == openedTrip }
    if (trip != null) {
        ArchiveScreen(listOf(trip), onBack = { openedTrip = null }, onOpenStats = null,
            onRepeat = { names, quantities, events -> viewModel.repeatArchived(names, quantities, events) { openedTrip = null } },
            onDelete = { viewModel.deleteArchived(it); openedTrip = null }, users = users,
            meId = profile?.id.orEmpty(), initiallyExpandedId = trip.id, viewModel = viewModel, navigation = navigation)
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
            title = { SheetHeader(if (editingBarcode == null) "Добавить штрихкод" else "Изменить штрихкод") }, minBodyHeight = 280.dp,
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SheetTextField(
                        value = barcodeInput,
                        onValueChange = { barcodeInput = it.filter(Char::isDigit).take(13); barcodeError = null },
                        hint = "EAN-8, UPC-A или EAN-13",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = barcodeError != null,
                    )
                    FreshCutListRow(R.drawable.ic_camera, "Сканировать камерой", "",
                        onClick = { if (!barcodeSaving) { barcodeEditorOpen = false; scanningBarcode = true } })
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
    if (editingOwnDetails && ownProduct != null && !barcodeEditorOpen && !scanningBarcode && deletingBarcode == null) {
        val parsedPrice = if (ownPriceInput.isBlank()) null
            else com.mok.buylist.data.parsePriceKopecks(ownPriceInput)
        AppSheet(
            onDismissRequest = { if (!savingOwnDetails) editingOwnDetails = false },
            containerColor = p.sheet,
            title = { SheetHeader("Редактировать товар") }, minBodyHeight = 480.dp,
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SheetTextField(ownNameInput,
                        { ownNameInput = it; ownDetailsError = null }, "Название товара")
                    SheetTextField(barcodes.firstOrNull().orEmpty(), {}, "Штрихкод", readOnly = true, placeholder = "Не указан", trailingAction = {
                        IconButton(onClick = { editingBarcode = barcodes.firstOrNull(); barcodeInput = editingBarcode.orEmpty(); barcodeError = null; barcodeEditorOpen = true }) {
                            Icon(painterResource(R.drawable.ic_camera), "Изменить штрихкод", tint = p.text)
                        }
                    })
                    FreshCutSegments(listOf("Штучный", "Весовой"), if (ownWeighted) 1 else 0, { ownWeighted = it == 1 })
                    SheetTextField(ownPriceInput,
                        { ownPriceInput = it; ownDetailsError = null }, if (ownWeighted) "Ориентировочная цена за кг, ₽" else "Ориентировочная цена, ₽",
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
            title = { SheetHeader("Цена и магазин") }, minBodyHeight = 430.dp,
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(product?.name ?: ownProduct?.name.orEmpty(), color = p.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    SheetTextField(linkedPrice, { linkedPrice = it; linkError = null }, "Цена, ₽", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    StoreSelectField(
                        stores,
                        linkedStore,
                        onSelected = { linkedStore = it; linkError = null },
                        onCreate = viewModel::createStore,
                    )
                    if (linkedStore?.id == com.mok.buylist.data.lentaStoreId()) Text("Очистите цену, чтобы снова использовать каталог.", color = p.textSecondary, fontSize = 12.sp)
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
            title = { SheetHeader("Удалить товар?") }, minBodyHeight = 230.dp,
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
        val parsed = if (purchasePrice.isBlank()) null
            else com.mok.buylist.data.parsePriceKopecks(purchasePrice)
        val fixedLenta = row.productKey.startsWith("lenta:") &&
            row.storeId == com.mok.buylist.data.lentaStoreId()
        val eventProduct = products.firstOrNull { "lenta:${it.id}" == row.productKey }
        AppSheet(
            onDismissRequest = { editingPurchase = null },
            containerColor = p.sheet,
            title = { SheetHeader("Цена и магазин") }, minBodyHeight = 430.dp,
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(row.name, color = p.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(formatter.format(Instant.ofEpochMilli(row.at).atZone(zone)), color = p.textSecondary, fontSize = 12.sp)
                    SheetTextField(purchasePrice, { purchasePrice = it; purchaseError = null; purchasePriceRevision++ },
                        if (row.weightGrams != null) "Цена за кг, ₽" else "Цена за единицу, ₽",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = purchasePrice.isNotBlank() && parsed == null)
                    Text("Неуказанная цена не участвует в расчёте расходов.", color = p.textSecondary, fontSize = 12.sp)
                    if (fixedLenta) {
                        SheetFixedValue("Магазин", "Лента")
                    } else {
                            StoreSelectField(stores, purchaseStore, onSelected = { store ->
                                purchaseStore = store
                                purchaseError = null
                                val revision = ++purchasePriceRevision
                                if (store != null) viewModel.latestStorePrice(
                                    row.productKey, store.id, row.id
                                ) { known ->
                                    if (purchaseStore?.id == store.id &&
                                        purchasePriceRevision == revision && known != null)
                                        purchasePrice = BigDecimal.valueOf(known, 2)
                                            .stripTrailingZeros().toPlainString()
                                }
                            }, onCreate = viewModel::createStore)
                    }
                    purchaseError?.let {
                        Text(it, color = p.error, fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                SheetActionButton(
                    enabled = purchasePrice.isBlank() || parsed != null,
                    onClick = {
                        viewModel.amendPurchase(row.id, parsed,
                            if (fixedLenta) "Лента" else purchaseStore?.name.orEmpty()
                        ) { error ->
                            if (error == null) editingPurchase = null
                            else purchaseError = error
                        }
                    },
                ) { Text("Сохранить изменения") }
            },
        )
    }
    val title = product?.name ?: ownProduct?.name ?: rows.firstOrNull()?.name ?: "Товар не найден"
    val back: () -> Unit = { if (detailTab != 0) detailTab = 0 else onBack() }
    BackHandler(onBack = back)
    Column(Modifier.fillMaxSize().background(p.background)) {
        SubScreenHeader("Информация о товаре", back) {
            if (product != null) {
                FreshCutToolbarAction(R.drawable.ic_edit, "Изменить цену Ленты", {
                    linkedStore = stores.firstOrNull { it.id == com.mok.buylist.data.lentaStoreId() }
                    linkedPrice = shopPrices.firstOrNull { it.storeId == com.mok.buylist.data.lentaStoreId() }?.priceKopecks
                        ?.let { BigDecimal.valueOf(it, 2).stripTrailingZeros().toPlainString() }.orEmpty()
                    linkError = null; editingStoreLink = true
                })
            }
                        if (ownProduct != null) {
                            Box {
                                IconButton(
                                    onClick = { ownDetailsMenuOpen = true },
                                    modifier = Modifier.semantics { contentDescription = "Действия с товаром" },
                                ) {
                                    Icon(painterResource(R.drawable.ic_more), contentDescription = null, tint = p.text, modifier = Modifier.size(24.dp))
                                }
                                AppDropdownMenu(
                                    expanded = ownDetailsMenuOpen,
                                    onDismissRequest = { ownDetailsMenuOpen = false },
                                ) {
                                    AppDropdownMenuItem(
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
                                    AppDropdownMenuItem(
                                        text = { Text("Удалить товар", color = p.error) },
                                        leadingIcon = {
                                            Icon(painterResource(R.drawable.ic_trash),
                                                contentDescription = null, tint = p.text)
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
        FreshCutTabs(listOf("Цены", "История"), detailTab.coerceIn(0, 1), { detailTab = it },
            Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        androidx.compose.runtime.key(detailTab) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                val current = product?.takeIf { it.address == address && address.isNotBlank() && it.channel == "locc" }
                val best = displayShopPrice(productKey, shopPrices)
                val cost = best?.priceKopecks?.let(::historyMoney)
                    ?: ownProduct?.estimatedPriceKopecks?.let(::historyMoney)
                    ?: current?.price?.takeIf { it.isNotBlank() && it != "null" }?.plus(" ₽")
                HistoryCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (ownProduct != null) CustomProductImage(ownProduct.id, Modifier.size(64.dp))
                        else LentaImage(product?.image.orEmpty(), Modifier.size(64.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(title, color = p.text, fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold)
                            Text(cost?.plus(if (ownProduct?.isWeighted == true) " /кг" else "") ?: "—",
                                color = p.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                            best?.let { Text(it.storeName, color = p.textSecondary, fontSize = 12.sp) }
                        }
                    }
                    if (!historyOpen) {
                        if (product != null) {
                            TextButton(
                                onClick = { viewModel.refreshPrice(product.id) },
                                enabled = refresh?.second != "Загрузка",
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(painterResource(R.drawable.ic_sync), null, Modifier.size(18.dp), tint = p.text)
                                Text(if (refresh?.first == product.id && refresh?.second == "Загрузка")
                                    "  Обновление…" else "  Обновить информацию", color = p.text)
                            }
                        }
                        CompactProductLine("Упаковка", product?.pack?.takeIf { it.isNotBlank() }
                            ?: if (ownProduct?.isWeighted == true) "Весовой товар" else "Штучный товар")
                        CompactProductLine("Последняя покупка", rows.firstOrNull()?.let {
                            DateTimeFormatter.ofPattern("dd.MM.yyyy").format(Instant.ofEpochMilli(it.at).atZone(zone))
                        } ?: "—")
                        if (product != null || ownProduct != null) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable(role = Role.Button) { barcodeExpanded = !barcodeExpanded },
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(if (barcodes.size > 1) "Штрихкоды · ${barcodes.size}" else "Штрихкод",
                                    Modifier.weight(1f), color = p.textSecondary, fontSize = 13.sp)
                                Text(barcodes.firstOrNull() ?: "Добавить", color = p.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Icon(painterResource(R.drawable.ic_chevron_down), if (barcodeExpanded) "Свернуть" else "Раскрыть",
                                    Modifier.size(20.dp).then(if (barcodeExpanded) Modifier.rotate(180f) else Modifier), tint = p.textSecondary)
                            }
                            if (barcodeExpanded) {
                                barcodes.forEach { code ->
                                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.field).padding(start = 12.dp),
                                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        Text(code, Modifier.weight(1f), color = p.text, fontSize = 14.sp)
                                        IconButton(onClick = { editingBarcode = code; barcodeInput = code; barcodeError = null; barcodeEditorOpen = true }) {
                                            Icon(painterResource(R.drawable.ic_edit), "Изменить $code", Modifier.size(20.dp), tint = p.text)
                                        }
                                        IconButton(onClick = { deletingBarcode = code }) {
                                            Icon(painterResource(R.drawable.ic_trash), "Удалить $code", Modifier.size(20.dp), tint = p.text)
                                        }
                                    }
                                }
                                TextButton(onClick = { editingBarcode = null; barcodeInput = ""; barcodeError = null; barcodeEditorOpen = true }) {
                                    Text("Добавить штрихкод", color = p.text)
                                }
                                if (ownProduct != null) CustomProductImageActions(ownProduct.id)
                            }
                        }
                    }
                }
            }
            if (detailTab == 0 && (product != null || ownProduct != null)) {
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        FreshCutSectionTitle("Магазины и цены", Modifier.weight(1f))
                        Text("Добавить", Modifier.clickable {
                            linkedStore = null; linkedPrice = ""; linkError = null; editingStoreLink = true
                        }.padding(8.dp), color = p.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
                if (shopPrices.isEmpty()) item { Text("Магазины пока не добавлены", color = p.textSecondary, fontSize = 13.sp) }
                items(shopPrices, key = { "store:${it.storeId}" }) { shopPrice ->
                    val editable = true
                    val attached = storeLinks.any { !it.deleted && it.storeId == shopPrice.storeId &&
                        (identities[it.productKey] ?: it.productKey) == sharedKey }
                    val updateAt = storeLinks.filter { !it.deleted && it.storeId == shopPrice.storeId &&
                        (identities[it.productKey] ?: it.productKey) == sharedKey }.maxOfOrNull { it.updatedAt }
                    val purchaseAt = rows.filter { it.storeId == shopPrice.storeId && it.unitPrice != null }.maxOfOrNull { it.at }
                    val date = (updateAt ?: purchaseAt)?.let { formatter.format(Instant.ofEpochMilli(it).atZone(zone)) }
                    var menu by remember { mutableStateOf(false) }
                    fun editPrice() {
                        linkedStore = stores.firstOrNull { it.id == shopPrice.storeId }
                        linkedPrice = shopPrice.priceKopecks?.let { BigDecimal.valueOf(it, 2).stripTrailingZeros().toPlainString() }.orEmpty()
                        linkError = null; editingStoreLink = true
                    }
                    FreshCutListRow(R.drawable.ic_store, shopPrice.storeName,
                        if (shopPrice.manual) "Ручная цена" else date?.let { "Цена от $it" } ?: "Цена магазина",
                        value = shopPrice.priceKopecks?.let(::historyMoney) ?: "—",
                        onClick = { if (editable) editPrice() },
                        trailing = if (editable) ({
                            Box {
                                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                                    Icon(painterResource(R.drawable.ic_more), "Действия", Modifier.size(20.dp), tint = p.textSecondary)
                                }
                                AppDropdownMenu(menu, { menu = false }) {
                                    AppDropdownMenuItem(text = { Text("Изменить цену") }, onClick = { menu = false; editPrice() })
                                    if (shopPrice.manual && shopPrice.storeId == com.mok.buylist.data.lentaStoreId()) AppDropdownMenuItem(text = { Text("Сбросить ручную цену") }, onClick = {
                                        menu = false; viewModel.saveStoreProduct(shopPrice.storeId, productKey, null) { error -> activity?.notifyUser(error ?: "Ручная цена сброшена") }
                                    })
                                    if (attached && !(shopPrice.storeId == com.mok.buylist.data.lentaStoreId() && product != null)) AppDropdownMenuItem(text = { Text("Убрать из магазина", color = p.error) },
                                        onClick = { menu = false; pendingLinkRemoval = shopPrice.storeId })
                                }
                            }
                        }) else null)
                }
            }
            if (historyOpen) item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Походов" to closedTripCount(rows).toString(), "Интервал" to (interval?.let { "$it дн." } ?: "—")).forEach { (label, value) ->
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(p.card).padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(value, color = p.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                            Text(label, color = p.textSecondary, fontSize = 12.sp)
                        }
                    }
                }
            }
            if (detailTab == 0) item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    FreshCutSectionTitle("Динамика цен", Modifier.weight(1f))
                    TextButton(onClick = { priceFiltersOpen = true }) { Text("Фильтры", color = p.text) }
                }
                HistoryCard {
                    Text("${pricePeriod.title} · ${storeLabel(priceStoreFilter)} · ${if (effectiveUnit == "kg") "за кг" else "за шт."}",
                        color = p.textSecondary, fontSize = 12.sp)
                    if (points.isEmpty()) Text("Нет данных за период", color = p.textSecondary)
                    else PriceChart(points, zone)
                }
            }
            if (historyOpen) {
                item { FreshCutSectionTitle("Покупки") }
                if (rows.isEmpty()) item { Text("История покупок пока пуста", color = p.textSecondary, fontSize = 13.sp) }
                rows.take(historyCount).groupBy { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }.forEach { (day, dayRows) ->
                    item(key = "day:$day") {
                        Text(day.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru-RU"))),
                            color = p.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    items(dayRows, key = { "event:${it.id}" }) { row ->
                        val shop = stores.firstOrNull { it.id == row.storeId }?.name ?: "Без магазина"
                        val amount = row.weightGrams?.let { BigDecimal.valueOf(it.toLong(), 3).stripTrailingZeros().toPlainString() + " кг" } ?: "${row.quantity} шт."
                        HistoryCard {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(shop, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                    Text(listOfNotNull(DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(row.at).atZone(zone)), buyers[row.buyer]).joinToString(" · "),
                                        color = p.textSecondary, fontSize = 12.sp)
                                }
                                Text(row.total?.let(::historyMoney) ?: "—", color = p.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                                IconButton(onClick = {
                                    editingPurchase = row
                                    purchasePrice = row.unitPrice?.let { BigDecimal.valueOf(it, 2).stripTrailingZeros().toPlainString() }.orEmpty()
                                    purchaseStore = stores.firstOrNull { it.id == row.storeId }; purchaseError = null
                                }) { Icon(painterResource(R.drawable.ic_edit), "Изменить покупку", Modifier.size(20.dp), tint = p.text) }
                            }
                            Text("$amount · ${row.unitPrice?.let(::historyMoney) ?: "—"}" + if (row.impulsive) " · Вне списка" else "",
                                color = p.textSecondary, fontSize = 13.sp)
                            if (row.trip != "active") TextButton(onClick = { openedTrip = row.trip }) { Text("Открыть список", color = p.text) }
                        }
                    }
                }
                if (historyCount < rows.size) item { FreshCutSecondaryButton("Показать ещё", { historyCount += 5 }) }
                val storePrices = rows.filter { it.unitPrice != null }.groupBy { it.storeId }
                if (storePrices.isNotEmpty()) item {
                    HistoryCard {
                        Row(Modifier.fillMaxWidth().clickable { historyStoresExpanded = !historyStoresExpanded }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("Цены прошлых покупок", Modifier.weight(1f), color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Icon(painterResource(R.drawable.ic_chevron_down), if (historyStoresExpanded) "Свернуть" else "Раскрыть", Modifier.size(20.dp).rotate(if (historyStoresExpanded) 180f else 0f), tint = p.text)
                        }
                        if (historyStoresExpanded) storePrices.forEach { (id, purchases) ->
                            val latest = purchases.maxBy { it.at }
                            CompactProductLine(stores.firstOrNull { it.id == id }?.name ?: "Без магазина", latest.unitPrice?.let(::historyMoney) ?: "—")
                        }
                    }
                }
                val trips = rows.filter { it.trip != "active" }.distinctBy { it.trip }
                if (trips.isNotEmpty()) item {
                    HistoryCard {
                        Row(Modifier.fillMaxWidth().clickable { historyTripsExpanded = !historyTripsExpanded }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("Походы с товаром · ${trips.size}", Modifier.weight(1f), color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Icon(painterResource(R.drawable.ic_chevron_down), if (historyTripsExpanded) "Свернуть" else "Раскрыть", Modifier.size(20.dp).rotate(if (historyTripsExpanded) 180f else 0f), tint = p.text)
                        }
                        if (historyTripsExpanded) {
                            trips.take(tripCount).forEach { row ->
                                TextButton(onClick = { openedTrip = row.trip }) { Text(formatter.format(Instant.ofEpochMilli(row.at).atZone(zone)), color = p.text) }
                            }
                            if (tripCount < trips.size) FreshCutSecondaryButton("Показать ещё", { tripCount += 5 })
                        }
                    }
                }
            }
        }
        }
        if (detailTab == 0 && (ownProduct != null || product != null)) Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            SheetActionButton(onClick = {
                if (ownProduct != null) viewModel.addCustomProduct(ownProduct) { added ->
                    activity?.notifyUser(if (added) "Добавлено в список" else "Уже в списке")
                } else if (product != null) viewModel.addProduct(product) { added ->
                    activity?.notifyUser(if (added) "Добавлено в список" else "Уже в списке")
                }
            }) { Text("Добавить в список", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        }
        navigation()
    }
}

@Composable
private fun HistoryChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Text(label, Modifier.clip(RoundedCornerShape(20.dp)).background(if (selected) p.actionFill else p.field)
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        color = if (selected) p.actionInk else p.textSecondary, fontSize = 13.sp)
}

@Composable
private fun PriceChart(points: List<ExpensePurchase>, zone: ZoneId) {
    if (points.isEmpty()) return
    val p = LocalPalette.current
    val values = points.mapNotNull { it.unitPrice }
    val min = values.min()
    val max = values.max()
    val padding = ((max.toDouble() - min) * .12).coerceAtLeast(max.toDouble() * .025).coerceAtLeast(100.0)
    val lo = (min - padding).coerceAtLeast(0.0)
    val hi = max + padding
    val first = points.first().at
    val last = points.last().at
    var selected by remember(points) { mutableIntStateOf(points.lastIndex) }
    val current = points[selected]
    val date = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    val shortDate = DateTimeFormatter.ofPattern("dd.MM")
    val axis = listOf(hi, (hi+lo)/2, lo)
    val chartColor = p.text
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(historyMoney(current.unitPrice!!), color = p.text, fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
            Text(date.format(Instant.ofEpochMilli(current.at).atZone(zone)), color = p.textSecondary, fontSize = 12.sp)
        }
        if (points.size > 1) {
            IconButton(onClick = { selected-- }, enabled = selected > 0) { Text("‹", fontSize = 24.sp, color = p.text) }
            IconButton(onClick = { selected++ }, enabled = selected < points.lastIndex) { Text("›", fontSize = 24.sp, color = p.text) }
        }
    }
    Row(Modifier.fillMaxWidth().height(168.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            axis.forEach { Text(historyMoney(it.toLong()), color = p.textSecondary, fontSize = 10.sp) }
        }
        Canvas(Modifier.weight(1f).fillMaxHeight().pointerInput(points) {
            detectTapGestures { tap ->
                val span = (last - first).coerceAtLeast(1L).toDouble()
                selected = points.indices.minBy { index ->
                    val x = if (last == first) size.width / 2f else ((points[index].at-first)/span*size.width).toFloat()
                    kotlin.math.abs(tap.x - x)
                }
            }
        }.semantics { contentDescription = "График цен. ${points.size} покупок. От ${historyMoney(min)} до ${historyMoney(max)}" }) {
            val pad = 7.dp.toPx()
            val width = (size.width - pad*2).coerceAtLeast(1f)
            val height = (size.height - pad*2).coerceAtLeast(1f)
            val span = (last-first).coerceAtLeast(1L).toDouble()
            val coords = points.map { point ->
                val x = if (last == first) .5 else (point.at-first)/span
                Offset(pad + width*x.toFloat(), pad + height*(1f-((point.unitPrice!!.toDouble()-lo)/(hi-lo)).toFloat()))
            }
            repeat(3) { index ->
                val y = pad + height*index/2
                drawLine(p.line, Offset(0f,y), Offset(size.width,y), strokeWidth = 1.dp.toPx())
            }
            if (coords.size > 1) {
                val line = Path().apply { moveTo(coords.first().x, coords.first().y); coords.drop(1).forEach { lineTo(it.x,it.y) } }
                val area = Path().apply {
                    moveTo(coords.first().x, pad+height); coords.forEach { lineTo(it.x,it.y) }
                    lineTo(coords.last().x,pad+height); close()
                }
                drawPath(area, Brush.verticalGradient(listOf(chartColor.copy(alpha=.15f), chartColor.copy(alpha=0f))))
                drawPath(line, chartColor, style = Stroke(width=2.dp.toPx(), cap=StrokeCap.Round))
            }
            val chosen = coords[selected]
            drawLine(p.textSecondary.copy(alpha=.5f), Offset(chosen.x,pad), Offset(chosen.x,pad+height), strokeWidth=1.dp.toPx())
            coords.forEachIndexed { index, pt ->
                if (coords.size <= 24 || index == selected) drawCircle(chartColor, if (index==selected) 5.dp.toPx() else 2.5.dp.toPx(), pt)
            }
            drawCircle(p.card, 2.dp.toPx(), chosen)
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(shortDate.format(Instant.ofEpochMilli(first).atZone(zone)), color = p.textSecondary, fontSize = 11.sp)
        if (first != last) Text(shortDate.format(Instant.ofEpochMilli(last).atZone(zone)), color = p.textSecondary, fontSize = 11.sp)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Минимум" to min, "Максимум" to max).forEach { (label, value) ->
            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(p.field).padding(10.dp)) {
                Text(label, color = p.textSecondary, fontSize = 11.sp)
                Text(historyMoney(value), color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun CompactProductLine(label: String, value: String) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), color = p.textSecondary, fontSize = 13.sp)
        Text(value, Modifier.weight(1f), color = p.text, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
private fun HistoryCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(LocalPalette.current.card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
}
