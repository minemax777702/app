package com.mok.buylist.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mok.buylist.R
import com.mok.buylist.cloud.CloudAdminException
import com.mok.buylist.cloud.CloudPurchaseBackupCodec
import com.mok.buylist.cloud.GitHubCloudAdmin
import com.mok.buylist.repository
import com.mok.buylist.sync.BackupCodec
import com.mok.buylist.sync.BackupContents
import com.mok.buylist.ui.theme.LocalPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun CloudBackupPanel(admin: GitHubCloudAdmin, onBack: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val repository = context.repository
    val scope = rememberCoroutineScope()
    var files by remember(admin) { mutableStateOf<List<GitHubCloudAdmin.BackupFile>>(emptyList()) }
    var loaded by remember(admin) { mutableStateOf(false) }
    var busy by remember(admin) { mutableStateOf(false) }
    var message by remember(admin) { mutableStateOf<String?>(null) }
    var visibleLimit by remember(admin) { mutableStateOf(12) }
    var selected by remember(admin) { mutableStateOf<GitHubCloudAdmin.BackupFile?>(null) }
    var form by remember(admin) { mutableStateOf(false) }
    var preview by remember(admin) { mutableStateOf<BackupContents?>(null) }
    var adoptIdentity by remember(admin) { mutableStateOf(false) }

    suspend fun refresh() {
        files = admin.listBackups()
        loaded = true
    }

    fun execute(operation: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try { operation() }
            catch (e: CancellationException) { throw e }
            catch (e: CloudAdminException) { message = e.message }
            catch (_: Exception) { message = "Операция не выполнена или результат не подтверждён. Обновите список." }
            finally { busy = false }
        }
    }

    LaunchedEffect(admin) { execute { refresh() } }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FreshCutSecondaryButton("К настройкам облака", onBack, enabled = !busy)
        FreshCutSectionTitle("Резервные копии покупок")
        Text("Ручная зашифрованная копия всей текущей локальной базы, включая данные, полученные через обмен рядом. Не является личным или групповым облачным пространством. Пароль не сохраняется. Максимальный размер копии — 512 КиБ.", color = p.textSecondary, fontSize = 14.sp)
        FreshCutSecondaryButton("Создать зашифрованную копию", {
            selected = null
            form = true
        }, enabled = !busy)
        FreshCutSecondaryButton("Обновить список", { execute { refresh() } }, enabled = !busy)
        if (busy) Text("Обработка…", color = p.textSecondary, fontSize = 14.sp)
        message?.let { Text(it, color = p.textSecondary, fontSize = 14.sp) }
        if (loaded) {
            FreshCutDetailLine("Копий в облаке", files.size.toString())
            if (files.isEmpty()) Text("Нет резервных копий", color = p.textSecondary, fontSize = 14.sp)
            files.take(visibleLimit).forEach { file ->
                FreshCutListRow(R.drawable.ic_archive, "Копия ${file.id.take(8)}",
                    "${(file.size + 1023) / 1024} КиБ · зашифрована",
                    onClick = { if (!busy) { selected = file; form = true } })
            }
            if (files.size > visibleLimit) FreshCutSecondaryButton("Показать ещё", { visibleLimit += 12 })
        }
    }

    if (form) CloudBackupPasswordSheet(
        restoring = selected != null, onDismiss = { form = false },
        onSubmit = { secret ->
            val file = selected
            form = false
            execute {
                try {
                    if (file == null) {
                        val plain = repository.exportBackup()
                        val encrypted = try { withContext(Dispatchers.Default) { CloudPurchaseBackupCodec.encrypt(plain, secret) } }
                        finally { plain.fill(0) }
                        val id = admin.uploadBackup(encrypted)
                        message = "Копия ${id.take(8)} сохранена"
                        refresh()
                    } else {
                        val encrypted = admin.downloadBackup(file)
                        preview = withContext(Dispatchers.Default) {
                            val plain = try { CloudPurchaseBackupCodec.decrypt(encrypted, secret) }
                            catch (_: Exception) { throw CloudAdminException("Неверная парольная фраза или повреждённый файл.") }
                            try { BackupCodec.decode(plain) }
                            finally { plain.fill(0) }
                        }
                        adoptIdentity = false
                    }
                } finally { secret.fill('\u0000') }
            }
        },
    )
    val contents = preview
    if (contents != null) AppSheet(
        onDismissRequest = { preview = null },
        containerColor = p.sheet,
        title = { SheetHeader("Восстановление покупок") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FreshCutDetailLine("Владелец копии", contents.ownerName.ifBlank { "Не указан" })
                FreshCutDetailLine("Позиций", contents.snapshot.items.size.toString())
                FreshCutDetailLine("Архивных списков", contents.snapshot.archives.size.toString())
                Text("Данные будут объединены с текущей локальной базой, а не заменены. Перед операцией создаётся точка отмены. Убедитесь, что это нужная копия.", color = p.textSecondary, fontSize = 14.sp)
                if (contents.ownerId.isNotBlank() && contents.ownerId != repository.currentUserId) {
                    FreshCutSecondaryButton(if (adoptIdentity) "Использовать прежний профиль ✓" else "Сохранить текущий профиль", {
                        adoptIdentity = !adoptIdentity
                    })
                    Text(if (adoptIdentity) "Текущий идентификатор пользователя будет заменён идентификатором владельца копии."
                        else "Данные владельца копии будут объединены без смены вашего профиля.", color = p.textSecondary, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            SheetActionButton(onClick = {
                val adopt = adoptIdentity
                preview = null
                execute {
                    repository.saveUndoPoint()
                    repository.restoreBackup(contents, asMe = adopt)
                    message = "Копия объединена с локальной базой"
                }
            }) { Text("Объединить данные") }
        },
    )
}

@Composable
private fun CloudBackupPasswordSheet(restoring: Boolean, onDismiss: () -> Unit, onSubmit: (CharArray) -> Unit) {
    val p = LocalPalette.current
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    AppSheet(onDismissRequest = onDismiss, containerColor = p.sheet,
        title = { SheetHeader(if (restoring) "Открыть копию" else "Новая копия") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Введите уникальную парольную фразу не менее 16 символов. Без неё восстановить копию невозможно.", color = p.textSecondary, fontSize = 14.sp)
                SheetTextField(password, { password = it.take(256) }, "Парольная фраза",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), visualTransformation = PasswordVisualTransformation())
                if (!restoring) SheetTextField(repeat, { repeat = it.take(256) }, "Повторите пароль",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), visualTransformation = PasswordVisualTransformation())
            }
        },
        confirmButton = {
            SheetActionButton(enabled = password.length in 16..256 && (restoring || password == repeat), onClick = {
                val secret = password.toCharArray()
                password = ""
                repeat = ""
                onSubmit(secret)
            }) { Text(if (restoring) "Открыть" else "Загрузить в облако") }
        })
}
