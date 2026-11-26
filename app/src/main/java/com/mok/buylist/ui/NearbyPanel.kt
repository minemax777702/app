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

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(p.card).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(p.boughtBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_sync), contentDescription = null, modifier = Modifier.size(25.dp), tint = p.text)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Устройства рядом", color = p.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text("Без интернета", color = p.textSecondary, fontSize = 13.sp)
            }
            Box(Modifier.clip(CircleShape).background(p.chipBg).padding(horizontal = 11.dp, vertical = 7.dp)) {
                Text(if (state.active) "Поиск" else "Готово", color = p.chipText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        Text(state.status, color = p.textSecondary, fontSize = 14.sp)

        Box(
            Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(15.dp))
                .background(if (state.active) p.field else p.actionFill)
                .clickable {
                    message = ""
                    if (state.active) nearby.stop()
                    else if (!nearby.hasPermissions()) permissions.launch(NearbySync.requiredPermissions())
                    else nearby.start()
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (state.active) "Остановить" else "Синхронизировать",
                color = if (state.active) p.text else p.actionInk,
                fontSize = 16.sp, fontWeight = FontWeight.ExtraBold,
            )
        }

        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .clickable { pairingExpanded = !pairingExpanded; showingQr = false; scanning = false }
                .padding(vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Доверенные телефоны", modifier = Modifier.weight(1f), color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("${state.trusted.size}", color = p.textSecondary, fontSize = 14.sp)
            Spacer(Modifier.width(10.dp))
            Icon(painterResource(R.drawable.ic_chevron_down), contentDescription = null,
                modifier = Modifier.size(24.dp).rotate(if (pairingExpanded) 180f else 0f), tint = p.textSecondary)
        }

        if (pairingExpanded) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NearbySmallAction("Мой QR", Modifier.weight(1f)) { showingQr = !showingQr; scanning = false }
                NearbySmallAction("Сканировать", Modifier.weight(1f)) {
                    showingQr = false
                    if (hasCamera) scanning = !scanning else cameraPermission.launch(Manifest.permission.CAMERA)
                }
            }
            if (showingQr) {
                Text("QR-код устройства", color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Canvas(
                    Modifier.fillMaxWidth().aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp)).background(Color.White).padding(14.dp),
                ) {
                    val cell = size.minDimension / (qr.size + 8)
                    val origin = (size.minDimension - cell * qr.size) / 2
                    for (y in 0 until qr.size) for (x in 0 until qr.size) if (qr[x, y]) {
                        drawRect(Color.Black, Offset(origin + x * cell, origin + y * cell), Size(cell + .15f, cell + .15f))
                    }
                }
                Text("Откройте приложение на другом телефоне и отсканируйте код.", color = p.textSecondary, fontSize = 14.sp)
            }
            if (scanning) {
                QrScanner(false, onText = { code ->
                    if (code.startsWith("BLPAIR1:")) scope.launch(Dispatchers.Main) {
                        if (nearby.trustQr(code)) {
                            scanning = false
                            message = "Телефон добавлен"
                        } else message = "Не удалось подтвердить QR"
                    }
                }, modifier = Modifier.fillMaxWidth().height(240.dp))
            }
            state.trusted.forEach { id ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(id.take(12) + "…", modifier = Modifier.weight(1f), color = p.textSecondary,
                        fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    NearbySmallAction("Удалить") { nearby.remove(id) }
                }
            }
        }
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
