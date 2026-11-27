package com.mok.buylist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mok.buylist.R
import com.mok.buylist.ui.theme.LocalPalette

@Composable
internal fun CloudHomePanel(onConnect: () -> Unit) {
    val p = LocalPalette.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("cloud_connection_v1", android.content.Context.MODE_PRIVATE) }
    val confirmedAt = remember { prefs.getLong("confirmed_at", 0L) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().background(p.card, RoundedCornerShape(24.dp)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(painterResource(R.drawable.ic_cloud), contentDescription = null,
                modifier = Modifier.size(36.dp), tint = p.text)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (confirmedAt > 0) "Доступ подтверждён ранее" else "Подключите облако", color = p.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("Покупки сохраняются на телефоне", color = p.textSecondary, fontSize = 13.sp)
            }
        }
        Text("Покажите QR-код администратору. Создавать аккаунт не нужно.", color = p.textSecondary, fontSize = 14.sp)
        FreshCutSecondaryButton(if (confirmedAt > 0) "Управление подключением" else "Подключить устройство", onConnect)
        Text("Обмен покупками через облако пока недоступен.", color = p.textSecondary, fontSize = 13.sp)
    }
}
