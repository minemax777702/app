package com.mok.buylist.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mok.buylist.R
import com.mok.buylist.data.CustomProduct
import com.mok.buylist.data.LentaProduct
import com.mok.buylist.data.parsePriceKopecks
import com.mok.buylist.ui.theme.LocalPalette
import java.math.BigDecimal
import java.math.RoundingMode

internal enum class ProductSort(val label: String) {
    DEFAULT("По умолчанию"),
    PRICE_ASC("Сначала дешевле"),
    PRICE_DESC("Сначала дороже"),
    UNIT_ASC("Выгоднее за кг / л"),
    UNIT_DESC("Дороже за кг / л"),
}

internal enum class MeasureUnit(val label: String) { MASS("кг"), VOLUME("л") }
internal data class PackMeasure(val amount: BigDecimal, val unit: MeasureUnit)
internal data class NormalizedPrice(val kopecks: BigDecimal, val unit: MeasureUnit) {
    fun label(): String = kopecks.movePointLeft(2).setScale(2, RoundingMode.HALF_UP)
        .toPlainString().replace('.', ',') + " ₽/" + unit.label
}

private val measurePattern = Regex(
    """(?<![\p{L}\p{N}.,])(\d+(?:[.,]\d+)?)\s*(кг|мл|kg|ml|гр\.?|г|g|л|l)(?![\p{L}\p{N}])""",
    RegexOption.IGNORE_CASE,
)
private val beforeMultiplier = Regex("""(?<![\d.,])(\d+)\s*[xх×*]\s*$""", RegexOption.IGNORE_CASE)
private val afterMultiplier = Regex("""^\s*[xх×*]\s*(\d+)(?![\d.,\p{L}])""", RegexOption.IGNORE_CASE)

internal fun parsePackMeasure(text: String): PackMeasure? {
    // Normalize compact multipacks, e.g. 3x200мл and 200мл×3.
    val normalizedText = text.replace(Regex("""(?<=\d)[xх×*](?=\d)""", RegexOption.IGNORE_CASE), " × ")
    val matches = measurePattern.findAll(normalizedText).toList()
    // Different amounts/dimensions in one name are ambiguous (e.g. 300 г + 50 г).
    if (matches.size != 1) return null
    val match = matches.single()
    val amount = match.groupValues[1].replace(',', '.').toBigDecimalOrNull() ?: return null
    val unit = match.groupValues[2].lowercase().trimEnd('.')
    val dimension = if (unit in setOf("мл", "ml", "л", "l")) MeasureUnit.VOLUME else MeasureUnit.MASS
    val scale = if (unit in setOf("кг", "kg", "л", "l")) BigDecimal.ONE else BigDecimal("0.001")
    val before = beforeMultiplier.find(normalizedText.substring(0, match.range.first))?.groupValues?.get(1)
    val after = afterMultiplier.find(normalizedText.substring(match.range.last + 1))?.groupValues?.get(1)
    if (before != null && after != null) return null
    val multiplier = (before ?: after ?: "1").toBigDecimalOrNull() ?: return null
    val normalized = amount.multiply(scale).multiply(multiplier)
    if (normalized.signum() <= 0 || normalized > BigDecimal("10000")) return null
    return PackMeasure(normalized, dimension)
}

internal fun productMeasure(pack: String, name: String): PackMeasure? =
    parsePackMeasure(pack) ?: parsePackMeasure(name)

internal fun unitPrice(price: Long?, measure: PackMeasure?): NormalizedPrice? {
    if (price == null || price < 0 || measure == null || measure.amount.signum() <= 0) return null
    return NormalizedPrice(BigDecimal.valueOf(price).divide(measure.amount, 12, RoundingMode.HALF_UP), measure.unit)
}

internal fun searchCatalogPrice(product: LentaProduct, address: String): Long? =
    product.takeIf { address.isNotBlank() && it.address == address && it.channel == "locc" }
        ?.price?.let(::parsePriceKopecks)

internal data class ProductSearchRow(
    val key: String,
    val price: Long?,
    val normalized: NormalizedPrice?,
    val custom: CustomProduct? = null,
    val lenta: LentaProduct? = null,
) {
    companion object {
        fun custom(product: CustomProduct, price: Long? = product.estimatedPriceKopecks): ProductSearchRow {
            val measure = if (product.isWeighted) PackMeasure(BigDecimal.ONE, MeasureUnit.MASS)
                else parsePackMeasure(product.name)
            return ProductSearchRow("custom:" + product.id, price, unitPrice(price, measure), custom = product)
        }

        fun lenta(product: LentaProduct, price: Long?): ProductSearchRow =
            ProductSearchRow("lenta:" + product.id, price, unitPrice(price, productMeasure(product.pack, product.name)), lenta = product)
    }
}

/** Unit-price ranking uses the user's approximation 1 kg ≈ 1 l.
 * Actual unit labels stay unchanged; unknown prices are always last. */
internal fun sortProductRows(rows: List<ProductSearchRow>, sort: ProductSort): List<ProductSearchRow> =
    when (sort) {
        ProductSort.DEFAULT -> rows
        ProductSort.PRICE_ASC, ProductSort.PRICE_DESC -> rows.sortedWith(
            compareBy<ProductSearchRow> { it.price == null }.thenComparator { a, b ->
                if (sort == ProductSort.PRICE_ASC) compareValues(a.price, b.price)
                else compareValues(b.price, a.price)
            },
        )
        ProductSort.UNIT_ASC, ProductSort.UNIT_DESC -> rows.sortedWith(
            compareBy<ProductSearchRow> { it.normalized == null }
                .thenComparator { a, b ->
                    if (sort == ProductSort.UNIT_ASC) compareValues(a.normalized?.kopecks, b.normalized?.kopecks)
                    else compareValues(b.normalized?.kopecks, a.normalized?.kopecks)
                },
        )
    }

@Composable
internal fun ProductSortMenu(sort: ProductSort, onSort: (ProductSort) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(onClick = { expanded = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Сортировка: " + sort.label, color = p.text, fontSize = 12.sp)
                Icon(painterResource(R.drawable.ic_chevron_down), "Выбрать сортировку",
                    Modifier.size(18.dp), tint = p.textSecondary)
            }
        }
        AppDropdownMenu(expanded, { expanded = false }) {
            ProductSort.entries.forEach { option ->
                AppDropdownMenuItem(text = {
                    Column {
                        Text(option.label, color = p.text)
                        if (option == ProductSort.UNIT_ASC || option == ProductSort.UNIT_DESC) {
                            Text("Сравнение: 1 л ≈ 1 кг", color = p.textSecondary, fontSize = 11.sp)
                        }
                    }
                },
                    onClick = { expanded = false; onSort(option) })
            }
        }
    }
}
