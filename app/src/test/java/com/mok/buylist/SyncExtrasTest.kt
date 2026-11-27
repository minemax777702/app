package com.mok.buylist

import com.mok.buylist.data.ArchivedList
import com.mok.buylist.sync.BackupCodec
import com.mok.buylist.sync.BackupContents
import com.mok.buylist.sync.Snapshot
import com.mok.buylist.sync.SyncArchive
import com.mok.buylist.sync.SyncFormatException
import com.mok.buylist.sync.SyncItem
import com.mok.buylist.sync.SyncMerge
import com.mok.buylist.sync.SyncReceiver
import com.mok.buylist.sync.SyncSender
import com.mok.buylist.ui.ListBuyers
import com.mok.buylist.ui.listBuyers
import java.time.Instant
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SyncExtrasTest {
    private val random = Random(11)

    private fun bigArchive(count: Int) = (0 until count).map { i ->
        val names = (0 until 20).map { "Продукт ${random.nextInt(400)}" }
        SyncArchive(
            uid = random.nextBytes(16).joinToString("") { "%02x".format(it) },
            completedAt = 1_700_000_000_000L + i,
            names = names,
            buyers = names.map { "u1" },
            tripMillis = 0,
            visits = 0,
        )
    }

    // --- Список первым ---

    @Test
    fun listArrivesLongBeforeTheArchive() {
        val snapshot = Snapshot(
            items = listOf(SyncItem("Молоко", 1, 2, false, "u1")),
            archives = bigArchive(80),
        )
        val sender = SyncSender(snapshot)
        val receiver = SyncReceiver()
        var frames = 0
        while (receiver.list == null && frames < 200) {
            frames++
            receiver.onScanned(sender.nextFrame())
        }
        assertEquals(listOf("Молоко"), receiver.list!!.items.map { it.name })
        // Архив ещё не дочитан, а список уже получен — через пару секунд показа, а не через весь круг.
        assertNull(receiver.archive)
        assertTrue("список пришёл слишком поздно: $frames кадров", frames <= 40)
        assertTrue(sender.frameCount > frames)
    }

    @Test
    fun listIsRepeatedBetweenArchiveBlocksSoLateStartStillGetsItQuickly() {
        val snapshot = Snapshot(items = listOf(SyncItem("Хлеб", 1, 2, false, "u1")), archives = bigArchive(80))
        val sender = SyncSender(snapshot)
        repeat(70) { sender.nextFrame() } // получатель включил камеру в середине круга
        val receiver = SyncReceiver()
        var frames = 0
        while (receiver.list == null && frames < 200) {
            frames++
            receiver.onScanned(sender.nextFrame())
        }
        assertTrue("список пришёл слишком поздно: $frames кадров", frames <= 40)
    }

    // --- Резервная копия ---

    @Test
    fun backupRoundTripsOwnerAndData() {
        val snapshot = Snapshot(
            items = listOf(SyncItem("Сыр", 1, 2, true, "u1")),
            archives = bigArchive(3),
        )
        val back = BackupCodec.decode(BackupCodec.encode(BackupContents("id-1", "Макс", snapshot)))
        assertEquals("id-1", back.ownerId)
        assertEquals("Макс", back.ownerName)
        assertEquals(snapshot, back.snapshot)
    }

    @Test
    fun foreignFileIsNotABackup() {
        try {
            BackupCodec.decode("не копия, просто текст".toByteArray())
            fail("чужой файл принят за копию")
        } catch (expected: SyncFormatException) {
            // нужный исход
        }
        try {
            BackupCodec.decode(ByteArray(0))
            fail("пустой файл принят за копию")
        } catch (expected: SyncFormatException) {
            // нужный исход
        }
    }

    // --- Кто купил ---

    private fun list(id: Long, iso: String, names: List<String>, buyers: List<String>) = ArchivedList(
        id = id,
        completedAt = Instant.parse(iso).toEpochMilli(),
        itemCount = names.size,
        items = names.joinToString("\n"),
        buyers = buyers.joinToString("\n"),
    )

    @Test
    fun allBoughtByOnePersonMarksTheListNotTheItems() {
        val one = list(1, "2026-10-09T10:00:00Z", listOf("Молоко", "Хлеб"), listOf("ann", "ann"))
        assertEquals(ListBuyers.One("ann"), listBuyers(one))
        val mixed = list(2, "2026-10-09T11:00:00Z", listOf("Молоко", "Хлеб"), listOf("ann", "bob"))
        assertEquals(ListBuyers.Mixed(listOf("ann", "bob")), listBuyers(mixed))
        // Часть покупателей неизвестна — это уже не «всё купил один».
        val partial = list(3, "2026-10-09T12:00:00Z", listOf("Молоко", "Хлеб"), listOf("ann", ""))
        assertEquals(ListBuyers.Mixed(listOf("ann", "")), listBuyers(partial))
        val unknown = list(4, "2026-10-09T13:00:00Z", listOf("Молоко"), listOf(""))
        assertEquals(ListBuyers.Unknown, listBuyers(unknown))
    }

    // --- Независимые покупки разных пользователей не схлопываются ---

    private fun archive(uid: String, iso: String, names: List<String>, buyers: List<String>) = SyncArchive(
        uid = uid,
        completedAt = Instant.parse(iso).toEpochMilli(),
        names = names,
        buyers = buyers,
        tripMillis = 0,
        visits = 0,
    )

    @Test
    fun sameProductByDifferentPeopleOnTheSameDayIsPreserved() {
        val a = archive("a", "2026-10-09T10:00:00Z", listOf("Молоко", "Хлеб"), listOf("ann", "ann"))
        val b = archive("b", "2026-10-09T18:30:00Z", listOf("молоко", "Сыр"), listOf("bob", "bob"))
        val merged = SyncMerge.merge(Snapshot(archives = listOf(a)), Snapshot(archives = listOf(b)))
        val onA = merged.archives.first { it.uid == "a" }
        val onB = merged.archives.first { it.uid == "b" }
        assertEquals(listOf("Молоко", "Хлеб"), onA.names)
        assertEquals(listOf("молоко", "Сыр"), onB.names)
        assertEquals(listOf("bob", "bob"), onB.buyers)
    }

    @Test
    fun independentPurchasesRemainAndResultDoesNotDependOnOrder() {
        val a = archive("a", "2026-10-09T10:00:00Z", listOf("Молоко"), listOf("ann"))
        val b = archive("b", "2026-10-09T18:00:00Z", listOf("Молоко"), listOf("bob"))
        val ab = SyncMerge.merge(Snapshot(archives = listOf(a)), Snapshot(archives = listOf(b)))
        val ba = SyncMerge.merge(Snapshot(archives = listOf(b)), Snapshot(archives = listOf(a)))
        assertEquals(listOf("a", "b"), ab.archives.map { it.uid })
        assertEquals(ab, ba)
        // Повторный обмен с исходным списком второго телефона ничего не возвращает.
        assertEquals(ab, SyncMerge.merge(ab, Snapshot(archives = listOf(b))))
    }

    @Test
    fun otherDaySamePersonOrUnknownBuyerIsKept() {
        val monday = archive("m", "2026-10-05T10:00:00Z", listOf("Молоко"), listOf("ann"))
        val tuesday = archive("t", "2026-10-06T10:00:00Z", listOf("Молоко"), listOf("bob"))
        assertEquals(2, SyncMerge.merge(Snapshot(archives = listOf(monday, tuesday)), Snapshot()).archives.size)

        val ann1 = archive("a1", "2026-10-09T10:00:00Z", listOf("Молоко"), listOf("ann"))
        val ann2 = archive("a2", "2026-10-09T18:30:00Z", listOf("Молоко"), listOf("ann"))
        assertEquals(2, SyncMerge.merge(Snapshot(archives = listOf(ann1, ann2)), Snapshot()).archives.size)

        val unknown = archive("u", "2026-10-09T10:00:00Z", listOf("Молоко"), listOf(""))
        val known = archive("k", "2026-10-09T11:00:00Z", listOf("Молоко"), listOf("bob"))
        assertEquals(2, SyncMerge.merge(Snapshot(archives = listOf(unknown, known)), Snapshot()).archives.size)
    }
}
