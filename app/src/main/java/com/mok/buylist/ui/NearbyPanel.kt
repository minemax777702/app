package com.mok.buylist.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mok.buylist.R
import com.mok.buylist.nearby.NearbySync
import com.mok.buylist.nearbySync
import com.mok.buylist.repository
import com.mok.buylist.sync.QrEncoder
import com.mok.buylist.ui.theme.LocalPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Ручная синхронизация рядом; поиск останавливается при уходе с экрана. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyPanel(onUndo: () -> Unit, qrUndoMinutes: Int?) {
    val context = LocalContext.current
    val nearby = remember { context.nearbySync }
    val state by nearby.state.collectAsStateWithLifecycle()
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var undoMinutes by remember { mutableStateOf(context.repository.undoAvailableMinutes()) }
    LaunchedEffect(state.status, state.active, qrUndoMinutes) {
        undoMinutes = context.repository.undoAvailableMinutes()
    }

    var pairingExpanded by remember { mutableStateOf(false) }
    var showingQr by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var hasCamera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCamera = granted
        if (granted) scanning = true else message = "Разрешите доступ к камере"
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (nearby.hasPermissions()) nearby.start() else message = "Разрешения не предоставлены"
    }
    val qr = remember { QrEncoder.encode(nearby.trust.pairingCode) }

    DisposableEffect(nearby) {
        onDispose { nearby.stop() }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.fillMaxWidth().heightIn(min = 210.dp).clip(RoundedCornerShape(24.dp)).background(p.card).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(54.dp).clip(RoundedCornerShape(18.dp)).background(p.field), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_sync), null, Modifier.size(24.dp), tint = p.text)
            }
            Text("Устройства рядом", color = p.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text("Подключите другой телефон для обмена списками и покупками.", color = p.textSecondary, fontSize = 14.sp, lineHeight = 18.sp)
            Text(state.status, color = p.textSecondary, fontSize = 12.sp)
        }
        SheetActionButton(onClick = {
            message = ""
            if (state.active) nearby.stop()
            else if (!nearby.hasPermissions()) permissions.launch(NearbySync.requiredPermissions())
            else nearby.start()
        }) { Text(if (state.active) "Остановить" else "Синхронизировать", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }

        FreshCutSectionTitle("Доверенные телефоны", Modifier.padding(top = 12.dp))
        if (state.trusted.isEmpty()) Text("Нет доверенных устройств", color = p.textSecondary, fontSize = 14.sp)
        state.trusted.forEach { id ->
            FreshCutListRow(R.drawable.ic_user_round, id.take(12) + "…", "Доверенное устройство",
                onClick = { pairingExpanded = true; showingQr = true; scanning = false },
                trailing = { NearbySmallAction("Удалить") { nearby.remove(id) } })
        }
        SheetActionButton(onClick = { pairingExpanded = true; showingQr = true; scanning = false }) { Text("Добавить устройство") }
        undoMinutes?.let { minutes ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(p.chipBg).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (minutes <= 0) "Последний обмен: только что" else "Последний обмен: $minutes мин назад",
                    modifier = Modifier.weight(1f), color = p.textSecondary, fontSize = 12.sp,
                )
                Text(
                    "Отменить", color = p.error, fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp))
                        .clickable { onUndo() }.padding(8.dp),
                )
            }
        }
        if (message.isNotEmpty()) Text(message, color = p.textSecondary, fontSize = 13.sp)
    }
    if (pairingExpanded) AppModalSheet(onDismissRequest = {
        pairingExpanded = false; showingQr = false; scanning = false
    }, containerColor = p.sheet) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SheetHeader("Обмен рядом")
            if (scanning) {
                QrScanner(false, onText = { code ->
                    if (code.startsWith("BLPAIR1:")) scope.launch(Dispatchers.Main) {
                        if (nearby.trustQr(code)) {
                            scanning = false; pairingExpanded = false; message = "Телефон добавлен"
                        } else message = "Не удалось подтвердить QR"
                    }
                }, modifier = Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(24.dp)))
                Text("Наведите камеру на QR-код другого устройства.", color = p.textSecondary, fontSize = 14.sp)
                FreshCutSecondaryButton("Мой QR", { scanning = false; showingQr = true })
            } else {
                Box(Modifier.size(216.dp).clip(RoundedCornerShape(24.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(168.dp)) {
                        val cell = size.minDimension / (qr.size + 8)
                        val origin = (size.minDimension - cell * qr.size) / 2
                        for (y in 0 until qr.size) for (x in 0 until qr.size) if (qr[x, y])
                            drawRect(Color.Black, Offset(origin + x * cell, origin + y * cell), Size(cell + .15f, cell + .15f))
                    }
                }
                Text("QR-код устройства", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text("Откройте приложение на другом телефоне и отсканируйте код.", color = p.textSecondary, fontSize = 14.sp)
                SheetActionButton(onClick = {
                    showingQr = false
                    if (hasCamera) scanning = true else cameraPermission.launch(Manifest.permission.CAMERA)
                }) { Text("Сканировать QR") }
            }
            if (message.isNotEmpty()) Text(message, color = p.textSecondary, fontSize = 13.sp)
        }
    }
}

@Composable
private fun NearbySmallAction(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        modifier.height(42.dp).clip(RoundedCornerShape(12.dp)).background(p.chipBg)
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = p.chipText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}
