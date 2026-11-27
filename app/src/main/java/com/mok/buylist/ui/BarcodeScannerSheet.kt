package com.mok.buylist.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.mok.buylist.R
import com.mok.buylist.ui.theme.LocalPalette
import com.mok.buylist.data.validProductBarcode
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BarcodeScannerSheet(onCode: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val accepted = remember { AtomicBoolean(false) }
    val active = remember { AtomicBoolean(true) }
    val currentOnCode by rememberUpdatedState(onCode)
    val handler = remember { Handler(Looper.getMainLooper()) }
    var cameraError by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }
    DisposableEffect(Unit) { onDispose { active.set(false) } }
    val p = LocalPalette.current
    var manual by remember { mutableStateOf(false) }
    var manualCode by remember { mutableStateOf("") }
    AppModalSheet(onDismissRequest = onDismiss, containerColor = p.sheet) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SheetHeader("Сканировать штрихкод")
            if (manual) {
                SheetTextField(manualCode, { manualCode = it.filter(Char::isDigit).take(14) },
                    "EAN-8, UPC-A, EAN-13 или GTIN-14", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = manualCode.isNotEmpty() && !validProductBarcode(manualCode))
                SheetActionButton(enabled = validProductBarcode(manualCode), onClick = {
                    if (accepted.compareAndSet(false, true)) currentOnCode(manualCode)
                }) { Text("Подтвердить") }
                SearchModeActionForScanner("Вернуться к камере") { manual = false }
            } else {
                Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF141916)), contentAlignment = Alignment.Center) {
                    if (granted && !cameraError) {
                        QrScanner(useFront = false, barcode = true, modifier = Modifier.fillMaxSize(),
                            onCameraError = { cameraError = true }, onText = { code ->
                                if (accepted.compareAndSet(false, true)) handler.post { if (active.get()) currentOnCode(code) }
                            })
                        Box(Modifier.fillMaxWidth(0.90f).height(92.dp)
                            .border(2.dp, Color.White, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                            Box(Modifier.fillMaxWidth(0.94f).height(2.dp).background(p.actionFill))
                        }
                    } else {
                        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(painterResource(R.drawable.ic_camera), null, Modifier.size(40.dp), tint = Color.White)
                            Text(if (cameraError) "Не удалось открыть камеру" else "Нужен доступ к камере",
                                color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center)
                            SheetActionButton(onClick = {
                                if (cameraError) cameraError = false else permission.launch(Manifest.permission.CAMERA)
                            }) { Text(if (cameraError) "Повторить" else "Разрешить камеру") }
                        }
                    }
                }
                Text("Наведите камеру на штрихкод", Modifier.fillMaxWidth(),
                    color = p.textSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                SheetActionButton(onClick = { manual = true }) { Text("Ввести вручную") }
            }
        }
    }
}

@Composable
private fun SearchModeActionForScanner(title: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(title) }
}
