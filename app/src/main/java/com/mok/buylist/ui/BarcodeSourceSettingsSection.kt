package com.mok.buylist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mok.buylist.barcodeSourceSettings
import com.mok.buylist.ui.theme.LocalPalette

@Composable
internal fun BarcodeSourceSettingsSection() {
    val p = LocalPalette.current
    val settings = LocalContext.current.barcodeSourceSettings
    val enabled by settings.enabled.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxWidth().background(p.card, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Поиск по штрихкоду", color = p.text,
            fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text("Выберите источники названий и изображений товаров. Отключённые API не опрашиваются.",
            color = p.textSecondary, fontSize = 12.sp)
        BarcodeSource.entries.forEach { source ->
            Row(Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(source.title, color = p.text, fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold)
                    Text(source.description, color = p.textSecondary, fontSize = 12.sp)
                }
                Switch(
                    checked = source in enabled,
                    onCheckedChange = { settings.setEnabled(source, it) },
                )
            }
        }
        Text("GS1 Россия открывается на официальном сайте. Автоматический API для обычного доступа не подтверждён.",
            color = p.textSecondary, fontSize = 12.sp)
    }
}
