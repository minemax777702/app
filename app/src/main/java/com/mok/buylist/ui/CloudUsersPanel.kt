package com.mok.buylist.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mok.buylist.R
import com.mok.buylist.cloud.CloudAdminException
import com.mok.buylist.cloud.CloudUserDraft
import com.mok.buylist.cloud.CloudGroupDraft
import com.mok.buylist.cloud.CloudUserRegistry
import com.mok.buylist.cloud.CloudUserRegistryStore
import com.mok.buylist.cloud.GitHubCloudAdmin
import com.mok.buylist.cloud.LoadedCloudUsers
import com.mok.buylist.ui.theme.LocalPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun CloudUsersPanel(admin: GitHubCloudAdmin, onBack: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val store = remember(context.applicationContext) { CloudUserRegistryStore(context) }
    val scope = rememberCoroutineScope()
    var loaded by remember(admin) { mutableStateOf<LoadedCloudUsers?>(null) }
    var busy by remember(admin) { mutableStateOf(true) }
    var error by remember(admin) { mutableStateOf<String?>(null) }
    var editing by remember(admin) { mutableStateOf(false) }
    var selected by remember(admin) { mutableStateOf<CloudUserDraft?>(null) }
    var name by remember(admin) { mutableStateOf("") }
    var showArchived by remember(admin) { mutableStateOf(false) }
    var section by remember(admin) { mutableStateOf(0) }
    var groupEditing by remember(admin) { mutableStateOf(false) }
    var selectedGroup by remember(admin) { mutableStateOf<CloudGroupDraft?>(null) }

    suspend fun reload() {
        busy = true
        error = null
        loaded = null
        try { loaded = store.load(admin) }
        catch (e: CancellationException) { throw e }
        catch (e: CloudAdminException) { error = e.message }
        catch (_: Exception) { error = "Не удалось загрузить реестр. Проверьте интернет и права Contents." }
        finally { busy = false }
    }

    LaunchedEffect(admin) { reload() }

    fun change(transform: (LoadedCloudUsers) -> com.mok.buylist.cloud.CloudUserRegistry) {
        val previous = loaded ?: return
        if (busy) return
        val next = try { transform(previous) } catch (e: IllegalArgumentException) {
            error = e.message ?: "Некорректные данные"
            return
        }
        busy = true
        error = null
        scope.launch {
            try { loaded = store.save(admin, previous, next) }
            catch (e: CancellationException) { throw e }
            catch (e: CloudAdminException) { loaded = null; error = e.message }
            catch (_: Exception) { loaded = null; error = "Запись не подтверждена. Обновите реестр перед повторной попыткой." }
            finally { busy = false }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FreshCutSecondaryButton("К настройкам облака", onBack)
        FreshCutSectionTitle("Пользователи и группы")
        FreshCutTabs(listOf("Пользователи", "Группы"), section, { section = it; showArchived = false })
        Text("Предварительный реестр. Записи пока не предоставляют доступ и не подключают устройства. Имена шифруются. До появления восстановления используйте только тестовые имена. При удалении приложения ключ может быть потерян. Реестр доступен только на создавшем его устройстве.", color = p.textSecondary, fontSize = 14.sp)
        val current = loaded
        if (busy) Text("Загрузка…", color = p.textSecondary, fontSize = 14.sp)
        error?.let { Text(it, color = p.error, fontSize = 14.sp) }
        if (current != null && section == 0) {
            val users = current.registry.users
            FreshCutDetailLine("Активные записи", users.count { !it.archived }.toString())
            FreshCutDetailLine("В архиве", users.count { it.archived }.toString())
            FreshCutSecondaryButton("Добавить пользователя", {
                selected = null
                name = ""
                editing = true
            }, enabled = !busy && users.size < com.mok.buylist.cloud.CloudUserRegistry.MAX_USERS)
            FreshCutTabs(listOf("Активные", "Архив"), if (showArchived) 1 else 0, { showArchived = it == 1 })
            val visible = users.filter { it.archived == showArchived }.sortedBy { it.name.lowercase() }
            if (visible.isEmpty()) Text("Нет пользователей", color = p.textSecondary, fontSize = 14.sp)
            visible.forEach { user ->
                FreshCutListRow(R.drawable.ic_user_round, user.name,
                    if (user.archived) "В архиве" else "Ожидает подключения",
                    onClick = {
                        if (!busy) {
                            selected = user
                            name = user.name
                            editing = true
                        }
                    })
            }
        }
        if (current != null && section == 1) {
            val groups = current.registry.groups
            FreshCutDetailLine("Активные группы", groups.count { !it.archived }.toString())
            FreshCutDetailLine("В архиве", groups.count { it.archived }.toString())
            FreshCutSecondaryButton("Создать группу", {
                selectedGroup = null
                groupEditing = true
            }, enabled = !busy && groups.size < CloudUserRegistry.MAX_GROUPS && current.registry.users.any { !it.archived })
            if (current.registry.users.none { !it.archived }) Text("Сначала добавьте пользователя.", color = p.textSecondary, fontSize = 14.sp)
            FreshCutTabs(listOf("Активные", "Архив"), if (showArchived) 1 else 0, { showArchived = it == 1 })
            val visible = groups.filter { it.archived == showArchived }.sortedBy { it.name.lowercase() }
            if (visible.isEmpty()) Text("Нет групп", color = p.textSecondary, fontSize = 14.sp)
            visible.forEach { group ->
                FreshCutListRow(R.drawable.ic_group, group.name, "Участников: ${group.members.size}", onClick = {
                    if (!busy) { selectedGroup = group; groupEditing = true }
                })
            }
        }
        FreshCutSecondaryButton("Обновить список", { scope.launch { reload() } }, enabled = !busy)
    }

    if (groupEditing && loaded != null) {
        val group = selectedGroup
        CloudGroupSheet(
            registry = requireNotNull(loaded).registry,
            group = group,
            onDismiss = { groupEditing = false },
            onSave = { groupName, members ->
                groupEditing = false
                change { it.registry.saveGroup(group?.id, groupName, members) }
            },
            onArchive = {
                groupEditing = false
                if (group != null) change { it.registry.archiveGroup(group.id, !group.archived) }
            },
        )
    }

    if (editing) {
        val user = selected
        AppSheet(
            onDismissRequest = { editing = false },
            containerColor = p.sheet,
            title = { SheetHeader(if (user == null) "Новый пользователь" else "Пользователь") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SheetTextField(name, { name = it.filterNot(Char::isISOControl).take(50) }, "Имя")
                    if (user != null) {
                        Text("ID: ${user.id}", color = p.textSecondary, fontSize = 12.sp)
                        FreshCutSecondaryButton(if (user.archived) "Восстановить запись" else "В архив", {
                            editing = false
                            change { it.registry.archive(user.id, !user.archived) }
                        }, enabled = !busy)
                    }
                }
            },
            confirmButton = {
                SheetActionButton(onClick = {
                    editing = false
                    val value = name.trim()
                    change { if (user == null) it.registry.add(value) else it.registry.rename(user.id, value) }
                }, enabled = !busy && name.isNotBlank()) { Text("Сохранить") }
            },
        )
    }
}
