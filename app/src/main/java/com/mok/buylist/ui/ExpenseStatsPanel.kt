package com.mok.buylist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mok.buylist.data.ShoppingStore
import com.mok.buylist.R
import com.mok.buylist.data.lentaStoreId
import com.mok.buylist.ui.theme.LocalPalette
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

private fun money(value: Long): String = NumberFormat.getNumberInstance(Locale.forLanguageTag("ru-RU"))
    .apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }.format(BigDecimal.valueOf(value, 2)) + " ₽"

@Composable
internal fun ExpenseStatsPanel(all: List<ExpensePurchase>, userId: String?, period: ExpensePeriod, closedTrips: List<com.mok.buylist.data.ArchivedList>, stores: List<ShoppingStore> = emptyList(), onProductClick: (String) -> Unit, section: Int = 0, onPeriodChange: (ExpensePeriod) -> Unit) {
    val p = LocalPalette.current
    val zone = ZoneId.systemDefault()
    val today = remember { LocalDate.now(zone) }
    var interval by rememberSaveable { mutableStateOf(ExpenseInterval.WEEK) }
    val mine = remember(all, userId) { all.filter { userId == null || it.buyer == userId } }
    val start = periodStart(period, today)
    val selected = remember(mine, start, today) { mine.filter { !expenseDate(it, zone).isAfter(today) && (start == null || !expenseDate(it, zone).isBefore(start)) } }
    val allCoverage = remember(mine) { purchaseCoverage(mine) }
    val coverage = remember(selected) { purchaseCoverage(selected) }
    val closedIds = remember(closedTrips) { closedTrips.map { it.uid }.toSet() }
    val closedPurchases = remember(mine, closedIds) { mine.filter { it.trip in closedIds } }
    val closedByStore = remember(closedPurchases) { expenseByStore(closedPurchases).associateBy { it.id } }
    val closedByProduct = remember(closedPurchases) { frequentProducts(closedPurchases).associateBy { it.key } }
    val pricedClosed = remember(closedPurchases) { closedPurchases.filter { it.total != null } }
    val pricedTrips = closedTripCount(pricedClosed)
    var storeLimit by rememberSaveable { mutableIntStateOf(5) }
    var productLimit by rememberSaveable { mutableIntStateOf(5) }
    var impulseLimit by rememberSaveable { mutableIntStateOf(3) }

    if (section == 0) PanelCard {
        Text("Расходы за выбранный период", color = p.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(tripCountLabel(closedTrips.size), color = p.textSecondary, fontSize = 12.sp)
        if (coverage.priced == 0) {
            Text(stringResource(R.string.stats_no_prices_title), color = p.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.stats_no_prices_description), color = p.textSecondary, fontSize = 13.sp)
        } else {
        Text(money(coverage.amount), color = p.text, fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold)
        if (coverage.unpriced > 0) Text(
            stringResource(R.string.stats_partial_prices, coverage.priced, coverage.purchases),
            color = p.textSecondary, fontSize = 13.sp)
        val first = start ?: selected.minOfOrNull { expenseDate(it, zone) } ?: today
        val length = (java.time.temporal.ChronoUnit.DAYS.between(first, today) + 1).coerceAtLeast(1L)
        val bucketCount = minOf(7L, length).toInt()
        val buckets = (0 until bucketCount).map { index ->
            val from = first.plusDays(length * index / bucketCount)
            val until = first.plusDays(length * (index + 1) / bucketCount - 1)
            val priced = selected.filter { row -> val day = expenseDate(row, zone); !day.isBefore(from) && !day.isAfter(until) && row.total != null }
            Triple(from, until, if (priced.isEmpty()) null else expenseSum(priced))
        }
        val peak = buckets.mapNotNull { it.third }.maxOrNull()?.coerceAtLeast(1L) ?: 1L
        var activeBucket by remember(buckets) { mutableIntStateOf(-1) }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = 4.dp
            val bucketWidth = maxOf(UiMetrics.touchTarget, (maxWidth - gap * (buckets.size - 1)) / buckets.size)
            Row(Modifier.fillMaxWidth().height(104.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
                buckets.forEachIndexed { index, bucket ->
                    val total = bucket.third
                    val action = if (total != null) Modifier
                        .clickable(role = androidx.compose.ui.semantics.Role.Button) { activeBucket = if (index == activeBucket) -1 else index }
                        .semantics {
                            contentDescription = "${bucket.first} – ${bucket.second}: ${money(total)}"
                            this.selected = index == activeBucket
                        } else Modifier
                    Box(Modifier.width(bucketWidth).fillMaxHeight().then(action), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
                        if (total != null) Box(Modifier.fillMaxWidth(0.70f)
                            .fillMaxHeight((total.toDouble() / peak).toFloat().coerceIn(0.03f, 1f))
                            .clip(RoundedCornerShape(7.dp)).background(if (index == activeBucket) p.text else p.actionFill))
                        else Box(Modifier.fillMaxWidth(0.70f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(p.line))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val label = DateTimeFormatter.ofPattern("dd.MM")
            Text(first.format(label), color = p.textSecondary, fontSize = 10.sp)
            Text(today.format(label), color = p.textSecondary, fontSize = 10.sp)
        }
        buckets.getOrNull(activeBucket)?.third?.let { Text(money(it), color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
    if (section == 0 && coverage.purchases > 0) {
        FreshCutSectionTitle("Что купили", Modifier.padding(top = 8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ExpenseMetric("Товаров", coverage.distinctProducts.toString(), Modifier.weight(1f))
            ExpenseMetric("Единиц", coverage.units.toString(), Modifier.weight(1f))
        }
    }

    val byStore = remember(selected) { expenseByStore(selected) }
    if (section == 1 && byStore.isEmpty()) FreshCutEmptyState(R.drawable.ic_chart, stringResource(R.string.empty_statistics_title),
        description = stringResource(R.string.empty_period_description), fillAvailable = false)
    if (section == 1 && byStore.isNotEmpty()) {
        FreshCutSectionTitle("По магазинам", Modifier.padding(top = 8.dp))
        val visible = byStore.take(storeLimit)
        visible.forEach { store ->
            val title = when {
                store.id.isBlank() -> "Магазин не указан"
                store.id == lentaStoreId() -> "Лента"
                else -> stores.firstOrNull { it.id == store.id }?.name ?: "Другой магазин"
            }
            FreshCutListRow(R.drawable.ic_store, title, tripCountLabel(closedByStore[store.id]?.trips ?: 0),
                value = if (store.priced > 0) money(store.total) else stringResource(R.string.stats_no_prices_short))
        }
        if (byStore.size > storeLimit) FreshCutSecondaryButton("Показать ещё", { storeLimit += 10 })
    }
    if (section == 0) PanelCard {
        Text("Расходы подробнее", color = p.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        FreshCutDetailLine("За всё время", if (allCoverage.priced > 0) money(allCoverage.amount) else stringResource(R.string.stats_no_prices_short))
        if (pricedTrips > 0) FreshCutDetailLine("Средний чек", money(expenseSum(pricedClosed) / pricedTrips))
    }

    val topProducts = remember(selected, closedByProduct) {
        frequentProducts(selected).sortedWith(compareByDescending<FrequentProductSummary> { closedByProduct[it.key]?.trips ?: 0 }
            .thenByDescending { it.units }.thenBy { it.name })
    }
    if (section == 2) {
        FreshCutSectionTitle("Чаще всего покупаю")
        if (topProducts.isEmpty()) FreshCutEmptyState(R.drawable.ic_chart, stringResource(R.string.empty_statistics_title),
            description = stringResource(R.string.empty_period_description), fillAvailable = false)
        val pricedProductKeys = remember(selected) { selected.filter { it.total != null }.mapTo(HashSet()) { it.productKey } }
        topProducts.take(productLimit).forEach { product ->
            FreshCutListRow(R.drawable.ic_package, product.name,
                "${tripCountLabel(closedByProduct[product.key]?.trips ?: 0)} · ${product.units} ед.",
                value = if (product.key in pricedProductKeys) money(product.knownTotal) else null,
                onClick = { onProductClick(product.key) })
        }
        if (topProducts.size > productLimit) FreshCutSecondaryButton("Показать ещё", { productLimit += 10 })
        val priced = selected.filter { it.unitPrice != null }
        if (priced.isNotEmpty()) {
            FreshCutSectionTitle("Цены покупок")
            listOf("Самая низкая цена" to priced.minBy { it.unitPrice!! },
                "Самая высокая цена" to priced.maxBy { it.unitPrice!! }).forEach { (label, row) ->
                FreshCutListRow(R.drawable.ic_tag, row.name, label, value = row.unitPrice?.let(::money),
                    onClick = { onProductClick(row.productKey) })
            }
        }
    }

    val impulsive = selected.filter { it.impulsive }
    if (section == 2 && impulsive.isNotEmpty()) PanelCard {
        Text("Импульсивные покупки", color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        val impulseCoverage = purchaseCoverage(impulsive)
        Text("${impulseCoverage.units} ед." +
            if (impulseCoverage.priced > 0) " · " + money(impulseCoverage.amount) else "",
            color = p.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        val date = DateTimeFormatter.ofPattern("dd.MM.yyyy")
        val groups = impulsive.groupBy { it.trip }.values
            .sortedByDescending { rows -> rows.maxOf { it.at } }
        groups.take(impulseLimit).forEach { rows ->
            val name = if (rows.first().trip == "active") "Текущий список" else date.format(expenseDate(rows.maxBy { it.at }, zone))
            val priced = rows.filter { it.total != null }
            Text(listOfNotNull(name, rows.size.toString(),
                priced.takeIf { it.isNotEmpty() }?.let { money(expenseSum(it)) })
                .joinToString(" · "), color = p.textSecondary, fontSize = 14.sp)
        }
        if (groups.size > impulseLimit) FreshCutSecondaryButton("Показать ещё", { impulseLimit += 10 })
    }

    if (section == 3) PanelCard {
        Text("Динамика расходов", color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        FilterSelect("Группировка", interval.title, ExpenseInterval.entries.map { it.title }) { interval = ExpenseInterval.entries[it] }
        val allGrouped = selected.groupBy {
            val day = expenseDate(it, zone)
            when (interval) {
                ExpenseInterval.DAY -> day
                ExpenseInterval.WEEK -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                ExpenseInterval.MONTH -> day.withDayOfMonth(1)
            }
        }.toSortedMap()
        val last = when (interval) {
            ExpenseInterval.DAY -> today
            ExpenseInterval.WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            ExpenseInterval.MONTH -> today.withDayOfMonth(1)
        }
        val first = start ?: allGrouped.keys.firstOrNull() ?: today
        val dates = generateSequence(last) { day ->
            when (interval) { ExpenseInterval.DAY -> day.minusDays(1); ExpenseInterval.WEEK -> day.minusWeeks(1); ExpenseInterval.MONTH -> day.minusMonths(1) }
        }.takeWhile { day ->
            val end = when (interval) { ExpenseInterval.DAY -> day.plusDays(1); ExpenseInterval.WEEK -> day.plusWeeks(1); ExpenseInterval.MONTH -> day.plusMonths(1) }
            end.isAfter(first)
        }.toList().asReversed()
        val bucketSize = ((dates.size + 6) / 7).coerceAtLeast(1)
        val buckets = dates.chunked(bucketSize)
        val totals = buckets.map { bucket ->
            val priced = bucket.flatMap { allGrouped[it].orEmpty() }.filter { it.total != null }
            if (priced.isEmpty()) null else expenseSum(priced)
        }
        val peak = totals.filterNotNull().maxOrNull() ?: 0
        if (totals.none { it != null }) {
            Text(stringResource(R.string.stats_no_prices_title), color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.stats_no_prices_description), color = p.textSecondary, fontSize = 13.sp)
        }
        if (totals.any { it != null }) {
            var selectedBucket by remember(buckets, allGrouped) {
                mutableIntStateOf(totals.indexOfLast { it != null })
            }
            val chosen = buckets.getOrNull(selectedBucket)
            if (chosen != null) {
                val from = maxOf(chosen.first(), start ?: chosen.first())
                val until = when (interval) {
                    ExpenseInterval.DAY -> chosen.last()
                    ExpenseInterval.WEEK -> chosen.last().plusDays(6)
                    ExpenseInterval.MONTH -> chosen.last().plusMonths(1).minusDays(1)
                }.coerceAtMost(today)
                val dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy")
                val label = from.format(dateFormat) + if (from == until) "" else " – ${until.format(dateFormat)}"
                Text("$label · ${money(totals[selectedBucket]!!)}",
                    color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = 4.dp
            val bucketWidth = maxOf(UiMetrics.touchTarget, (maxWidth - gap * (buckets.size - 1)) / buckets.size)
            Row(Modifier.fillMaxWidth().height(150.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
                buckets.forEachIndexed { index, bucket ->
                    val total = totals[index]
                    val label = bucket.first().format(DateTimeFormatter.ofPattern("dd.MM"))
                    val selection = if (total != null) Modifier
                        .clickable(role = androidx.compose.ui.semantics.Role.Button) { selectedBucket = index }
                        .semantics {
                            contentDescription = "$label: ${money(total)}"
                            this.selected = index == selectedBucket
                        } else Modifier
                    Column(Modifier.width(bucketWidth).fillMaxHeight().then(selection), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
                            if (total != null) Box(Modifier.fillMaxWidth(0.65f)
                                .fillMaxHeight((total.toDouble() / peak.coerceAtLeast(1)).toFloat().coerceIn(0.03f, 1f))
                                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)).background(if (selectedBucket == index) p.accent else p.ring))
                        }
                        Text(label, color = p.textSecondary, fontSize = 10.sp, maxLines = 1)
                    }
                }
            }
            }
        }
    }


}

@Composable
private fun ExpenseMetric(label: String, value: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Column(modifier.heightIn(min = 86.dp).clip(RoundedCornerShape(16.dp)).background(p.card).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, androidx.compose.ui.Alignment.CenterVertically)) {
        Text(value, color = p.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(label, color = p.textSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun MetricBar(label: String, value: String, fraction: Float) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(value, color = p.textSecondary, fontSize = 13.sp)
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(p.boughtBg)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).background(p.accent))
        }
    }
}

@Composable
private fun PanelCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(LocalPalette.current.card).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Text(label, Modifier.clip(RoundedCornerShape(24.dp)).background(if (selected) p.accent else p.chipBg)
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        color = if (selected) p.onAccent else p.chipText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
}
