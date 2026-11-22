package com.mok.buylist.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class PreviewColors(
    val background: Color, val card: Color, val text: Color, val secondary: Color,
    val accent: Color, val onAccent: Color, val field: Color, val chip: Color,
    val sheet: Color, val error: Color,
)

private val light = PreviewColors(
    Color(0xFFF1F8F3), Color.White, Color(0xFF14231B), Color(0xFF4F6057),
    Color(0xFF0C7F50), Color.White, Color(0xFFEEF6F1), Color(0xFFE3F4EA),
    Color.White, Color(0xFFB3261E),
)

private val dark = PreviewColors(
    Color(0xFF0E1512), Color(0xFF17211C), Color(0xFFE4F0E9), Color(0xFF9DB0A5),
    Color(0xFF5FE0A0), Color(0xFF08261A), Color(0xFF1A261F), Color(0xFF1F3A2D),
    Color(0xFF17211C), Color(0xFFFFB4AB),
)

/** A browser-only preview. Android data, Room and the physical camera are intentionally not used. */
@Composable
fun PreviewApplication() {
    var darkTheme by remember { mutableStateOf(false) }
    var narrow by remember { mutableStateOf(false) }
    val c = if (darkTheme) dark else light
    val scheme = if (darkTheme) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = scheme.copy(
        primary = c.accent, onPrimary = c.onAccent, background = c.background,
        surface = c.card, onSurface = c.text, surfaceContainerLow = c.sheet,
        error = c.error,
    )) {
        Row(Modifier.fillMaxSize().background(c.background).padding(12.dp),
            horizontalArrangement = Arrangement.Center) {
            Column(Modifier.fillMaxHeight().widthIn(max = if (narrow) 340.dp else 480.dp)) {
                PreviewToolbar(c, darkTheme, { darkTheme = !darkTheme }, narrow, { narrow = !narrow })
                Spacer(Modifier.height(12.dp))
                ProductPreview(c)
            }
        }
    }
}

@Composable
private fun PreviewToolbar(c: PreviewColors, darkTheme: Boolean, onTheme: () -> Unit,
    narrow: Boolean, onSize: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text("UI Playground", fontWeight = FontWeight.Bold, color = c.text)
            Text("Compose Multiplatform · Wasm", fontSize = 11.sp, color = c.secondary)
        }
        TextButton(onClick = onSize) { Text(if (narrow) "Шире" else "Уже", color = c.accent) }
        TextButton(onClick = onTheme) { Text(if (darkTheme) "☀" else "☾", color = c.accent) }
    }
}

@Composable
private fun ProductPreview(c: PreviewColors) {
    val barcodes = remember { mutableStateListOf("4607078115610", "4607078115627") }
    var editing by remember { mutableStateOf<String?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var codeInput by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun openEditor(old: String?) {
        editing = old
        codeInput = old.orEmpty()
        error = null
        editorOpen = true
    }

    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)).background(c.background)) {
        Row(Modifier.fillMaxWidth().background(c.accent).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("‹", fontSize = 32.sp, color = c.onAccent)
            Spacer(Modifier.width(12.dp))
            Text("Информация о товаре", color = c.onAccent, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                PreviewCard(c) {
                    Text("Молоко 3,2%, 1,4 л", color = c.text, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                }
            }
            item {
                PreviewCard(c) {
                    Text("Молоко · 1,4 л", color = c.text, fontSize = 16.sp)
                    Text("Цена: 169,99 ₽", color = c.secondary, fontSize = 14.sp)
                    Button(onClick = {}, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent)) {
                        Text("Добавить в список")
                    }
                }
            }
            item {
                PreviewCard(c) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Штрихкоды товара", color = c.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("Сохранено: ${barcodes.size}", color = c.secondary, fontSize = 12.sp)
                        }
                        IconButton(onClick = { openEditor(null) },
                            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(c.chip)) {
                            Text("+", fontSize = 26.sp, color = c.accent)
                        }
                    }
                    barcodes.forEach { code ->
                        BarcodeRow(c, code, code == "4607078115627",
                            onEdit = { openEditor(code) }, onDelete = { pendingDelete = code })
                    }
                    if (barcodes.isEmpty()) Text("Штрихкоды ещё не добавлены", color = c.secondary)
                }
            }
            item {
                PreviewCard(c) {
                    Text("Динамика цен", fontSize = 18.sp, color = c.text, fontWeight = FontWeight.Bold)
                    Text("Месяц     6 месяцев     Год", color = c.accent, fontSize = 13.sp)
                    Text("Тестовые данные — график появится при переносе экрана.", color = c.secondary, fontSize = 13.sp)
                }
            }
        }
    }

    if (editorOpen) {
        BarcodeEditorSheet(
            c = c, old = editing, value = codeInput,
            onValue = { codeInput = it.filter(Char::isDigit).take(13); error = null },
            error = error,
            onDismiss = { editorOpen = false },
            onScan = {
                // Simulates the scanner result in the browser; it does not access a camera.
                codeInput = "4607078115627"
                error = null
            },
            onSave = {
                val code = codeInput.trim()
                when {
                    !validBarcode(code) -> error = "Некорректный штрихкод"
                    code != editing && code in barcodes -> error = "Этот штрихкод уже добавлен"
                    else -> {
                        editing?.let { barcodes.remove(it) }
                        if (code !in barcodes) barcodes.add(code)
                        editorOpen = false
                    }
                }
            },
        )
    }

    pendingDelete?.let { code ->
        ConfirmDeleteSheet(c, code, onDismiss = { pendingDelete = null }) {
            barcodes.remove(code)
            pendingDelete = null
        }
    }
}

@Composable
private fun PreviewCard(c: PreviewColors, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

@Composable
private fun BarcodeRow(c: PreviewColors, code: String, current: Boolean,
    onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.field)
        .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(code, color = c.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            if (current) Text("Код из Ленты", color = c.secondary, fontSize = 11.sp)
        }
        IconButton(onClick = onEdit) { Text("✎", fontSize = 22.sp, color = c.accent) }
        IconButton(onClick = onDelete) { Text("×", fontSize = 23.sp, color = c.secondary) }
    }
}

private fun validBarcode(code: String): Boolean {
    if (code.length !in setOf(8, 12, 13) || !code.all { it in '0'..'9' }) return false
    val sum = code.dropLast(1).reversed().mapIndexed { i, ch ->
        ch.digitToInt() * if (i % 2 == 0) 3 else 1
    }.sum()
    return (10 - sum % 10) % 10 == code.last().digitToInt()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BarcodeEditorSheet(c: PreviewColors, old: String?, value: String, onValue: (String) -> Unit,
    error: String?, onDismiss: () -> Unit, onScan: () -> Unit, onSave: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (old == null) "Добавить штрихкод" else "Изменить штрихкод",
                fontSize = 20.sp, fontWeight = FontWeight.Bold, color = c.text)
            OutlinedTextField(value = value, onValueChange = onValue, singleLine = true,
                label = { Text("EAN-8, UPC-A или EAN-13") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(onClick = onScan) { Text("▣", fontSize = 25.sp, color = c.accent) }
                },
                isError = error != null)
            if (error != null) Text(error, fontSize = 13.sp, color = c.error)
            Text("Веб-демо: иконка камеры подставляет тестовый код.",
                color = c.secondary, fontSize = 12.sp)
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth().height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent)) {
                Text("Сохранить")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfirmDeleteSheet(c: PreviewColors, code: String, onDismiss: () -> Unit,
    onDelete: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Удалить штрихкод?", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = c.text)
            Text("Код $code будет удалён из тестового списка.", color = c.secondary)
            Button(onClick = onDelete, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = c.error)) {
                Text("Удалить")
            }
        }
    }
}
