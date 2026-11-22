package com.mok.buylist

import com.mok.buylist.sync.Snapshot
import com.mok.buylist.sync.SyncArchive
import com.mok.buylist.sync.SyncHidden
import com.mok.buylist.sync.SyncHistory
import com.mok.buylist.sync.SyncItem
import com.mok.buylist.sync.SyncMerge
import com.mok.buylist.sync.SyncTomb
import com.mok.buylist.sync.SyncUser
import com.mok.buylist.sync.TombKind
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergeTest {
    private fun item(name: String, at: Long, bought: Boolean = false, author: String = "a", createdAt: Long = at) =
        SyncItem(name, createdAt, at, bought, author)

    private fun tomb(kind: TombKind, key: String, at: Long) = SyncTomb(kind, key, at)

    private fun archive(uid: String, names: List<String> = listOf("Молоко")) =
        SyncArchive(uid, 1_000, names, names.map { "a" }, 0, 0)

    @Test
    fun laterActionWins() {
        val a = Snapshot(items = listOf(item("Молоко", at = 10)))
        val b = Snapshot(items = listOf(item("молоко", at = 20, bought = true, author = "b")))
        val merged = SyncMerge.merge(a, b)
        assertEquals(1, merged.items.size)
        assertTrue(merged.items.single().bought)
        assertEquals("b", merged.items.single().author)
    }

    @Test
    fun absenceDoesNotDelete() {
        val a = Snapshot(items = listOf(item("Хлеб", at = 10)))
        val merged = SyncMerge.merge(a, Snapshot())
        assertEquals(listOf("Хлеб"), merged.items.map { it.name })
    }

    @Test
    fun tombstoneRemovesOlderItemButNotNewer() {
        val deleted = Snapshot(tombs = listOf(tomb(TombKind.ITEM, "хлеб", 50)))
        val old = Snapshot(items = listOf(item("Хлеб", at = 40)))
        assertTrue(SyncMerge.merge(old, deleted).items.isEmpty())
        // Товар добавили заново уже после удаления — он остаётся.
        val readded = Snapshot(items = listOf(item("Хлеб", at = 60)))
        val merged = SyncMerge.merge(readded, deleted)
        assertEquals(1, merged.items.size)
        // Надгробие, которое проиграло живой записи, забывается.
        assertTrue(merged.tombs.isEmpty())
    }

    @Test
    fun equalTimeTombstoneWins() {
        val merged = SyncMerge.merge(
            Snapshot(items = listOf(item("Хлеб", at = 50))),
            Snapshot(tombs = listOf(tomb(TombKind.ITEM, "хлеб", 50))),
        )
        assertTrue(merged.items.isEmpty())
        assertEquals(1, merged.tombs.size)
    }

    @Test
    fun historyKeepsMaxCountButNotBeforeDeletion() {
        val a = Snapshot(history = listOf(SyncHistory("Сыр", useCount = 5, lastUsedAt = 100)))
        val b = Snapshot(history = listOf(SyncHistory("сыр", useCount = 3, lastUsedAt = 200)))
        val merged = SyncMerge.merge(a, b)
        assertEquals(5, merged.history.single().useCount)
        assertEquals(200, merged.history.single().lastUsedAt)

        // Продукт удалили в момент 150, а потом снова использовали (счётчик начался с 1): старый счётчик не возвращается.
        val deleted = Snapshot(
            history = listOf(SyncHistory("Сыр", 1, 300)),
            tombs = listOf(tomb(TombKind.HISTORY, "сыр", 150)),
        )
        val afterDelete = SyncMerge.merge(Snapshot(history = listOf(SyncHistory("Сыр", 5, 100))), deleted)
        assertEquals(1, afterDelete.history.single().useCount)
    }

    @Test
    fun hiddenAndUnhideCompareByTime() {
        val hidden = Snapshot(hidden = listOf(SyncHidden("сыр", 100)))
        val unhidden = Snapshot(tombs = listOf(tomb(TombKind.HIDDEN, "сыр", 200)))
        assertTrue(SyncMerge.merge(hidden, unhidden).hidden.isEmpty())
        val hiddenAgain = Snapshot(hidden = listOf(SyncHidden("сыр", 300)))
        assertEquals(1, SyncMerge.merge(hiddenAgain, unhidden).hidden.size)
    }

    @Test
    fun deletedArchiveDoesNotComeBack() {
        val merged = SyncMerge.merge(
            Snapshot(archives = listOf(archive("u1"))),
            Snapshot(tombs = listOf(tomb(TombKind.ARCHIVE, "u1", 1))),
        )
        assertTrue(merged.archives.isEmpty())
        assertEquals(1, merged.tombs.size)
    }

    @Test
    fun peerDoesNotGetArchivesItAlreadyHas() {
        val mine = Snapshot(
            archives = listOf(
                archive("00112233445566778899aabbccddeeff"),
                archive("ffeeddccbbaa99887766554433221100"),
                archive("0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f"),
            ),
        )
        val theirs = Snapshot(
            archives = listOf(
                archive("00112233445566778899aabbccddeeff"),
                archive("ffeeddccbbaa99887766554433221100"),
                archive("0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f"),
            ),
        )
        val sent = SyncMerge.forPeer(mine, SyncMerge.helloOf(theirs))
        assertTrue(sent.archives.isEmpty())
    }

    @Test
    fun mergeIsCommutativeAssociativeAndIdempotentOnRandomStates() {
        val random = Random(42)
        val names = listOf("Молоко", "Хлеб", "Сыр", "Яйца", "Чай", "Кофе")
        fun state(): Snapshot {
            fun at() = random.nextLong(1, 100)
            return Snapshot(
                users = listOf(SyncUser("u${random.nextInt(2)}", "Имя${random.nextInt(3)}", at())),
                items = names.filter { random.nextBoolean() }.map {
                    item(it, at(), bought = random.nextBoolean(), author = "u${random.nextInt(2)}")
                },
                history = names.filter { random.nextBoolean() }.map { SyncHistory(it, random.nextInt(1, 6), at()) },
                hidden = names.filter { random.nextInt(4) == 0 }.map { SyncHidden(it.lowercase(), at()) },
                archives = (0 until random.nextInt(3)).map {
                    archive("u${random.nextInt(3)}")
                },
                // Удаление записи истории подсказок здесь не берём: счётчик слияется максимумом, и порядок слияния трёх
                // телефонов при «удалили → снова использовали» может дать разный счётчик (на двух телефонах — один и тот же).
                tombs = names.filter { random.nextInt(3) == 0 }.map {
                    val kind = TombKind.entries.filter { k -> k != TombKind.HISTORY }.random(random)
                    tomb(kind, if (random.nextBoolean()) it.lowercase() else "u${random.nextInt(3)}", at())
                },
            )
        }
        repeat(200) {
            val a = state()
            val b = state()
            val c = state()
            assertSame("commutative", SyncMerge.merge(a, b), SyncMerge.merge(b, a))
            assertSame(
                "associative",
                SyncMerge.merge(SyncMerge.merge(a, b), c),
                SyncMerge.merge(a, SyncMerge.merge(b, c)),
            )
            val ab = SyncMerge.merge(a, b)
            assertSame("idempotent", ab, SyncMerge.merge(ab, ab))
            // Повторный обмен с теми же данными ничего не меняет.
            assertSame("repeat", ab, SyncMerge.merge(ab, a))
        }
    }

    /** Сравнение по полям: при расхождении сразу видно, какая часть состояния разошлась. */
    private fun assertSame(what: String, expected: Snapshot, actual: Snapshot) {
        assertEquals("$what: users", expected.users, actual.users)
        assertEquals("$what: items", expected.items, actual.items)
        assertEquals("$what: history", expected.history, actual.history)
        assertEquals("$what: hidden", expected.hidden, actual.hidden)
        assertEquals("$what: archives", expected.archives, actual.archives)
        assertEquals("$what: tombs", expected.tombs, actual.tombs)
    }

    @Test
    fun divergentVersionsOfOneArchivePreserveBothPurchases() {
        val old = SyncArchive("shared-uid", 1000L, listOf("Молоко"), listOf("u"), 0, 1)
        val newer = SyncArchive("shared-uid", 1000L, listOf("Яйца"), listOf("u"), 0, 1)
        val merged = SyncMerge.merge(Snapshot(archives = listOf(old)), Snapshot(archives = listOf(newer)))
        assertEquals(1, merged.archives.size)
        assertEquals(setOf("Молоко", "Яйца"), merged.archives.single().names.toSet())
        assertEquals(2, merged.archives.single().names.size)
        assertEquals(merged, SyncMerge.merge(merged, Snapshot(archives = listOf(old))))
    }

    @Test
    fun sameDaySameProductDifferentBuyersKeepsFirstAndOtherProducts() {
        val day = 1_700_006_400_000L // UTC-day boundary
        val first = SyncArchive("archive-one", day + 10_000,
            listOf("Молоко", "Яйца"), listOf("person-a", "person-a"), 0, 1,
            listOf(1, 1), listOf("", ""))
        val second = SyncArchive("archive-two", day + 20_000,
            listOf("Молоко", "Раскраска"), listOf("person-b", "person-b"), 0, 1,
            listOf(1, 1), listOf("", ""))
        val merged = SyncMerge.merge(Snapshot(archives = listOf(first)), Snapshot(archives = listOf(second)))
        assertEquals(2, merged.archives.size)
        assertEquals(3, merged.archives.sumOf { it.names.size })
        assertEquals(listOf("Молоко", "Яйца"), merged.archives.first { it.uid == first.uid }.names)
        assertEquals(listOf("Раскраска"), merged.archives.first { it.uid == second.uid }.names)
        assertEquals(listOf("person-a", "person-a"), merged.archives.first { it.uid == first.uid }.buyers)
        assertEquals(merged, SyncMerge.merge(merged, Snapshot(archives = listOf(second))))
    }

    @Test
    fun twoPurchasesOfSameProductBySameBuyerArePreserved() {
        val first = SyncArchive("first", 1_000L, listOf("Молоко"), listOf("a"), 0, 1)
        val second = SyncArchive("second", 2_000L, listOf("Молоко"), listOf("a"), 0, 1)
        val merged = SyncMerge.merge(Snapshot(archives = listOf(first)), Snapshot(archives = listOf(second)))
        assertEquals(2, merged.archives.size)
        assertEquals(2, merged.archives.sumOf { it.names.size })
    }

    @Test
    fun differentUtcDaysAreNotCollapsed() {
        val first = SyncArchive("first", 1_000L, listOf("Молоко"), listOf("a"), 0, 1)
        val second = SyncArchive("second", 86_401_000L, listOf("Молоко"), listOf("b"), 0, 1)
        assertEquals(2, SyncMerge.merge(Snapshot(archives = listOf(first)),
            Snapshot(archives = listOf(second))).archives.size)
    }

    @Test
    fun sameDayUnknownBuyerDoesNotCauseDeletion() {
        val first = SyncArchive("first", 1_000L, listOf("Молоко"), listOf(""), 0, 1)
        val second = SyncArchive("second", 2_000L, listOf("Молоко"), listOf("b"), 0, 1)
        assertEquals(2, SyncMerge.merge(Snapshot(archives = listOf(first)),
            Snapshot(archives = listOf(second))).archives.size)
    }

    @Test
    fun twoPhonesConvergeAfterOneExchange() {
        // Телефон A купил молоко и удалил сыр; телефон B добавил чай и купил хлеб. После обмена списки одинаковы.
        val a = Snapshot(
            items = listOf(item("Молоко", 30, bought = true, author = "a"), item("Хлеб", 5, author = "a")),
            tombs = listOf(tomb(TombKind.ITEM, "сыр", 25)),
        )
        val b = Snapshot(
            items = listOf(item("Сыр", 4, author = "b"), item("Хлеб", 40, bought = true, author = "b"), item("Чай", 35, author = "b")),
        )
        val onA = SyncMerge.merge(a, b)
        val onB = SyncMerge.merge(b, a)
        assertEquals(onA, onB)
        assertEquals(listOf("Молоко", "Хлеб", "Чай"), onA.items.map { it.name })
        assertTrue(onA.items.first { it.name == "Хлеб" }.bought)
        assertFalse(onA.items.any { it.name == "Сыр" })
    }
    @Test
    fun unfinishedListsAreUnionedWithoutChangingAuthors() {
        val first = Snapshot(items = listOf(
            SyncItem("Молоко", 10, 20, true, "buyer-a", 1, "", 101),
            SyncItem("Раскраска", 11, 11, false, "buyer-a", 1, "", 303),
        ))
        val second = Snapshot(items = listOf(
            SyncItem("Яйца", 12, 21, true, "buyer-b", 1, "", 202),
        ))
        val merged = SyncMerge.merge(first, second)
        assertEquals(3, merged.items.size)
        assertEquals("buyer-a", merged.items.single { it.name == "Молоко" }.author)
        assertEquals("buyer-b", merged.items.single { it.name == "Яйца" }.author)
        assertFalse(merged.items.single { it.name == "Раскраска" }.bought)
        assertEquals(merged, SyncMerge.merge(merged, second))
    }

    @Test
    fun earliestArchiveDeletionWinsAndDeletedListDoesNotReturn() {
        val uid = "some-archive"
        val original = Snapshot(archives = listOf(archive(uid)))
        val deletedLater = Snapshot(tombs = listOf(tomb(TombKind.ARCHIVE, uid, 900)))
        val deletedEarlier = Snapshot(tombs = listOf(tomb(TombKind.ARCHIVE, uid, 200)))
        val merged = SyncMerge.merge(original, SyncMerge.merge(deletedLater, deletedEarlier))
        assertTrue(merged.archives.isEmpty())
        assertEquals(200, merged.tombs.single { it.kind == TombKind.ARCHIVE }.at)
        assertEquals(merged, SyncMerge.merge(merged, original))
    }

    @Test
    fun divergentArchiveRemaindersAreCombined() {
        // Неизвестные старые строки остаются отдельными, даже если совпадает uid.
        val a = SyncArchive("same", 100, listOf("Молоко"), listOf("a"), 0, 1,
            remainingEvents = "v1|first-remaining")
        val b = SyncArchive("same", 100, listOf("Яйца"), listOf("b"), 0, 1,
            remainingEvents = "v1|second-remaining")
        val merged = SyncMerge.merge(Snapshot(archives = listOf(a)), Snapshot(archives = listOf(b)))
        assertEquals(setOf("first-remaining", "second-remaining"),
            merged.archives.single().remainingEvents.removePrefix("v1|").split('\n').toSet())
    }

    @Test
    fun archivePurchaseKeepsOriginalBuyerWhenOtherCopyHasNoBuyer() {
        val known = SyncArchive("shared", 100, listOf("Молоко"), listOf("buyer-a"), 0, 1)
        val unknown = known.copy(buyers = listOf(""))
        val merged = SyncMerge.merge(Snapshot(archives = listOf(known)), Snapshot(archives = listOf(unknown)))
        assertEquals(listOf("buyer-a"), merged.archives.single().buyers)
        assertEquals(merged, SyncMerge.merge(merged, Snapshot(archives = listOf(unknown))))
    }

    @Test
    fun overlappingArchivesMergeByExactEventId() {
        val shared = "ACQwMDAwMDAwMS0xMTExLTQyMjItODMzMy1hYmNkZWZhYmNkZWYAAAAAAApFIABG0JHQsNGC0L7QvSDQodCe0JLQldCi0KHQmtCY0Jkg0KXQm9CV0JEg0J/QvtC00LzQvtGB0LrQvtCy0L3Ri9C5LCA0MDDQswAAAAAAABV7AARjYXJkAAAAAQAAAaH6w/sBACQwMDAwMDAwMi0xMTExLTQyMjItODMzMy1hYmNkZWZhYmNkZWYAE9CZ0L7RiNC60LDRgC3QvtC70LAABGxvY2MAAAGh+p4BGAA="
        val uniqueFirst = "ACQwMDAwMDAwMy0xMTExLTQyMjItODMzMy1hYmNkZWZhYmNkZWYAAAAAAAkXYgA30J3QsNCz0LPQtdGC0YHRiyBDSElLT04g0JrQu9Cw0YHRgdC40YfQtdGB0LrQuNC1LCAzMDDQswAAAAAAACzrAARjYXJkAAAAAQAAAaH6xbr+ACQwMDAwMDAwNC0xMTExLTQyMjItODMzMy1hYmNkZWZhYmNkZWYAE9CZ0L7RiNC60LDRgC3QvtC70LAABGxvY2MAAAGh+sV4OwA="
        val uniqueSecond = "ACQwMDAwMDAwNS0xMTExLTQyMjItODMzMy1hYmNkZWZhYmNkZWYAAAAAAAb0IgBJ0JrQvtC70LHQsNGB0LAg0LLQsNGA0LXQvdCw0Y8g0JvQldCd0KLQkCDQpNC40LvQtdC50L3QsNGPLCDQstC10YHQvtCy0LDRjwAAAAAAALG7AARjYXJkAAAAAQAAAaH6xcg6ACQwMDAwMDAwMi0xMTExLTQyMjItODMzMy1hYmNkZWZhYmNkZWYAE9CZ0L7RiNC60LDRgC3QvtC70LAABGxvY2MAAAGh+p0NGgA="
        val first = SyncArchive("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 1_000_000,
            listOf("Батон", "Наггетсы"), listOf("u1", "u2"), 0, 1,
            purchaseEvents = listOf(shared, uniqueFirst))
        val second = SyncArchive("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", 1_001_000,
            listOf("Батон", "Колбаса"), listOf("u1", "u1"), 0, 1,
            purchaseEvents = listOf(shared, uniqueSecond))
        val merged = SyncMerge.merge(Snapshot(archives = listOf(first)), Snapshot(archives = listOf(second)))
        assertEquals(1, merged.archives.size)
        assertEquals(3, merged.archives.single().names.size)
        assertEquals(1, merged.archives.single().purchaseEvents.count { it == shared })
        assertEquals(2, merged.archives.single().purchaseEvents.count { it != shared })
        val repeated = SyncMerge.merge(merged, Snapshot(archives = listOf(second)))
        assertEquals(merged.archives, repeated.archives)
    }

    @Test
    fun equalProductWithoutSameEventDoesNotMergeArchives() {
        val a = archive("first").copy(completedAt = 1_000)
        val b = archive("second").copy(completedAt = 1_001)
        assertEquals(2, SyncMerge.merge(Snapshot(archives = listOf(a)),
            Snapshot(archives = listOf(b))).archives.size)
    }

    @Test fun productBarcodeSetsAreMergedWithoutLosingOldCodes() {
        val product = com.mok.buylist.data.StoredLentaProduct(
            7, "Молоко", "", "", null, "", "4607078115627", "", 0, "", 1,
        )
        val old = Snapshot(products = listOf(product), productBarcodes = listOf(
            com.mok.buylist.data.StoredProductBarcode(7, "4607078115610"),
        ))
        val recent = Snapshot(products = listOf(product), productBarcodes = listOf(
            com.mok.buylist.data.StoredProductBarcode(7, "4607078115627"),
        ))
        val merged = SyncMerge.merge(old, recent)
        assertEquals(setOf("4607078115610", "4607078115627"),
            merged.productBarcodes.map { it.code }.toSet())
        assertEquals(merged, SyncMerge.merge(merged, old))
    }

    @Test fun manuallyDeletedBarcodeIsNotRestoredByOlderPhone() {
        val product = com.mok.buylist.data.StoredLentaProduct(
            7, "Молоко", "", "", null, "", "4607078115627", "", 0, "", 1,
        )
        val saved = Snapshot(products = listOf(product), productBarcodes = listOf(
            com.mok.buylist.data.StoredProductBarcode(7, "4607078115610", updatedAt = 10),
        ))
        val deleted = Snapshot(products = listOf(product), productBarcodes = listOf(
            com.mok.buylist.data.StoredProductBarcode(7, "4607078115610", updatedAt = 20, deleted = true),
        ))
        val merged = SyncMerge.merge(saved, deleted)
        assertEquals(true, merged.productBarcodes.single { it.code == "4607078115610" }.deleted)
        assertEquals(merged, SyncMerge.merge(merged, saved))
        assertEquals(merged, SyncMerge.merge(saved, merged))
    }

    @Test fun newerManualRestoreWinsOverBarcodeDeletion() {
        val product = com.mok.buylist.data.StoredLentaProduct(
            7, "Молоко", "", "", null, "", "4607078115627", "", 0, "", 1,
        )
        val deleted = Snapshot(products = listOf(product), productBarcodes = listOf(
            com.mok.buylist.data.StoredProductBarcode(7, "4607078115610", updatedAt = 20, deleted = true),
        ))
        val restored = Snapshot(products = listOf(product), productBarcodes = listOf(
            com.mok.buylist.data.StoredProductBarcode(7, "4607078115610", updatedAt = 30, deleted = false),
        ))
        assertEquals(false, SyncMerge.merge(deleted, restored).productBarcodes
            .single { it.code == "4607078115610" }.deleted)
    }

    @Test fun barcodeOfDeletedProductIsNotRestoredBySync() {
        val product = com.mok.buylist.data.StoredLentaProduct(
            7, "Молоко", "", "", null, "", "4607078115627", "", 0, "", 1,
        )
        val older = Snapshot(products = listOf(product), productBarcodes = listOf(
            com.mok.buylist.data.StoredProductBarcode(7, "4607078115610"),
        ))
        val deleted = Snapshot(tombs = listOf(tomb(TombKind.PRODUCT, "7", 10)))
        assertEquals(emptyList<com.mok.buylist.data.StoredProductBarcode>(),
            SyncMerge.merge(older, deleted).productBarcodes)
    }


}
