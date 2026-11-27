package com.mok.buylist.cloud

import com.mok.buylist.sync.Snapshot
import com.mok.buylist.sync.TombKind

/** Conservative three-way transfer: only local, unacknowledged changes whose target has
 * not independently changed. Conflicts remain in the original database for review.
 */
internal data class CloudLatePlan(
    val before: Snapshot,
    val patch: Snapshot,
    val changes: Int,
    val conflicts: Int,
)

internal object CloudLateChanges {
    fun plan(base: Snapshot, old: Snapshot, target: Snapshot): CloudLatePlan {
        var conflicts = 0
        var changes = 0
        val delta = CloudSnapshotDelta.between(base, old)
        fun <T, K> safe(rows: List<T>, prior: List<T>, current: List<T>, key: (T) -> K,
                        blocked: (T) -> Boolean = { false }): List<T> {
            val b = prior.associateBy(key)
            val t = current.associateBy(key)
            return rows.filter { row ->
                val k = key(row)
                when {
                    t[k] == row -> false
                    blocked(row) || t[k] != b[k] -> { conflicts++; false }
                    else -> { changes++; true }
                }
            }
        }
        val tombs = target.tombs.map { it.kind to it.key }.toSet()
        val items = safe(delta.items, base.items, target.items, { it.key }) { (TombKind.ITEM to it.key) in tombs }
        val archives = safe(delta.archives, base.archives, target.archives, { it.uid }) { (TombKind.ARCHIVE to it.uid) in tombs }
        val products = safe(delta.products, base.products, target.products, { it.id }) { (TombKind.PRODUCT to it.id.toString()) in tombs }
        val custom = safe(delta.customProducts, base.customProducts, target.customProducts, { it.id })
        val stores = safe(delta.stores, base.stores, target.stores, { it.id })
        val lentaIds = (target.products + products).mapTo(HashSet()) { it.id }
        val customIds = (target.customProducts + custom).filterNot { it.deleted }.mapTo(HashSet()) { it.id }
        val storeIds = (target.stores + stores).filterNot { it.deleted }.mapTo(HashSet()) { it.id }
        val codes = safe(delta.productBarcodes, base.productBarcodes, target.productBarcodes, { it.productId to it.code }) { it.productId !in lentaIds }
        val ownCodes = safe(delta.customProductBarcodes, base.customProductBarcodes, target.customProductBarcodes, { it.productId to it.code }) { it.productId !in customIds }
        val links = safe(delta.storeProducts, base.storeProducts, target.storeProducts, { it.storeId to it.productKey }) { it.storeId !in storeIds }
        // Destructive tombstones are allowed only if the exact previous row still exists
        // unchanged in the new space. Never delete a merged/independently changed archive.
        val removals = safe(delta.tombs, base.tombs, target.tombs, { it.kind to it.key }) { tomb ->
            when (tomb.kind) {
                TombKind.ITEM -> base.items.singleOrNull { it.key == tomb.key } != target.items.singleOrNull { it.key == tomb.key }
                TombKind.ARCHIVE -> base.archives.singleOrNull { it.uid == tomb.key } != target.archives.singleOrNull { it.uid == tomb.key }
                TombKind.PRODUCT -> base.products.singleOrNull { it.id.toString() == tomb.key } != target.products.singleOrNull { it.id.toString() == tomb.key }
                TombKind.HISTORY -> base.history.singleOrNull { it.key == tomb.key } != target.history.singleOrNull { it.key == tomb.key }
                TombKind.HIDDEN -> base.hidden.singleOrNull { it.key == tomb.key } != target.hidden.singleOrNull { it.key == tomb.key }
            }
        }
        val patch = Snapshot(
            items = items, archives = archives, products = products,
            customProducts = custom, stores = stores, productBarcodes = codes,
            customProductBarcodes = ownCodes, storeProducts = links, tombs = removals,
            history = safe(delta.history, base.history, target.history, { it.key }) { (TombKind.HISTORY to it.key) in tombs },
            hidden = safe(delta.hidden, base.hidden, target.hidden, { it.key }) { (TombKind.HIDDEN to it.key) in tombs },
        )
        return CloudLatePlan(target, patch, changes, conflicts)
    }
}
