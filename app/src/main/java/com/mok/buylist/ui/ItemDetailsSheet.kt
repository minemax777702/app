package com.mok.buylist.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mok.buylist.data.LentaProduct
import com.mok.buylist.data.ShoppingItem
import com.mok.buylist.lentaCatalog
import com.mok.buylist.ui.theme.LocalPalette
import kotlinx.coroutines.CancellationException
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ItemDetailsSheet(item: ShoppingItem, product: LentaProduct?, address: String, refresh: String?, onRefresh: () -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val shown = product
    val price = shown?.takeIf { address.isNotBlank() && it.address == address && it.channel == "locc" }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = p.sheet,
        dragHandle = { SheetDragHandle(onDismiss) }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NoticeInSheet()
            SheetHeader(item.name)
            LentaImage(shown?.image.orEmpty(), Modifier.size(160.dp))
            price?.price?.takeIf { it.isNotBlank() && it != "null" }?.let {
                Text("$it ₽", color = p.text, fontWeight = FontWeight.Bold)
            }
            if (price != null) {
                val date = price.fetchedAt.takeIf { it > 0 }?.let { DateFormat.getDateTimeInstance().format(Date(it)) }
                if (date != null) Text("Цена получена: $date", color = p.textSecondary)
            }
            shown?.pack?.takeIf { it.isNotBlank() }?.let { Text("Упаковка: $it", color = p.textSecondary) }
            shown?.ean?.takeIf { it.isNotBlank() }?.let { Text("Штрихкод: $it", color = p.textSecondary) }
            SheetActionButton(onClick = onRefresh, enabled = product != null && refresh != "Загрузка") {
                Text(if (refresh == "Загрузка") "Обновление…" else "Обновить цену")
            }
        }
    }
}
