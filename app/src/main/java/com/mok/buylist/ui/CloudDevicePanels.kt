package com.mok.buylist.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.mok.buylist.cloud.CloudDeviceIdentity
import com.mok.buylist.cloud.CloudDeviceProtocol
import com.mok.buylist.cloud.CloudDeviceRequest
import com.mok.buylist.sync.QrEncoder
import com.mok.buylist.ui.theme.LocalPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

@Composable
internal fun CloudDeviceRequestPanel() {
    val p = LocalPalette.current
    var refresh by remember { mutableStateOf(0) }
    var code by remember { mutableStateOf<String?>(null) }
    var request by remember { mutableStateOf<CloudDeviceRequest?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refresh) {
        code = null
        request = null
        message = null
        try {
            val qr = withContext(Dispatchers.IO) { CloudDeviceIdentity.request() }
            request = CloudDeviceProtocol.parse(qr, System.currentTimeMillis())
            code = qr
            delay(CloudDeviceProtocol.TTL_MILLIS)
            code = null
            request = null
            message = "Срок QR-кода истёк. Обновите его."
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { message = "Не удалось создать ключ устройства." }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FreshCutSectionTitle("Подключение устройства")
        Text("Покажите QR администратору лично. Сверьте отпечаток ключа на обоих телефонах. Сейчас регистрируется только открытый ключ, доступ к покупкам ещё не выдаётся.", color = p.textSecondary, fontSize = 14.sp)
        val qr = code?.let { value -> remember(value) { QrEncoder.encode(value) } }
        if (qr != null) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(264.dp).background(Color.White)) {
                val cell = kotlin.math.floor(size.minDimension / (qr.size + 8)).coerceAtLeast(1f)
                val origin = (size.minDimension - cell * qr.size) / 2f
                for (y in 0 until qr.size) for (x in 0 until qr.size) if (qr[x,y])
                    drawRect(Color.Black, Offset(origin + x * cell, origin + y * cell), Size(cell, cell))
            }
        }
        request?.let { Text(it.fingerprint.chunked(8).joinToString(" "), color = p.text, fontSize = 13.sp) }
        message?.let { Text(it, color = p.textSecondary, fontSize = 14.sp) }
        FreshCutSecondaryButton("Обновить QR-код", { refresh++ })
        CloudSshProbePanel()
        CloudIntegrationTestPanel()
    }
}

@Composable
internal fun CloudDeviceScanSheet(onRequest: (CloudDeviceRequest) -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val accepted = remember { AtomicBoolean(false) }
    val active = remember { AtomicBoolean(true) }
    val handler = remember { Handler(Looper.getMainLooper()) }
    val callback by rememberUpdatedState(onRequest)
    var error by remember { mutableStateOf<String?>(null) }
    var cameraFailed by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { active.set(false) } }

    AppSheet(onDismissRequest = onDismiss, containerColor = p.sheet,
        title = { SheetHeader("QR устройства") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (granted && !cameraFailed) QrScanner(false, modifier = Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(20.dp)),
                    onCameraError = { cameraFailed = true }, onText = { text ->
                        if (accepted.compareAndSet(false, true)) handler.post {
                            if (active.get()) {
                                val request = runCatching { CloudDeviceProtocol.parse(text, System.currentTimeMillis()) }.getOrNull()
                                if (request != null) callback(request)
                                else {
                                    error = "Неверный или просроченный QR-код. Обновите QR на другом устройстве."
                                    accepted.set(false)
                                }
                            }
                        }
                    })
                else FreshCutSecondaryButton(if (cameraFailed) "Повторить" else "Разрешить камеру", {
                    if (cameraFailed) cameraFailed = false else permission.launch(Manifest.permission.CAMERA)
                })
                Text("Сканируйте QR с экрана другого телефона.", color = p.textSecondary, fontSize = 14.sp)
                error?.let { Text(it, color = p.error, fontSize = 13.sp) }
            }
        }, confirmButton = {})
}
