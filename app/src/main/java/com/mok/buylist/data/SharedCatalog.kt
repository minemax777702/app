package com.mok.buylist.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** User-owned catalog entry. UUID is portable across synced devices. */
@Entity(tableName = "custom_products")
data class CustomProduct(
    @PrimaryKey val id: String,
    val name: String,
    val barcode: String = "",
    val updatedAt: Long,
    val deleted: Boolean = false,
    val estimatedPriceKopecks: Long? = null,
    @ColumnInfo(defaultValue = "0") val isWeighted: Boolean = false,
)

/** One shared store directory; blank storeId on a purchase means unspecified. */
@Entity(tableName = "stores", indices = [Index(value = ["nameKey"])])
data class ShoppingStore(
    @PrimaryKey val id: String,
    val name: String,
    val nameKey: String = nameKeyOf(name),
    val updatedAt: Long,
    val deleted: Boolean = false,
)

/** A source-specific catalog ID is kept stable, even if another source has the same barcode. */
fun customKey(id: String): String = "custom:$id"

/** Stable across devices: the same written store name resolves to one synced ID. */
fun stableStoreId(name: String): String = java.util.UUID.nameUUIDFromBytes(
    "buylist:store:${nameKeyOf(name)}".toByteArray(Charsets.UTF_8)
).toString()

/** Reserved non-editable directory entry for the Lenta catalog. */
fun lentaStoreId(): String = stableStoreId("Лента")
