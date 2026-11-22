package com.mok.buylist

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mok.buylist.data.AppDatabase
import com.mok.buylist.data.ShoppingRepository
import com.mok.buylist.data.UserProfile
import com.mok.buylist.sync.Snapshot
import com.mok.buylist.sync.SyncMerge
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Два «телефона» — два репозитория на двух отдельных базах Room (в памяти). Обмен идёт теми же снимками,
 * что передаются по QR: проверяем запись в БД, надгробия и сходимость списков, а не только чистое слияние.
 */
@RunWith(AndroidJUnit4::class)
class SyncRepositoryTest {
    private lateinit var context: Context
    private lateinit var dbA: AppDatabase
    private lateinit var dbB: AppDatabase
    private lateinit var profileA: UserProfile
    private lateinit var profileB: UserProfile
    private lateinit var a: ShoppingRepository
    private lateinit var b: ShoppingRepository

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("test-profile-a", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("test-profile-b", Context.MODE_PRIVATE).edit().clear().commit()
        dbA = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dbB = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        profileA = UserProfile(context, "test-profile-a")
        profileB = UserProfile(context, "test-profile-b")
        a = ShoppingRepository(context, dbA, profileA)
        b = ShoppingRepository(context, dbB, profileB)
        runBlocking {
            a.saveProfile("Аня")
            b.saveProfile("Борис")
        }
    }

    @After
    fun tearDown() {
        dbA.close()
        dbB.close()
        context.getSharedPreferences("test-profile-a", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("test-profile-b", Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** Один обмен: каждый телефон применяет то, что вторая сторона показала до обмена. */
    private suspend fun sync() {
        val fromA = a.exportSnapshot()
        val fromB = b.exportSnapshot()
        a.applyRemote(fromB)
        b.applyRemote(fromA)
    }

    private suspend fun ShoppingRepository.addFixture(name: String) = addProduct(
        com.mok.buylist.data.LentaProduct(name.hashCode().toLong() and 0x7fffffff, name, "", "", "", "", "", "")
    )

    private fun tick() = Thread.sleep(5)

    private suspend fun state(repo: ShoppingRepository): Snapshot = SyncMerge.merge(repo.exportSnapshot(), Snapshot())

    private suspend fun itemsOf(repo: ShoppingRepository) =
        repo.exportSnapshot().items.map { it.name to it.bought }.sortedBy { it.first }

    private suspend fun itemId(repo: ShoppingRepository, name: String) = repo.getItems().first { it.name == name }.id

    @Test
    fun weightSurvivesExchangeAndArchive() = runBlocking {
        val product = com.mok.buylist.data.LentaProduct(903, "Картофель", "", "", "30", "card", "", "")
        a.addProduct(product)
        val id = a.getItems().single().id
        a.setWeight(id, 2540)
        sync()
        assertEquals(2540, b.getItems().single().weightGrams)
        a.buy(id)
        val saved = a.exportSnapshot().items.single().purchaseEvent
        assertEquals(2540, com.mok.buylist.data.decodePurchaseEvent(saved)!!.weightGrams)
        a.finishTrip()
        assertEquals(2540, com.mok.buylist.data.decodePurchaseEvent(a.exportSnapshot().archives.single().purchaseEvents.single())!!.weightGrams)
    }

    @Test
    fun scannedPurchaseIsImpulsiveOnlyWhenProductIsNotInList() = runBlocking {
        val planned = com.mok.buylist.data.LentaProduct(901, "Хлеб", "", "", "", "", "", "")
        val extra = planned.copy(id = 902, name = "Сыр")
        a.addProduct(planned)
        val first = a.buyScanned(planned)!!
        val second = a.buyScanned(extra)!!
        assertTrue(!first.impulsive)
        assertTrue(second.impulsive)
        val purchases = a.exportSnapshot().items.associateBy { it.productId }
        assertTrue(!com.mok.buylist.data.decodePurchaseEvent(purchases.getValue(901).purchaseEvent)!!.impulsive)
        assertTrue(com.mok.buylist.data.decodePurchaseEvent(purchases.getValue(902).purchaseEvent)!!.impulsive)
        a.restore(first.item)
        a.restore(second.item)
        assertEquals(listOf(901L), a.getItems().map { it.productId })
        assertTrue(a.exportSnapshot().items.none { it.productId == 902L })
    }

    @Test
    fun sameNameWithDifferentIdsSurvivesExchange() = runBlocking {
        val first = com.mok.buylist.data.LentaProduct(101, "Молоко", "", "", "", "", "", "")
        val second = first.copy(id = 102)
        assertTrue(a.addProduct(first))
        assertTrue(a.addProduct(second))
        assertTrue(!a.addProduct(first))
        sync()
        assertEquals(setOf(101L, 102L), b.exportSnapshot().items.map { it.productId }.toSet())
    }

    @Test
    fun purchaseEventSurvivesExchangeArchiveAndUndoClearsIt() = runBlocking {
        a.addFixture("Тестовый товар"); a.addFixture("Другая позиция")
        val original = a.getItems().first { it.name == "Тестовый товар" }
        a.buy(original.id)
        val firstEvent = a.exportSnapshot().items.first { it.name == original.name }.purchaseEvent
        assertTrue(firstEvent.isNotBlank())
        sync()
        assertEquals(firstEvent, b.exportSnapshot().items.first { it.name == original.name }.purchaseEvent)
        a.restore(original)
        assertEquals("", a.exportSnapshot().items.first { it.name == original.name }.purchaseEvent)
        a.buy(original.id)
        val secondEvent = a.exportSnapshot().items.first { it.name == original.name }.purchaseEvent
        assertTrue(secondEvent.isNotBlank() && secondEvent != firstEvent)
        a.finishTrip()
        assertEquals(secondEvent, a.exportSnapshot().archives.single().purchaseEvents.single())
        sync()
        assertEquals(secondEvent, b.exportSnapshot().archives.single().purchaseEvents.single())
    }

    @Test
    fun finishPartialTripArchivesBoughtAndKeepsRemainingAcrossSync() = runBlocking {
        a.addFixture("Молоко"); tick()
        a.addFixture("Хлеб"); tick()
        sync()
        a.buy(itemId(a, "Молоко")); tick()
        a.finishTrip()
        assertEquals(listOf("Хлеб" to false), itemsOf(a))
        assertEquals(listOf("Молоко"), a.observeArchive().first().single().names)
        val remainingSnapshot = a.observeArchive().first().single().remainingEvents
        assertTrue(remainingSnapshot.startsWith("v1|") && remainingSnapshot.length > 3)
        sync()
        assertEquals(listOf("Хлеб" to false), itemsOf(b))
        assertEquals(listOf("Молоко"), b.observeArchive().first().single().names)
        assertEquals(remainingSnapshot, b.observeArchive().first().single().remainingEvents)
        a.finishTrip()
        assertEquals(1, a.observeArchive().first().size)
        a.buy(itemId(a, "Хлеб"))
        a.finishTripIfDone()
        assertEquals(2, a.observeArchive().first().size)
    }

    @Test
    fun listAndPurchasesReachTheOtherPhone() = runBlocking {
        a.addFixture("Молоко"); tick()
        a.addFixture("Хлеб"); tick()
        a.addFixture("Яйца"); tick()
        a.buy(itemId(a, "Яйца"))
        sync()
        assertEquals(listOf("Молоко" to false, "Хлеб" to false, "Яйца" to true), itemsOf(b))
        assertEquals(itemsOf(a), itemsOf(b))
        // Кто что купил: автор последнего действия виден на втором телефоне.
        val eggs = b.exportSnapshot().items.first { it.name == "Яйца" }
        assertEquals(profileA.id, eggs.author)
    }

    @Test
    fun removedItemDoesNotComeBackAndBothFinishSameTripOnce() = runBlocking {
        a.addFixture("Молоко"); tick()
        a.addFixture("Хлеб"); tick()
        a.addFixture("Сыр"); tick()
        sync()
        // Без связи: Борис убирает сыр и покупает хлеб, Аня покупает молоко.
        b.remove(itemId(b, "Сыр")); tick()
        b.buy(itemId(b, "Хлеб")); tick()
        a.buy(itemId(a, "Молоко")); tick()
        sync()
        // Сыр удалён у обоих (иначе он воскрес бы), всё остальное куплено — список ушёл в архив один раз.
        assertTrue(a.getItems().isEmpty())
        assertTrue(b.getItems().isEmpty())
        assertEquals(1, a.observeArchive().first().size)
        assertEquals(1, b.observeArchive().first().size)
        sync()
        sync()
        assertEquals(1, a.observeArchive().first().size)
        assertEquals(1, b.observeArchive().first().size)
        val archive = a.observeArchive().first().single()
        assertEquals(listOf("Хлеб", "Молоко").sorted(), archive.names.sorted())
        // Покупатели в архиве: кто что купил.
        val buyers = archive.names.zip(archive.buyerIds).toMap()
        assertEquals(profileB.id, buyers["Хлеб"])
        assertEquals(profileA.id, buyers["Молоко"])
        assertEquals(state(a), state(b))
    }

    @Test
    fun archiveDeletionPropagates() = runBlocking {
        a.addFixture("Молоко"); tick()
        a.buy(itemId(a, "Молоко")); tick()
        a.finishTripIfDone()
        sync()
        val onA = a.observeArchive().first().single()
        val onB = b.observeArchive().first().single()
        assertEquals(onA.uid, onB.uid)

        tick()
        b.deleteArchived(b.observeArchive().first().single().id)
        sync()
        assertTrue(a.observeArchive().first().isEmpty())
        assertEquals(state(a), state(b))
    }

    @Test
    fun dataCreatedBeforeRegistrationBelongsToTheNewUser() = runBlocking {
        context.getSharedPreferences("test-profile-c", Context.MODE_PRIVATE).edit().clear().commit()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val profile = UserProfile(context, "test-profile-c")
            val repo = ShoppingRepository(context, db, profile)
            repo.addFixture("Чай")
            assertEquals("", repo.exportSnapshot().items.single().author)
            repo.saveProfile("Вика")
            assertEquals(profile.id, repo.exportSnapshot().items.single().author)
        } finally {
            db.close()
            context.getSharedPreferences("test-profile-c", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
