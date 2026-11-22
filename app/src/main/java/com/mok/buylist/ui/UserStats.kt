package com.mok.buylist.ui

import com.mok.buylist.data.AppUser
import com.mok.buylist.data.ArchivedList

/**
 * Архив, оставленный только по покупкам [userId]. Список, в котором пользователь ничего не покупал, пропадает;
 * в оставшемся — только его товары. Время похода остаётся общим: поход делали вместе.
 */
internal fun filterByUser(
    archive: List<ArchivedList>,
    userId: String,
): List<ArchivedList> =
    archive.mapNotNull { list ->
        val names = list.names
        val buyers = list.buyerIds
        val mine = names.indices.filter { buyers[it] == userId }
        if (mine.isEmpty()) return@mapNotNull null
        list.copy(
            itemCount = mine.size,
            items = mine.joinToString("\n") { names[it] },
            buyers = mine.joinToString("\n") { userId },
            quantities = mine.joinToString("\n") { list.counts[it].toString() },
            purchaseEvents = mine.joinToString("\n") { list.events[it] },
        )
    }

/** Пользователи, которые что-то покупали в [archive]: только по ним имеет смысл делить статистику. */
internal fun buyersIn(archive: List<ArchivedList>, users: List<AppUser>): List<AppUser> {
    val ids = archive.flatMapTo(HashSet()) { it.buyerIds }
    return users.filter { it.id in ids }.sortedBy { it.name.lowercase() }
}
