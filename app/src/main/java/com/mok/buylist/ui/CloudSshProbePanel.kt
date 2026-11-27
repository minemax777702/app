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
    var reports by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }

    fun copy(value: String, label: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, value))
    }

    suspend fun check(port: Int) {
        val report = try { CloudSshProbe.check(port) }
        catch (e: CancellationException) { throw e }
        catch (e: CloudAdminException) { e.message ?: "SSH $port: неизвестная ошибка" }
        catch (e: Exception) { "SSH $port: ${e.javaClass.simpleName}" }
        catch (e: LinkageError) { "SSH $port: несовместимость клиента (${e.javaClass.simpleName})" }
        reports = reports + (port to report)
    }

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
            val fingerprint = remember(key) { com.mok.buylist.cloud.CloudSshWire.fingerprint(key) }
            Text("Отпечаток тестового ключа", color = p.text, fontSize = 14.sp)
            Text(fingerprint, color = p.textSecondary, fontSize = 12.sp)
            FreshCutSecondaryButton("Копировать открытый ключ", {
                copy(key, "SSH public key")
                message = "Открытый ключ скопирован"
            }, enabled = !busy)
            FreshCutSecondaryButton("Проверить оба порта", {
                execute {
                    reports = emptyMap()
                    check(443)
                    check(22)
                }
            }, enabled = !busy)
            FreshCutSecondaryButton("Проверить SSH через 443", { execute { check(443) } }, enabled = !busy)
            FreshCutSecondaryButton("Проверить SSH через 22", { execute { check(22) } }, enabled = !busy)
            reports.toSortedMap().values.forEach { Text(it, color = p.text, fontSize = 13.sp) }
            if (reports.isNotEmpty()) FreshCutSecondaryButton("Копировать отчёт", {
                copy("BuyList SSH diagnostic\nAndroid API ${android.os.Build.VERSION.SDK_INT}\n$fingerprint\n\n" +
                    reports.toSortedMap().values.joinToString("\n\n"), "SSH diagnostic")
            }, enabled = !busy)
        }
        if (busy) Text("Проверяем…", color = p.textSecondary, fontSize = 14.sp)
        message?.let { Text(it, color = p.text, fontSize = 14.sp) }
    }
}
