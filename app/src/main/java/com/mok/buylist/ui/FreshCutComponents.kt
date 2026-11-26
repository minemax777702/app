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
    Row(modifier.fillMaxWidth().height(66.dp).clip(RoundedCornerShape(20.dp))
        .background(p.card).border(1.dp, p.line, RoundedCornerShape(20.dp)).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        destinations.forEach { (destination, icon, title) ->
            val active = destination == current
            val ink = if (active) p.text else p.textSecondary
            Column(Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(12.dp))
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
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
        verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(eyebrow, color = p.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(title, color = p.text, fontSize = 32.sp, lineHeight = 39.sp, fontWeight = FontWeight.SemiBold)
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
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.field).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.forEachIndexed { index, label ->
            val active = index == selectedIndex
            Box(Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(12.dp))
                .background(if (active) p.card else p.field)
                .clickable(role = Role.Tab) { onSelect(index) }.semantics { selected = active },
                contentAlignment = Alignment.Center) {
                Text(label, color = if (active) p.text else p.textSecondary,
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}
