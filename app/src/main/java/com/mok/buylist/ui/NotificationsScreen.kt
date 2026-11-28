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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mok.buylist.R
import com.mok.buylist.ui.theme.LocalPalette
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun NotificationsScreen(
    actions: List<ListAction>,
    availableUndoIds: Set<String>,
    onBack: () -> Unit,
    onUndo: (String) -> Unit,
    onClear: (NotificationGroup?) -> Unit,
    onClearAll: () -> Unit,
) {
    val p = LocalPalette.current
    val zone = ZoneId.systemDefault()
    val date = remember { DateTimeFormatter.ofPattern("dd.MM.yyyy · HH:mm") }
    val undoIds = undoableRecentActions(actions).mapTo(HashSet()) { it.id }.intersect(availableUndoIds)
    val visible = remember(actions, undoIds) { visibleNotifications(actions, undoIds) }
    val undoable = visible.filter { it.id in undoIds }
    val grouped = remember(visible, undoIds) {
        visible.filterNot { it.id in undoIds }.groupBy { it.group() }
    }
    var confirmClearAll by rememberSaveable { mutableStateOf(false) }
    var completedLimit by rememberSaveable { mutableIntStateOf(3) }
    var pricesLimit by rememberSaveable { mutableIntStateOf(3) }
    var actionsLimit by rememberSaveable { mutableIntStateOf(5) }
    var otherLimit by rememberSaveable { mutableIntStateOf(3) }
    var accountLimit by rememberSaveable { mutableIntStateOf(5) }

    Column(Modifier.fillMaxSize().background(p.background)) {
        SubScreenHeader("Уведомления", onBack) {
            if (actions.isNotEmpty()) FreshCutToolbarAction(R.drawable.ic_trash, "Очистить уведомления", { confirmClearAll = true })
        }
        if (visible.isEmpty()) {
            FreshCutEmptyState(
                icon = R.drawable.ic_bell,
                title = stringResource(R.string.empty_notifications_title),
                description = stringResource(R.string.empty_notifications_description),
                modifier = Modifier.weight(1f),
            )
        } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), overscrollEffect = null) {
            if (undoable.isNotEmpty()) {
                item(key = "group:undoable") {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Можно отменить", Modifier.weight(1f), color = p.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(undoable.size.toString(), color = p.textSecondary, fontSize = 12.sp)
                    }
                }
                items(undoable, key = { it.id }) { action ->
                    NotificationRow(action, action.group(), date.format(Instant.ofEpochMilli(action.at).atZone(zone)),
                        canUndo = true, onUndo = { onUndo(action.id) })
                }
            }
            NotificationGroup.entries.forEach { group ->
                val entries = grouped[group].orEmpty()
                if (entries.isEmpty()) return@forEach
                val limit = when (group) {
                    NotificationGroup.COMPLETED -> completedLimit
                    NotificationGroup.PRICES -> pricesLimit
                    NotificationGroup.ACTIONS -> actionsLimit
                    NotificationGroup.ACCOUNT -> accountLimit
                    NotificationGroup.OTHER -> otherLimit
                }
                item(key = "group:${group.name}") {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(group.title, Modifier.weight(1f), color = p.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(entries.size.toString(), color = p.textSecondary, fontSize = 12.sp)
                        FreshCutToolbarAction(R.drawable.ic_trash, "Очистить раздел", { onClear(group) })
                    }
                }
                items(entries.take(limit), key = { it.id }) { action ->
                    NotificationRow(action, group, date.format(Instant.ofEpochMilli(action.at).atZone(zone)),
                        canUndo = false, onUndo = { onUndo(action.id) })
                }
                if (entries.size > limit) item(key = "more:${group.name}") {
                    TextButton(onClick = {
                        when (group) {
                            NotificationGroup.COMPLETED -> completedLimit += 10
                            NotificationGroup.PRICES -> pricesLimit += 10
                            NotificationGroup.ACTIONS -> actionsLimit += 10
                            NotificationGroup.ACCOUNT -> accountLimit += 10
                            NotificationGroup.OTHER -> otherLimit += 10
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("Показать ещё · ${entries.size - limit}", color = p.text)
                    }
                }
            }
        }
    }
    if (confirmClearAll) {
        AppSheet(
            onDismissRequest = { confirmClearAll = false },
            containerColor = p.sheet,
            title = { SheetHeader("Очистить все уведомления?") },
            minBodyHeight = 230.dp,
            text = {
                Text("История уведомлений будет очищена, включая действия, которые ещё можно отменить. Списки покупок, товары и архив останутся без изменений.",
                    color = p.textSecondary)
            },
            confirmButton = {
                SheetActionButton(destructive = true, onClick = {
                    confirmClearAll = false
                    onClearAll()
                }) { Text("Очистить всё") }
            },
        )
    }
}

@Composable
private fun NotificationRow(action: ListAction, group: NotificationGroup, timestamp: String, canUndo: Boolean, onUndo: () -> Unit) {
    val p = LocalPalette.current
    val icon = when (group) {
        NotificationGroup.COMPLETED -> R.drawable.ic_list_check
        NotificationGroup.PRICES -> R.drawable.ic_tag
        NotificationGroup.ACTIONS -> when (action.title) {
            "Товар удалён" -> R.drawable.ic_trash
            "Покупка отмечена" -> R.drawable.ic_check
            "Добавлен товар", "Создан товар" -> R.drawable.ic_plus
            else -> R.drawable.ic_edit
        }
        NotificationGroup.ACCOUNT -> when {
            action.title.startsWith("Пользователь создан") || action.title.startsWith("Участник добавлен") || action.title.startsWith("Участники добавлены") -> R.drawable.ic_user_plus
            action.title.startsWith("Участник исключён") -> R.drawable.ic_user_minus
            action.title.startsWith("Выход из группы") -> R.drawable.ic_log_out
            action.title.startsWith("Группа") -> R.drawable.ic_group
            action.title.startsWith("Список") -> R.drawable.ic_list_check
            action.title.startsWith("Доступ") -> R.drawable.ic_shield
            else -> R.drawable.ic_user_round
        }
        NotificationGroup.OTHER -> if(noticeStyle(action.title)==NoticeStyle.WARNING) R.drawable.ic_info else R.drawable.ic_bell
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(UiMetrics.cardRadius)).background(p.card).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(UiMetrics.nestedRadius)).background(p.field), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, Modifier.size(22.dp), tint = p.text)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val payload = noticePayload(0,action.title)
            val title = if (action.detail.isNotBlank()) action.title else payload.title
            val detail = action.detail.ifBlank { payload.message }
            Text(title, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (detail.isNotBlank() && detail != title) Text(detail, color = p.textSecondary, fontSize = 13.sp, lineHeight=18.sp)
            Text(timestamp, color = p.textSecondary, fontSize = 11.sp)

        }
        if (canUndo) TextButton(onClick = onUndo) { Text("Отменить", color = p.text) }
    }
}
