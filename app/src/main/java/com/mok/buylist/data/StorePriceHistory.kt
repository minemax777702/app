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
        .filter {
            val legacyLenta = storeId == lentaStoreId() && it.storeId.isBlank() &&
                it.productId != null && it.customProductId.isBlank()
            (it.storeId == storeId || legacyLenta) &&
                it.id != excludedEventId && it.unitPrice != null
        }
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

/**
 * Human-readable shop for archive and history.
 * Historical Lenta purchases did not have a storeId before the store feature,
 * so their Lenta catalog product ID is the safe legacy fallback.
 */
internal fun purchaseStoreName(
    event: PurchaseEvent?,
    stores: List<ShoppingStore>,
): String? {
    if (event == null) return null
    if (event.storeId.isNotBlank()) {
        if (event.storeId == lentaStoreId()) return "Лента"
        return stores.firstOrNull { it.id == event.storeId }?.name ?: "Неизвестный магазин"
    }
    return if (event.productId != null && event.customProductId.isBlank()) "Лента" else null
}
