package com.mok.buylist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mok.buylist.R
import com.mok.buylist.data.ShoppingStore
import com.mok.buylist.ui.theme.LocalPalette

/**
 * In-sheet store selector using the app's own cards and icons.
 * Choosing a store always closes and resets the inline "add store" form.
 */
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
    var anchorWidth by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    var newName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val available = stores.filterNot { it.deleted }.distinctBy { it.id }.sortedBy { it.nameKey }

    fun choose(store: ShoppingStore?) {
        expanded = false
        adding = false
        newName = ""
        error = false
        onSelected(store)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().onSizeChanged { anchorWidth = it.width }
                    .clip(RoundedCornerShape(18.dp)).background(p.field)
                    .clickable { expanded = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(painterResource(R.drawable.ic_store), contentDescription = null,
                    tint = p.text, modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f)) {
                    Text("Магазин", color = p.textSecondary, fontSize = 12.sp)
                    Text(selectedStore?.name ?: "Не выбран", color = p.text,
                        fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                }
                Icon(painterResource(R.drawable.ic_chevron_down),
                    contentDescription = "Выбрать магазин",
                    tint = p.textSecondary, modifier = Modifier.size(20.dp))
            }
            AppDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = if (anchorWidth > 0) Modifier.width(with(density) { anchorWidth.toDp() }) else Modifier,
            ) {
                AppDropdownMenuItem(
                    text = { Text("Без магазина") },
                    onClick = { choose(null) },
                    trailingIcon = if (selectedStore == null) ({
                        Icon(painterResource(R.drawable.ic_check), contentDescription = "Выбрано",
                            tint = p.text, modifier = Modifier.size(20.dp))
                    }) else null,
                )
                available.forEach { store ->
                    AppDropdownMenuItem(
                        text = { Text(store.name) },
                        onClick = { choose(store) },
                        trailingIcon = if (selectedStore?.id == store.id) ({
                            Icon(painterResource(R.drawable.ic_check), contentDescription = "Выбрано",
                                tint = p.text, modifier = Modifier.size(20.dp))
                        }) else null,
                    )
                }
                AppDropdownMenuItem(
                    text = { Text("Добавить магазин") },
                    leadingIcon = {
                        Icon(painterResource(R.drawable.ic_plus), contentDescription = null,
                            tint = p.text, modifier = Modifier.size(20.dp))
                    },
                    onClick = {
                        expanded = false
                        adding = true
                        newName = ""
                        error = false
                    },
                )
            }
        }
        if (adding) {
            Column(Modifier.fillMaxWidth().background(p.field, RoundedCornerShape(18.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SheetTextField(newName, { newName = it; error = false },
                    "Название магазина", isError = error)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = {
                        adding = false
                        newName = ""
                        error = false
                    }, enabled = !saving) { Text("Отмена", color = p.textSecondary) }
                    TextButton(
                        enabled = !saving && newName.isNotBlank() && newName.length <= 100,
                        onClick = {
                            saving = true
                            onCreate(newName.trim()) { store ->
                                saving = false
                                if (store != null) choose(store) else error = true
                            }
                        },
                    ) { Text("Сохранить", color = p.text) }
                }
                if (error) Text("Не удалось сохранить магазин", color = p.error, fontSize = 12.sp)
            }
        }
    }
}
