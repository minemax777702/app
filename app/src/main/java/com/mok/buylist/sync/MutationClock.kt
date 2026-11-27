package com.mok.buylist.sync

import android.content.Context
import android.content.SharedPreferences

/** A persisted, monotonic millisecond clock shared by all local workspaces. */
internal object MutationClock {
    private var prefs: SharedPreferences? = null
    private var last = 0L

    @Synchronized fun initialize(context: Context) {
        if (prefs != null) return
        prefs = context.getSharedPreferences("mutation_clock", Context.MODE_PRIVATE)
        last = maxOf(last, prefs!!.getLong("last", 0L))
    }

    @Synchronized fun observe(at: Long) {
        if (at <= last) return
        last = at
        prefs?.let { check(it.edit().putLong("last",last).commit()) }
    }

    @Synchronized fun next(): Long {
        observe(maxOf(System.currentTimeMillis(), last + 1))
        return last
    }

    fun observe(snapshot: Snapshot) {
        val timestamps = sequenceOf(
            snapshot.items.asSequence().map { it.at },
            snapshot.users.asSequence().map { it.at },
            snapshot.tombs.asSequence().map { it.at },
            snapshot.history.asSequence().map { it.lastUsedAt },
            snapshot.hidden.asSequence().map { it.at },
            snapshot.archives.asSequence().flatMap { archive ->
                sequenceOf(archive.completedAt) + archive.purchaseEvents.asSequence().mapNotNull {
                    com.mok.buylist.data.decodePurchaseEvent(it)?.revisedAt
                }
            },
            snapshot.products.asSequence().map { it.updatedAt },
            snapshot.productBarcodes.asSequence().map { it.updatedAt },
            snapshot.customProducts.asSequence().map { it.updatedAt },
            snapshot.customProductBarcodes.asSequence().map { it.updatedAt },
            snapshot.stores.asSequence().map { it.updatedAt },
            snapshot.storeProducts.asSequence().map { it.updatedAt },
        ).flatten()
        timestamps.maxOrNull()?.let(::observe)
    }
}
