package com.mok.buylist.ui

import com.mok.buylist.data.ShoppingItem
import org.junit.Assert.*
import org.junit.Test

class ActionJournalRulesTest {
    @Test fun rotationLimitsEveryGroupIndependently() {
        val titles = listOf("Добавлен товар", "Список завершён", "Цена обновлена", "Обмен выполнен", "Группа создана")
        val rows = (150 downTo 1).flatMap { n ->
            titles.map { ListAction(id = "$n:$it", at = n.toLong(), title = it) }
        }
        val kept = rotateNotifications(rows)
        assertEquals(500, kept.size)
        NotificationGroup.entries.forEach { group ->
            val entries = kept.filter { it.group() == group }
            assertEquals(100, entries.size)
            assertEquals(150L, entries.first().at)
            assertEquals(51L, entries.last().at)
        }
        assertEquals(NotificationGroup.ACTIONS, NotificationGroup.entries.first())
    }

    @Test fun rotationDoesNotDiscardUndoSlots() {
        val recent = (150 downTo 1).map { ListAction(id = "notice:$it", title = "Добавлен товар") }
        val reversible = (3 downTo 1).map {
            ListAction(id = "undo:$it", title = "Добавлен товар", after = ShoppingItem(name = "Товар $it"))
        }
        val kept = rotateNotifications(recent + reversible)
        assertEquals(100, kept.size)
        assertEquals(reversible.map { it.id }, undoableRecentActions(kept).map { it.id })
        assertEquals(recent.take(97) + reversible, kept)
    }

    @Test fun clearProtectsOnlyAvailableIdsAndRespectsGroup() {
        val active = ListAction(id = "active", title = "Добавлен товар", after = ShoppingItem(name = "Товар"))
        val finished = active.copy(id = "finished", result = "Отменено")
        val price = ListAction(id = "price", title = "Цена обновлена")
        val rows = listOf(active, finished, price)
        assertEquals(setOf("finished"), notificationIdsToClear(rows, NotificationGroup.ACTIONS, setOf("active")))
        assertEquals(setOf("finished", "price"), notificationIdsToClear(rows, null, setOf("active")))
        assertEquals(setOf("active", "finished", "price"), notificationIdsToClear(rows, null, emptySet()))
    }
    @Test fun transientAndExpiredActionsAreNotShown() {
        val undo = ListAction(id="undo",title="Добавлен товар",after=ShoppingItem(name="Молоко"))
        val stale = undo.copy(id="stale")
        val duplicate = ListAction(id="duplicate",title="Уже в списке")
        val created = ListAction(id="created",title="Пользователь создан · Анна")
        assertEquals(listOf(undo,created),visibleNotifications(listOf(undo,stale,duplicate,created),setOf("undo")))
        assertEquals(NotificationGroup.ACCOUNT,created.group())
    }
}
