package com.mok.buylist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val own = customProducts.firstOrNull { it.id == item.customProductId && !it.deleted }
    val livePrice = product?.takeIf {
        address.isNotBlank() && it.address == address && it.channel == "locc"
    }
    val productKey = if (own != null) "custom:${own.id}" else "lenta:${item.productId}"
    val aliases = customBarcodes.filter { it.productId == own?.id }
    val codes = aliases.filterNot { it.deleted }.map { it.code }.toMutableSet()
    if (own != null && own.barcode.isNotBlank() &&
        aliases.none { it.code == own.barcode && it.deleted }) codes += own.barcode

    val shopPrices = productShopPrices(
        productKey, stores, storeLinks, expensePurchases(archive, bought))

    AppModalSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.sheet,
        dragHandle = { SheetDragHandle(onDismiss) },
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NoticeInSheet()
            SheetHeader("Информация о товаре")
            Row(
                Modifier.fillMaxWidth().background(p.card, RoundedCornerShape(20.dp))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (own != null) CustomProductImage(own.id, Modifier.size(88.dp))
                else LentaImage(product?.image.orEmpty(), Modifier.size(88.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(item.name, color = p.text, fontSize = 17.sp,
                        fontWeight = FontWeight.Bold, lineHeight = 23.sp,
                        maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            own?.isWeighted == true -> "Весовой товар"
                            own != null -> "Свой товар"
                            else -> "Лента"
                        },
                        color = p.textSecondary, fontSize = 12.sp,
                    )
                }
            }

            if (own != null) {
                CustomProductImageActions(own.id, codes.firstOrNull().orEmpty())
                androidx.compose.runtime.LaunchedEffect(own.id, codes.firstOrNull()) {
                    CustomProductPreviews.fetch(
                        context = context,
                        id = own.id,
                        barcode = codes.firstOrNull().orEmpty(),
                    )
                }
            }
            val catalogPrice = livePrice?.price?.takeIf { it.isNotBlank() && it != "null" }
            val lowest = cheapestShopPrice(shopPrices)
            val displayPrice = lowest?.priceKopecks?.let(::unitMoney)
                ?: own?.estimatedPriceKopecks?.let(::unitMoney)
                ?: catalogPrice?.let { "$it ₽" }
            if (displayPrice != null) {
                Column(
                    Modifier.fillMaxWidth().background(p.card, RoundedCornerShape(20.dp))
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        when {
                            lowest != null -> "Минимальная цена"
                            own != null -> "Ориентировочная цена"
                            else -> "Текущая цена"
                        },
                        color = p.textSecondary, fontSize = 12.sp,
                    )
                    Text(displayPrice + if (own?.isWeighted == true) " /кг" else "",
                        color = p.accent, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    lowest?.let {
                        Text(it.storeName, color = p.textSecondary, fontSize = 13.sp)
                    }
                }
            }

            if (shopPrices.isNotEmpty()) {
                Text("Магазины и цены", color = p.text,
                    fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Column(
                    Modifier.fillMaxWidth().background(p.card, RoundedCornerShape(20.dp))
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    shopPrices.forEach { shop ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(shop.storeName, modifier = Modifier.weight(1f),
                                color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(shop.priceKopecks?.let(::unitMoney)?.plus(
                                if (own?.isWeighted == true) "/кг" else "") ?: "—",
                                color = if (shop.priceKopecks != null) p.accent else p.textSecondary,
                                fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
            }

            val packageName = product?.pack?.takeIf { it.isNotBlank() }
            val barcodeList = if (own != null) codes.sorted()
                else product?.ean?.takeIf { it.isNotBlank() }?.let { listOf(it) }.orEmpty()
            val updated = livePrice?.fetchedAt?.takeIf { it > 0L }
                ?.let { DateFormat.getDateTimeInstance().format(Date(it)) }
            if (packageName != null || barcodeList.isNotEmpty() || updated != null) {
                Text("Информация", color = p.text,
                    fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Column(
                    Modifier.fillMaxWidth().background(p.card, RoundedCornerShape(20.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    packageName?.let {
                        Text("Упаковка", color = p.textSecondary, fontSize = 12.sp)
                        Text(it, color = p.text, fontSize = 14.sp)
                    }
                    if (barcodeList.isNotEmpty()) {
                        Text("Штрихкод" + if (barcodeList.size > 1) "ы" else "",
                            color = p.textSecondary, fontSize = 12.sp)
                        barcodeList.forEach { code ->
                            Text(code, color = p.text, fontSize = 14.sp)
                        }
                    }
                    updated?.let {
                        Text("Цена обновлена $it", color = p.textSecondary, fontSize = 12.sp)
                    }
                }
            }
            if (product != null) {
                SheetActionButton(onClick = onRefresh, enabled = refresh != "Загрузка") {
                    Text(if (refresh == "Загрузка") "Обновление…" else "Обновить цену")
                }
            }
        }
    }
}
