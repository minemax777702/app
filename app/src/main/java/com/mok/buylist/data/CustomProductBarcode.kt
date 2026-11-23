package com.mok.buylist.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/** Barcode aliases for user-created products, synced by (productId, code). */
@Entity(
    tableName = "custom_product_barcodes",
    primaryKeys = ["productId", "code"],
    indices = [Index(value = ["code"])],
)
data class CustomProductBarcode(
    val productId: String,
    val code: String,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)
