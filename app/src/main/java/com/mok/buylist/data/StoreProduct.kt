package com.mok.buylist.data

import androidx.room.Entity
import androidx.room.Index

/**
 * A product can be offered by multiple stores, with an independent optional
 * known unit price (per piece or per kg). Actual purchases remain immutable
 * events, not overwritten by this catalog estimate.
 */
@Entity(
    tableName = "store_products",
    primaryKeys = ["storeId", "productKey"],
    indices = [Index(value = ["productKey"])],
)
data class StoreProduct(
    val storeId: String,
    val productKey: String,
    val priceKopecks: Long? = null,
    val updatedAt: Long = 0L,
    val deleted: Boolean = false,
)
