package com.mok.buylist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mok.buylist.R
import com.mok.buylist.ui.theme.LocalPalette
import com.mok.buylist.ui.theme.UiTextStyles

/** Общие размеры элементов интерфейса, не зависящие от темы. */
internal object UiMetrics {
    val screenInset = 20.dp
    val cardRadius = 20.dp
    val controlRadius = 16.dp
    val iconRadius = 14.dp
    val sheetRadius = 28.dp
    val touchTarget = 48.dp
    val fieldHeight = 52.dp
    val primaryHeight = 54.dp
    val rowHeight = 68.dp
    val gap = 8.dp
    val sectionGap = 12.dp
}

/** App / Empty state (Pixso 27:2137): единая геометрия для обеих тем. */
@Composable
internal fun FreshCutEmptyState(
    icon: Int,
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    fillAvailable: Boolean = true,
) {
    val p = LocalPalette.current
    val bounds = if (fillAvailable) Modifier.fillMaxSize() else Modifier.fillMaxWidth()
    BoxWithConstraints(modifier.then(bounds)) {
        val viewport = if (fillAvailable && constraints.hasBoundedHeight) {
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        } else Modifier.fillMaxWidth()
        Column(
            viewport.padding(
                horizontal = if (fillAvailable) UiMetrics.screenInset else 0.dp,
                vertical = UiMetrics.gap,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        ) {
            Box(
                Modifier.size(96.dp).clip(RoundedCornerShape(32.dp)).background(p.card),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(36.dp), tint = p.text)
            }
            Column(
                Modifier.fillMaxWidth().heightIn(min = 60.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    title,
                    modifier = Modifier.fillMaxWidth(),
                    color = p.text,
                    fontSize = 23.sp,
                    lineHeight = 28.sp,
                    letterSpacing = 0.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                if (description != null) {
                    Text(
                        description,
                        modifier = Modifier.fillMaxWidth(),
                        color = p.textSecondary,
                        fontSize = 14.sp,
                        lineHeight = 24.sp,
                        letterSpacing = 0.5.sp,
                        fontWeight = FontWeight.Normal,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

internal enum class FreshCutDestination { LIST, CATALOG, ARCHIVE, STATS, PROFILE }

@Composable
internal fun FreshCutNavigation(
    current: FreshCutDestination,
    onSelect: (FreshCutDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val destinations = listOf(
        Triple(FreshCutDestination.LIST, R.drawable.ic_shopping_cart, "Список"),
        Triple(FreshCutDestination.CATALOG, R.drawable.ic_package, "Товары"),
        Triple(FreshCutDestination.ARCHIVE, R.drawable.ic_archive, "Архив"),
        Triple(FreshCutDestination.STATS, R.drawable.ic_chart, "Статистика"),
        Triple(FreshCutDestination.PROFILE, R.drawable.ic_user_round, "Профиль"),
    )
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(UiMetrics.cardRadius))
        .background(p.card).border(1.dp, p.line, RoundedCornerShape(UiMetrics.cardRadius)).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        destinations.forEach { (destination, icon, title) ->
            val active = destination == current
            val ink = if (active) p.text else p.textSecondary
            Column(Modifier.weight(1f).heightIn(min = UiMetrics.primaryHeight).clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Tab) { if (!active) onSelect(destination) }
                .semantics { selected = active },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(24.dp, 3.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (active) p.text else p.card))
                Spacer(Modifier.height(4.dp))
                Icon(painterResource(icon), null, Modifier.size(24.dp), tint = ink)
                Text(title, color = ink, fontSize = 11.sp,
                    maxLines = if (LocalDensity.current.fontScale >= 1.3f) Int.MAX_VALUE else 2,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    overflow = TextOverflow.Ellipsis, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium)
            }
        }
    }
}

@Composable
internal fun FreshCutRootHeader(
    eyebrow: String,
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit = {},
) {
    val p = LocalPalette.current
    val headerMaxLines = if (LocalDensity.current.fontScale >= 1.3f) Int.MAX_VALUE else 2
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = UiMetrics.screenInset, end = UiMetrics.screenInset, top = 6.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = p.text, fontSize = 27.sp, lineHeight = 33.sp, fontWeight = FontWeight.SemiBold,
                maxLines = headerMaxLines, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, color = p.textSecondary, fontSize = 13.sp, lineHeight = 18.sp,
                maxLines = headerMaxLines, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

@Composable
internal fun FreshCutSectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier, color = LocalPalette.current.text, style = UiTextStyles.sectionTitle)
}

@Composable
internal fun FreshCutSegments(labels: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label ->
            val active = index == selectedIndex
            Box(Modifier.heightIn(min = UiMetrics.touchTarget).widthIn(min = 96.dp).clip(RoundedCornerShape(UiMetrics.controlRadius))
                .background(if (active) p.actionFill else p.field)
                .clickable(role = Role.Tab) { onSelect(index) }.semantics { selected = active }
                .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center) {
                Text(label, color = if (active) p.actionInk else p.text,
                    fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

/** Карточка 350 × 68 в макете, адаптивная по ширине и длине текста. */
@Composable
internal fun FreshCutListRow(
    icon: Int, title: String, subtitle: String = "", value: String? = null,
    selected: Boolean = false, onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    showChevron: Boolean = true,
) {
    val p = LocalPalette.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Keep important prices readable on narrow devices and at large text scales.
        val stackValue = value != null && (maxWidth < 340.dp || LocalDensity.current.fontScale >= 1.3f || value.length > 16)
        Row(Modifier.fillMaxWidth().heightIn(min = UiMetrics.rowHeight).clip(RoundedCornerShape(UiMetrics.cardRadius))
            .background(p.card).border(1.dp, if (selected) p.text else p.line, RoundedCornerShape(UiMetrics.cardRadius))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(UiMetrics.touchTarget).clip(RoundedCornerShape(UiMetrics.iconRadius)).background(p.field), contentAlignment = Alignment.Center) {
                if (leading != null) leading() else Icon(painterResource(icon), null, Modifier.size(24.dp), tint = p.text)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = p.text, style = UiTextStyles.rowTitle)
                if (subtitle.isNotBlank()) Text(subtitle, color = p.textSecondary, style = UiTextStyles.rowSubtitle)
                if (stackValue && value != null) Text(value, color = p.text, style = UiTextStyles.rowValue)
            }
            if (!stackValue && value != null) Text(value, Modifier.widthIn(max = 112.dp), color = p.text,
                style = UiTextStyles.rowValue, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            if (trailing != null) trailing() else if (selected) Icon(painterResource(R.drawable.ic_check), "Выбрано", Modifier.size(20.dp), tint = p.text)
            else if (showChevron && value == null && onClick != null) Icon(painterResource(R.drawable.ic_chevron_down), null, Modifier.size(20.dp).rotate(-90f), tint = p.textSecondary)
        }
    }
}

@Composable
internal fun FreshCutSecondaryButton(title: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val p = LocalPalette.current
    Box(modifier.fillMaxWidth().heightIn(min = UiMetrics.fieldHeight).clip(RoundedCornerShape(UiMetrics.controlRadius))
        .background(if (enabled) p.card else p.field).border(1.dp, p.line, RoundedCornerShape(UiMetrics.controlRadius))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(title, color = if (enabled) p.text else p.textSecondary, style = UiTextStyles.action, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
internal fun FreshCutDetailLine(label: String, value: String) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(1f), color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(value, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun FreshCutToolbarAction(icon: Int, label: String, onClick: () -> Unit, primary: Boolean = false, enabled: Boolean = true) {
    val p = LocalPalette.current
    Box(Modifier.size(UiMetrics.touchTarget).clip(RoundedCornerShape(UiMetrics.iconRadius))
        .background(if (primary && enabled) p.actionFill else p.field)
        .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), label, Modifier.size(24.dp),
            tint = if (!enabled) p.textSecondary else if (primary) p.actionInk else p.text)
    }
}

/** Равномерные вкладки раздела: не требуют горизонтальной прокрутки. */
@Composable
internal fun FreshCutTabs(labels: List<String>, current: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.field).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        labels.forEachIndexed { index, label ->
            val active = index == current
            Box(Modifier.weight(1f).heightIn(min = UiMetrics.touchTarget).clip(RoundedCornerShape(11.dp))
                .background(if (active) p.card else p.field)
                .clickable(role = Role.Tab) { onSelect(index) }.semantics { selected = active }
                .padding(horizontal = 2.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                Text(label, color = if (active) p.text else p.textSecondary,
                    fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

/** One labeled dropdown, shared by filter sheets. */
@Composable
internal fun FilterSelect(label: String, value: String, options: List<String>, onSelect: (Int) -> Unit) {
    val p = LocalPalette.current
    var expanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(UiMetrics.gap)) {
        Text(label, color = p.textSecondary, style = UiTextStyles.fieldLabel)
        Box(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().heightIn(min = UiMetrics.fieldHeight).clip(RoundedCornerShape(UiMetrics.controlRadius))
                .background(p.card).border(1.dp,p.textSecondary,RoundedCornerShape(UiMetrics.controlRadius))
                .clickable { expanded = true }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(value, Modifier.weight(1f), color = p.text, fontSize = 14.sp)
                Icon(painterResource(R.drawable.ic_chevron_down), "Выбрать $label", Modifier.size(20.dp), tint = p.textSecondary)
            }
            AppDropdownMenu(expanded, { expanded = false }) {
                options.forEachIndexed { index, title ->
                    AppDropdownMenuItem(text = { Text(title) }, onClick = { expanded = false; onSelect(index) })
                }
            }
        }
    }
}
