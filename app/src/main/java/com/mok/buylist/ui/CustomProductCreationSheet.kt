package com.mok.buylist.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mok.buylist.lentaCatalog
import com.mok.buylist.barcodeSourceSettings
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val catalog = context.lentaCatalog
    val sourceSettings = context.barcodeSourceSettings
    val enabledSources by sourceSettings.enabled.collectAsStateWithLifecycle()
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
    val scope = rememberCoroutineScope()
    var lookupBusy by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<BarcodeProductSuggestion>>(emptyList()) }
    var lookedUpCode by remember { mutableStateOf("") }
    var nameEdited by remember(initialName) { mutableStateOf(initialName.isNotBlank()) }
    var showAllSuggestions by remember { mutableStateOf(false) }

    val localSuggestions = remember(barcode, localProducts, localCodes) {
        if (!validProductBarcode(barcode)) emptyList() else {
            localProducts.asSequence().filter { product ->
                product.ean == barcode || localCodes.any { row ->
                    row.productId == product.id && row.code == barcode && !row.deleted
                }
            }.map { product ->
                BarcodeProductSuggestion(product.name, "Локальная база Ленты",
                    product.image.takeIf { it.isNotBlank() }, score = 100)
            }.filter { BarcodeProductLookup.usefulName(it.name, barcode) }
                .distinctBy { it.name.lowercase() }.take(3).toList()
        }
    }
    val shownSuggestions = remember(localSuggestions, suggestions) {
        (localSuggestions + suggestions).distinctBy { it.name.lowercase().trim() }
            .sortedByDescending { it.score }
    }

    LaunchedEffect(barcode, localSuggestions, enabledSources) {
        suggestions = emptyList()
        lookedUpCode = ""
        if (!validProductBarcode(barcode)) return@LaunchedEffect
        showAllSuggestions = false
        if (name.isBlank() && !nameEdited && localSuggestions.isNotEmpty())
            name = localSuggestions.first().name
        delay(650)
        lookupBusy = true
        try {
            val found = BarcodeProductLookup.lookup(barcode, sources = enabledSources)
            suggestions = found
            lookedUpCode = barcode
            if (name.isBlank() && !nameEdited && found.isNotEmpty())
                name = found.first().name
        } finally { lookupBusy = false }
    }

    if (!scanning) AppSheet(
        onDismissRequest = { if (!saving && !scanning) onDismiss() },
        containerColor = p.sheet,
        title = { SheetHeader("Свой товар") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SheetTextField(name, { name = it; nameEdited = true; error = null }, "Название товара")
                if (name.length > 200) Text("Максимум 200 символов", color = p.error, fontSize = 12.sp)
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
                if (lookupBusy) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp,
                            color = p.accent)
                        Text("Ищем название по штрихкоду…", color = p.textSecondary, fontSize = 12.sp)
                    }
                }
                if (shownSuggestions.isNotEmpty()) {
                    Text("Найденные названия", color = p.textSecondary, fontSize = 12.sp)
                    (if (showAllSuggestions) shownSuggestions else shownSuggestions.take(4)).forEach { suggestion ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(p.field)
                            .clickable { name = suggestion.name; nameEdited = true }
                            .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LentaImage(suggestion.imageUrl.orEmpty(), Modifier.size(48.dp))
                            Column(Modifier.weight(1f)) {
                                Text(suggestion.name, color = p.text, fontSize = 14.sp)
                                Text(suggestion.source, color = p.textSecondary, fontSize = 11.sp)
                            }
                            Icon(painterResource(R.drawable.ic_plus), null,
                                tint = p.accent, modifier = Modifier.size(18.dp))
                        }
                    }
                    if (shownSuggestions.size > 4) {
                        TextButton(onClick = { showAllSuggestions = !showAllSuggestions }) {
                            Text(if (showAllSuggestions) "Свернуть варианты"
                                else "Все варианты (${shownSuggestions.size})", color = p.accent)
                        }
                    }
                } else if (!lookupBusy && lookedUpCode == barcode && barcode.isNotBlank()) {
                    Text("Название не найдено — его можно ввести вручную",
                        color = p.textSecondary, fontSize = 12.sp)
                }
                if (validProductBarcode(barcode) && !lookupBusy) {
                    TextButton(onClick = {
                        scope.launch {
                            lookupBusy = true
                            try {
                                suggestions = BarcodeProductLookup.lookup(barcode, force = true,
                                    sources = enabledSources)
                                lookedUpCode = barcode
                                if (name.isBlank() && !nameEdited && suggestions.isNotEmpty())
                                    name = suggestions.first().name
                            } finally { lookupBusy = false }
                        }
                    }) { Text("Повторить поиск", color = p.accent) }
                }
                if (validProductBarcode(barcode) && BarcodeSource.GS1_RUSSIA in enabledSources) {
                    TextButton(onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(
                            "https://search.gs1ru.org/gtin/$barcode"))
                        runCatching { context.startActivity(intent) }
                    }) {
                        Text("Проверить GTIN в GS1 Россия", color = p.accent)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !isWeighted, onClick = { isWeighted = false },
                        label = { Text("Штучный") })
                    FilterChip(selected = isWeighted, onClick = { isWeighted = true },
                        label = { Text("Весовой") })
                }
                SheetTextField(
                    price,
                    { price = it; error = null },
                    if (isWeighted) "Цена за кг, ₽" else "Цена, ₽",
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
