package com.mok.buylist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mok.buylist.R
import com.mok.buylist.ui.theme.LocalPalette
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun NotificationsScreen(actions: List<ListAction>, onBack: () -> Unit, onUndo: (String) -> Unit) {
    val p = LocalPalette.current
    val zone = ZoneId.systemDefault()
    val date = remember { DateTimeFormatter.ofPattern("dd.MM.yyyy · HH:mm") }
    var limit by remember { mutableIntStateOf(20) }
    Column(Modifier.fillMaxSize().background(p.background)) {
        SubScreenHeader("Уведомления", onBack)
        if (actions.isEmpty()) Column(Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(painterResource(R.drawable.ic_bell), null, Modifier.size(40.dp), tint = p.textSecondary)
            Spacer(Modifier.height(12.dp))
            Text("Пока нет уведомлений", color = p.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("Здесь появятся ваши действия", color = p.textSecondary, fontSize = 13.sp)
        } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(actions.take(limit), key = { it.id }) { action ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.card).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(painterResource(when (action.title) {
                        "Товар удалён" -> R.drawable.ic_trash
                        "Покупка отмечена" -> R.drawable.ic_check
                        "Добавлен товар" -> R.drawable.ic_plus
                        else -> R.drawable.ic_bell
                    }), null, Modifier.size(24.dp), tint = p.text)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(action.title, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        if (action.detail.isNotBlank()) Text(action.detail, color = p.text, fontSize = 13.sp)
                        Text(date.format(Instant.ofEpochMilli(action.at).atZone(zone)), color = p.textSecondary, fontSize = 11.sp)
                        if (action.result.isNotBlank()) Text(action.result, color = p.textSecondary, fontSize = 12.sp)
                    }
                    if (action.canUndo) TextButton(onClick = { onUndo(action.id) }) { Text("Отменить", color = p.text) }
                }
            }
            if (actions.size > limit) item { FreshCutSecondaryButton("Показать ещё", { limit += 20 }) }
        }
    }
}
