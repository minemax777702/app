package com.mok.buylist.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
    var showAll by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val available = stores.filterNot { it.deleted }.distinctBy { it.id }.sortedBy { it.nameKey }

    fun choose(store: ShoppingStore?) {
        expanded = false
        adding = false
        showAll = false
        newName = ""
        error = false
        onSelected(store)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .background(p.field, RoundedCornerShape(18.dp))
                .clickable {
                    expanded = !expanded
                    if (!expanded) {
                        adding = false
                        newName = ""
                        error = false
                    }
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(painterResource(R.drawable.ic_store), contentDescription = null,
                tint = p.accent, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text("Магазин", color = p.textSecondary, fontSize = 12.sp)
                Text(selectedStore?.name ?: "Не выбран", color = p.text,
                    fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }
            Icon(painterResource(R.drawable.ic_chevron_down),
                contentDescription = if (expanded) "Свернуть магазины" else "Выбрать магазин",
                tint = p.iconMuted, modifier = Modifier.size(22.dp))
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                Modifier.fillMaxWidth()
                    .background(p.field, RoundedCornerShape(18.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                @Composable
                fun option(name: String, selected: Boolean, onClick: () -> Unit) {
                    Row(
                        Modifier.fillMaxWidth().clickable(onClick = onClick)
                            .background(if (selected) p.chipBg else p.field, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(name, Modifier.weight(1f), color = p.text, fontSize = 15.sp)
                        if (selected) Icon(painterResource(R.drawable.ic_check), null,
                            tint = p.accent, modifier = Modifier.size(18.dp))
                    }
                }
                option("Без магазина", selectedStore == null) { choose(null) }
                (if (showAll) available else available.take(6)).forEach { store ->
                    option(store.name, selectedStore?.id == store.id) { choose(store) }
                }
                if (!showAll && available.size > 6)
                    TextButton(onClick = { showAll = true }) {
                        Text("Показать все (${available.size})", color = p.accent)
                    }
                if (!adding) {
                    Row(Modifier.fillMaxWidth().clickable {
                        adding = true
                        newName = ""
                        error = false
                    }.padding(horizontal = 12.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_plus), null, tint = p.accent,
                            modifier = Modifier.size(20.dp))
                        Text("Добавить магазин", color = p.accent, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SheetTextField(newName, { newName = it; error = false },
                            "Название магазина", isError = error)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(onClick = {
                                adding = false
                                newName = ""
                                error = false
                            }) { Text("Отмена", color = p.textSecondary) }
                            TextButton(
                                enabled = !saving && newName.isNotBlank() && newName.length <= 100,
                                onClick = {
                                    saving = true
                                    onCreate(newName.trim()) { store ->
                                        saving = false
                                        if (store != null) choose(store)
                                        else error = true
                                    }
                                },
                            ) { Text("Сохранить", color = p.accent) }
                        }
                        if (error) Text("Не удалось сохранить магазин", color = p.error, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
