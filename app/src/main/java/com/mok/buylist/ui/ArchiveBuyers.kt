package com.mok.buylist.ui

import com.mok.buylist.data.ArchivedList

/** Кто купил список: один человек (метка на списке), несколько (метки на товарах) или неизвестно. */
internal sealed interface ListBuyers {
    data object Unknown : ListBuyers

    /** Всё купил один человек: помечается список, а не каждый товар. */
    data class One(val id: String) : ListBuyers

    /** Покупатели по порядку товаров; пустая строка — неизвестно. */
    data class Mixed(val ids: List<String>) : ListBuyers
}

internal fun listBuyers(list: ArchivedList): ListBuyers {
    val ids = list.buyerIds
    val known = ids.filter { it.isNotEmpty() }.toSet()
    return when {
        known.isEmpty() -> ListBuyers.Unknown
        known.size == 1 && ids.none { it.isEmpty() } -> ListBuyers.One(known.single())
        else -> ListBuyers.Mixed(ids)
    }
}
