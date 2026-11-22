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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
    onDismiss: () -> Unit,
    onAddressSettings: () -> Unit,
) {
    val p = LocalPalette.current
    var text by rememberSaveable { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var scannedCode by remember { mutableStateOf<String?>(null) }
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

    suspend fun saveLenta(product: LentaProduct) {
        lenta.save(product)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = p.sheet,
        contentColor = p.text,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { SheetDragHandle(onDismiss) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .imePadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        ) {
            NoticeInSheet()
            SheetHeader(stringResource(R.string.add_title))


            AnimatedVisibility(
                visible = voice.listening,
                enter = expandVertically(Motion.smooth()) + fadeIn(Motion.smooth()),
                exit = shrinkVertically(Motion.smooth(Motion.Quick)) + fadeOut(Motion.smooth(Motion.Quick)),
            ) {
                ListeningIndicator(voice.levels)
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(p.field)
                    .drawBehind {
                        val line = 3.dp.toPx()
                        drawRect(p.accent, Offset(0f, size.height - line), Size(size.width, line))
                    }
                    .padding(start = 18.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        scannedCode = null
                        onQueryChange(it)
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = TextStyle(color = p.text, fontFamily = Manrope, fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
                    cursorBrush = SolidColor(p.accent),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Search,
                    ),
                    keyboardActions = KeyboardActions(onSearch = { }),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (text.isEmpty()) {
                                Text(
                                    stringResource(R.string.add_hint),
                                    color = p.textSecondary,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            inner()
                        }
                    },
                )
                AnimatedVisibility(
                    visible = voice.available,
                    enter = fadeIn(Motion.smooth(Motion.Quick)) + scaleIn(Motion.smooth(Motion.Quick), initialScale = 0.8f),
                ) {
                    MicButton(voice)
                }
                IconButton(onClick = { scanning = true }) {
                    Icon(painterResource(R.drawable.ic_camera), contentDescription = "Сканировать штрихкод", tint = p.accent)
                }
            }

            if (!voice.listening) LentaSuggestions(
                query = text,
                modifier = Modifier.weight(1f),
                onSettings = onAddressSettings,
                onAdd = { product ->
                    scope.launch {
                        val chosen = if ((product.ean.isBlank() || product.ean == "null") && scannedCode == text) product.copy(ean = text) else product
                        saveLenta(chosen)
                        onAdd(chosen) { added ->
                            activity?.notifyUser(if (added) "Добавлено в список" else "Уже в списке")
                            if (added) { text = ""; scannedCode = null; onQueryChange("") }
                        }
                    }
                },
            )

        }
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
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            Modifier.weight(1f),
            color = p.text,
            fontSize = 20.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.ExtraBold,
        )
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
            tint = if (listening) p.onAccent else p.chipText,
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
