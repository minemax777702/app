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

internal class ActionJournal private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("shopping_action_journal", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val entries: StateFlow<List<ListAction>> = state

    fun record(title: String, detail: String = "", before: ShoppingItem? = null,
        after: ShoppingItem? = null, removedAt: Long? = null) {
        save(listOf(ListAction(title = title, detail = detail, before = before, after = after, removedAt = removedAt)) + state.value)
    }

    fun notice(message: String) {
        val latest = state.value.firstOrNull()
        if (latest != null && System.currentTimeMillis() - latest.at < 1500 &&
            (latest.title == message || (message == "Добавлено в список" && latest.title == "Добавлен товар"))) return
        record(message)
    }

    fun completeUndo(id: String, success: Boolean) {
        val current = state.value.firstOrNull { it.id == id } ?: return
        val result = if (success) "Отменено" else "Действие уже изменено или завершено"
        val updated = state.value.map { if (it.id == id) it.copy(result = result) else it }
        save(listOf(ListAction(title = if (success) "Действие отменено" else "Не удалось отменить",
            detail = current.detail.ifBlank { current.title })) + updated)
    }

    private fun save(rows: List<ListAction>) {
        val kept = rows.take(200)
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
        @Volatile private var instance: ActionJournal? = null
        fun get(context: Context): ActionJournal = instance ?: synchronized(this) {
            instance ?: ActionJournal(context.applicationContext).also { instance = it }
        }
    }
}
