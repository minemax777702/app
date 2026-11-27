package com.mok.buylist.ui

import com.mok.buylist.data.StoreProduct
import com.mok.buylist.data.lentaStoreId

/** Current assortment, independent of past receipts. Lenta's own catalog is
 * implicitly attached; all other stores require a live explicit link.
 * Return existing product keys, deduplicated by confirmed barcode identity.
 */
internal fun currentStoreProductKeys(
    storeId: String,
    links: List<StoreProduct>,
    activeKeys: Set<String>,
    lentaKeys: Set<String>,
    aliases: Map<String, String>,
): List<String> {
    val implicit = if (storeId == lentaStoreId()) lentaKeys.filter { it in activeKeys }.sorted() else emptyList()
    val explicit = links.asSequence()
        .filter { it.storeId == storeId && !it.deleted && it.productKey in activeKeys }
        .sortedWith(compareByDescending<StoreProduct> { it.updatedAt }.thenBy { it.productKey })
        .map { it.productKey }.toList()
    return (implicit + explicit).distinctBy { aliases[it] ?: it }
}

internal fun productCountLabel(count: Int): String {
    val noun = when {
        count % 100 in 11..14 -> "товаров"
        count % 10 == 1 -> "товар"
        count % 10 in 2..4 -> "товара"
        else -> "товаров"
    }
    return "$count $noun"
}
