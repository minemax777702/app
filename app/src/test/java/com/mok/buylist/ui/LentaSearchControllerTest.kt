package com.mok.buylist.ui

import com.mok.buylist.data.LentaProduct
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LentaSearchControllerTest {
    private fun product(id: Long, name: String) = LentaProduct(id, name, "", "", "1", "card", "", "address", storeId = 7)

    @Test fun fullSearchLoadsAllPagesIncludingCheaperLaterResults() = runBlocking {
        val pages = mutableListOf<Int>()
        val controller = LentaSearchController({ _, _, page, _ ->
            pages += page
            when (page) {
                1 -> listOf(product(1, "Молоко 500 мл").copy(price = "100"))
                in 2..8 -> listOf(product(page.toLong(), "Печенье с молоком"))
                9 -> listOf(product(9, "Молоко 1 л").copy(price = "110"))
                else -> emptyList()
            }
        }, this)
        controller.update("молоко", "address", emptyList(), false, fullSearch = true)
        delay(1800)
        assertEquals((1..10).toList(), pages)
        assertEquals(true, controller.state.value.complete)
        val rows = controller.state.value.online.map { ProductSearchRow.lenta(it, com.mok.buylist.data.parsePriceKopecks(it.price)) }
        assertEquals(listOf("lenta:9", "lenta:1"), sortProductRows(rows, ProductSort.UNIT_ASC).map { it.key })
    }

    @Test fun selectingFullSortContinuesExistingPagesAndStopsOnRepeatedResponses() = runBlocking {
        val pages = mutableListOf<Int>()
        val controller = LentaSearchController({ _, _, page, _ ->
            pages += page
            listOf(product(1, "Молоко"))
        }, this)
        controller.update("молоко", "address", emptyList(), false)
        controller.searchOnline()
        delay(50)
        controller.setFullSearch(true)
        delay(650)
        assertEquals((1..6).toList(), pages)
        assertEquals(true, controller.state.value.stalled)
        assertFalse(controller.state.value.complete)
        assertEquals(listOf(1L), controller.state.value.online.map { it.id })
    }

    @Test fun fullSearchUpdatesLocalPricesWithoutDuplicates() = runBlocking {
        val saved = product(1, "Молоко 1 л").copy(price = "200")
        val controller = LentaSearchController({ _, _, page, _ ->
            if (page == 1) listOf(saved.copy(price = "100")) else emptyList()
        }, this)
        controller.update("молоко", "address", listOf(saved), false, fullSearch = true)
        delay(800)
        assertEquals("100", controller.state.value.local.single().price)
        assertEquals(emptyList<LentaProduct>(), controller.state.value.online)
        assertEquals(true, controller.state.value.complete)
    }

    @Test fun irrelevantPagesAreSkippedAndLaterRelevantResultsRemain() = runBlocking {
        val pages = mutableListOf<Int>()
        val controller = LentaSearchController({ _, _, page, _ ->
            pages += page
            when (page) {
                1 -> listOf(product(1, "Печенье со сгущенным молоком"), product(4, "Конфеты Птичье молоко"), product(5, "Вафли Топленое молоко"),
                    product(6, "Аромадиффузор, клубничное молоко"),
                    product(7, "Вспениватель молока"), product(8, "Сироп Топленое молоко"),
                    product(9, "Вино Клубничное молоко"))
                2 -> listOf(product(2, "Молоко 1 л"), product(3, "Шоколад с молоком"))
                else -> emptyList()
            }
        }, this)
        controller.update("Молоко", "address", emptyList(), false)
        controller.searchOnline()
        delay(50)
        assertEquals(listOf(1, 2), pages)
        assertEquals(listOf(2L), controller.state.value.online.map { it.id })
    }

    @Test fun savedIngredientsDoNotPreventOnlineSearch() = runBlocking {
        val controller = LentaSearchController({ _, _, _, _ -> listOf(product(2, "Молоко")) }, this)
        controller.update("Молоко", "address", listOf(product(1, "Печенье с молоком")), false)
        delay(550)
        assertEquals(emptyList<Long>(), controller.state.value.local.map { it.id })
        assertEquals(listOf(2L), controller.state.value.online.map { it.id })
    }

    @Test fun savedProductCanBeFoundByConfirmedOldBarcode() = runBlocking {
        val requests = mutableListOf<Int>()
        val controller = LentaSearchController({ _, _, page, _ ->
            requests += page
            emptyList()
        }, this) { id -> if (id == 7L) setOf("4607078115610") else emptySet() }
        val saved = product(7, "Молоко").copy(ean = "4607078115627")
        controller.update("4607078115610", "address", listOf(saved), false)
        delay(550)
        assertEquals(listOf(7L), controller.state.value.local.map { it.id })
        assertEquals(emptyList<Int>(), requests)
    }

    @Test fun pagesContainingOnlySavedProductsAreSkipped() = runBlocking {
        val requests = mutableListOf<Int>()
        val controller = LentaSearchController({ _, _, page, _ ->
            requests += page
            when (page) {
                1 -> listOf(product(1, "Молоко"))
                2 -> listOf(product(2, "Молоко"))
                else -> emptyList()
            }
        }, this)
        controller.update("молоко", "address", listOf(product(1, "Молоко")), false)
        controller.searchOnline()
        delay(50)
        assertEquals(listOf(1, 2), requests)
        assertEquals(listOf(2L), controller.state.value.online.map { it.id })
    }

    @Test fun repeatedServerPageDoesNotHideLaterProducts() = runBlocking {
        val requests = mutableListOf<Int>()
        val controller = LentaSearchController({ _, _, page, _ ->
            requests += page
            when (page) {
                1, 2 -> listOf(product(1, "Молоко"))
                3 -> listOf(product(2, "Молоко"))
                else -> emptyList()
            }
        }, this)
        controller.update("молоко", "address", emptyList(), false)
        controller.searchOnline()
        delay(50)
        controller.searchOnline()
        delay(50)
        assertEquals(listOf(1L, 2L), controller.state.value.online.map { it.id })
        assertEquals(listOf(1, 2, 3), requests)
        controller.searchOnline()
        delay(50)
        assertFalse(controller.state.value.hasMore)
        assertFalse(controller.state.value.failed)
        assertEquals(listOf(1, 2, 3, 4), requests)
    }

    @Test fun repeatedServerPagesHaveBoundedRequestsPerAction() = runBlocking {
        val requests = mutableListOf<Int>()
        val controller = LentaSearchController({ _, _, page, _ ->
            requests += page
            listOf(product(1, "Молоко"))
        }, this)
        controller.update("молоко", "address", emptyList(), false)
        controller.searchOnline()
        delay(50)
        controller.searchOnline()
        delay(50)
        assertEquals((1..6).toList(), requests)
        assertEquals(listOf(1L), controller.state.value.online.map { it.id })
        assertFalse(controller.state.value.failed)
        assertEquals(true, controller.state.value.hasMore)
    }

    @Test fun newProductOnSixthPageAppearsAfterResuming() = runBlocking {
        val requests = mutableListOf<Int>()
        val saved = (1L..5L).map { product(it, "Молоко") }
        val controller = LentaSearchController({ _, _, page, _ ->
            requests += page
            when (page) {
                in 1..5 -> listOf(product(page.toLong(), "Молоко"))
                6 -> listOf(product(6, "Молоко новое"))
                else -> emptyList()
            }
        }, this)
        controller.update("молоко", "address", saved, false)
        controller.searchOnline()
        delay(50)
        assertEquals((1..5).toList(), requests)
        assertEquals(emptyList<Long>(), controller.state.value.online.map { it.id })
        controller.searchOnline()
        delay(50)
        assertEquals((1..6).toList(), requests)
        assertEquals(listOf(6L), controller.state.value.online.map { it.id })
        assertFalse(controller.state.value.loading)
    }

    @Test fun scanLimitCanBeResumedWithoutRepeatingEarlierPages() = runBlocking {
        val requests = mutableListOf<Int>()
        val saved = (1L..12L).map { product(it, "Молоко") }
        val controller = LentaSearchController({ _, _, page, _ ->
            requests += page
            if (page <= 12) listOf(product(page.toLong(), "Молоко"))
            else if (page == 13) listOf(product(13, "Молоко новое"))
            else emptyList()
        }, this)
        controller.update("молоко", "address", saved, false)
        controller.searchOnline()
        delay(50)
        assertEquals((1..5).toList(), requests)
        assertEquals(emptyList<Long>(), controller.state.value.online.map { it.id })
        assertEquals(true, controller.state.value.hasMore)
        controller.searchOnline()
        delay(50)
        assertEquals((1..10).toList(), requests)
        controller.searchOnline()
        delay(50)
        assertEquals((1..13).toList(), requests)
        assertEquals(listOf(13L), controller.state.value.online.map { it.id })
    }

    @Test fun savedAndRepeatedPagesAreSkippedUntilNewProduct() = runBlocking {
        val requests = mutableListOf<Int>()
        val controller = LentaSearchController({ _, _, page, _ ->
            requests += page
            when (page) {
                in 1..3 -> listOf(product(1, "Молоко"))
                4 -> listOf(product(2, "Молоко"))
                else -> emptyList()
            }
        }, this)
        controller.update("молоко", "address", listOf(product(1, "Молоко")), false)
        controller.searchOnline()
        delay(50)
        assertEquals(listOf(1, 2, 3, 4), requests)
        assertEquals(listOf(2L), controller.state.value.online.map { it.id })
    }

    @Test fun localMatchesWaitForActionAndOnlinePagesExcludeSavedIds() = runBlocking {
        val requests = mutableListOf<Int>()
        val controller = LentaSearchController({ _, _, page, _ ->
            requests += page
            when (page) {
                1 -> listOf(product(1, "Молоко"), product(2, "Молоко"))
                2 -> listOf(product(2, "Молоко"), product(3, "Молоко"))
                else -> emptyList()
            }
        }, this)
        controller.update("молоко", "address", listOf(product(1, "Молоко")), false)
        delay(550)
        assertEquals(emptyList<Int>(), requests)
        controller.searchOnline()
        delay(50)
        assertEquals(listOf(2L), controller.state.value.online.map { it.id })
        controller.searchOnline()
        delay(50)
        assertEquals(listOf(2L, 3L), controller.state.value.online.map { it.id })
        controller.searchOnline()
        delay(50)
        assertFalse(controller.state.value.hasMore)
        assertEquals(listOf(1, 2, 3), requests)
    }

    @Test fun updatingSavedProductsKeepsPaginationAndExistingOnlineResults() = runBlocking {
        val requests = mutableListOf<Int>()
        val controller = LentaSearchController({ _, _, page, _ ->
            requests += page
            when (page) {
                1 -> listOf(product(1, "Молоко"))
                2 -> listOf(product(2, "Молоко"))
                else -> emptyList()
            }
        }, this)
        controller.update("молоко", "address", emptyList(), false)
        controller.searchOnline()
        delay(50)
        assertEquals(listOf(1L), controller.state.value.online.map { it.id })
        controller.update("молоко", "address", listOf(product(1, "Молоко")), false)
        assertEquals(emptyList<Long>(), controller.state.value.online.map { it.id })
        controller.searchOnline()
        delay(50)
        assertEquals(listOf(1, 2), requests)
        assertEquals(listOf(2L), controller.state.value.online.map { it.id })
    }

    @Test fun changingBarcodeAliasesDoesNotRestartSearch() = runBlocking {
        val requests = mutableListOf<Int>()
        var aliases = setOf<String>()
        val controller = LentaSearchController({ _, _, page, _ ->
            requests += page
            listOf(product(page.toLong(), "Молоко"))
        }, this) { aliases }
        controller.update("4601234567893", "address", emptyList(), false)
        controller.searchOnline()
        delay(50)
        aliases = setOf("4601234567893")
        controller.update("4601234567893", "address", listOf(product(99, "Кофе")), false)
        controller.searchOnline()
        delay(50)
        assertEquals(listOf(1, 2), requests)
        assertEquals(listOf(99L), controller.state.value.local.map { it.id })
    }

    @Test fun missingLocalMatchStartsSearchAndOldRequestIsCancelled() = runBlocking {
        val requests = mutableListOf<String>()
        val controller = LentaSearchController({ query, _, _, _ ->
            requests += query
            if (query == "старый") delay(300)
            listOf(product(if (query == "новый") 2 else 1, query))
        }, this)
        controller.update("старый", "address", emptyList(), false)
        delay(550)
        controller.update("новый", "address", emptyList(), false)
        delay(650)
        assertEquals(listOf("старый", "новый"), requests)
        assertEquals(listOf(2L), controller.state.value.online.map { it.id })
    }

    @Test fun debounceCanBeStoppedAndResumed() = runBlocking {
        val pages = mutableListOf<Int>()
        val controller = LentaSearchController({ _, _, page, _ ->
            pages += page
            listOf(product(1, "Молоко"))
        }, this)
        controller.update("молоко", "address", emptyList(), false)
        assertEquals(true, controller.state.value.pending)
        controller.stop()
        delay(550)
        assertEquals(emptyList<Int>(), pages)
        assertEquals(true, controller.state.value.paused)
        assertFalse(controller.state.value.loading)
        controller.searchOnline()
        delay(50)
        assertEquals(listOf(1), pages)
        assertEquals(listOf(1L), controller.state.value.online.map { it.id })
    }

    @Test fun failureKeepsResultsAndRetriesSamePage() = runBlocking {
        var fail = true
        val pages = mutableListOf<Int>()
        val controller = LentaSearchController({ _, _, page, _ ->
            pages += page
            when {
                page == 1 -> listOf(product(1, "Молоко"))
                page == 2 && fail -> throw java.io.IOException("offline")
                page == 2 -> listOf(product(2, "Молоко"))
                else -> emptyList()
            }
        }, this)
        controller.update("молоко", "address", emptyList(), false)
        controller.searchOnline()
        delay(50)
        controller.searchOnline()
        delay(50)
        assertEquals(true, controller.state.value.failed)
        assertFalse(controller.state.value.complete)
        assertFalse(controller.state.value.loading)
        assertEquals(listOf(1L), controller.state.value.online.map { it.id })
        fail = false
        controller.searchOnline()
        delay(50)
        assertFalse(controller.state.value.failed)
        assertEquals(listOf(1, 2, 2), pages)
        assertEquals(listOf(1L, 2L), controller.state.value.online.map { it.id })
    }

    @Test fun stopDuringRequestDoesNotPublishLateResult() = runBlocking {
        val controller = LentaSearchController({ _, _, _, _ ->
            delay(300)
            listOf(product(1, "Молоко"))
        }, this)
        controller.update("молоко", "address", emptyList(), false)
        controller.searchOnline()
        delay(50)
        controller.stop()
        delay(350)
        assertFalse(controller.state.value.loading)
        assertFalse(controller.state.value.complete)
        assertEquals(true, controller.state.value.paused)
        assertEquals(emptyList<LentaProduct>(), controller.state.value.online)
    }
}
