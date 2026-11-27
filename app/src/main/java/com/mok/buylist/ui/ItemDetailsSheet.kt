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
import androidx.compose.runtime.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.rotate
import com.mok.buylist.R
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
    prices: List<ProductShopPrice>,
    lentaCodes: List<String>,
    onSaveManualPrice: (Long?, (String?) -> Unit) -> Unit,
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

    val shopPrices = prices
    val lowest = displayShopPrice(productKey, shopPrices)
    val displayPrice = lowest?.priceKopecks?.let(::unitMoney) ?: own?.estimatedPriceKopecks?.let(::unitMoney)
        ?: livePrice?.price?.let { com.mok.buylist.data.parsePriceKopecks(it) }?.let(::unitMoney)
    val barcodeList = if (own != null) codes.sorted() else lentaCodes.distinct().sorted()
    val lastPurchase = expensePurchases(archive, bought).filter { it.productKey == productKey }.maxOfOrNull { it.at }
    var expanded by remember(item.id) { mutableStateOf(false) }
    var editPrice by remember(item.id) { mutableStateOf(false) }
    var manualPrice by remember(item.id) { mutableStateOf("") }
    var priceError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
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
            SheetHeader("Информация о товаре")
            Column(Modifier.fillMaxWidth().background(p.card, RoundedCornerShape(20.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (own != null) CustomProductImage(own.id, Modifier.size(64.dp))
                    else LentaImage(product?.image.orEmpty(), Modifier.size(64.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.name, color = p.text, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
                        Text(displayPrice?.plus(if (own?.isWeighted == true) " /кг" else "") ?: "—",
                            color = p.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                        lowest?.let { Text(it.storeName, color = p.textSecondary, fontSize = 12.sp) }
                    }
                }
                SheetInfoLine("Упаковка", product?.pack?.takeIf { it.isNotBlank() } ?: if (own?.isWeighted == true) "Весовой товар" else "Штучный товар")
                SheetInfoLine("Последняя покупка", lastPurchase?.let { DateFormat.getDateInstance().format(Date(it)) } ?: "—")
                Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (barcodeList.size > 1) "Штрихкоды · ${barcodeList.size}" else "Штрихкод", Modifier.weight(1f), color = p.textSecondary, fontSize = 13.sp)
                    Text(barcodeList.firstOrNull() ?: "—", color = p.text, fontSize = 13.sp)
                    Icon(painterResource(R.drawable.ic_chevron_down), if (expanded) "Свернуть" else "Раскрыть",
                        Modifier.size(20.dp).rotate(if (expanded) 180f else 0f), tint = p.textSecondary)
                }
                if (expanded) {
                    barcodeList.drop(1).forEach { code -> SheetInfoLine("", code) }
                    if (own != null) CustomProductImageActions(own.id)
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

            if (product != null || shopPrices.any { it.storeId == com.mok.buylist.data.lentaStoreId() }) {
                if (editPrice) {
                    SheetTextField(manualPrice, { manualPrice = it; priceError = null }, "Цена Ленты, ₽",
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                    Text("Пустое поле возвращает автоматическую цену.", color = p.textSecondary, fontSize = 12.sp)
                    priceError?.let { Text(it, color = p.error, fontSize = 12.sp) }
                    val amount = com.mok.buylist.data.parsePriceKopecks(manualPrice)
                    SheetActionButton(enabled = !saving && (manualPrice.isBlank() || amount != null), onClick = {
                        saving = true
                        onSaveManualPrice(amount) { issue -> saving = false; priceError = issue; if (issue == null) editPrice = false }
                    }) { Text("Сохранить цену") }
                } else SheetActionButton(onClick = {
                    manualPrice = shopPrices.firstOrNull { it.storeId == com.mok.buylist.data.lentaStoreId() }?.priceKopecks
                        ?.let { BigDecimal.valueOf(it, 2).stripTrailingZeros().toPlainString() }.orEmpty()
                    editPrice = true
                }) { Text("Изменить цену Ленты") }
                if (product != null)
                    TextButton(onClick = onRefresh, enabled = refresh != "Загрузка", modifier = Modifier.fillMaxWidth()) {
                        Text(if (refresh == "Загрузка") "Обновление…" else "Обновить информацию", color = p.text)
                    }
            }
        }
    }
}

@Composable
private fun SheetInfoLine(label: String, value: String) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), color = p.textSecondary, fontSize = 13.sp)
        Text(value, Modifier.weight(1f), color = p.text, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}
