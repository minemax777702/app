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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        purchases.filter { it.productKey.startsWith("lenta:") }
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
    val rows = remember(purchases, productKey) { purchases.filter { it.productKey == productKey }.sortedByDescending { it.at } }
    val catalog = LocalContext.current.lentaCatalog
    val products by catalog.products.collectAsStateWithLifecycle()
    val barcodeRows by catalog.barcodeRows.collectAsStateWithLifecycle()
    val address by catalog.address.collectAsStateWithLifecycle()
    val refresh by viewModel.priceRefresh.collectAsStateWithLifecycle()
    val product = products.firstOrNull { "lenta:${it.id}" == productKey }
    val barcodes = product?.let { item -> barcodeRows.filter { it.productId == item.id && !it.deleted }.map { it.code } }?.sorted().orEmpty()
    val activity = androidx.activity.compose.LocalActivity.current as? MainActivity
    val scope = rememberCoroutineScope()
    var editingBarcode by remember(productKey) { mutableStateOf<String?>(null) }
    var barcodeInput by remember(productKey) { mutableStateOf("") }
    var barcodeError by remember(productKey) { mutableStateOf<String?>(null) }
    var deletingBarcode by remember(productKey) { mutableStateOf<String?>(null) }
    var barcodeEditorOpen by remember(productKey) { mutableStateOf(false) }
    var scanningBarcode by remember(productKey) { mutableStateOf(false) }
    var barcodeSaving by remember(productKey) { mutableStateOf(false) }
    var historyCount by rememberSaveable(productKey) { mutableIntStateOf(5) }
    var tripCount by rememberSaveable(productKey) { mutableIntStateOf(5) }
    var openedTrip by rememberSaveable(productKey) { mutableStateOf<String?>(null) }
    val archive by viewModel.archive.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val days = remember(rows, zone) { rows.map { expenseDate(it, zone) }.distinct() }
    val interval = remember(days) { averagePurchaseInterval(days) }
    var pricePeriod by rememberSaveable { mutableStateOf(PricePeriod.MONTH) }
    val today = LocalDate.now(zone)
    val periodRows = remember(rows, pricePeriod, today, zone) { pricesForPeriod(rows, pricePeriod, today, zone) }
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
    if (barcodeEditorOpen && !scanningBarcode && product != null) {
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
                    )
                    SheetActionButton(onClick = {
                        barcodeEditorOpen = false
                        scanningBarcode = true
                    }, enabled = !barcodeSaving) { Text("Сканировать камерой") }
                    barcodeError?.let { Text(it, color = p.error, fontSize = 13.sp) }
                    Text("Проверьте распознанный код перед сохранением.", color = p.textSecondary, fontSize = 13.sp)
                }
            },
            confirmButton = {
                SheetActionButton(enabled = !barcodeSaving && barcodeInput.isNotBlank(), onClick = {
                    barcodeSaving = true
                    scope.launch {
                        try {
                            val previous = editingBarcode
                            val error = if (previous == null) catalog.addBarcode(product.id, barcodeInput)
                                else catalog.editBarcode(product.id, previous, barcodeInput)
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
                    val productId = product?.id ?: return@SheetActionButton
                    scope.launch {
                        val error = runCatching { catalog.removeBarcode(productId, code) }
                            .getOrElse { "Не удалось удалить штрихкод" }
                        deletingBarcode = null
                        if (error != null) activity?.notifyUser(error)
                    }
                }) { Text("Удалить") }
            },
        )
    }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(p.background)) {
        SubScreenHeader("Информация о товаре", onBack)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp,
            bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                HistoryCard {
                    Text(product?.name ?: rows.firstOrNull()?.name ?: "Товар не найден", color = p.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
            if (product != null) item {
                HistoryCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Штрихкоды товара", color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        TextButton(onClick = {
                            editingBarcode = null
                            barcodeInput = ""
                            barcodeError = null
                            barcodeEditorOpen = true
                        }) { Text("Добавить") }
                    }
                    if (barcodes.isEmpty()) Text("Нет сохранённых штрихкодов", color = p.textSecondary)
                    barcodes.forEach { code ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(code, color = p.text)
                                if (code == product.ean) Text("Текущий в Ленте", color = p.textSecondary, fontSize = 12.sp)
                            }
                            TextButton(onClick = {
                                editingBarcode = code
                                barcodeInput = code
                                barcodeError = null
                                barcodeEditorOpen = true
                            }) { Text("Изменить") }
                            TextButton(onClick = { deletingBarcode = code }) { Text("Удалить") }
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
                    if (summaries.isEmpty()) Text("Известных цен за период нет", color = p.textSecondary)
                    if (summaries.isNotEmpty()) {
                        PriceChart(points, zone)
                    }
                }
            }
            item { Text("История покупок", Modifier.padding(top = 8.dp), color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            items(rows.take(historyCount), key = { "event:${it.id}" }) { row ->
                HistoryCard {
                    Text(formatter.format(Instant.ofEpochMilli(row.at).atZone(zone)), color = p.text, fontWeight = FontWeight.Bold)
                    if (row.impulsive) Text("Импульсивная покупка", color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    row.unitPrice?.let { Text("Цена: ${historyMoney(it)}", color = p.text) }
                    val amount = row.weightGrams?.let { "${java.math.BigDecimal.valueOf(it.toLong(), 3).stripTrailingZeros().toPlainString()} кг" } ?: "Количество: ${row.quantity}"
                    Text(listOfNotNull(amount, row.total?.let { "Стоимость: ${historyMoney(it)}" }).joinToString(" · "), color = p.textSecondary)
                    if (row.buyer.isNotBlank()) Text("Покупатель: ${buyers[row.buyer] ?: row.buyer}", color = p.textSecondary)
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
