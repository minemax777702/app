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
import java.util.concurrent.atomic.AtomicBoolean

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
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            NoticeInSheet()
            if (granted && !cameraError) {
                QrScanner(useFront = false, barcode = true, modifier = Modifier.fillMaxSize(), onCameraError = { cameraError = true }, onText = { code ->
                    if (accepted.compareAndSet(false, true)) handler.post { if (active.get()) currentOnCode(code) }
                })
            }
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = "Назад", tint = Color.White)
                    }
                    Text("Штрихкод", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (granted && !cameraError) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.fillMaxWidth(0.84f).height(150.dp).clip(RoundedCornerShape(18.dp))
                                .border(2.dp, Color.White, RoundedCornerShape(18.dp)))
                            Text("Наведите на штрихкод", Modifier.padding(top = 16.dp)
                                .clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.7f))
                                .padding(horizontal = 16.dp, vertical = 8.dp), color = Color.White, textAlign = TextAlign.Center)
                        }
                    } else if (cameraError) {
                        Text("Не удалось открыть камеру", color = Color.White, textAlign = TextAlign.Center)
                    } else {
                        Column(Modifier.padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            SheetActionButton(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Разрешить камеру") }
                        }
                    }
                }
            }
        }
    }
}
