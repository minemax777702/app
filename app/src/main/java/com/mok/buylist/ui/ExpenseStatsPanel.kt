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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mok.buylist.data.ShoppingStore
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
internal fun ExpenseStatsPanel(all: List<ExpensePurchase>, userId: String?, period: ExpensePeriod, stores: List<ShoppingStore> = emptyList(), onProductClick: (String) -> Unit, onPeriodChange: (ExpensePeriod) -> Unit) {
    val p = LocalPalette.current
    val zone = ZoneId.systemDefault()
    val today = remember { LocalDate.now(zone) }
    var interval by rememberSaveable { mutableStateOf(ExpenseInterval.WEEK) }
    val mine = remember(all, userId) { all.filter { userId == null || it.buyer == userId } }
    val start = periodStart(period, today)
    val selected = remember(mine, start, today) { mine.filter { !expenseDate(it, zone).isAfter(today) && (start == null || !expenseDate(it, zone).isBefore(start)) } }
    val allCoverage = remember(mine) { purchaseCoverage(mine) }
    val coverage = remember(selected) { purchaseCoverage(selected) }
    val pricedTrips = selected.filter { it.total != null }.map { it.trip }.distinct().size
    var showMoreStores by rememberSaveable { mutableStateOf(false) }
    var showMoreProducts by rememberSaveable { mutableStateOf(false) }
    var showMoreImpulse by rememberSaveable { mutableStateOf(false) }

    PanelCard {
        Text("Расходы", color = p.text, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ExpensePeriod.entries.forEach { option -> Choice(option.title, option == period) { onPeriodChange(option) } }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExpenseMetric("За период", if (coverage.priced > 0) money(coverage.amount) else "—",
                Modifier.weight(1f))
            ExpenseMetric("За всё время",
                if (allCoverage.priced > 0) money(allCoverage.amount) else "—",
                Modifier.weight(1f))
        }
        if (pricedTrips > 0) ExpenseMetric("Средний чек по покупкам с ценой",
            money(coverage.amount / pricedTrips), Modifier.fillMaxWidth())
        if (coverage.unpriced > 0) Text(
            "Без цены: ${coverage.unpriced} из ${coverage.purchases} покупок. Они учитываются в количестве, но не в расходах.",
            color = p.textSecondary, fontSize = 12.sp)

    }

    val impulsive = mine.filter { it.impulsive }
    if (impulsive.isNotEmpty()) PanelCard {
        Text("Импульсивные покупки", color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        val total = exactExpenseSum(impulsive)
        Text(listOfNotNull(impulsive.size.toString(), total?.let(::money)).joinToString(" · "),
            color = p.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        val date = DateTimeFormatter.ofPattern("dd.MM.yyyy")
        impulsive.groupBy { it.trip }.values.sortedByDescending { rows -> rows.maxOf { it.at } }.forEach { rows ->
            val name = if (rows.first().trip == "active") "Текущий список" else date.format(expenseDate(rows.maxBy { it.at }, zone))
            val amount = exactExpenseSum(rows)
            Text(listOfNotNull(name, rows.size.toString(), amount?.let(::money)).joinToString(" · "), color = p.textSecondary, fontSize = 14.sp)
        }
    }

    PanelCard {
        Text("Динамика расходов", color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ExpenseInterval.entries.forEach { option -> Choice(option.title, option == interval) { interval = option } }
        }
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
        val totals = buckets.map { bucket -> exactExpenseSum(bucket.flatMap { allGrouped[it].orEmpty() }) }
        val peak = totals.filterNotNull().maxOrNull() ?: 0
        if (totals.none { it != null }) Text("Нет данных за период", color = p.textSecondary)
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
            Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
                buckets.forEachIndexed { index, bucket ->
                    val total = totals[index]
                    val label = bucket.first().format(DateTimeFormatter.ofPattern("dd.MM"))
                    val selection = if (total != null) Modifier
                        .clickable(role = androidx.compose.ui.semantics.Role.Button) { selectedBucket = index }
                        .semantics { contentDescription = "$label: ${money(total)}" } else Modifier
                    Column(Modifier.weight(1f).fillMaxHeight().then(selection), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
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

@Composable
private fun ExpenseMetric(label: String, value: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(p.field).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = p.textSecondary, fontSize = 12.sp)
        Text(value, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
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
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(LocalPalette.current.card).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Text(label, Modifier.clip(RoundedCornerShape(20.dp)).background(if (selected) p.accent else p.chipBg)
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        color = if (selected) p.onAccent else p.chipText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
}
