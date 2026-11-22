package com.mok.buylist.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ShoppingDao {
    @Query("SELECT * FROM custom_product_barcodes ORDER BY productId, code")
    fun observeCustomProductBarcodes(): Flow<List<CustomProductBarcode>>

    @Query("SELECT * FROM custom_product_barcodes ORDER BY productId, code")
    suspend fun getCustomProductBarcodes(): List<CustomProductBarcode>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCustomProductBarcode(row: CustomProductBarcode)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCustomProductBarcodes(rows: List<CustomProductBarcode>)

    @Query("DELETE FROM custom_product_barcodes")
    suspend fun clearCustomProductBarcodes()

    @Query("SELECT * FROM custom_products ORDER BY name, id")
    fun observeCustomProducts(): Flow<List<CustomProduct>>

    @Query("SELECT * FROM custom_products")
    suspend fun getCustomProducts(): List<CustomProduct>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCustomProducts(rows: List<CustomProduct>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCustomProduct(row: CustomProduct)

    @Query("DELETE FROM custom_products")
    suspend fun clearCustomProducts()

    @Query("SELECT * FROM stores ORDER BY name, id")
    fun observeStores(): Flow<List<ShoppingStore>>

    @Query("SELECT * FROM stores")
    suspend fun getStores(): List<ShoppingStore>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStores(rows: List<ShoppingStore>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStore(row: ShoppingStore)

    @Query("DELETE FROM stores")
    suspend fun clearStores()

    @Query("SELECT * FROM lenta_products ORDER BY name, id")
    fun observeLentaProducts(): Flow<List<StoredLentaProduct>>

    @Query("SELECT * FROM lenta_products")
    suspend fun getLentaProducts(): List<StoredLentaProduct>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLentaProduct(product: StoredLentaProduct)

    @Query("DELETE FROM lenta_products WHERE id = :id")
    suspend fun deleteLentaProduct(id: Long)

    @Query("DELETE FROM lenta_products")
    suspend fun clearLentaProducts()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLentaProducts(products: List<StoredLentaProduct>)

    @Query("SELECT * FROM product_barcodes ORDER BY productId, code")
    fun observeProductBarcodes(): Flow<List<StoredProductBarcode>>

    @Query("SELECT * FROM product_barcodes ORDER BY productId, code")
    suspend fun getProductBarcodes(): List<StoredProductBarcode>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertProductBarcode(row: StoredProductBarcode)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProductBarcode(row: StoredProductBarcode)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertProductBarcodes(rows: List<StoredProductBarcode>)

    @Query("DELETE FROM product_barcodes WHERE productId = :id")
    suspend fun deleteProductBarcodes(id: Long)

    @Query("DELETE FROM product_barcodes")
    suspend fun clearProductBarcodes()

    @Query("SELECT * FROM items WHERE deleted = 0 ORDER BY createdAt DESC")
    fun observeItems(): Flow<List<ShoppingItem>>

    @Query("SELECT * FROM items ORDER BY createdAt DESC")
    fun observeAllItems(): Flow<List<ShoppingItem>>

    @Query("SELECT * FROM items WHERE deleted = 0 ORDER BY createdAt DESC")
    suspend fun getItems(): List<ShoppingItem>

    @Query("SELECT * FROM items WHERE nameKey = :nameKey")
    suspend fun findByKey(nameKey: String): ShoppingItem?

    @Insert
    suspend fun insertItem(item: ShoppingItem)

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun findById(id: String): ShoppingItem?

    @Query("SELECT * FROM items")
    suspend fun getAllItems(): List<ShoppingItem>

    @Update
    suspend fun updateItem(item: ShoppingItem)

    @Query("UPDATE items SET quantity = :quantity, updatedAt = :now, authorId = :author WHERE id = :id AND deleted = 0")
    suspend fun setQuantity(id: String, quantity: Int, now: Long, author: String)

    @Query("UPDATE items SET weightGrams = :weight, quantity = 1, updatedAt = :now, authorId = :author WHERE id = :id AND deleted = 0")
    suspend fun setWeight(id: String, weight: Int?, now: Long, author: String)

    /** @return сколько строк обновлено; 0 — товар уже стёрт из базы. */
    @Query("UPDATE items SET deleted = 0, updatedAt = :now, authorId = :author, purchaseEvent = '' WHERE id = :id")
    suspend fun markRestored(id: String, now: Long, author: String): Int

    @Query("DELETE FROM items WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM items WHERE id IN (:ids)")
    suspend fun deleteItemsByIds(ids: List<String>)

    /** Данные, созданные до регистрации, принадлежат тому, кто зарегистрировался. */
    @Query("UPDATE items SET authorId = :author WHERE authorId = ''")
    suspend fun claimItems(author: String)

    /** Смена идентичности при восстановлении копии: действия прежнего id становятся действиями [new]. */
    @Query("UPDATE items SET authorId = :new WHERE authorId = :old")
    suspend fun reassignItems(old: String, new: String)

    @Query("SELECT COUNT(*) FROM items WHERE deleted = 1")
    fun observeBoughtCount(): Flow<Int>

    @Query("SELECT * FROM items WHERE deleted = 1 ORDER BY updatedAt ASC")
    fun observeBought(): Flow<List<ShoppingItem>>

    @Query("SELECT COUNT(*) FROM items WHERE deleted = 0")
    suspend fun countActive(): Int

    /** Купленные товары в порядке покупки. */
    @Query("SELECT * FROM items WHERE deleted = 1 ORDER BY updatedAt ASC")
    suspend fun getBought(): List<ShoppingItem>

    /** @return id новой записи архива. */
    @Insert
    suspend fun insertArchived(list: ArchivedList): Long

    @Query("SELECT * FROM archived_lists ORDER BY completedAt DESC")
    fun observeArchive(): Flow<List<ArchivedList>>

    @Query("SELECT * FROM archived_lists")
    suspend fun getArchives(): List<ArchivedList>

    @Query("SELECT * FROM archived_lists WHERE buyers = ''")
    suspend fun getArchivesWithoutBuyers(): List<ArchivedList>

    @Query("SELECT id FROM archived_lists WHERE uid = :uid")
    suspend fun archiveIdByUid(uid: String): Long?

    @Query("SELECT uid FROM archived_lists WHERE id = :id")
    suspend fun archiveUid(id: Long): String?

    @Update
    suspend fun updateArchived(list: ArchivedList)

    @Query("SELECT * FROM tombstones")
    suspend fun getTombstones(): List<Tombstone>

    @Query("DELETE FROM tombstones")
    suspend fun clearTombstones()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTombstone(tombstone: Tombstone)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTombstones(tombstones: List<Tombstone>)

    @Query("SELECT * FROM users")
    fun observeUsers(): Flow<List<AppUser>>

    @Query("SELECT * FROM users")
    suspend fun getUsers(): List<AppUser>

    @Query("DELETE FROM users")
    suspend fun clearUsers()

    @Query("DELETE FROM users WHERE id = :id")
    suspend fun deleteUser(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUser(user: AppUser)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUsers(users: List<AppUser>)

    @Query("DELETE FROM archived_lists WHERE id = :id")
    suspend fun deleteArchived(id: Long)

    /** Новый «поход в магазин»: купленные товары прошлого списка больше не нужны. */
    @Query("DELETE FROM items WHERE deleted = 1")
    suspend fun purgeBought()

    /** Товар снова добавлен после покупки: оживляем ту же строку, он снова наверху списка. */
    @Query("UPDATE items SET name = :name, createdAt = :now, updatedAt = :now, deleted = 0, authorId = :author, quantity = :quantity, weightGrams = NULL, purchaseEvent = '' WHERE id = :id")
    suspend fun revive(id: String, name: String, now: Long, author: String, quantity: Int)

}
