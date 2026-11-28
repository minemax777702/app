package com.mok.buylist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mok.buylist.R
import com.mok.buylist.data.ShoppingStore
import com.mok.buylist.ui.theme.LocalPalette

/** Отдельная панель выбора и создания магазина: Figma select-store/create-store. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StoreSelectField(
    stores: List<ShoppingStore>,
    selectedStore: ShoppingStore?,
    onSelected: (ShoppingStore?) -> Unit,
    onCreate: (String, (ShoppingStore?) -> Unit) -> Unit,
) {
    val p = LocalPalette.current
    var expanded by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val available = stores.filterNot { it.deleted }.distinctBy { it.id }.sortedBy { it.nameKey }
    fun close() { expanded = false; adding = false; newName = ""; error = false }
    fun choose(store: ShoppingStore?) { close(); onSelected(store) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Магазин", color = p.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Row(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(UiMetrics.controlRadius)).background(p.card)
            .border(1.dp, p.controlOutline, RoundedCornerShape(UiMetrics.controlRadius)).clickable { expanded = true }.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(selectedStore?.name ?: "Без магазина", Modifier.weight(1f), color = p.text, fontSize = 15.sp)
            Icon(painterResource(R.drawable.ic_chevron_down), "Выбрать магазин", Modifier.size(20.dp), tint = p.textSecondary)
        }
    }
    if (expanded && !adding) AppModalSheet(onDismissRequest = { close() }, containerColor = p.sheet) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SheetHeader("Выбрать магазин")
            Spacer(Modifier.height(12.dp))
            available.forEach { store ->
                FreshCutListRow(R.drawable.ic_store, store.name, if (selectedStore?.id == store.id) "Выбран" else "",
                    selected = selectedStore?.id == store.id, showChevron = false, onClick = { choose(store) })
            }
            FreshCutListRow(R.drawable.ic_store, "Без магазина", "Не указывать", selected = selectedStore == null,
                showChevron = false, onClick = { choose(null) })
            Spacer(Modifier.height(20.dp))
            SheetActionButton(onClick = { adding = true; newName = ""; error = false }) {
                Text("Добавить магазин")
            }
        }
    }
    if (adding) AppSheet(
        onDismissRequest = { if (!saving) adding = false },
        containerColor = p.sheet,
        title = { SheetHeader("Новый магазин") },
        text = {
            SheetTextField(newName, { newName = it; error = false }, "Название магазина", isError = error)
            if (error) Text("Не удалось сохранить магазин", color = p.error, fontSize = 12.sp)
        },
        confirmButton = {
            SheetActionButton(enabled = !saving && newName.isNotBlank() && newName.length <= 100, onClick = {
                saving = true
                onCreate(newName.trim()) { store ->
                    saving = false
                    if (store != null) choose(store) else error = true
                }
            }) { Text(if (saving) "Сохранение…" else "Добавить магазин") }
        },
    )

}
