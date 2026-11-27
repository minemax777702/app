package com.mok.buylist.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mok.buylist.R
import com.mok.buylist.ShoppingApp
import com.mok.buylist.cloud.*
import com.mok.buylist.ui.theme.LocalPalette
import kotlinx.coroutines.*
import java.text.DateFormat
import java.util.Date

@Composable
internal fun CloudHomePanel(onConnect: () -> Unit, onOpen: (CloudSpaceSession) -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val cloud = (context.applicationContext as ShoppingApp).cloudSpaces
    val sessions by cloud.sessions.collectAsStateWithLifecycle()
    val issue by cloud.issue.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    var invitation by remember { mutableStateOf<CloudInvitation?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmed by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri) ?: error("No invitation")
                    CloudSpaces.readBounded(input)
                }
                val decoded = withContext(Dispatchers.IO) {
                    CloudInvitation.decode(bytes).also { it.verify(CloudDeviceIdentity.fingerprint()) }
                }
                pending = bytes; invitation = decoded; confirmed = false; error = null
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "Приглашение недействительно или предназначено для другого телефона." }
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().background(p.card, RoundedCornerShape(24.dp)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(painterResource(R.drawable.ic_cloud), contentDescription = null, modifier = Modifier.size(36.dp), tint = p.text)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (sessions.isEmpty()) "Ваши списки в облаке" else "Мои списки", color = p.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(if (sessions.isEmpty()) "Личные и общие покупки на разных телефонах" else "Локальные покупки отделены от облачных", color = p.textSecondary, fontSize = 13.sp)
            }
        }
        if (sessions.isEmpty()) Text("Покажите QR-код администратору, затем откройте полученное приглашение.", color = p.textSecondary, fontSize = 14.sp)
        sessions.forEach { session ->
            val status by session.status.collectAsStateWithLifecycle()
            val historical by session.historicalState.collectAsStateWithLifecycle()
            FreshCutListRow(if (session.space.kind == CloudSpaceKind.PERSONAL) R.drawable.ic_user_round else R.drawable.ic_group,
                session.invitation.label + (if (historical) " · прежняя версия" else ""),
                when {
                    historical -> "Сохранено для просмотра"
                    status.syncing -> "Синхронизация…"
                    status.issue != null -> "Сохранено на телефоне"
                    status.lastSuccess > 0 -> "Обновлено " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(status.lastSuccess))
                    else -> "Ожидает синхронизации"
                }, onClick = { onOpen(session) })
        }
        FreshCutSecondaryButton("Открыть приглашение", { picker.launch(arrayOf("*/*")) }, enabled = !busy)
        FreshCutSecondaryButton("Подключить устройство", onConnect, enabled = !busy)
        (error ?: issue)?.let { Text(it, color = p.error, fontSize = 13.sp) }
    }
    invitation?.let { invite ->
        AppSheet(onDismissRequest = { if (!busy) { invitation = null; pending = null } }, containerColor = p.sheet,
            title = { SheetHeader("Присоединиться к списку") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(invite.label, color = p.text, fontSize = 18.sp)
                    Text("Пользователь: ${invite.userName}", color = p.textSecondary, fontSize = 14.sp)
                    Text("Сверьте код с администратором лично или по доверенному каналу.", color = p.textSecondary, fontSize = 13.sp)
                    Text(invite.adminFingerprint.chunked(8).joinToString(" "), color = p.text, fontSize = 13.sp)
                    FreshCutSecondaryButton(if (confirmed) "Код совпадает ✓" else "Код совпадает", { confirmed = !confirmed }, enabled = !busy)
                    error?.let { Text(it, color = p.error, fontSize = 13.sp) }
                }
            }, confirmButton = {
                SheetActionButton(onClick = {
                    val bytes = pending ?: return@SheetActionButton
                    busy = true; error = null
                    scope.launch {
                        try {
                            cloud.importInvitation(bytes, invite.adminFingerprint)
                            invitation = null; pending = null
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { error = "Не удалось открыть список. Проверьте приглашение и код администратора." }
                        catch (_: LinkageError) { error = "Облачный модуль недоступен в этой сборке." }
                        finally { busy = false }
                    }
                }, enabled = confirmed && !busy) { Text(if (busy) "Подключаем…" else "Присоединиться") }
            })
    }
}

@Composable
internal fun CloudWorkspacePanel(session: CloudSpaceSession) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val cloud = (context.applicationContext as ShoppingApp).cloudSpaces
    val scope = rememberCoroutineScope()
    val repository = session.repository
    val items by remember(session) { repository.observeItems() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val bought by remember(session) { repository.observeBought() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val archive by remember(session) { repository.observeArchive() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val status by session.status.collectAsStateWithLifecycle()
    var section by remember(session) { mutableStateOf(0) }
    var adding by remember(session) { mutableStateOf(false) }
    var name by remember(session) { mutableStateOf("") }
    var selected by remember(session) { mutableStateOf<com.mok.buylist.data.ShoppingItem?>(null) }
    var price by remember(session) { mutableStateOf("") }
    var error by remember(session) { mutableStateOf<String?>(null) }
    var busy by remember(session) { mutableStateOf(false) }
    var archiveLimit by remember(session) { mutableStateOf(12) }

    fun edit(action: suspend (com.mok.buylist.data.ShoppingRepository) -> Unit) {
        if (busy || !session.writable) return
        busy = true; error = null
        scope.launch {
            try { cloud.edit(session, action) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "Не удалось сохранить изменение." }
            finally { busy = false }
        }
    }
    LaunchedEffect(session) { cloud.requestSync(session) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (status.syncing) "Синхронизация…" else if (status.issue != null) "Доступно офлайн" else if (status.lastSuccess > 0) "Синхронизировано" else "Ожидает синхронизации",
            color = p.textSecondary, fontSize = 13.sp)
        status.issue?.let { Text(it, color = p.textSecondary, fontSize = 13.sp) }
        if (!session.writable) Text(if (session.historical) "Прежняя версия. Покупки сохранены для просмотра." else "Только просмотр", color = p.textSecondary, fontSize = 13.sp)
        FreshCutSecondaryButton("Обновить", { cloud.requestSync(session) }, enabled = !status.syncing && !session.historical)
        FreshCutTabs(listOf("Список", "Архив"), section, { section = it })
        if (section == 0) {
            if (session.writable) FreshCutSecondaryButton("Добавить товар", { name = ""; adding = true }, enabled = !busy)
            if (items.isEmpty()) Text("Список пуст", color = p.textSecondary, fontSize = 14.sp)
            items.forEach { item ->
                FreshCutListRow(R.drawable.ic_archive, item.name, "${item.quantity} шт.",
                    onClick = { if (session.writable && !busy) { selected = item; price = "" } }, showChevron = session.writable)
            }
            if (bought.isNotEmpty()) {
                FreshCutDetailLine("Куплено", bought.size.toString())
                if (session.writable) FreshCutSecondaryButton("Завершить покупки", { edit { it.finishTrip() } }, enabled = !busy)
            }
        } else {
            if (archive.isEmpty()) Text("Покупок пока нет", color = p.textSecondary, fontSize = 14.sp)
            archive.take(archiveLimit).forEach { row ->
                FreshCutSectionTitle(DateFormat.getDateInstance().format(Date(row.completedAt)))
                row.names.forEachIndexed { index, item -> FreshCutDetailLine(item, "${row.counts[index]} шт.") }
            }
            if (archive.size > archiveLimit) FreshCutSecondaryButton("Показать ещё", { archiveLimit += 12 })
        }
        error?.let { Text(it, color = p.error, fontSize = 13.sp) }
    }
    if (adding) AppSheet(onDismissRequest = { adding = false }, containerColor = p.sheet,
        title = { SheetHeader("Добавить товар") }, text = {
            SheetTextField(name, { name = it.take(200) }, "Название")
        }, confirmButton = {
            SheetActionButton(onClick = {
                val value = name.trim(); adding = false
                edit { require(it.addManualProduct(value)) }
            }, enabled = !busy && name.isNotBlank()) { Text("Добавить") }
        })
    selected?.let { item ->
        val current = items.firstOrNull { it.id == item.id }
        if (current != null) AppSheet(onDismissRequest = { selected = null }, containerColor = p.sheet,
            title = { SheetHeader(current.name) }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FreshCutSecondaryButton("−", { edit { it.setQuantity(current.id, current.quantity - 1) } }, enabled = !busy && current.quantity > 1)
                        Text("${current.quantity} шт.", color = p.text, fontSize = 16.sp)
                        FreshCutSecondaryButton("+", { edit { it.setQuantity(current.id, current.quantity + 1) } }, enabled = !busy && current.quantity < 999)
                    }
                    SheetTextField(price, { price = it.take(12) }, "Цена за единицу (необязательно)")
                    FreshCutSecondaryButton("Удалить товар", { selected = null; edit { it.remove(current.id) } }, enabled = !busy)
                }
            }, confirmButton = {
                val amount = price.trim().replace(',', '.').toBigDecimalOrNull()?.let { runCatching { it.movePointRight(2).longValueExact() }.getOrNull() }
                SheetActionButton(onClick = {
                    selected = null; edit { it.buy(current.id, priceOverride = amount) }
                }, enabled = !busy && (price.isBlank() || (amount != null && amount >= 0))) { Text("Куплено") }
            })
    }
}
