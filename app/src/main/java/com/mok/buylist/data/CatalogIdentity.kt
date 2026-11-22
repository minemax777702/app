package com.mok.buylist.data

/**
 * A stable, reversible identity projection across independently synced catalogs.
 *
 * This does not rewrite either catalog's external IDs or historic purchases.
 * Joining is derived from the live confirmed GS1 codes. Removing an alias
 * immediately separates the cards on both devices after sync.
 *
 * Never join two distinct Lenta products, including through a chain of
 * multiple barcodes on a custom product: that is an ambiguous collision.
 */
internal fun sharedCatalogIdentities(
    lenta: List<LentaProduct>,
    custom: List<CustomProduct>,
    lentaBarcodes: List<StoredProductBarcode>,
    customBarcodes: List<CustomProductBarcode>,
): Map<String, String> {
    val nodes = (lenta.map { "lenta:${it.id}" } +
        custom.filterNot { it.deleted }.map { "custom:${it.id}" }).distinct().sorted()
    val parent = nodes.associateWith { it }.toMutableMap()
    val lentaCount = nodes.associateWith { if (it.startsWith("lenta:")) 1 else 0 }.toMutableMap()
    fun root(key: String): String {
        var current = key
        while (parent[current] != current) current = parent.getValue(current)
        return current
    }
    fun join(first: String, second: String) {
        val a = root(first)
        val b = root(second)
        if (a == b || lentaCount.getValue(a) + lentaCount.getValue(b) > 1) return
        val canonical = minOf(a, b)
        val other = maxOf(a, b)
        parent[other] = canonical
        lentaCount[canonical] = lentaCount.getValue(a) + lentaCount.getValue(b)
    }
    val associations = sortedMapOf<String, MutableSet<String>>()
    fun assign(code: String, node: String) {
        if (node in parent && validProductBarcode(code)) {
            associations.getOrPut(code) { sortedSetOf() }.add(node)
        }
    }
    val deletedLentaCodes = lentaBarcodes.filter { it.deleted }.mapTo(HashSet()) { it.productId to it.code }
    lenta.forEach { product ->
        if ((product.id to product.ean) !in deletedLentaCodes) assign(product.ean, "lenta:${product.id}")
    }
    lentaBarcodes.filterNot { it.deleted }.forEach { assign(it.code, "lenta:${it.productId}") }

    val deletedOwnCodes = customBarcodes.filter { it.deleted }.mapTo(HashSet()) { it.productId to it.code }
    custom.filterNot { it.deleted }.forEach { product ->
        if ((product.id to product.barcode) !in deletedOwnCodes)
            assign(product.barcode, "custom:${product.id}")
    }
    customBarcodes.filterNot { it.deleted }.forEach { assign(it.code, "custom:${it.productId}") }

    associations.values.forEach { keys ->
        // A code used by multiple distinct external catalog entries cannot
        // identify a single physical product reliably.
        if (keys.count { it.startsWith("lenta:") } > 1) return@forEach
        val ordered = keys.sorted()
        for (key in ordered.drop(1)) join(ordered.first(), key)
    }
    val grouped = nodes.groupBy(::root)
    return nodes.associateWith { node ->
        val related = grouped.getValue(root(node))
        related.filter { it.startsWith("lenta:") }.minOrNull()
            ?: related.minOrNull() ?: node
    }
}
