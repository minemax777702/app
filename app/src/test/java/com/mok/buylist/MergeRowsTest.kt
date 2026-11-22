package com.mok.buylist

import com.mok.buylist.data.ShoppingItem
import com.mok.buylist.ui.ListRow
import com.mok.buylist.ui.RowState
import com.mok.buylist.ui.mergeRows
import org.junit.Assert.assertEquals
import org.junit.Test

class MergeRowsTest {
    private fun item(name: String, createdAt: Long) = ShoppingItem(id = name, name = name, createdAt = createdAt)

    @Test
    fun justBoughtItemStaysInItsPlaceAndIsMarked() {
        val milk = item("Молоко", createdAt = 3)
        val bread = item("Хлеб", createdAt = 2)
        val eggs = item("Яйца", createdAt = 1)

        val rows = mergeRows(active = listOf(milk, eggs), recent = listOf(ListRow(bread, RowState.BOUGHT)))

        assertEquals(listOf("Молоко", "Хлеб", "Яйца"), rows.map { it.item.name })
        assertEquals(listOf(RowState.ACTIVE, RowState.BOUGHT, RowState.ACTIVE), rows.map { it.state })
    }

    @Test
    fun justRemovedItemStaysInItsPlaceAndIsMarked() {
        val milk = item("Молоко", createdAt = 2)
        val bread = item("Хлеб", createdAt = 1)

        val rows = mergeRows(active = listOf(bread), recent = listOf(ListRow(milk, RowState.REMOVED)))

        assertEquals(listOf("Молоко", "Хлеб"), rows.map { it.item.name })
        assertEquals(listOf(RowState.REMOVED, RowState.ACTIVE), rows.map { it.state })
    }

    @Test
    fun restoredItemIsNotDuplicated() {
        val milk = item("Молоко", createdAt = 1)

        // Покупку отменили: товар уже снова в базе, но ещё числится среди «отменяемых».
        val rows = mergeRows(active = listOf(milk), recent = listOf(ListRow(milk, RowState.BOUGHT)))

        assertEquals(1, rows.size)
        assertEquals(RowState.ACTIVE, rows.single().state)
    }

    @Test
    fun emptyInputsGiveEmptyList() {
        assertEquals(emptyList<Any>(), mergeRows(emptyList(), emptyList()))
    }
}
