package com.mok.buylist.ui

import com.mok.buylist.data.ShoppingStore
import com.mok.buylist.data.StoreProduct

/** One price per store, with explicit catalog prices preferred over receipts. */
internal data class ProductShopPrice(
    val storeId: String,
    val storeName: String,
    val priceKopecks: Long?,
)

/**
 * Use the same projection in the shopping list and product database.
 *
 * A product belongs to a store either via store_products or a confirmed
 * historical purchase. Missing prices are never treated as zero.
 * Barcode-linked catalog identities resolve to the same product.
 */
internal fun productShopPrices(
    productKey: String,
    stores: List<ShoppingStore>,
    links: List<StoreProduct>,
    purchases: List<ExpensePurchase>,
    aliases: Map<String, String> = emptyMap(),
): List<ProductShopPrice> {
    val canonical = aliases[productKey] ?: productKey
    fun isSame(key: String): Boolean = (aliases[key] ?: key) == canonical

    val linked = links.filter { !it.deleted && isSame(it.productKey) }
        .groupBy { it.storeId }
        .mapValues { (_, rows) ->
            rows.maxWith(compareBy<StoreProduct> { it.updatedAt }
                .thenBy { it.productKey })
        }
    val paid = purchases.filter { it.storeId.isNotBlank() && isSame(it.productKey) }
        .groupBy { it.storeId }
        .mapValues { (_, rows) ->
            rows.filter { it.unitPrice != null }
                .maxWithOrNull(compareBy<ExpensePurchase> { it.at }.thenBy { it.id })
                ?.unitPrice
        }
    return (linked.keys + paid.keys).distinct().map { storeId ->
        val store = stores.firstOrNull { it.id == storeId }
        ProductShopPrice(storeId, store?.name ?: if (storeId ==
            com.mok.buylist.data.lentaStoreId()) "Лента" else "Магазин",
            linked[storeId]?.priceKopecks ?: paid[storeId])
    }.sortedWith(compareBy<ProductShopPrice> { it.priceKopecks == null }
        .thenBy { it.priceKopecks ?: Long.MAX_VALUE }
        .thenBy { it.storeName.lowercase() })
}

internal fun cheapestShopPrice(prices: List<ProductShopPrice>): ProductShopPrice? =
    prices.filter { it.priceKopecks != null }.minWithOrNull(
        compareBy<ProductShopPrice> { it.priceKopecks!! }.thenBy { it.storeName.lowercase() })
