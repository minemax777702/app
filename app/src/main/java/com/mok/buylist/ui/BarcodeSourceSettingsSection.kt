package com.mok.buylist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Поиск по штрихкоду", color = p.text,
            fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
        Text("Выберите источники названий и изображений. Отключённые API не опрашиваются.",
            color = p.textSecondary, fontSize = 14.sp, lineHeight = 17.sp)
        Spacer(Modifier.height(16.dp))
        BarcodeSource.entries.forEach { source ->
            Row(Modifier.fillMaxWidth().heightIn(min = if (source == BarcodeSource.entries.first()) 80.dp else 100.dp)
                .background(p.card, RoundedCornerShape(16.dp)).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(source.title, color = p.text, fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold)
                    Text(source.description, color = p.textSecondary, fontSize = 12.sp)
                }
                val checked = source in enabled
                Box(Modifier.size(52.dp, 28.dp).background(if (checked) p.actionFill else p.field, CircleShape)
                    .toggleable(value = checked, role = Role.Switch, onValueChange = { settings.setEnabled(source, it) })
                    .padding(4.dp)) {
                    Box(Modifier.align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                        .size(20.dp).background(if (checked) p.actionInk else p.textSecondary, CircleShape))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(if (enabled.isEmpty()) "Все внешние источники отключены." else "DiSAI проверяется по открытой странице товара. Если сайт недоступен или название не распознано, остальные источники продолжат работать.",
            color = p.textSecondary, fontSize = 12.sp)
    }
}
