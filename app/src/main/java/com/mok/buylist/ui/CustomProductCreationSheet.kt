package com.mok.buylist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mok.buylist.lentaCatalog
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
import com.mok.buylist.data.LentaProduct
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
        modifier.fillMaxWidth().clip(RoundedCornerShape(UiMetrics.controlRadius))
            .background(p.field)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(R.drawable.ic_plus), contentDescription = null, tint = p.text)
        Text("Создать свой товар", modifier = Modifier.weight(1f),
            color = p.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
internal fun CustomProductCreationSheet(
    initialName: String = "",
    initialBarcode: String = "",
    onCreate: (name: String, barcode: String, estimatedPriceKopecks: Long?, isWeighted: Boolean, onResult: (Boolean) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onCreated: () -> Unit,
    onChooseLenta: ((LentaProduct, (Boolean) -> Unit) -> Unit)? = null,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val catalog = context.lentaCatalog
    val localProducts by catalog.products.collectAsStateWithLifecycle()
    val localCodes by catalog.barcodeRows.collectAsStateWithLifecycle()
    var name by remember(initialName) { mutableStateOf(initialName) }
    var barcode by remember(initialBarcode) { mutableStateOf(initialBarcode) }
    var price by remember { mutableStateOf("") }
    var isWeighted by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val parsedPrice = if (price.isBlank()) null else parsePriceKopecks(price)
    var nameEdited by remember(initialName) { mutableStateOf(initialName.isNotBlank()) }

    val matchingLenta = remember(barcode, localProducts, localCodes) {
        if (!validProductBarcode(barcode)) emptyList() else localProducts.filter { product ->
            com.mok.buylist.sync.barcodeMatches(product.ean, barcode) || localCodes.any { row ->
                row.productId == product.id && com.mok.buylist.sync.barcodeMatches(row.code, barcode) && !row.deleted
            }
        }.distinctBy { it.id }
    }
    LaunchedEffect(barcode, matchingLenta) {
        if (name.isBlank() && !nameEdited) matchingLenta.firstOrNull()?.let { name = it.name }
    }

    if (!scanning) AppSheet(
        onDismissRequest = { if (!saving && !scanning) onDismiss() },
        containerColor = p.sheet,
        title = { SheetHeader("Свой товар") }, minBodyHeight = 480.dp,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SheetTextField(name, { name = it; nameEdited = true; error = null }, "Название товара", placeholder = "Введите название")
                if (name.length > 200) Text("Максимум 200 символов", color = p.error, fontSize = 12.sp)
                SheetTextField(
                    barcode,
                    { barcode = it.filter(Char::isDigit).take(14); error = null },
                    "Штрихкод (необязательно)",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    placeholder = "Введите или отсканируйте",
                    trailingAction = {
                        IconButton(onClick = { scanning = true }, enabled = !saving) {
                            Icon(painterResource(R.drawable.ic_camera), "Сканировать штрихкод камерой",
                                Modifier.size(24.dp), tint = p.text)
                        }
                    },
                )
                if (matchingLenta.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(UiMetrics.cardRadius))
                        .background(p.card).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("Сначала проверьте товар Ленты", color = p.accent,
                            fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        matchingLenta.take(4).forEach { product ->
                            Row(Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                LentaImage(product.image, Modifier.size(56.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(product.name, color = p.text, fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold)
                                    if (product.pack.isNotBlank())
                                        Text(product.pack, color = p.textSecondary, fontSize = 12.sp)
                                }
                            }
                            if (onChooseLenta != null) {
                                SheetActionButton(enabled = !saving, onClick = {
                                    saving = true
                                    onChooseLenta(product) { added ->
                                        saving = false
                                        if (added) onCreated()
                                        else error = "Товар уже в списке"
                                    }
                                }) { Text("Добавить товар Ленты") }
                            } else {
                                TextButton(onClick = {
                                    name = product.name
                                    nameEdited = true
                                }) { Text("Использовать название Ленты", color = p.accent) }
                            }
                        }
                        if (matchingLenta.size > 4) Text("Найдено ещё несколько совпадений в Ленте",
                            color = p.textSecondary, fontSize = 12.sp)
                        Text("Можно также создать свой товар ниже",
                            color = p.textSecondary, fontSize = 12.sp)
                    }
                }
                FreshCutSegments(listOf("Штучный", "Весовой"), if (isWeighted) 1 else 0,
                    { isWeighted = it == 1 })
                SheetTextField(
                    price,
                    { price = it; error = null },
                    if (isWeighted) "Цена за кг, ₽ (необязательно)" else "Цена, ₽ (необязательно)",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                error?.let { Text(it, color = p.error, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            SheetActionButton(
                enabled = !saving && name.isNotBlank() && name.length <= 200 && (price.isBlank() || parsedPrice != null),
                onClick = {
                    if (barcode.isNotBlank() && !validProductBarcode(barcode)) {
                        error = "Некорректный штрихкод"
                    } else {
                        saving = true
                        onCreate(name.trim(), barcode, parsedPrice, isWeighted) { success ->
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
