package com.mok.buylist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.horizontalScroll
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
    Row(modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(20.dp))
        .background(p.card).border(1.dp, p.line, RoundedCornerShape(20.dp)).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        destinations.forEach { (destination, icon, title) ->
            val active = destination == current
            val ink = if (active) p.text else p.textSecondary
            Column(Modifier.weight(1f).height(54.dp).clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Tab) { if (!active) onSelect(destination) }
                .semantics { selected = active },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(24.dp, 3.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (active) p.text else p.card))
                Spacer(Modifier.height(4.dp))
                Icon(painterResource(icon), null, Modifier.size(24.dp), tint = ink)
                Text(title, color = ink, fontSize = 10.sp, maxLines = 1,
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
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = p.text, fontSize = 27.sp, lineHeight = 33.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = p.textSecondary, fontSize = 13.sp, lineHeight = 18.sp)
        }
        trailing()
    }
}

@Composable
internal fun FreshCutSectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier, color = LocalPalette.current.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
internal fun FreshCutSegments(labels: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label ->
            val active = index == selectedIndex
            Box(Modifier.height(36.dp).widthIn(min = 96.dp).clip(RoundedCornerShape(18.dp))
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
) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).clip(RoundedCornerShape(20.dp))
        .background(p.card).then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(p.field), contentAlignment = Alignment.Center) {
            if (leading != null) leading() else Icon(painterResource(icon), null, Modifier.size(24.dp), tint = p.text)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, color = p.textSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (value != null) Text(value, color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        if (trailing != null) trailing() else if (selected) Icon(painterResource(R.drawable.ic_check), "Выбрано", Modifier.size(20.dp), tint = p.text)
        else if (value == null && onClick != null) Icon(painterResource(R.drawable.ic_chevron_down), null, Modifier.size(20.dp).rotate(-90f), tint = p.textSecondary)
    }
}

@Composable
internal fun FreshCutSecondaryButton(title: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val p = LocalPalette.current
    Box(modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp))
        .background(p.card).border(1.dp, p.line, RoundedCornerShape(16.dp))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(title, color = if (enabled) p.text else p.textSecondary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
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
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
        .background(if (primary && enabled) p.actionFill else p.field)
        .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), label, Modifier.size(23.dp),
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
            Box(Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(11.dp))
                .background(if (active) p.card else p.field)
                .clickable(role = Role.Tab) { onSelect(index) }.semantics { selected = active }
                .padding(horizontal = 2.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                Text(label, color = if (active) p.text else p.textSecondary,
                    fontSize = 12.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}
