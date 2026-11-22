package com.mok.buylist.data

/**
 * Last known *unit* price of a product in one store.
 *
 * Only confirmed purchases with a known price are considered.
 * Cross-catalog barcode aliases are resolved before comparing products.
 * The returned price is per item or per kilogram, never the receipt total.
 */
internal fun lastStorePurchasePrice(
    events: List<PurchaseEvent>,
    productKey: String,
    storeId: String,
    aliases: Map<String, String> = emptyMap(),
    excludedEventId: String? = null,
): Long? {
    if (storeId.isBlank()) return null
    val target = aliases[productKey] ?: productKey
    return events.asSequence()
        .filter { it.storeId == storeId && it.id != excludedEventId && it.unitPrice != null }
        .filter { event ->
            val key = when {
                event.customProductId.isNotBlank() -> customKey(event.customProductId)
                event.productId != null -> "lenta:${event.productId}"
                else -> return@filter false
            }
            (aliases[key] ?: key) == target
        }
        .maxWithOrNull(compareBy<PurchaseEvent> { it.boughtAt }
            .thenBy { it.revisedAt }.thenBy { it.id })
        ?.unitPrice
}
