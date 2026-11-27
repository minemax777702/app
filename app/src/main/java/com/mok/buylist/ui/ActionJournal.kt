package com.mok.buylist.ui

import android.content.Context
import com.mok.buylist.data.ShoppingItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class ListAction(
    val id: String = UUID.randomUUID().toString(),
    val at: Long = System.currentTimeMillis(),
    val title: String,
    val detail: String = "",
    val before: ShoppingItem? = null,
    val after: ShoppingItem? = null,
    val removedAt: Long? = null,
    val result: String = "",
) {
    val canUndo: Boolean get() = result.isEmpty() && (after != null || (before != null && removedAt != null))
}

internal class ActionJournal private constructor(context: Context, namespace: String) {
    private val prefs = context.getSharedPreferences("shopping_action_journal" + namespace, Context.MODE_PRIVATE)
    private val state = MutableStateFlow<List<ListAction>>(emptyList())
    val entries: StateFlow<List<ListAction>> = state

    init { save(read()) }

    @Synchronized
    fun record(title: String, detail: String = "", before: ShoppingItem? = null,
        after: ShoppingItem? = null, removedAt: Long? = null) {
        save(listOf(ListAction(title = title, detail = detail, before = before, after = after, removedAt = removedAt)) + state.value)
    }

    @Synchronized
    fun notice(message: String) {
        val historyMessage = message.replace(" · Ожидает синхронизации", " · Изменение сохранено локально").replace(" · Добавление в группу ожидает синхронизации", "")
        if (!keepNoticeInHistory(historyMessage)) return
        val now = System.currentTimeMillis()
        val recent = state.value.take(8).filter { now - it.at in 0..3000 }
        if (recent.any { it.title == historyMessage || (historyMessage == "Добавлено в список" && it.title == "Добавлен товар") ||
            (historyMessage == "Покупка отмечена" && it.title == "Покупка отмечена") }) return
        record(historyMessage)
    }

    @Synchronized
    fun completeUndo(id: String, success: Boolean, restored: ShoppingItem? = null) {
        val rows = state.value
        val current = rows.firstOrNull { it.id == id } ?: return
        val result = if (success) "Отменено" else "Действие уже изменено или завершено"
        // After restoring a snapshot, its revision is advanced for sync. Rebase only the
        // preceding action that actually produced this exact snapshot.
        val previous = if (success && current.before != null && restored != null &&
            restored.copy(updatedAt = current.before.updatedAt, authorId = current.before.authorId) == current.before)
            rows.dropWhile { it.id != id }.drop(1).firstOrNull { it.canUndo && it.after == current.before }
        else null
        val updated = rows.map {
            when (it.id) {
                id -> it.copy(result = result)
                previous?.id -> it.copy(after = restored)
                else -> it
            }
        }
        save(updated)
    }

    @Synchronized
    fun clear(ids: Set<String>) {
        if (ids.isNotEmpty()) save(state.value.filterNot { it.id in ids })
    }

    private fun save(rows: List<ListAction>) {
        val kept = rotateNotifications(rows)
        val array = JSONArray()
        kept.forEach { a ->
            array.put(JSONObject().put("id", a.id).put("at", a.at).put("title", a.title)
                .put("detail", a.detail).put("before", a.before?.let(::encode)).put("after", a.after?.let(::encode))
                .put("removedAt", a.removedAt).put("result", a.result))
        }
        prefs.edit().putString("entries", array.toString()).apply()
        state.value = kept
    }

    private fun read(): List<ListAction> = runCatching {
        val array = JSONArray(prefs.getString("entries", "[]") ?: "[]")
        (0 until array.length()).mapNotNull { index -> runCatching {
            val o = array.getJSONObject(index)
            ListAction(o.getString("id"), o.getLong("at"), o.getString("title"), o.optString("detail"),
                o.optJSONObject("before")?.let(::decode), o.optJSONObject("after")?.let(::decode),
                if (o.has("removedAt") && !o.isNull("removedAt")) o.getLong("removedAt") else null, o.optString("result"))
        }.getOrNull() }
    }.getOrDefault(emptyList())

    private fun encode(i: ShoppingItem) = JSONObject().put("id", i.id).put("name", i.name)
        .put("nameKey", i.nameKey).put("createdAt", i.createdAt).put("updatedAt", i.updatedAt)
        .put("deleted", i.deleted).put("authorId", i.authorId).put("quantity", i.quantity)
        .put("purchaseEvent", i.purchaseEvent).put("productId", i.productId)
        .put("weightGrams", i.weightGrams).put("customProductId", i.customProductId)

    private fun decode(o: JSONObject) = ShoppingItem(id = o.getString("id"), name = o.getString("name"),
        nameKey = o.getString("nameKey"), createdAt = o.getLong("createdAt"), updatedAt = o.getLong("updatedAt"),
        deleted = o.getBoolean("deleted"), authorId = o.optString("authorId"), quantity = o.getInt("quantity"),
        purchaseEvent = o.optString("purchaseEvent"), productId = o.optLong("productId"),
        weightGrams = if (o.has("weightGrams") && !o.isNull("weightGrams")) o.getInt("weightGrams") else null,
        customProductId = o.optString("customProductId"))

    companion object {
        private val instances = mutableMapOf<String, ActionJournal>()
        @Synchronized fun get(context: Context, namespace: String = (context.applicationContext as com.mok.buylist.ShoppingApp).workspaceNamespace): ActionJournal {
            val app = context.applicationContext as com.mok.buylist.ShoppingApp
            return instances.getOrPut(namespace) { ActionJournal(app, namespace) }
        }
    }
}

/** Only the newest three reversible operations, excluding status messages, are eligible. */
internal fun undoableRecentActions(rows: List<ListAction>): List<ListAction> =
    rows.filter { it.after != null || (it.before != null && it.removedAt != null) }
        .take(3).filter { it.canUndo }

internal enum class NotificationGroup(val title: String) {
    ACTIONS("Действия с товарами"),
    COMPLETED("Завершённые списки"),
    PRICES("Обновления цен"),
    ACCOUNT("Аккаунт и группы"),
    OTHER("Важное"),
}

internal fun ListAction.group(): NotificationGroup {
    val normalized = title.lowercase().replace('ё', 'е')
    return when {
        normalized.startsWith("список завершен") -> NotificationGroup.COMPLETED
        isAccountNotice(title) -> NotificationGroup.ACCOUNT
        normalized.contains("цен") || normalized.startsWith("не обновлено:") ||
            normalized.startsWith("данные товара обновлены") || normalized.startsWith("данные каталога обновлены") ||
            normalized.startsWith("обновление остановлено") -> NotificationGroup.PRICES
        before != null || after != null || normalized in setOf("добавлен товар", "товар добавлен", "создан товар",
            "покупка отмечена", "товар удален", "количество изменено", "вес изменен", "добавлено в список",
            "уже в списке", "добавлен товар ленты") -> NotificationGroup.ACTIONS
        else -> NotificationGroup.OTHER
    }
}

internal fun isAccountNotice(message: String): Boolean {
    val normalized = message.lowercase().replace('ё','е')
    return listOf("пользователь создан", "пользователь изменен", "группа создана", "группа изменена",
        "группа удалена", "участник добавлен", "участники добавлены", "участник исключен",
        "список создан", "список изменен", "список удален", "имя профиля изменено",
        "доступ отключен", "доступ восстановлен", "почта подтверждена", "выход из группы", "ключ доступа создан", "ключ доступа удален", "вход по ключу доступа выполнен").any { normalized.startsWith(it) }
}

/** Transient hints stay in the overlay, not in the persistent history. */
internal fun keepNoticeInHistory(message: String): Boolean {
    val normalized = message.trim().lowercase().replace('ё','е')
    return normalized.isNotBlank() && normalized !in setOf("действие отменено", "уже в списке",
        "добавлено в список", "покупка отмечена", "загрузка", "готово", "информация", "офлайн",
        "нет соединения", "изменения ожидают синхронизации", "действие уже изменено или завершено")
        && !normalized.startsWith("ожидают синхронизации:")
}

/** Keep undo slots even if the corresponding status is no longer worth showing. */
internal fun visibleNotifications(rows: List<ListAction>, undoIds: Set<String>): List<ListAction> =
    rows.filter { action ->
        action.id in undoIds || (action.result.isEmpty() && (action.before != null || action.after != null ||
            keepNoticeInHistory(action.title)) && !(action.group() == NotificationGroup.ACTIONS &&
            (action.before != null || action.after != null) && action.id !in undoIds))
    }

internal const val NOTIFICATION_GROUP_LIMIT = 100

/** Сохраняем последние записи каждой группы и не вытесняем кандидатов на отмену уведомлениями. */
internal fun rotateNotifications(rows: List<ListAction>): List<ListAction> {
    val visible = rows.filterNot { it.title == "Действие отменено" }
    // Сохраняем также занятые слоты отмены, чтобы ротация не открывала старые операции.
    val undoSlots = visible.filter { it.after != null || (it.before != null && it.removedAt != null) }
        .take(3).mapTo(HashSet()) { it.id }
    val reserved = visible.filter { it.id in undoSlots }.groupingBy { it.group() }.eachCount()
    val counts = mutableMapOf<NotificationGroup, Int>()
    return visible.filter { action ->
        if (action.id in undoSlots) true else {
            val group = action.group()
            val count = counts[group] ?: 0
            if (count >= NOTIFICATION_GROUP_LIMIT - (reserved[group] ?: 0)) false
            else { counts[group] = count + 1; true }
        }
    }
}

internal fun notificationIdsToClear(
    rows: List<ListAction>,
    group: NotificationGroup?,
    protectedIds: Set<String>,
): Set<String> = rows.asSequence()
    .filter { group == null || it.group() == group }
    .filterNot { it.id in protectedIds }
    .map { it.id }.toSet()
