package com.mok.buylist.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mok.buylist.ShoppingApp
import com.mok.buylist.cloud.CloudDeviceIdentity
import com.mok.buylist.cloud.CloudDeviceProtocol
import com.mok.buylist.cloud.CloudDeviceRequest
import com.mok.buylist.ui.theme.LocalPalette
import kotlinx.coroutines.*

@Composable
internal fun CloudDeviceRequestPanel(onDone: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val cloud = (context.applicationContext as ShoppingApp).cloudSpaces
    val saved by cloud.connectionCode.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var code by remember(saved) { mutableStateOf(saved.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FreshCutSecondaryButton("Поделиться кодом устройства", {
            if (!busy) {
                busy = true
                scope.launch {
                    try {
                        val request = withContext(Dispatchers.IO) { CloudDeviceIdentity.request() }
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, request)
                        }
                        context.startActivity(android.content.Intent.createChooser(send, null))
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { error = "Не удалось создать код устройства" }
                    finally { busy = false }
                }
            }
        }, enabled = !busy)
        SheetTextField(code, { code = it.take(128) }, "Код подключения")
        FreshCutSecondaryButton(if (busy) "Сохранение…" else "Подключиться", {
            busy = true
            error = null
            scope.launch {
                try { cloud.connect(code); onDone() }
                catch (e: CancellationException) { throw e }
                catch (e: IllegalArgumentException) { error = e.message }
                catch (_: Exception) { error = "Не удалось сохранить подключение" }
                finally { busy = false }
            }
        }, enabled = !busy && code.filterNot(Char::isWhitespace).length == 64)
        error?.let { Text(it, color = p.error, fontSize = 13.sp) }
    }
}

@Composable
internal fun CloudDeviceCodeSheet(onRequest: (CloudDeviceRequest) -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AppSheet(onDismissRequest = { if (!busy) onDismiss() }, containerColor = p.sheet,
        title = { SheetHeader("Подключить устройство") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SheetTextField(code, { code = it.take(2048) }, "Код устройства")
                FreshCutSecondaryButton("Это устройство", {
                    busy = true
                    scope.launch {
                        try {
                            val request = withContext(Dispatchers.IO) {
                                CloudDeviceProtocol.parse(CloudDeviceIdentity.request(), System.currentTimeMillis())
                            }
                            onRequest(request)
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { error = "Не удалось создать код устройства" }
                        finally { busy = false }
                    }
                }, enabled = !busy)
                error?.let { Text(it, color = p.error, fontSize = 13.sp) }
            }
        }, confirmButton = {
            SheetActionButton(onClick = {
                try { onRequest(CloudDeviceProtocol.parse(code.trim(), System.currentTimeMillis())) }
                catch (_: Exception) { error = "Недействительный или просроченный код устройства" }
            }, enabled = !busy && code.isNotBlank()) { Text("Продолжить") }
        })
}
