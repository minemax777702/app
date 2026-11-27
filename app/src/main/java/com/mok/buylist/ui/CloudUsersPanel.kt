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
import com.mok.buylist.cloud.CloudDeviceRequest
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
    var scanningDevice by remember(admin) { mutableStateOf(false) }
    var deviceRequest by remember(admin) { mutableStateOf<CloudDeviceRequest?>(null) }
    var deviceUser by remember(admin) { mutableStateOf<String?>(null) }
    var confirmedFingerprint by remember(admin) { mutableStateOf(false) }
    var accessStatus by remember(admin) { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var groupEditing by remember(admin) { mutableStateOf(false) }
    var selectedGroup by remember(admin) { mutableStateOf<CloudGroupDraft?>(null) }

    suspend fun reload() {
        busy = true
        error = null
        loaded = null
        try {
            val current = store.load(admin)
            loaded = current
            accessStatus = emptyMap()
            if (current.registry.devices.isNotEmpty()) accessStatus = admin.deviceAccesses(current.registry.devices)
        }
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

    fun access(device: com.mok.buylist.cloud.CloudDeviceDraft, enable: Boolean) {
        if (busy || loaded == null) return
        busy = true
        error = null
        scope.launch {
            try {
                // Re-read the registry before granting access; do not act on a stale UI row.
                val current = store.load(admin)
                val verified = current.registry.devices.single { it.fingerprint == device.fingerprint }
                if (enable) admin.activateDeviceReadOnly(verified) else admin.deactivateDevice(verified)
                accessStatus = accessStatus + (device.fingerprint to admin.deviceAccess(verified))
                loaded = current
            } catch (e: CancellationException) { throw e }
            catch (e: CloudAdminException) { error = e.message }
            catch (_: Exception) { error = "Не удалось подтвердить изменение SSH-ключа. Проверьте права токена и обновите статус." }
            finally { busy = false }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FreshCutSecondaryButton("К настройкам облака", onBack)
        FreshCutSectionTitle("Пользователи и группы")
        FreshCutTabs(listOf("Пользователи", "Группы", "Устройства"), section, { section = it; showArchived = false })
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
        if (current != null && section == 2) {
            val devices = current.registry.devices
            FreshCutSecondaryButton("Сканировать QR устройства", {
                scanningDevice = true
            }, enabled = !busy && current.registry.users.any { !it.archived } && devices.size < CloudUserRegistry.MAX_DEVICES)
            Text("После регистрации можно выдать устройству SSH-доступ для чтения. Доступ к покупкам потребует отдельного ключа пространства.", color = p.textSecondary, fontSize = 14.sp)
            devices.forEach { device ->
                val active = accessStatus[device.fingerprint]
                val owner = current.registry.users.firstOrNull { it.id == device.userId }?.name.orEmpty()
                FreshCutListRow(R.drawable.ic_scan_barcode, owner,
                    when {
                        device.revoked && active == true -> "Отозвано · ключ ещё доступен"
                        device.revoked -> "Отозвано"
                        active == true -> "SSH-доступ предоставлен"
                        active == false -> "Ожидает активации"
                        else -> "Статус SSH не проверен"
                    }, showChevron = false)
                Text(device.fingerprint.chunked(8).joinToString(" "), color = p.textSecondary, fontSize = 12.sp)
                if (!device.revoked && active != true) FreshCutSecondaryButton("Разрешить SSH-чтение", {
                    access(device, true)
                }, enabled = !busy)
                if (active != false) FreshCutSecondaryButton("Отключить SSH-доступ", { access(device, false) }, enabled = !busy)
                if (!device.revoked) FreshCutSecondaryButton("Отозвать регистрацию", {
                    if (!busy) {
                        busy = true
                        scope.launch {
                            try {
                                val latest = store.load(admin)
                                val verified = latest.registry.devices.single { it.fingerprint == device.fingerprint }
                                admin.deactivateDevice(verified)
                                loaded = store.save(admin, latest, latest.registry.revokeDevice(verified.fingerprint))
                                accessStatus = accessStatus + (device.fingerprint to false)
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { loaded = null; error = "Отзыв не подтверждён. Обновите реестр и повторите." }
                            finally { busy = false }
                        }
                    }
                }, enabled = !busy)
            }
        }
        FreshCutSecondaryButton("Обновить список", { scope.launch { reload() } }, enabled = !busy)
    }

    if (scanningDevice) CloudDeviceScanSheet(onRequest = {
        scanningDevice = false
        deviceRequest = it
        deviceUser = null
        confirmedFingerprint = false
    }, onDismiss = { scanningDevice = false })
    val pendingDevice = deviceRequest
    if (pendingDevice != null && loaded != null) {
        AppSheet(onDismissRequest = { deviceRequest = null }, containerColor = p.sheet,
            title = { SheetHeader("Регистрация устройства") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(pendingDevice.fingerprint.chunked(8).joinToString(" "), color = p.text, fontSize = 14.sp)
                    FreshCutSecondaryButton(if (confirmedFingerprint) "Отпечаток совпадает ✓" else "Подтвердить совпадение отпечатка", {
                        confirmedFingerprint = !confirmedFingerprint
                    })
                    FreshCutSectionTitle("Пользователь")
                    loaded?.registry?.users?.filterNot { it.archived }?.forEach { user ->
                        FreshCutListRow(R.drawable.ic_user_round, user.name, selected = deviceUser == user.id,
                            value = if (deviceUser == user.id) "Выбран" else null, onClick = { deviceUser = user.id })
                    }
                }
            }, confirmButton = {
                SheetActionButton(onClick = {
                    val user = deviceUser ?: return@SheetActionButton
                    deviceRequest = null
                    change { it.registry.registerDevice(user, pendingDevice, System.currentTimeMillis()) }
                }, enabled = !busy && confirmedFingerprint && deviceUser != null) { Text("Зарегистрировать") }
            })
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
