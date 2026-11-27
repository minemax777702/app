package com.mok.buylist.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mok.buylist.cloud.CloudAdminException
import com.mok.buylist.cloud.CloudUserRegistryStore
import com.mok.buylist.cloud.GitHubCloudAdmin
import com.mok.buylist.ui.theme.LocalPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun CloudRecoverySheet(
    admin: GitHubCloudAdmin,
    recoveryFile: ByteArray?,
    onExport: (ByteArray) -> Unit,
    onRecovered: () -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val store = remember(context.applicationContext) { CloudUserRegistryStore(context) }
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmed by remember { mutableStateOf(false) }
    val restoring = recoveryFile != null
    val valid = password.length in 16..256 && (if (restoring) confirmed else password == repeat)

    AppSheet(onDismissRequest = { if (!busy) onDismiss() }, required = busy, containerColor = p.sheet,
        title = { SheetHeader(if (restoring) "Восстановить реестр" else "Копия реестра") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (restoring) "Реестр будет зашифрован ключом этого телефона. Старое устройство потеряет доступ, если у него другой ключ. Копия должна точно соответствовать текущему файлу GitHub."
                    else "Сохраните зашифрованную копию вне приложения. Используйте уникальную длинную парольную фразу (не менее 16 символов). После изменения пользователей, групп или устройств создайте новую копию.",
                    color = p.textSecondary, fontSize = 14.sp)
                SheetTextField(password, { password = it.take(256) }, "Парольная фраза",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(), readOnly = busy)
                if (!restoring) SheetTextField(repeat, { repeat = it.take(256) }, "Повторите пароль",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(), readOnly = busy)
                else FreshCutSecondaryButton(if (confirmed) "Подтверждено" else "Подтвердить перенос ключа", {
                    confirmed = !confirmed
                }, enabled = !busy)
                error?.let { Text(it, color = p.error, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            SheetActionButton(enabled = valid && !busy, onClick = {
                val secret = password.toCharArray()
                password = ""
                repeat = ""
                busy = true
                error = null
                scope.launch {
                    try {
                        if (recoveryFile == null) {
                            val bytes = store.exportRecovery(admin, secret)
                            onExport(bytes)
                        } else {
                            store.recover(admin, recoveryFile, secret)
                            onRecovered()
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: CloudAdminException) { error = e.message }
                    catch (_: Exception) { error = "Не удалось выполнить операцию. Проверьте доступ и повторите." }
                    finally { secret.fill('\u0000'); busy = false }
                }
            }) { Text(if (busy) "Обработка…" else if (restoring) "Восстановить" else "Сохранить файл") }
        })
}
