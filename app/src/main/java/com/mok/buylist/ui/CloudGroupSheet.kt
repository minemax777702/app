package com.mok.buylist.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mok.buylist.R
import com.mok.buylist.cloud.CloudGroupDraft
import com.mok.buylist.cloud.CloudGroupRole
import com.mok.buylist.cloud.CloudUserRegistry
import com.mok.buylist.ui.theme.LocalPalette

private fun CloudGroupRole.label() = when (this) {
    CloudGroupRole.OWNER -> "Владелец"
    CloudGroupRole.MEMBER -> "Участник"
    CloudGroupRole.VIEWER -> "Просмотр"
}

/** Edit a draft locally; a single CAS write publishes name, owner and all roles together. */
@Composable
internal fun CloudGroupSheet(
    registry: CloudUserRegistry,
    group: CloudGroupDraft?,
    onDismiss: () -> Unit,
    onSave: (String, Map<String, CloudGroupRole>) -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    val p = LocalPalette.current
    var name by remember(group?.id) { mutableStateOf(group?.name.orEmpty()) }
    var members by remember(group?.id) { mutableStateOf(group?.members.orEmpty()) }
    var query by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var visibleLimit by remember { mutableStateOf(12) }
    val archived = group?.archived == true
    val active = registry.users.filterNot { it.archived }
    val selected = active.firstOrNull { it.id == selectedId }
    val ownerId = members.entries.firstOrNull { it.value == CloudGroupRole.OWNER }?.key
    val hasArchivedMembers = members.keys.any { id -> active.none { it.id == id } }
    val valid = name.isNotBlank() && ownerId != null && !hasArchivedMembers && !archived

    fun setRole(id: String, role: CloudGroupRole?) {
        val old = members[id]
        if (old == CloudGroupRole.OWNER && role != CloudGroupRole.OWNER) return
        val next = members.toMutableMap()
        if (role == null) next.remove(id)
        else {
            if (role == CloudGroupRole.OWNER) ownerId?.let { next[it] = CloudGroupRole.MEMBER }
            next[id] = role
        }
        members = next.toMap()
        selectedId = null
    }

    AppSheet(
        onDismissRequest = onDismiss,
        containerColor = p.sheet,
        title = { SheetHeader(if (group == null) "Новая группа" else "Группа") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SheetTextField(name, { name = it.filterNot(Char::isISOControl).take(50) }, "Название", readOnly = archived)
                FreshCutDetailLine("Владелец", registry.users.firstOrNull { it.id == ownerId }?.name ?: "Не выбран")
                FreshCutDetailLine("Участников", members.size.toString())
                if (archived) {
                    if (hasArchivedMembers) Text("Сначала восстановите архивных участников.", color = p.error, fontSize = 13.sp)
                    registry.users.filter { it.id in members }.forEach { user ->
                        FreshCutListRow(R.drawable.ic_user_round, user.name, members.getValue(user.id).label(), showChevron = false)
                    }
                } else {
                    SheetTextField(query, { query = it.take(50); visibleLimit = 12 }, "Поиск участника")
                    val candidates = active.filter { it.name.contains(query.trim(), ignoreCase = true) }
                        .sortedWith(compareByDescending<com.mok.buylist.cloud.CloudUserDraft> { it.id in members }.thenBy { it.name.lowercase() })
                    if (candidates.isEmpty()) Text("Нет пользователей", color = p.textSecondary, fontSize = 13.sp)
                    candidates.take(visibleLimit).forEach { user ->
                        FreshCutListRow(R.drawable.ic_user_round, user.name,
                            members[user.id]?.label() ?: "Не в группе",
                            onClick = { selectedId = if (selectedId == user.id) null else user.id })
                        if (selected?.id == user.id) {
                            if (members[user.id] == CloudGroupRole.OWNER) {
                                Text("Чтобы сменить владельца, выберите другого пользователя и назначьте его владельцем.", color = p.textSecondary, fontSize = 13.sp)
                            } else {
                                FreshCutSecondaryButton(if (ownerId == null) "Назначить владельцем" else "Передать владение", {
                                    setRole(user.id, CloudGroupRole.OWNER)
                                })
                                FreshCutSecondaryButton("Участник", { setRole(user.id, CloudGroupRole.MEMBER) })
                                FreshCutSecondaryButton("Только просмотр", { setRole(user.id, CloudGroupRole.VIEWER) })
                                if (user.id in members) FreshCutSecondaryButton("Исключить из группы", { setRole(user.id, null) })
                            }
                        }
                    }
                    if (candidates.size > visibleLimit) FreshCutSecondaryButton("Показать ещё", { visibleLimit += 12 })
                    if (ownerId == null) Text("Выберите владельца группы.", color = p.textSecondary, fontSize = 13.sp)
                }
                if (group != null && archived) FreshCutSecondaryButton("Восстановить группу", onArchive, enabled = !hasArchivedMembers)
                if (group != null) FreshCutSecondaryButton("Удалить группу", onDelete)
            }
        },
        confirmButton = {
            if (!archived) SheetActionButton(onClick = { onSave(name.trim(), members.toMap()) }, enabled = valid) { Text("Сохранить") }
        },
    )
}
