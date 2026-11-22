package com.mok.buylist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mok.buylist.R
import com.mok.buylist.data.parsePriceKopecks
import com.mok.buylist.data.validProductBarcode
import com.mok.buylist.ui.theme.LocalPalette

/**
 * The same real creation form is available from the shopping list and the
 * product database. Price is only an estimate, never a purchase event.
 */
@Composable
internal fun CreateCustomProductAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(p.field)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(R.drawable.ic_plus), contentDescription = null, tint = p.accent)
        Column(Modifier.weight(1f)) {
            Text("Создать свой товар", color = p.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("Без каталога Ленты", color = p.textSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
internal fun CustomProductCreationSheet(
    initialName: String = "",
    initialBarcode: String = "",
    onCreate: (name: String, barcode: String, estimatedPriceKopecks: Long?, onResult: (Boolean) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onCreated: () -> Unit,
) {
    val p = LocalPalette.current
    var name by remember(initialName) { mutableStateOf(initialName) }
    var barcode by remember(initialBarcode) { mutableStateOf(initialBarcode) }
    var price by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val parsedPrice = if (price.isBlank()) null else parsePriceKopecks(price)

    if (!scanning) AppSheet(
        onDismissRequest = { if (!saving && !scanning) onDismiss() },
        containerColor = p.sheet,
        title = { SheetHeader("Свой товар") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SheetTextField(name, { name = it; error = null }, "Название товара")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        SheetTextField(
                            barcode,
                            { barcode = it.filter(Char::isDigit).take(13); error = null },
                            "Штрихкод (необязательно)",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                    IconButton(onClick = { scanning = true }, enabled = !saving) {
                        Icon(painterResource(R.drawable.ic_camera),
                            contentDescription = "Сканировать штрихкод камерой", tint = p.accent)
                    }
                }
                SheetTextField(
                    price,
                    { price = it; error = null },
                    "Ориентировочная цена, ₽ (необязательно)",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Text(
                    "Цена сохраняется как ориентир. Фактическую стоимость можно указать при покупке.",
                    color = p.textSecondary, fontSize = 12.sp,
                )
                error?.let { Text(it, color = p.error, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            SheetActionButton(
                enabled = !saving && name.isNotBlank() && (price.isBlank() || parsedPrice != null),
                onClick = {
                    if (barcode.isNotBlank() && !validProductBarcode(barcode)) {
                        error = "Некорректный штрихкод"
                    } else {
                        saving = true
                        onCreate(name.trim(), barcode, parsedPrice) { success ->
                            saving = false
                            if (success) onCreated()
                            else error = "Товар уже в списке или штрихкод неоднозначен"
                        }
                    }
                },
            ) { Text(if (saving) "Сохранение…" else "Добавить товар") }
        },
    )
    if (scanning) {
        BarcodeScannerSheet(
            onDismiss = { scanning = false },
            onCode = { code ->
                scanning = false
                barcode = code
                error = if (validProductBarcode(code)) null else "Камера распознала некорректный штрихкод"
            },
        )
    }
}
