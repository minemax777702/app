package com.mok.buylist

import com.mok.buylist.data.AppUser
import com.mok.buylist.data.ArchivedList
import com.mok.buylist.ui.buyersIn
import com.mok.buylist.ui.filterByUser
import org.junit.Assert.assertEquals
import org.junit.Test

class UserStatsTest {
    private fun list(id: Long, names: List<String>, buyers: List<String>) = ArchivedList(
        id = id,
        completedAt = id * 1000,
        itemCount = names.size,
        items = names.joinToString("\n"),
        buyers = buyers.joinToString("\n"),
    )

    private val archive = listOf(
        list(1, listOf("Молоко", "Хлеб"), listOf("max", "ann")),
        list(2, listOf("Сыр"), listOf("ann")),
        list(3, listOf("Чай", "Кофе"), listOf("max", "max")),
    )

    @Test
    fun keepsOnlyTheUsersItemsAndDropsEmptyLists() {
        val lists = filterByUser(archive, "max")
        assertEquals(listOf(1L, 3L), lists.map { it.id })
        assertEquals(listOf("Молоко"), lists[0].names)
        assertEquals(1, lists[0].itemCount)
        assertEquals(listOf("Чай", "Кофе"), lists[1].names)
    }

    @Test
    fun unknownBuyerIsNobodysAndListsOnlyBuyersWhoBought() {
        val legacy = archive + list(4, listOf("Яйца"), listOf(""))
        assertEquals(emptyList<ArchivedList>(), filterByUser(legacy, "bob"))
        val users = listOf(AppUser("max", "Макс", 0), AppUser("ann", "Аня", 0), AppUser("bob", "Боб", 0))
        assertEquals(listOf("Аня", "Макс"), buyersIn(legacy, users).map { it.name })
    }

    @Test
    fun buyersAlignWithNamesEvenWhenFieldIsShortOrEmpty() {
        assertEquals(listOf("", ""), list(5, listOf("А", "Б"), emptyList()).buyerIds)
        assertEquals(listOf("max", ""), ArchivedList(completedAt = 1, itemCount = 2, items = "А\nБ", buyers = "max").buyerIds)
    }

    @Test
    fun filteringKeepsQuantityPairedWithBuyer() {
        val original = list(1, listOf("А", "Б"), listOf("ann", "max")).copy(quantities = "2\n5")
        assertEquals(listOf(5), filterByUser(listOf(original), "max").single().counts)
    }
}
