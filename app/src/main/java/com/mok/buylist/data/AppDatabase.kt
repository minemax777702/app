package com.mok.buylist.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ShoppingItem::class,
        ArchivedList::class,
        Tombstone::class,
        AppUser::class,
        StoredLentaProduct::class,
        StoredProductBarcode::class,
        CustomProduct::class,
        ShoppingStore::class,
        CustomProductBarcode::class,
    ],
    version = 23,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun shoppingDao(): ShoppingDao

    companion object {
        /** Добавляем поля: время изменения и отметку об удалении. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE items ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE items SET updatedAt = createdAt")
            }
        }

        /** Архив завершённых списков. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS archived_lists (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "completedAt INTEGER NOT NULL, " +
                        "itemCount INTEGER NOT NULL, " +
                        "items TEXT NOT NULL)"
                )
            }
        }

        /** Время похода в архиве. У старых записей оно неизвестно (0). */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE archived_lists ADD COLUMN tripMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE archived_lists ADD COLUMN visits INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Цены и количество купленного, введённые после похода. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS purchases (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "nameKey TEXT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "priceKopecks INTEGER NOT NULL, " +
                        "quantity REAL NOT NULL, " +
                        "unit TEXT NOT NULL, " +
                        "boughtAt INTEGER NOT NULL, " +
                        "archivedListId INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_purchases_nameKey ON purchases (nameKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_purchases_archivedListId ON purchases (archivedListId)")
            }
        }

        /** База продуктов: текущая цена продукта и дата её изменения. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS product_prices (" +
                        "nameKey TEXT NOT NULL PRIMARY KEY, " +
                        "name TEXT NOT NULL, " +
                        "priceKopecks INTEGER NOT NULL, " +
                        "unit TEXT NOT NULL, " +
                        "updatedAt INTEGER NOT NULL)"
                )
                // Уже введённые цены не пропадают: последняя цена каждого продукта становится его ценой в базе.
                // Дата изменения — день той покупки, точнее старая версия не знала.
                db.execSQL(
                    "INSERT INTO product_prices (nameKey, name, priceKopecks, unit, updatedAt) " +
                        "SELECT p.nameKey, p.name, p.priceKopecks, p.unit, p.boughtAt FROM purchases p " +
                        "WHERE p.id = (SELECT q.id FROM purchases q WHERE q.nameKey = p.nameKey " +
                        "ORDER BY q.boughtAt DESC, q.id DESC LIMIT 1)"
                )
            }
        }

        /** Продукты, удалённые пользователем из базы продуктов. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS hidden_products (nameKey TEXT NOT NULL PRIMARY KEY)")
            }
        }

        /**
         * Синхронизация по QR: автор действий, глобальный идентификатор записей архива, «надгробия» удалений
         * и список пользователей.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN authorId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE archived_lists ADD COLUMN uid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE archived_lists ADD COLUMN buyers TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE archived_lists ADD COLUMN pricesAt INTEGER NOT NULL DEFAULT 0")
                // Старые записи получают случайный uid (randomblob вычисляется для каждой строки отдельно).
                db.execSQL("UPDATE archived_lists SET uid = lower(hex(randomblob(16)))")
                db.execSQL(
                    "UPDATE archived_lists SET pricesAt = completedAt " +
                        "WHERE id IN (SELECT DISTINCT archivedListId FROM purchases)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_archived_lists_uid ON archived_lists (uid)")
                db.execSQL("ALTER TABLE hidden_products ADD COLUMN hiddenAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS tombstones (" +
                        "kind INTEGER NOT NULL, " +
                        "target TEXT NOT NULL, " +
                        "deletedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(kind, target))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS users (" +
                        "id TEXT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "updatedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(id))"
                )
            }
        }

        /** Удаляет отключённый учёт цен, сохраняя архив покупок. */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE archived_lists_new (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "completedAt INTEGER NOT NULL, itemCount INTEGER NOT NULL, items TEXT NOT NULL, " +
                        "tripMillis INTEGER NOT NULL DEFAULT 0, visits INTEGER NOT NULL DEFAULT 0, " +
                        "uid TEXT NOT NULL DEFAULT '', buyers TEXT NOT NULL DEFAULT '')"
                )
                db.execSQL(
                    "INSERT INTO archived_lists_new (id, completedAt, itemCount, items, tripMillis, visits, uid, buyers) " +
                        "SELECT id, completedAt, itemCount, items, tripMillis, visits, uid, buyers FROM archived_lists"
                )
                db.execSQL("DROP TABLE archived_lists")
                db.execSQL("ALTER TABLE archived_lists_new RENAME TO archived_lists")
                db.execSQL("CREATE UNIQUE INDEX index_archived_lists_uid ON archived_lists (uid)")
                db.execSQL("DROP TABLE IF EXISTS purchases")
                db.execSQL("DROP TABLE IF EXISTS product_prices")
                db.execSQL("DELETE FROM tombstones WHERE kind = 2")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN quantity INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE archived_lists ADD COLUMN quantities TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN purchaseEvent TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE archived_lists ADD COLUMN purchaseEvents TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS lenta_products (id INTEGER NOT NULL PRIMARY KEY, name TEXT NOT NULL, image TEXT NOT NULL, pack TEXT NOT NULL, priceKopecks INTEGER, priceType TEXT NOT NULL, ean TEXT NOT NULL, address TEXT NOT NULL, storeId INTEGER NOT NULL, channel TEXT NOT NULL, fetchedAt INTEGER NOT NULL)")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN productId INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE lenta_products ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS history")
                db.execSQL("DROP TABLE IF EXISTS hidden_products")
                db.execSQL("DELETE FROM tombstones WHERE kind IN (1, 3)")
            }
        }

        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE archived_lists ADD COLUMN remainingEvents TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN weightGrams INTEGER")
            }
        }

        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS product_barcodes (productId INTEGER NOT NULL, code TEXT NOT NULL, PRIMARY KEY(productId, code))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_product_barcodes_code ON product_barcodes (code)")
                db.execSQL("INSERT OR IGNORE INTO product_barcodes (productId, code) SELECT id, TRIM(ean) FROM lenta_products WHERE LENGTH(TRIM(ean)) IN (8, 12, 13) AND TRIM(ean) NOT GLOB '*[^0-9]*'")
            }
        }

        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE product_barcodes ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE product_barcodes ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS custom_products (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, barcode TEXT NOT NULL, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS stores (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, nameKey TEXT NOT NULL, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_stores_nameKey ON stores (nameKey)")
                db.execSQL("ALTER TABLE items ADD COLUMN customProductId TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS custom_product_barcodes (productId TEXT NOT NULL, code TEXT NOT NULL, updatedAt INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(productId, code))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_custom_product_barcodes_code ON custom_product_barcodes (code)")
                db.execSQL("INSERT OR IGNORE INTO custom_product_barcodes (productId, code, updatedAt, deleted) SELECT id, TRIM(barcode), updatedAt, 0 FROM custom_products WHERE LENGTH(TRIM(barcode)) IN (8, 12, 13) AND TRIM(barcode) NOT GLOB '*[^0-9]*' AND deleted = 0")
            }
        }

        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE custom_products ADD COLUMN estimatedPriceKopecks INTEGER")
            }
        }

        private val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE custom_products ADD COLUMN isWeighted INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "shopping.db")
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                    MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23,
                )
                .build()
    }
}
