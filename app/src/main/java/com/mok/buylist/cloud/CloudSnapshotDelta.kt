package com.mok.buylist.cloud

import com.mok.buylist.sync.Snapshot

/** Build an incremental patch. Absence never means deletion; explicit tombstones/deleted rows carry that intent. */
internal object CloudSnapshotDelta {
    private fun <T, K> changed(before: List<T>, after: List<T>, key: (T) -> K): List<T> {
        val old = before.associateBy(key)
        return after.filter { old[key(it)] != it }
    }

    fun between(before: Snapshot, after: Snapshot): Snapshot {
        val lentaCodes = changed(before.productBarcodes, after.productBarcodes) { it.productId to it.code }
        val customCodes = changed(before.customProductBarcodes, after.customProductBarcodes) { it.productId to it.code }
        val changedLenta = changed(before.products, after.products) { it.id }.mapTo(HashSet()) { it.id }
        val changedCustom = changed(before.customProducts, after.customProducts) { it.id }.mapTo(HashSet()) { it.id }
        changedLenta.addAll(lentaCodes.map { it.productId })
        changedCustom.addAll(customCodes.map { it.productId })
        // Include the parent with a barcode patch, since SyncMerge filters orphan codes.
        return Snapshot(
            users = changed(before.users, after.users) { it.id },
            items = changed(before.items, after.items) { it.key },
            history = changed(before.history, after.history) { it.key },
            hidden = changed(before.hidden, after.hidden) { it.key },
            archives = changed(before.archives, after.archives) { it.uid },
            tombs = changed(before.tombs, after.tombs) { it.kind to it.key },
            products = after.products.filter { it.id in changedLenta },
            productBarcodes = lentaCodes,
            customProducts = after.customProducts.filter { it.id in changedCustom },
            stores = changed(before.stores, after.stores) { it.id },
            customProductBarcodes = customCodes,
            storeProducts = changed(before.storeProducts, after.storeProducts) { it.storeId to it.productKey },
        )
    }
}
