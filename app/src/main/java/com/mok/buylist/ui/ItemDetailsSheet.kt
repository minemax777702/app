package com.mok.buylist.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mok.buylist.data.ArchivedList
import com.mok.buylist.data.CustomProduct
import com.mok.buylist.data.CustomProductBarcode
import com.mok.buylist.data.LentaProduct
import com.mok.buylist.data.ShoppingItem
import com.mok.buylist.data.ShoppingStore
import com.mok.buylist.data.StoreProduct
import com.mok.buylist.data.lentaStoreId
import com.mok.buylist.ui.theme.LocalPalette
import java.math.BigDecimal
import java.text.DateFormat
import java.util.Date

private fun unitMoney(kopecks: Long): String =
    BigDecimal.valueOf(kopecks, 2).stripTrailingZeros().toPlainString() + " ₽"

/**
 * Information about planned products. The price refresh action belongs only
 * to the live Lenta catalog; manually created items show meaningful metadata.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ItemDetailsSheet(
    item: ShoppingItem,
    product: LentaProduct?,
    address: String,
    refresh: String?,
    onRefresh: () -> Unit,
    customProducts: List<CustomProduct>,
    customBarcodes: List<CustomProductBarcode>,
    storeLinks: List<StoreProduct>,
    stores: List<ShoppingStore>,
    archive: List<ArchivedList>,
    bought: List<ShoppingItem>,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    val own = customProducts.firstOrNull { it.id == item.customProductId && !it.deleted }
    val livePrice = product?.takeIf {
        address.isNotBlank() && it.address == address && it.channel == "locc"
    }
    val productKey = if (own != null) "custom:${own.id}" else "lenta:${item.productId}"
    val aliases = customBarcodes.filter { it.productId == own?.id }
    val codes = aliases.filterNot { it.deleted }.map { it.code }.toMutableSet()
    if (own != null && own.barcode.isNotBlank() &&
        aliases.none { it.code == own.barcode && it.deleted }) codes += own.barcode

    val history = expensePurchases(archive, bought).filter {
        it.productKey == productKey && it.storeId.isNotBlank()
    }
    val shopPrices = (history.map { it.storeId } +
        storeLinks.filter { !it.deleted && it.productKey == productKey }.map { it.storeId })
        .distinct().map { shopId ->
            val link = storeLinks.firstOrNull {
                it.storeId == shopId && it.productKey == productKey && !it.deleted
            }
            val latest = history.filter { it.storeId == shopId && it.unitPrice != null }
                .maxByOrNull { it.at }
            val name = stores.firstOrNull { it.id == shopId }?.name
                ?: if (shopId == lentaStoreId()) "Лента" else "Другой магазин"
            Triple(name, latest?.unitPrice ?: link?.priceKopecks,
                latest != null)
        }.sortedBy { it.first }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.sheet,
        dragHandle = { SheetDragHandle(onDismiss) },
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NoticeInSheet()
            SheetHeader(item.name)
            LentaImage(product?.image.orEmpty(), Modifier.size(136.dp))

            if (own != null) {
                Text(if (own.isWeighted) "Весовой товар" else "Штучный товар",
                    color = p.textSecondary, fontSize = 13.sp)
                own.estimatedPriceKopecks?.let {
                    Text("Ориентировочная цена: ${unitMoney(it)}" +
                        if (own.isWeighted) "/кг" else "",
                        color = p.text, fontWeight = FontWeight.SemiBold)
                }
                if (codes.isNotEmpty()) {
                    Text("Штрихкоды", color = p.textSecondary, fontSize = 13.sp)
                    codes.sorted().forEach { code ->
                        Text(code, color = p.text, fontSize = 15.sp)
                    }
                }
                Text("Цены по магазинам", color = p.text,
                    fontWeight = FontWeight.Bold, fontSize = 16.sp)
                if (shopPrices.isEmpty()) Text("Пока нет известных цен",
                    color = p.textSecondary, fontSize = 14.sp)
                shopPrices.forEach { (name, price, fromHistory) ->
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(name, color = p.text, modifier = Modifier.weight(1f))
                        Text(price?.let(::unitMoney)?.plus(if (own.isWeighted) "/кг" else "")
                            ?: "Цена не указана", color = p.accent, fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                livePrice?.price?.takeIf { it.isNotBlank() && it != "null" }?.let {
                    Text("$it ₽", color = p.text, fontWeight = FontWeight.Bold)
                }
                if (livePrice != null) {
                    val date = livePrice.fetchedAt.takeIf { it > 0 }
                        ?.let { DateFormat.getDateTimeInstance().format(Date(it)) }
                    if (date != null) Text("Цена получена: $date", color = p.textSecondary)
                }
                product?.pack?.takeIf { it.isNotBlank() }?.let {
                    Text("Упаковка: $it", color = p.textSecondary)
                }
                product?.ean?.takeIf { it.isNotBlank() }?.let {
                    Text("Штрихкод: $it", color = p.textSecondary)
                }
                SheetActionButton(
                    onClick = onRefresh,
                    enabled = product != null && refresh != "Загрузка",
                ) {
                    Text(if (refresh == "Загрузка") "Обновление…" else "Обновить цену")
                }
            }
        }
    }
}
