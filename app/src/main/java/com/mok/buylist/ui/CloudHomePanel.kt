package com.mok.buylist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mok.buylist.R
import com.mok.buylist.ui.theme.LocalPalette

@Composable
internal fun CloudHomePanel(onConnect: () -> Unit, onHelp: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().background(p.card, RoundedCornerShape(24.dp)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(painterResource(R.drawable.ic_cloud), contentDescription = null,
                modifier = Modifier.size(36.dp), tint = p.text)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Не подключено", color = p.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("Покупки сохраняются на устройстве", color = p.textSecondary, fontSize = 13.sp)
            }
        }
        Text("В этой Beta доступна предварительная регистрация устройства. Автоматический обмен покупками пока не включён.", color = p.textSecondary, fontSize = 14.sp)
        FreshCutSecondaryButton("Подключить устройство", onConnect)
        FreshCutSectionTitle("Поддержка")
        FreshCutSecondaryButton("Помощь с подключением", onHelp)
    }
}

@Composable
internal fun CloudConnectionHelpPanel() {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CloudSshProbePanel()
        CloudIntegrationTestPanel()
    }
}
