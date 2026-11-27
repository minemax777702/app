package com.mok.buylist.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
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
import com.mok.buylist.cloud.CloudAdminException
import com.mok.buylist.cloud.CloudSshProbe
import com.mok.buylist.cloud.CloudDeviceIdentity
import com.mok.buylist.cloud.CloudDeviceProtocol
import com.mok.buylist.cloud.CloudDeviceRequest
import com.mok.buylist.sync.QrEncoder
import com.mok.buylist.ui.theme.LocalPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

@Composable
internal fun CloudDeviceRequestPanel() {
    val p = LocalPalette.current
    val context = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    var code by remember { mutableStateOf<String?>(null) }
    var request by remember { mutableStateOf<CloudDeviceRequest?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var accessMessage by remember { mutableStateOf<String?>(null) }
    var accessVerified by remember { mutableStateOf(false) }
    var checkedPort by remember { mutableStateOf(443) }

    fun checkAccess(port: Int) {
        if (checking || request == null) return
        checking = true
        checkedPort = port
        accessMessage = null
        accessVerified = false
        scope.launch {
            try {
                // Enrollment only needs an authenticated, read-only Git service.
                // Do not initialize JGit's JVM filesystem/transport stack here.
                accessMessage = CloudSshProbe.check(port, registeredDevice = true)
                accessVerified = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: CloudAdminException) {
                accessMessage = e.message ?: "SSH $port: доступ не подтверждён."
            } catch (e: LinkageError) {
                accessMessage = "SSH $port: несовместимость клиента (${e.javaClass.simpleName})."
            } catch (e: Exception) {
                accessMessage = "SSH $port: подключение не подтверждено (${e.javaClass.simpleName})."
            } finally {
                checking = false
            }
        }
    }
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
        Text("Покажите QR-код администратору. Сравните код безопасности на обоих телефонах. После регистрации понадобится активация облачного доступа.", color = p.textSecondary, fontSize = 14.sp)
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
        FreshCutSecondaryButton(
            if (checking) "Проверяем…" else "Проверить подключение",
            { checkAccess(443) },
            enabled = !checking && request != null,
        )
        accessMessage?.let { report ->
            if (accessVerified) {
                Text(
                    if (checkedPort == 443) "SSH-доступ подтверждён."
                    else "SSH через порт 22 доступен. Основной транспорт использует 443.",
                    color = p.text,
                    fontSize = 15.sp,
                )
                Text("Для обмена покупками ещё нужен ключ рабочего пространства.", color = p.textSecondary, fontSize = 13.sp)
            } else {
                Text("Доступ не подтверждён", color = p.error, fontSize = 15.sp)
            }
            Text(report, color = p.textSecondary, fontSize = 13.sp)
            if (!accessVerified && checkedPort == 443) {
                FreshCutSecondaryButton("Проверить через порт 22", { checkAccess(22) }, enabled = !checking && request != null)
            }
            FreshCutSecondaryButton("Копировать отчёт", {
                val id = request?.fingerprint ?: "недоступен"
                val text = "BuyList device connection\nAndroid API ${android.os.Build.VERSION.SDK_INT}\nУстройство: $id\n\n$report"
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                    ClipData.newPlainText("Cloud connection diagnostic", text))
            }, enabled = !checking)
        }

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
