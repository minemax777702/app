package com.mok.buylist.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mok.buylist.cloud.CloudAdminException
import com.mok.buylist.cloud.CloudSshProbe
import com.mok.buylist.ui.theme.LocalPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun CloudSshProbePanel() {
    val context = LocalContext.current
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var publicKey by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun execute(operation: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try { operation() }
            catch (e: CancellationException) { throw e }
            catch (e: CloudAdminException) { message = e.message }
            catch (_: Exception) { message = "Не удалось завершить проверку SSH. Проверьте соединение и наличие ключа в Deploy keys." }
            finally { busy = false }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FreshCutSectionTitle("Проверка ключа устройства")
        Text("Сначала создайте тестовый ключ. Администратор должен добавить открытый ключ в app-cloud → Settings → Deploy keys. Allow write access оставьте выключенным. Без этого проверка авторизации не пройдёт. После проверки удалите ключ.", color = p.textSecondary, fontSize = 13.sp)
        val key = publicKey
        if (key == null) FreshCutSecondaryButton("Создать тестовый SSH-ключ", {
            execute { publicKey = CloudSshProbe.publicKey() }
        }, enabled = !busy)
        else {
            Text(key, color = p.textSecondary, fontSize = 12.sp)
            FreshCutSecondaryButton("Копировать открытый ключ", {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("SSH public key", key))
                message = "Открытый ключ скопирован"
            }, enabled = !busy)
            FreshCutSecondaryButton("Проверить SSH через 443", { execute { message = CloudSshProbe.check(443) } }, enabled = !busy)
            FreshCutSecondaryButton("Проверить SSH через 22", { execute { message = CloudSshProbe.check(22) } }, enabled = !busy)
        }
        if (busy) Text("Проверяем…", color = p.textSecondary, fontSize = 14.sp)
        message?.let { Text(it, color = p.text, fontSize = 14.sp) }
    }
}
