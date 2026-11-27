package com.mok.buylist.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mok.buylist.R
import com.mok.buylist.cloud.*
import com.mok.buylist.ui.theme.LocalPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun CloudUsersPanel(admin: GitHubCloudAdmin, onBack: () -> Unit, onExportInvite: (ByteArray) -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val store = remember(context.applicationContext) { CloudUserRegistryStore(context) }
    val scope = rememberCoroutineScope()
    var loaded by remember(admin) { mutableStateOf<LoadedCloudUsers?>(null) }
    var access by remember(admin) { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var busy by remember(admin) { mutableStateOf(true) }
    var error by remember(admin) { mutableStateOf<String?>(null) }
    var notice by remember(admin) { mutableStateOf<String?>(null) }
    var section by remember(admin) { mutableStateOf(0) }
    var editing by remember(admin) { mutableStateOf(false) }
    var user by remember(admin) { mutableStateOf<CloudUserDraft?>(null) }
    var name by remember(admin) { mutableStateOf("") }
    var groupEditing by remember(admin) { mutableStateOf(false) }
    var group by remember(admin) { mutableStateOf<CloudGroupDraft?>(null) }
    var device by remember(admin) { mutableStateOf<CloudDeviceDraft?>(null) }
    var scanning by remember(admin) { mutableStateOf(false) }
    var pending by remember(admin) { mutableStateOf<CloudDeviceRequest?>(null) }
    var chosenUser by remember(admin) { mutableStateOf<String?>(null) }
    var newName by remember(admin) { mutableStateOf("") }
    var confirmed by remember(admin) { mutableStateOf(false) }
    var inviteDevice by remember(admin) { mutableStateOf<CloudDeviceDraft?>(null) }
    var inviteSpace by remember(admin) { mutableStateOf<CloudSpace?>(null) }
    var confirmDelete by remember(admin) { mutableStateOf<Pair<Int, String>?>(null) }

    suspend fun reload() {
        busy = true
        error = null
        loaded = null
        access = emptyMap()
        try {
            val current = store.load(admin)
            loaded = current
            if (current.registry.devices.isNotEmpty()) access = admin.deviceAccesses(current.registry.devices)
        } catch (e: CancellationException) { throw e }
        catch (e: CloudAdminException) { error = e.message }
        catch (_: Exception) { error = "Не удалось загрузить данные. Повторите попытку." }
        finally { busy = false }
    }

    LaunchedEffect(admin) { reload() }

    fun perform(operation: suspend (LoadedCloudUsers) -> LoadedCloudUsers, success: String) {
        if (busy || loaded == null) return
        busy = true
        error = null
        notice = null
        scope.launch {
            try {
                val latest = store.load(admin)
                loaded = operation(latest)
                notice = success
                access = emptyMap()
                val devices = requireNotNull(loaded).registry.devices
                if (devices.isNotEmpty()) access = admin.deviceAccesses(devices)
            } catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { error = e.message ?: "Проверьте данные."; loaded = null }
            catch (e: CloudAdminException) { error = e.message; loaded = null }
            catch (_: Exception) { error = "Операция не подтверждена. Обновите список."; loaded = null }
            finally { busy = false }
        }
    }

    fun change(transform: (CloudUserRegistry) -> CloudUserRegistry) =
        perform({ latest ->
            val next = CloudMembership.prepare(admin, latest.registry, transform)
            val saved = store.save(admin, latest, next)
            CloudMembership.publish(admin, saved.registry)
            saved
        }, "Изменения сохранены. Для изменённых списков выдайте новые приглашения.")

    fun activate(id: String, enable: Boolean) = perform({ latest ->
        val row = latest.registry.devices.single { it.fingerprint == id }
        if (enable) admin.activateDevice(row, latest.registry.canWriteDevice(row.fingerprint)) else admin.deactivateDevice(row)
        latest
    }, if (enable) "Доступ предоставлен" else "Доступ отключён")

    fun remove(kind: Int, id: String) = perform({ latest ->
        // Validate the resulting structure BEFORE any remote key revocation.
        val next = CloudMembership.prepare(admin, latest.registry) { registry ->
            when (kind) {
                0 -> registry.deleteUser(id)
                1 -> registry.deleteGroup(id)
                else -> registry.deleteDevice(id)
            }
        }
        val keys = when (kind) {
            0 -> latest.registry.devices.filter { it.userId == id }
            2 -> latest.registry.devices.filter { it.fingerprint == id }
            else -> emptyList()
        }
        // Removing a record never leaves its managed Deploy Key intentionally active.
        // On an interrupted operation the old record remains, allowing retry.
        keys.forEach { admin.deactivateDevice(it) }
        val saved = store.save(admin, latest, next)
        CloudMembership.publish(admin, saved.registry)
        saved
    }, "Удалено. Для изменённых списков выдайте новые приглашения.")

    fun register(request: CloudDeviceRequest) {
        if (!confirmed || busy) return
        val id = chosenUser
        val label = newName.trim()
        if (id == null && label.isBlank()) return
        pending = null
        perform({ latest ->
            CloudDeviceProtocol.requireFresh(request, System.currentTimeMillis())
            val existing = latest.registry.devices.singleOrNull { it.fingerprint == request.fingerprint }
            val registered = if (existing != null) {
                require(existing.userId == id && !existing.revoked) { "Устройство уже принадлежит другому пользователю или отозвано." }
                val next = if (request.encryptionPublicKey.isNotEmpty()) latest.registry.updateDeviceEncryption(request, System.currentTimeMillis()) else latest.registry
                if (next == latest.registry) latest else store.save(admin, latest, next)
            } else {
                val next = CloudMembership.prepare(admin, latest.registry) { registry ->
                    if (id == null) registry.registerNewUser(label, request, System.currentTimeMillis())
                    else registry.registerDevice(id, request, System.currentTimeMillis())
                }
                store.save(admin, latest, next).also { CloudMembership.publish(admin, it.registry) }
            }
            val row = registered.registry.devices.single { it.fingerprint == request.fingerprint }
            admin.activateDevice(row, registered.registry.canWriteDevice(row.fingerprint))
            registered
        }, "Устройство зарегистрировано. Выдайте приглашение в нужный список.")
    }

    fun issueInvitation(target: CloudDeviceDraft, space: CloudSpace) {
        if (busy || loaded == null) return
        inviteDevice = null
        inviteSpace = null
        busy = true
        error = null
        notice = null
        scope.launch {
            try {
                val latest = store.load(admin)
                val row = latest.registry.devices.single { it.fingerprint == target.fingerprint && !it.revoked }
                require(row.encryptionPublicKey.isNotEmpty()) { "Обновите приложение на телефоне и повторно отсканируйте его QR." }
                val updated = latest.registry.prepareSpace(space)
                val provision = updated.provisions.single { it.space == space && !it.retired }
                val invitation = CloudInvitation.create(updated, space, row, admin.invitationCheckpoint(provision))
                // Persist keys and membership before allowing any encrypted invitation to leave the admin.
                val saved = if (updated == latest.registry) latest else store.save(admin, latest, updated)
                loaded = saved
                CloudMembership.publish(admin, saved.registry)
                admin.activateDevice(row, saved.registry.canWriteDevice(row.fingerprint))
                access = access + (row.fingerprint to true)
                notice = "Приглашение готово"
                onExportInvite(invitation.encode())
            } catch (e: CancellationException) { throw e }
            catch (e: CloudAdminException) { error = e.message }
            catch (e: IllegalArgumentException) { error = e.message ?: "Не удалось подготовить список." }
            catch (_: Exception) { error = "Приглашение не выдано. Повторите попытку." }
            finally { busy = false }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FreshCutSecondaryButton("Назад", onBack)
        FreshCutTabs(listOf("Пользователи", "Группы", "Устройства"), section, { section = it })
        if (busy) Text("Загрузка…", color = p.textSecondary, fontSize = 14.sp)
        loaded?.registry?.adminSigner?.let { signer ->
            FreshCutDetailLine("Код администратора", CloudDeviceProtocol.fingerprint(signer.publicKey).chunked(8).joinToString(" "))
        }
        notice?.let { Text(it, color = p.text, fontSize = 14.sp) }
        error?.let { Text(it, color = p.error, fontSize = 13.sp) }
        val registry = loaded?.registry
        if (registry != null) when (section) {
            0 -> {
                FreshCutSecondaryButton("Добавить пользователя", {
                    user = null; name = ""; editing = true
                }, enabled = !busy && registry.users.size < CloudUserRegistry.MAX_USERS)
                if (registry.users.isEmpty()) Text("Нет пользователей", color = p.textSecondary, fontSize = 14.sp)
                registry.users.sortedBy { it.name.lowercase() }.forEach { row ->
                    val count = registry.devices.count { it.userId == row.id && !it.revoked && access[it.fingerprint] == true }
                    FreshCutListRow(R.drawable.ic_user_round, row.name,
                        if (row.archived) "В архиве" else "Подключённых устройств: $count",
                        onClick = { if (!busy) { user = row; name = row.name; editing = true } })
                }
            }
            1 -> {
                FreshCutSecondaryButton("Создать группу", { group = null; groupEditing = true },
                    enabled = !busy && registry.groups.size < CloudUserRegistry.MAX_GROUPS && registry.users.any { !it.archived })
                if (registry.groups.isEmpty()) Text("Нет групп", color = p.textSecondary, fontSize = 14.sp)
                registry.groups.sortedBy { it.name.lowercase() }.forEach { row ->
                    FreshCutListRow(R.drawable.ic_group, row.name,
                        if (row.archived) "В архиве" else "Участников: ${row.members.size}",
                        onClick = { if (!busy) { group = row; groupEditing = true } })
                }
            }
            2 -> {
                FreshCutSecondaryButton("Подключить устройство", { scanning = true },
                    enabled = !busy && registry.devices.size < CloudUserRegistry.MAX_DEVICES)
                if (registry.devices.isEmpty()) Text("Нет устройств", color = p.textSecondary, fontSize = 14.sp)
                registry.devices.forEach { row ->
                    val active = access[row.fingerprint]
                    FreshCutListRow(R.drawable.ic_scan_barcode,
                        registry.users.firstOrNull { it.id == row.userId }?.name ?: "Устройство",
                        when {
                            row.revoked && active == true -> "Отозвано · требуется отключение"
                            row.revoked -> "Отозвано"
                            active == true -> "Доступ предоставлен"
                            active == false -> "Не подключено"
                            else -> "Статус неизвестен"
                        }, onClick = { if (!busy) device = row })
                }
            }
        }
        if (!busy) FreshCutSecondaryButton("Обновить", { scope.launch { reload() } })
        if (!busy && registry?.provisions?.isNotEmpty() == true) FreshCutSecondaryButton("Обновить доступ к спискам", {
            perform({ latest -> CloudMembership.publish(admin, latest.registry); latest }, "Версии списков подтверждены")
        })
    }

    if (editing) {
        val row = user
        AppSheet(onDismissRequest = { editing = false }, containerColor = p.sheet,
            title = { SheetHeader(if (row == null) "Новый пользователь" else "Пользователь") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SheetTextField(name, { name = it.filterNot(Char::isISOControl).take(50) }, "Имя")
                    if (row != null) {
                        if (row.archived) FreshCutSecondaryButton("Восстановить", {
                            editing = false; change { it.archive(row.id, false) }
                        }, enabled = !busy)
                        FreshCutSecondaryButton("Подключить устройство", {
                            editing = false; section = 2; chosenUser = row.id; scanning = true
                        }, enabled = !busy && !row.archived)
                        FreshCutSecondaryButton("Удалить пользователя", {
                            editing = false; confirmDelete = 0 to row.id
                        }, enabled = !busy)
                    }
                }
            }, confirmButton = {
                SheetActionButton(onClick = {
                    editing = false
                    change { if (row == null) it.add(name) else it.rename(row.id, name) }
                }, enabled = !busy && name.isNotBlank()) { Text("Сохранить") }
            })
    }

    val registry = loaded?.registry
    if (groupEditing && registry != null) {
        val row = group
        CloudGroupSheet(registry, row, onDismiss = { groupEditing = false },
            onSave = { title, members ->
                groupEditing = false; change { it.saveGroup(row?.id, title, members) }
            },
            onArchive = { groupEditing = false; if (row != null) change { it.archiveGroup(row.id, false) } },
            onDelete = { groupEditing = false; if (row != null) confirmDelete = 1 to row.id })
    }

    device?.let { row ->
        AppSheet(onDismissRequest = { device = null }, containerColor = p.sheet,
            title = { SheetHeader("Устройство") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(registry?.users?.firstOrNull { it.id == row.userId }?.name.orEmpty(), color = p.text, fontSize = 16.sp)
                    if (!row.revoked && row.encryptionPublicKey.isNotEmpty()) FreshCutSecondaryButton("Пригласить в список", {
                        device = null; inviteDevice = row; inviteSpace = null
                    }, enabled = !busy)
                    if (!row.revoked && row.encryptionPublicKey.isEmpty()) Text("Для приглашения повторно отсканируйте QR после обновления приложения.", color = p.textSecondary, fontSize = 13.sp)
                    if (!row.revoked && access[row.fingerprint] != true) FreshCutSecondaryButton("Предоставить доступ", {
                        device = null; activate(row.fingerprint, true)
                    }, enabled = !busy)
                    if (access[row.fingerprint] != false) FreshCutSecondaryButton("Отключить", {
                        device = null; activate(row.fingerprint, false)
                    }, enabled = !busy)
                    FreshCutSecondaryButton("Удалить устройство", {
                        device = null; confirmDelete = 2 to row.fingerprint
                    }, enabled = !busy)
                    Text("Код безопасности", color = p.textSecondary, fontSize = 12.sp)
                    Text(row.fingerprint.chunked(8).joinToString(" "), color = p.textSecondary, fontSize = 12.sp)
                }
            }, confirmButton = {})
    }

    if (scanning) CloudDeviceScanSheet(onRequest = { request ->
        scanning = false
        val existing = registry?.devices?.singleOrNull { it.fingerprint == request.fingerprint }
        chosenUser = existing?.userId ?: chosenUser?.takeIf { id -> registry?.users?.any { it.id == id && !it.archived } == true }
        newName = ""
        confirmed = false
        pending = request
    }, onDismiss = { scanning = false; chosenUser = null })

    pending?.let { request ->
        AppSheet(onDismissRequest = { pending = null; chosenUser = null }, containerColor = p.sheet,
            title = { SheetHeader("Подключение устройства") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Сверьте код безопасности на обоих телефонах.", color = p.textSecondary, fontSize = 13.sp)
                    Text(request.fingerprint.chunked(8).joinToString(" "), color = p.text, fontSize = 13.sp)
                    FreshCutSecondaryButton(if (confirmed) "Код совпадает ✓" else "Подтвердить код", { confirmed = !confirmed })
                    FreshCutSectionTitle("Пользователь")
                    registry?.users?.filterNot { it.archived }?.forEach { row ->
                        FreshCutListRow(R.drawable.ic_user_round, row.name, selected = chosenUser == row.id,
                            onClick = { chosenUser = row.id })
                    }
                    if (chosenUser != null) FreshCutSecondaryButton("Новый пользователь", { chosenUser = null })
                    else SheetTextField(newName, { newName = it.filterNot(Char::isISOControl).take(50) }, "Имя нового пользователя")
                }
            }, confirmButton = {
                SheetActionButton(onClick = { register(request); chosenUser = null },
                    enabled = !busy && confirmed && (chosenUser != null || newName.isNotBlank())) { Text("Подключить") }
            })
    }

    inviteDevice?.let { target ->
        val current = registry
        val choices = if (current == null) emptyList() else
            listOf(CloudSpace(CloudSpaceKind.PERSONAL, target.userId) to "Личный список") +
                current.groups.filter { !it.archived && target.userId in it.members }.map {
                    CloudSpace(CloudSpaceKind.GROUP, it.id) to it.name
                }
        AppSheet(onDismissRequest = { inviteDevice = null; inviteSpace = null }, containerColor = p.sheet,
            title = { SheetHeader("Пригласить в список") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    choices.forEach { (space, title) ->
                        FreshCutListRow(if (space.kind == CloudSpaceKind.PERSONAL) R.drawable.ic_user_round else R.drawable.ic_group,
                            title, selected = inviteSpace == space, onClick = { inviteSpace = space })
                    }
                    Text("При изменении состава выдайте обновлённые приглашения всем участникам. Перед сменой состава синхронизируйте покупки.", color = p.textSecondary, fontSize = 13.sp)
                    Text("Передайте сохранённый файл владельцу этого телефона и сверяйте код администратора.", color = p.textSecondary, fontSize = 13.sp)
                }
            }, confirmButton = {
                SheetActionButton(onClick = { inviteSpace?.let { issueInvitation(target, it) } }, enabled = !busy && inviteSpace != null) { Text("Создать приглашение") }
            })
    }

    confirmDelete?.let { (kind, id) ->
        val label = when (kind) {
            0 -> "Удалить пользователя?"
            1 -> "Удалить группу?"
            else -> "Удалить устройство?"
        }
        AppSheet(onDismissRequest = { confirmDelete = null }, containerColor = p.sheet,
            title = { SheetHeader(label) }, text = {
                Text(when (kind) {
                    0 -> "Устройства будут отключены, пользователь исключён из групп. Группы, где он единственный участник, будут удалены. Если у группы есть другие участники, сначала передайте владение. Покупки не удаляются."
                    1 -> "Группа исчезнет из настроек. Пользователи и покупки сохранятся."
                    else -> "Доступ этого устройства будет отозван. Покупки на телефоне не удаляются. Повторное подключение потребует подтверждения администратора."
                }, color = p.textSecondary, fontSize = 14.sp)
            }, confirmButton = {
                SheetActionButton(onClick = { confirmDelete = null; remove(kind, id) }, enabled = !busy) { Text("Удалить") }
            })
    }
}
