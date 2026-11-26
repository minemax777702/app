package com.mok.buylist.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.IconButton
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mok.buylist.lentaCatalog
import com.mok.buylist.data.decodePurchaseEvent
import com.mok.buylist.R
import com.mok.buylist.data.AppUser
import com.mok.buylist.data.ArchivedList
import com.mok.buylist.data.ShoppingStore
import com.mok.buylist.data.purchaseStoreName
import com.mok.buylist.data.tripMinutes
import com.mok.buylist.ui.theme.LocalPalette
import com.mok.buylist.ui.theme.Motion
import java.time.ZoneId

/** Сколько названий показывать в свёрнутой карточке. */
private const val PREVIEW_ITEMS = 3

@Composable
fun ArchiveScreen(
    archive: List<ArchivedList>,
    onBack: () -> Unit,
    onOpenStats: (() -> Unit)?,
    /** Какие товары вернуть в текущий список. */
    onRepeat: (names: List<String>, quantities: List<Int>, events: List<String>) -> Unit,
    onDelete: (ArchivedList) -> Unit,
    /** Известные пользователи: по ним подписывается, кто купил. Пока человек один, подписи не нужны. */
    users: List<AppUser>,
    meId: String,
    initiallyExpandedId: Long? = null,
    viewModel: ShoppingViewModel,
    navigation: @Composable () -> Unit = {},
) {
    val p = LocalPalette.current
    var expandedId by rememberSaveable(initiallyExpandedId) { mutableStateOf(initiallyExpandedId) }
    var pendingDelete by remember { mutableStateOf<ArchivedList?>(null) }
    var details by remember { mutableStateOf<Pair<ArchivedList, Int>?>(null) }
    var selectedProduct by rememberSaveable { mutableStateOf<String?>(null) }
    val allArchive by viewModel.archive.collectAsStateWithLifecycle()
    val stores by viewModel.stores.collectAsStateWithLifecycle()
    val bought by viewModel.bought.collectAsStateWithLifecycle()
    val now = remember { System.currentTimeMillis() }
    val userNames = remember(users) { users.associate { it.id to it.name } }
    val multiUser = users.size > 1
    if (selectedProduct != null) {
        ProductDetailsScreen(selectedProduct!!, remember(allArchive, bought) { expensePurchases(allArchive, bought) },
            users, viewModel, onBack = { selectedProduct = null })
        return
    }
    Column(Modifier.fillMaxSize().background(p.background)) {
        FreshCutRootHeader("ИСТОРИЯ", stringResource(R.string.archive_title), "Ваши завершённые покупки") {
            if (archive.isNotEmpty() && onOpenStats != null) {
                HeaderButton(R.drawable.ic_chart, stringResource(R.string.stats_open), onOpenStats)
            }
        }
        if (archive.isEmpty()) {
            EmptyArchive(Modifier.weight(1f))
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 0.dp,
                    bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(archive, key = { it.id }) { list ->
                    ArchiveCard(
                        list = list,
                        now = now,
                        expanded = expandedId == list.id,
                        onToggle = { expandedId = if (expandedId == list.id) null else list.id },
                        onRepeat = onRepeat,
                        onDelete = { pendingDelete = list },
                        onInfo = { index -> details = list to index },
                        buyers = remember(list) { listBuyers(list) },
                        userNames = userNames,
                        meId = meId,
                        showBuyers = multiUser,
                        stores = stores,
                        modifier = Modifier.animateItem(
                            fadeInSpec = Motion.smooth(),
                            placementSpec = Motion.settle(),
                            fadeOutSpec = Motion.smooth(Motion.Quick),
                        ),
                    )
                }
            }
        }
        navigation()
    }

    details?.let { (list, index) ->
        ArchiveProductDetails(list, index, userNames, meId, multiUser, stores,
            onProduct = { id -> details = null; selectedProduct = id }) { details = null }
    }

    pendingDelete?.let { list ->
        AppSheet(
            onDismissRequest = { pendingDelete = null },
            containerColor = p.sheet,
            title = { Text(stringResource(R.string.archive_delete_title), color = p.text, fontWeight = FontWeight.ExtraBold) },
            text = { Text(stringResource(R.string.archive_delete_text), color = p.textSecondary) },
            confirmButton = {
                SheetActionButton(onClick = { onDelete(list); pendingDelete = null }, destructive = true) {
                    Text(stringResource(R.string.archive_delete), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                }
            },
            dismissButton = {
                SheetActionButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                }
            },
        )
    }
}

/** «32 мин», «1 ч 05 мин», «2 ч». */
@Composable
internal fun durationText(minutes: Int): String = when {
    minutes < 60 -> stringResource(R.string.duration_minutes, minutes)
    minutes % 60 == 0 -> stringResource(R.string.duration_hours, minutes / 60)
    else -> stringResource(R.string.duration_hours_minutes, minutes / 60, minutes % 60)
}

/** Шапка вложенного экрана: «назад», заголовок и кнопки справа. */
@Composable
internal fun SubScreenHeader(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        HeaderButton(R.drawable.ic_back, "Назад", onBack)
        Spacer(Modifier.width(12.dp))
        Text(title, Modifier.weight(1f), color = p.text, fontSize = 24.sp,
            lineHeight = 30.sp, fontWeight = FontWeight.SemiBold)
        trailing()
    }
}

@Composable
internal fun HeaderButton(icon: Int, description: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(p.field)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = description, Modifier.size(24.dp), tint = p.onHeader)
    }
}

@Composable
private fun ArchiveCard(
    list: ArchivedList,
    now: Long,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRepeat: (names: List<String>, quantities: List<Int>, events: List<String>) -> Unit,
    onDelete: () -> Unit,
    onInfo: (Int) -> Unit,
    /** Кто купил: один человек подписывается на самом списке, несколько — на товарах. */
    buyers: ListBuyers,
    userNames: Map<String, String>,
    meId: String,
    showBuyers: Boolean,
    stores: List<ShoppingStore>,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val names = list.names
    val storeNames = list.events.mapNotNull { purchaseStoreName(decodePurchaseEvent(it), stores) }.distinct()
    val youLabel = stringResource(R.string.archive_buyer_you)
    fun buyerLabel(id: String): String = when {
        id.isEmpty() -> ""
        id == meId -> youLabel
        else -> userNames[id].orEmpty()
    }
    // Подпись на карточке: имя, если всё купил один; «Аня + Борис», если покупали вместе.
    val listLabel = if (!showBuyers) null else when (buyers) {
        is ListBuyers.One -> buyerLabel(buyers.id).ifEmpty { null }
        is ListBuyers.Mixed -> buyers.ids.map { buyerLabel(it) }.filter { it.isNotEmpty() }.distinct().joinToString(" + ").ifEmpty { null }
        ListBuyers.Unknown -> null
    }
    // Какие товары не нужны при повторе. Свернули карточку — выбор сбрасывается, снова всё отмечено.
    var skipped by remember(list.id, expanded) { mutableStateOf(emptySet<Int>()) }
    val chosenIndices = names.indices.filter { it !in skipped }
    val chosen = chosenIndices.map { names[it] }
    val today = stringResource(R.string.archive_today)
    val yesterday = stringResource(R.string.archive_yesterday)
    val locale = LocalConfiguration.current.locales[0]
    val date = remember(list.completedAt, now, today, yesterday, locale) {
        formatArchiveDate(list.completedAt, now, ZoneId.systemDefault(), today, yesterday, locale)
    }
    val arrow = animateFloatAsState(if (expanded) 180f else 0f, Motion.smooth(), label = "archive-arrow")
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(p.card)
            .clickable(role = Role.Button, onClick = onToggle)
            .animateContentSize(Motion.smooth())
            .padding(start = 18.dp, end = 10.dp, top = 14.dp, bottom = 14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(date, color = p.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    if (listLabel != null) {
                        Text(
                            listLabel,
                            Modifier
                                .clip(CircleShape)
                                .background(p.chipBg)
                                .padding(horizontal = 10.dp, vertical = 3.dp),
                            color = p.chipText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // «5 товаров · 32 мин». Времени нет у старых записей и у походов из одной покупки.
                val summary = listOfNotNull(
                    pluralStringResource(R.plurals.archive_items, list.itemCount, list.itemCount),
                ).joinToString(" · ")
                Text(
                    summary,
                    color = p.textSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (storeNames.isNotEmpty())
                    Text(if (storeNames.size == 1) storeNames.single()
                        else "Магазины: " + storeNames.joinToString(", "),
                        color = p.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(
                painterResource(R.drawable.ic_chevron_down),
                contentDescription = null,
                Modifier.size(24.dp).graphicsLayer { rotationZ = arrow.value },
                tint = p.iconMuted,
            )
            Spacer(Modifier.width(8.dp))
        }

        // Сумма отображается и в свёрнутом состоянии, только по фактическим покупкам.
        val spent = com.mok.buylist.data.archivedPurchaseTotal(list)
        if (spent.known > 0) Text(formatAmount(spent), Modifier.padding(top = 12.dp),
            color = p.text, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold)
        else Text("Без указанных цен", Modifier.padding(top = 8.dp), color = p.textSecondary, fontSize = 13.sp)

        if (!expanded) {
            val preview = names.take(PREVIEW_ITEMS).joinToString(", ") +
                if (names.size > PREVIEW_ITEMS) ", " + stringResource(R.string.archive_more, names.size - PREVIEW_ITEMS) else ""
            Text(
                preview,
                Modifier.padding(top = 6.dp, end = 8.dp),
                color = p.textSecondary,
                fontSize = 15.sp,
                lineHeight = 21.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (expanded) {
            Column(Modifier.padding(top = 10.dp, end = 8.dp)) {
                names.forEachIndexed { index, name ->
                    ArchiveItemRow(
                        name = name,
                        store = purchaseStoreName(decodePurchaseEvent(list.events[index]), stores),
                        impulsive = decodePurchaseEvent(list.events[index])?.impulsive == true,
                        checked = index !in skipped,
                        onToggle = { skipped = if (index in skipped) skipped - index else skipped + index },
                        onInfo = { onInfo(index) },
                    )
                }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(p.chipBorder.copy(alpha = 0.6f)))
                Row(
                    Modifier.fillMaxWidth().padding(top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Главное действие — широкая зелёная кнопка со значком; удаление — отдельная тихая кнопка-значок,
                    // чтобы по ней нельзя было промахнуться рядом с главной.
                    Row(
                        Modifier
                            .weight(1f)
                            .height(52.dp)
                            .clip(CircleShape)
                            .background(p.accent.copy(alpha = if (chosen.isEmpty()) 0.38f else 1f))
                            .clickable(enabled = chosen.isNotEmpty(), role = Role.Button) { onRepeat(chosen, chosenIndices.map { list.counts[it] }, chosenIndices.map { list.events[it] }) }
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(painterResource(R.drawable.ic_repeat), contentDescription = null, Modifier.size(20.dp), tint = p.onAccent)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.archive_repeat),
                            modifier = Modifier.weight(1f, fill = false),
                            color = p.onAccent,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (chosen.isNotEmpty() && chosen.size != names.size) {
                            Text(" (${chosen.size})", color = p.onAccent, fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold, maxLines = 1, softWrap = false)
                        }
                    }
                    val deleteLabel = stringResource(R.string.archive_delete)
                    Box(
                        Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(p.error.copy(alpha = if (p.isDark) 0.16f else 0.1f))
                            .clickable(role = Role.Button, onClickLabel = deleteLabel, onClick = onDelete),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(painterResource(R.drawable.ic_trash), contentDescription = deleteLabel, Modifier.size(22.dp), tint = p.error)
                    }
                }
            }
        }
    }
}

/** Товар в раскрытой карточке: нажатием отмечается, нужен ли он при повторе. */
@Composable
private fun ArchiveItemRow(name: String, store: String?, impulsive: Boolean, checked: Boolean, onToggle: () -> Unit, onInfo: () -> Unit) {
    val p = LocalPalette.current
    val on = animateFloatAsState(if (checked) 1f else 0f, Motion.smooth(Motion.Quick), label = "archive-check")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 1f - on.value }
                    .border(2.dp, p.ring, CircleShape),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val scale = 0.6f + 0.4f * on.value
                        scaleX = scale
                        scaleY = scale
                        alpha = on.value
                    }
                    .clip(CircleShape)
                    .background(p.accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_check), contentDescription = null, Modifier.size(15.dp), tint = p.onAccent)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = if (checked) p.text else p.textSecondary,
                fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (store != null) Text(store, color = p.textSecondary, fontSize = 12.sp)
            if (impulsive) Text("Импульсивная покупка", color = p.accent, fontSize = 12.sp)
        }
        IconButton(onClick = onInfo) {
            Icon(painterResource(R.drawable.ic_info), contentDescription = "Информация о товаре: $name", tint = p.iconMuted, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun ArchiveProductDetails(list: ArchivedList, index: Int, userNames: Map<String, String>, meId: String, showBuyers: Boolean,
    stores: List<ShoppingStore>, onProduct: (String) -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val event = remember(list, index) { decodePurchaseEvent(list.events[index]) }
    val products by LocalContext.current.lentaCatalog.products.collectAsStateWithLifecycle()
    val image = products.firstOrNull { it.id == event?.productId }?.image.orEmpty()
    val price = event?.unitPrice
    val total = price?.let { if (event.weightGrams != null) com.mok.buylist.data.priceForWeight(it, event.weightGrams)
        else runCatching { Math.multiplyExact(it, event.quantity.toLong()) }.getOrNull() }
    fun money(value: Long): String = java.text.NumberFormat.getNumberInstance(java.util.Locale.forLanguageTag("ru-RU"))
        .apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }
        .format(java.math.BigDecimal.valueOf(value, 2)) + " ₽"
    val name = event?.name ?: list.names.getOrNull(index).orEmpty()
    val buyerId = event?.buyer?.ifBlank { null } ?: list.buyerIds.getOrNull(index).orEmpty()
    val buyer = if (buyerId == meId) stringResource(R.string.archive_buyer_you)
        else userNames[buyerId].orEmpty()
    val store = purchaseStoreName(event, stores)
    val quantity = event?.quantity ?: list.counts.getOrNull(index) ?: 1
    val weight = event?.weightGrams
    val quantityText = weight?.let {
        java.math.BigDecimal.valueOf(it.toLong(), 3)
            .stripTrailingZeros().toPlainString() + " кг"
    } ?: "$quantity шт."
    val productKey = event?.customProductId?.takeIf { it.isNotBlank() }?.let { "custom:$it" }
        ?: event?.productId?.let { "lenta:$it" }

    AppSheet(
        onDismissRequest = onDismiss,
        containerColor = p.sheet,
        title = { SheetHeader("Покупка") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.fillMaxWidth().background(p.card, RoundedCornerShape(20.dp))
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val customId = event?.customProductId.orEmpty()
                    if (customId.isNotBlank()) CustomProductImage(customId, Modifier.size(88.dp))
                    else LentaImage(image, Modifier.size(88.dp))
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(name, color = p.text, fontWeight = FontWeight.Bold,
                            fontSize = 17.sp, lineHeight = 23.sp, maxLines = 4,
                            overflow = TextOverflow.Ellipsis)
                        Text(if (customId.isNotBlank()) "Свой товар" else "Товар из истории",
                            color = p.textSecondary, fontSize = 12.sp)
                    }
                }

                Column(
                    Modifier.fillMaxWidth().background(p.card, RoundedCornerShape(20.dp))
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Text("Стоимость покупки", color = p.textSecondary, fontSize = 12.sp)
                    Text(total?.let(::money) ?: "Цена не указана",
                        color = if (total == null) p.textSecondary else p.accent,
                        fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Количество", color = p.textSecondary, fontSize = 13.sp)
                        Text(quantityText, color = p.text, fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold)
                    }
                    if (price != null) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(if (weight != null) "Цена за кг" else "Цена за шт.",
                                color = p.textSecondary, fontSize = 13.sp)
                            Text(money(price), color = p.text, fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                if (store != null || (showBuyers && buyer.isNotBlank())) {
                    Text("Информация о покупке", color = p.text,
                        fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Column(
                        Modifier.fillMaxWidth().background(p.card, RoundedCornerShape(20.dp))
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        store?.let {
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("Магазин", color = p.textSecondary, fontSize = 12.sp)
                                Text(it, color = p.text, fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold)
                            }
                        }
                        if (showBuyers && buyer.isNotBlank()) {
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("Покупатель", color = p.textSecondary, fontSize = 12.sp)
                                Text(buyer, color = p.text, fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            productKey?.let { key ->
                SheetActionButton(onClick = { onProduct(key) }) {
                    Text("Открыть товар")
                }
            }
        },
    )
}

@Composable
private fun EmptyArchive(modifier: Modifier = Modifier) {
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
            Icon(painterResource(R.drawable.ic_history), contentDescription = null, Modifier.size(64.dp), tint = p.accent)
        }
        Text(
            stringResource(R.string.archive_empty_title),
            color = p.text,
            fontSize = 22.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text("Завершённые списки появятся здесь.", color = p.textSecondary, fontSize = 14.sp)
    }
}
