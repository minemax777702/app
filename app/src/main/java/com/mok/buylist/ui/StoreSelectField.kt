package com.mok.buylist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mok.buylist.data.ShoppingStore
import com.mok.buylist.ui.theme.LocalPalette

/** Single shared picker used by checkout and purchase corrections. */
@Composable
internal fun StoreSelectField(
    stores: List<ShoppingStore>,
    selectedStore: ShoppingStore?,
    onSelected: (ShoppingStore?) -> Unit,
    onCreate: (String, (ShoppingStore?) -> Unit) -> Unit,
) {
    val p = LocalPalette.current
    var menuOpen by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            Row(
                Modifier.fillMaxWidth()
                    .background(p.field, RoundedCornerShape(20.dp))
                    .clickable { menuOpen = true }
                    .padding(horizontal = 18.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(selectedStore?.name ?: "Магазин не указан",
                    modifier = Modifier.weight(1f), color = p.text,
                    fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text("⌄", color = p.textSecondary, fontSize = 20.sp)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Без магазина") },
                    onClick = { menuOpen = false; onSelected(null) },
                )
                stores.filterNot { it.deleted }.sortedBy { it.nameKey }.forEach { store ->
                    DropdownMenuItem(
                        text = { Text(store.name) },
                        onClick = { menuOpen = false; onSelected(store) },
                    )
                }
                DropdownMenuItem(
                    text = { Text("+ Добавить магазин", color = p.accent) },
                    onClick = { menuOpen = false; adding = true; error = false },
                )
            }
        }
        if (adding) {
            SheetTextField(newName, { newName = it; error = false }, "Название магазина",
                isError = error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { adding = false; newName = ""; error = false }) {
                    Text("Отмена", color = p.textSecondary)
                }
                TextButton(
                    enabled = !saving && newName.isNotBlank() && newName.length <= 100,
                    onClick = {
                        saving = true
                        onCreate(newName.trim()) { store ->
                            saving = false
                            if (store != null) {
                                onSelected(store)
                                adding = false
                                newName = ""
                                error = false
                            } else error = true
                        }
                    },
                ) { Text("Добавить", color = p.accent) }
            }
            if (error) Text("Не удалось сохранить магазин", color = p.error, fontSize = 12.sp)
        }
    }
}
