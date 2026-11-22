package com.mok.buylist.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mok.buylist.lentaCatalog
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mok.buylist.R
import com.mok.buylist.data.AppUser
import com.mok.buylist.data.ArchivedList
import com.mok.buylist.data.ShoppingItem
import com.mok.buylist.ui.theme.LocalPalette
import com.mok.buylist.ui.theme.Motion
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

/**
 * Статистика по завершённым спискам из архива. Выезжает поверх архива, как и он поверх главного экрана.
 * Общая по умолчанию; если покупали несколько человек, сверху чипы «Все / имя / имя» — смотреть по каждому отдельно.
 */
@Composable
fun StatsScreen(
    archive: List<ArchivedList>,
    bought: List<ShoppingItem>,
    users: List<AppUser>,
    meId: String,
    onBack: () -> Unit,
    viewModel: ShoppingViewModel,
) {
    val p = LocalPalette.current
    val buyers = remember(archive, bought, users) {
        val ids = bought.filter { it.deleted }.map { it.authorId }.toSet()
        (buyersIn(archive, users) + users.filter { it.id in ids }).distinctBy { it.id }
    }
    val catalog = LocalContext.current.lentaCatalog
    val lentaProducts by catalog.products.collectAsStateWithLifecycle()
    val barcodeRows by catalog.barcodeRows.collectAsStateWithLifecycle()
    val customProducts by viewModel.customProducts.collectAsStateWithLifecycle()
    val purchases = remember(archive, bought, customProducts, lentaProducts, barcodeRows) {
        mergeCatalogPriceHistory(expensePurchases(archive, bought), customProducts, lentaProducts, barcodeRows)
    }
    var selectedUser by rememberSaveable { mutableStateOf<String?>(null) }
    var period by rememberSaveable { mutableStateOf(ExpensePeriod.MONTH) }
    val zone = ZoneId.systemDefault()
    val today = remember { java.time.LocalDate.now(zone) }
    // Выбранного человека могло не стать (например, после обмена): тогда снова показываем всех.
    val userId = selectedUser?.takeIf { id -> buyers.any { it.id == id } }
    val shownArchive = remember(archive, userId, period) {
        val byUser = if (userId == null) archive else filterByUser(archive, userId)
        archiveForPeriod(byUser, periodStart(period, today), today, zone)
    }
    val stats = remember(shownArchive) { computeStats(shownArchive, System.currentTimeMillis(), ZoneId.systemDefault()) }
    // Столбики «вырастают» при открытии экрана: один раз, без повторов при прокрутке.
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var selectedProduct by rememberSaveable { mutableStateOf<String?>(null) }
    var monthly by rememberSaveable { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    val historyPurchases = remember(purchases, userId, period, today) {
        val start = periodStart(period, today)
        purchases.filter { row ->
            val day = expenseDate(row, zone)
            (userId == null || row.buyer == userId) && !day.isAfter(today) && (start == null || !day.isBefore(start))
        }
    }
    if (selectedProduct != null) {
        ProductDetailsScreen(selectedProduct!!, purchases.filter { userId == null || it.buyer == userId }, users, viewModel, onBack = { selectedProduct = null })
        return
    }
    if (showHistory) {
        BackHandler { showHistory = false }
        ProductHistoryScreen(historyPurchases, onProductClick = { selectedProduct = it }, onBack = { showHistory = false })
        return
    }

    Column(Modifier.fillMaxSize().background(p.background)) {
        SubScreenHeader(stringResource(R.string.stats_title), onBack)
        if (buyers.size > 1) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                UserChip(stringResource(R.string.stats_everyone), selected = userId == null) { selectedUser = null }
                buyers.forEach { user ->
                    UserChip(
                        if (user.id == meId) stringResource(R.string.stats_you, user.name) else user.name,
                        selected = userId == user.id,
                    ) { selectedUser = user.id }
                }
            }
        }
        if (stats.lists == 0 && purchases.none { userId == null || it.buyer == userId }) {
            EmptyStats(Modifier.weight(1f))
        } else {
            LazyColumn(
                Modifier
                    .weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp,
                    bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ExpenseStatsPanel(purchases, userId, period, onProductClick = { selectedProduct = it }) { period = it }
                } }
                if (stats.lists > 0) item { Card {
                    Text(stringResource(R.string.stats_overview), color = p.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberCard(stats.lists.toString(), stringResource(R.string.stats_lists), Modifier.weight(1f))
                        NumberCard(stats.items.toString(), stringResource(R.string.stats_items), Modifier.weight(1f))
                        NumberCard(stats.averageItems.toString(), stringResource(R.string.stats_average_label), Modifier.weight(1f))
                    }
                } }
                item { WeeksCard(if (monthly) stats.months else stats.weeks, shown, monthly) { monthly = it } }
                stats.busiestDay?.let { day ->
                    item {
                    val name = day.getDisplayName(TextStyle.FULL_STANDALONE, LocalConfiguration.current.locales[0])
                    Card {
                        Text(
                            stringResource(R.string.stats_busiest_day),
                            color = p.textSecondary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            name.replaceFirstChar { it.uppercase() },
                            color = p.text,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    } }
                }
            }
        }
    }
}

@Composable
private fun HistoryButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.card)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(p.boughtBg), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_history), contentDescription = null, modifier = Modifier.size(21.dp), tint = p.accent)
        }
        Text(stringResource(R.string.stats_product_history), Modifier.weight(1f), color = p.text, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
        Text("›", color = p.textSecondary, fontSize = 24.sp)
    }
}

@Composable
private fun UserChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier
            .height(40.dp)
            .clip(CircleShape)
            .background(if (selected) p.accent else p.chipBg)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) p.onAccent else p.chipText,
            fontSize = 15.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(p.card)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        content()
    }
}

@Composable
private fun NumberCard(value: String, label: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(value, color = p.accent, fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, color = p.textSecondary, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TripCard(averageMinutes: Int, totalMinutes: Int) {
    val p = LocalPalette.current
    Card {
        Text(stringResource(R.string.stats_trip_average), color = p.textSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(durationText(averageMinutes), color = p.accent, fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold)
        Text(
            stringResource(R.string.stats_trip_total, durationText(totalMinutes)),
            Modifier.padding(top = 4.dp),
            color = p.textSecondary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun WeeksCard(weeks: List<WeekBar>, shown: Boolean, monthly: Boolean, onMonthlyChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    val locale = LocalConfiguration.current.locales[0]
    val label = remember(locale, monthly) { DateTimeFormatter.ofPattern(if (monthly) "MM.yy" else "d.MM", locale) }
    val max = weeks.maxOf { it.lists }.coerceAtLeast(1)
    Card {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.stats_activity), Modifier.weight(1f), color = p.text, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            PeriodChip(stringResource(R.string.stats_by_week), !monthly) { onMonthlyChange(false) }
            PeriodChip(stringResource(R.string.stats_by_month), monthly) { onMonthlyChange(true) }
        }
        Text(stringResource(if (monthly) R.string.stats_months_subtitle else R.string.stats_weeks_subtitle), color = p.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().height(132.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            weeks.forEachIndexed { index, week ->
                val current = index == weeks.lastIndex
                val fraction = animateFloatAsState(
                    if (shown) week.lists.toFloat() / max else 0f,
                    Motion.smooth(Motion.Slow),
                    label = "week-bar",
                )
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                            if (week.lists > 0) {
                                Text(
                                    week.lists.toString(),
                                    color = p.text,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    textAlign = TextAlign.Center,
                                )
                                Spacer(Modifier.height(2.dp))
                            }
                            Box(
                                Modifier
                                    .fillMaxWidth(0.65f)
                                    .height(maxOf(4.dp, 80.dp * fraction.value))
                                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 3.dp, bottomEnd = 3.dp))
                                    .background(if (week.lists == 0) p.boughtBg else if (current) p.accent else p.ring),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        label.format(week.start),
                        color = if (current) p.text else p.textSecondary,
                        fontSize = 11.sp,
                        fontWeight = if (current) FontWeight.ExtraBold else FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun PeriodChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(Modifier.height(36.dp).clip(CircleShape).background(if (selected) p.accent else p.chipBg)
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (selected) p.onAccent else p.chipText, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun EmptyStats(modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Box(
            Modifier.padding(bottom = 16.dp).size(132.dp).clip(CircleShape).background(p.boughtBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_chart), contentDescription = null, Modifier.size(64.dp), tint = p.accent)
        }
        Text(
            stringResource(R.string.stats_empty_title),
            color = p.text,
            fontSize = 22.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.ExtraBold,
        )
    }
}

