package com.mok.buylist.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mok.buylist.R
import com.mok.buylist.lentaCatalog
import com.mok.buylist.data.LentaProduct
import com.mok.buylist.data.CustomProductBarcode
import com.mok.buylist.data.CustomProduct
import com.mok.buylist.data.matchesProductName
import com.mok.buylist.data.validProductBarcode
import com.mok.buylist.ui.theme.LocalPalette
import com.mok.buylist.ui.theme.Manrope
import com.mok.buylist.ui.theme.Motion
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

private val Recording = Color(0xFFD93A3A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddItemSheet(
    onQueryChange: (String) -> Unit,
    onAdd: (LentaProduct, (Boolean) -> Unit) -> Unit,
    customProducts: List<CustomProduct>,
    customBarcodes: List<CustomProductBarcode>,
    onAddCustom: (CustomProduct, (Boolean) -> Unit) -> Unit,
    onCreateCustom: (String, String, Long?, Boolean, (Boolean) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onAddressSettings: () -> Unit,
) {
    val p = LocalPalette.current
    var text by rememberSaveable { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var scannedCode by remember { mutableStateOf<String?>(null) }
    var createOpen by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newBarcode by remember { mutableStateOf("") }
    val lenta = LocalContext.current.lentaCatalog
    val scope = rememberCoroutineScope()
    fun showHeard(name: String) {
        text = name
        onQueryChange(name)
    }
    val voice = rememberVoiceInput(
        // Пока человек говорит, показываем услышанное как есть, но с заглавной буквы.
        onPartial = { heard -> showHeard(heard.trim().replaceFirstChar { it.uppercase() }) },
        // Распознанная фраза служит поисковым запросом; товар выбирается из результатов «Ленты».
        onFinal = { heard -> showHeard(heard.firstOrNull().orEmpty().trim().replaceFirstChar { it.uppercase() }) },
    )
    val activity = androidx.activity.compose.LocalActivity.current as? MainActivity
    val context = LocalContext.current
    LaunchedEffect(voice.message) {
        voice.message?.let { activity?.notifyUser(context.getString(it)) }
    }
    DisposableEffect(Unit) { onDispose { onQueryChange("") } }

    suspend fun saveLenta(product: LentaProduct, scannedBarcode: String?) {
        lenta.save(product, scannedBarcode)
    }

    AppModalSheet(
        onDismissRequest = onDismiss,
        containerColor = p.sheet,
        contentColor = p.text,
        dragHandle = { SearchSheetDragHandle(onDismiss) },
        sheetGesturesEnabled = false,
    ) {
        // ModalBottomSheet uses its own window and focus owner.
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = LocalFocusManager.current
        val view = androidx.compose.ui.platform.LocalView.current
        fun dismissKeyboard() {
            focus.clearFocus(force = true)
            keyboard?.hide()
            val imm = view.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding().navigationBarsPadding()
                .fillMaxHeight(0.98f)
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SheetHeader(stringResource(R.string.add_title))


            Row(
                Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(p.card).border(1.dp, p.line, RoundedCornerShape(16.dp))
                    .padding(start = 12.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(painterResource(R.drawable.ic_search), null, Modifier.size(24.dp), tint = p.textSecondary)
                BasicTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        scannedCode = null
                        onQueryChange(it)
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = TextStyle(color = p.text, fontFamily = Manrope, fontSize = 15.sp, fontWeight = FontWeight.Medium),
                    cursorBrush = SolidColor(p.accent),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Search,
                    ),
                    keyboardActions = KeyboardActions(onSearch = { dismissKeyboard() }),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (text.isEmpty()) {
                                Text(
                                    stringResource(R.string.add_hint),
                                    color = p.textSecondary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                            inner()
                        }
                    },
                )
                IconButton(onClick = { dismissKeyboard(); voice.toggle() }, enabled = voice.available, modifier = Modifier.size(40.dp)) {
                    Icon(painterResource(if (voice.listening) R.drawable.ic_close else R.drawable.ic_mic),
                        if (voice.listening) "Остановить запись" else "Голосовой поиск", Modifier.size(22.dp), tint = p.text)
                }
                IconButton(onClick = { dismissKeyboard(); scanning = true }, modifier = Modifier.size(40.dp)) {
                    Icon(painterResource(R.drawable.ic_scan_barcode), "Сканировать штрихкод", Modifier.size(22.dp), tint = p.text)
                }
            }
            if (voice.listening) {
                ListeningIndicator(voice.levels)
                Spacer(Modifier.weight(1f))
            }

            val matches = remember(customProducts, customBarcodes, text) {
                if (text.isBlank()) emptyList() else customProducts.filter { !it.deleted &&
                    (matchesProductName(it.name, text) ||
                        customBarcodes.any { barcode -> !barcode.deleted &&
                            barcode.productId == it.id && barcode.code == text.trim() } ||
                        (it.barcode == text.trim() && customBarcodes.none { barcode ->
                            barcode.productId == it.id && barcode.code == text.trim() && barcode.deleted })) }
            }
            if (!voice.listening) {
                if (text.isBlank()) {
                    Text("Введите название или штрихкод", color = p.textSecondary, fontSize = 13.sp)
                    FreshCutSecondaryButton("Создать свой товар", { dismissKeyboard(); newName = ""; newBarcode = ""; createOpen = true })
                    Spacer(Modifier.weight(1f))
                } else LentaSuggestions(
                    query = text,
                    modifier = Modifier.weight(1f),
                    hasOtherResults = matches.isNotEmpty(),
                    onUserScroll = { dismissKeyboard() },
                    customResults = matches.map { ProductSearchRow.custom(it) },
                    customContent = { row ->
                        row.custom?.let { custom ->
                            FreshCutListRow(
                                icon = R.drawable.ic_package,
                                title = custom.name,
                                subtitle = listOfNotNull(if (custom.isWeighted) "Весовой товар" else "Свой товар", row.normalized?.label()).joinToString(" · "),
                                value = row.price?.let {
                                    java.math.BigDecimal.valueOf(it, 2).stripTrailingZeros().toPlainString() + " ₽"
                                },
                                onClick = {
                                    dismissKeyboard()
                                    onAddCustom(custom) { added ->
                                        activity?.notifyUser(if (added) "Добавлено в список" else "Уже в списке")
                                        if (added) { text = ""; onQueryChange(""); scannedCode = null }
                                    }
                                },
                            )
                        }
                    },
                    footer = {
                        FreshCutSecondaryButton("Создать свой товар", {
                            dismissKeyboard()
                            newName = if (scannedCode == text) "" else text
                            newBarcode = scannedCode.orEmpty()
                            createOpen = true
                        })
                    },
                    onSettings = onAddressSettings,
                    onAdd = { product ->
                        dismissKeyboard()
                        scope.launch {
                            val chosen = if ((product.ean.isBlank() || product.ean == "null") && scannedCode == text) product.copy(ean = text) else product
                            saveLenta(chosen, scannedCode?.takeIf { it == text }
                                 ?: text.trim().takeIf { validProductBarcode(it) &&
                                     lenta.barcodeAliases(product.id).any { alias ->
                                         com.mok.buylist.sync.possiblePackagingBarcodeMatch(alias, it)
                                     } })
                            onAdd(chosen) { added ->
                                activity?.notifyUser(if (added) "Добавлено в список" else "Уже в списке")
                                if (added) { text = ""; scannedCode = null; onQueryChange("") }
                            }
                        }
                    },
                )
            }

        }
    }
    if (createOpen) {
        CustomProductCreationSheet(
            initialName = newName,
            initialBarcode = newBarcode,
            onCreate = onCreateCustom,
            onChooseLenta = { product, onResult ->
                onAdd(product) { added ->
                    onResult(added)
                    if (added) activity?.notifyUser("Добавлен товар Ленты")
                }
            },
            onDismiss = { createOpen = false },
            onCreated = {
                createOpen = false
                text = ""
                scannedCode = null
                onQueryChange("")
                activity?.notifyUser("Товар добавлен")
            },
        )
    }
    if (scanning) BarcodeScannerSheet(onDismiss = { scanning = false }, onCode = { code ->
        scanning = false
        text = code
        scannedCode = code
        onQueryChange(code)

    })

}

@Composable
internal fun SheetHeader(title: String) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            Modifier.weight(1f),
            color = p.text,
            fontSize = 24.sp,
            lineHeight = 30.sp,
            fontWeight = FontWeight.SemiBold,
        )
        LocalSheetClose.current?.let { SheetCloseButton(it) }
    }
}

@Composable
private fun MicButton(voice: VoiceInput) {
    val p = LocalPalette.current
    val listening = voice.listening
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Restart),
        label = "pulse",
    )
    Box(
        Modifier
            .size(48.dp)
            .drawBehind {
                if (listening) {
                    val base = size.minDimension / 2
                    drawCircle(p.accent.copy(alpha = 0.22f * (1f - pulse)), base + 12.dp.toPx() * pulse)
                }
            }
            .clip(CircleShape)
            .background(if (listening) p.accent else p.chipBg)
            .clickable(role = Role.Button, onClick = voice::toggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_mic),
            contentDescription = stringResource(if (listening) R.string.voice_stop else R.string.voice_start),
            Modifier.size(24.dp),
            tint = if (listening) p.onAccent else p.text,
        )
    }
}

@Composable
private fun ListeningIndicator(levels: List<Float>) {
    val p = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.height(64.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            levels.forEach { level ->
                val barHeight by animateDpAsState(12.dp + 46.dp * level, tween(120), label = "bar")
                Box(
                    Modifier
                        .width(5.dp)
                        .height(barHeight)
                        .clip(RoundedCornerShape(3.dp))
                        .background(p.accent.copy(alpha = 0.35f + 0.65f * level)),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(Recording))
            Text(
                stringResource(R.string.voice_listening),
                color = p.chipText,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun SearchModeAction(icon: Int, title: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(modifier.height(48.dp).clip(RoundedCornerShape(16.dp)).background(p.card)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(painterResource(icon), null, Modifier.size(24.dp), tint = if (enabled) p.text else p.textSecondary)
        Text(title, color = if (enabled) p.text else p.textSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
