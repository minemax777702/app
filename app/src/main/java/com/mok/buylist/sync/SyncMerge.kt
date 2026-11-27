package com.mok.buylist.sync

import com.mok.buylist.data.nameKeyOf
import com.mok.buylist.data.decodePurchaseEvent

/**
 * Слияние состояний двух телефонов. Это функция множеств без «вычитания»: отсутствие записи ничего не удаляет,
 * удаляет только надгробие. Поэтому порядок слияния и повторы не важны: `merge(a, b) == merge(b, a)`,
 * `merge(a, a) == a`, и два телефона после обмена приходят к одному состоянию.
 */
object SyncMerge {
    fun merge(a: Snapshot, b: Snapshot): Snapshot {
        val tombAt = HashMap<Pair<TombKind, String>, Long>()
        for (t in a.tombs + b.tombs) {
            // Удаление целого архива необратимо: сохраняем самое первое время удаления.
            // Для текущих товаров и остальных сущностей действует обычный LWW.
            tombAt.merge(t.kind to t.key, t.at) { x, y ->
                if (t.kind == TombKind.ARCHIVE) minOf(x, y) else maxOf(x, y)
            }
        }

        val keptTombs = ArrayList<SyncTomb>()

        // Запись старше надгробия считается удалённой; из живых побеждает более поздняя.
        fun <T> pick(
            candidates: List<T>,
            kind: TombKind,
            key: (T) -> String,
            at: (T) -> Long,
            order: Comparator<T>,
        ): List<T> {
            val winners = HashMap<String, T>()
            for (c in candidates) {
                val k = key(c)
                val t = tombAt[kind to k]
                if (t != null && at(c) <= t) continue
                val current = winners[k]
                if (current == null || order.compare(c, current) > 0) winners[k] = c
            }
            // Надгробие нужно, только пока оно сильнее всех живых записей: иначе его можно забыть.
            for ((id, t) in tombAt) if (id.first == kind && id.second !in winners) keptTombs += SyncTomb(kind, id.second, t)
            return winners.values.sortedBy(key)
        }

        val users = (a.users + b.users)
            .groupBy { it.id }
            .values
            .map { group -> group.maxWith(compareBy<SyncUser> { it.at }.thenBy { it.name }) }
            .sortedBy { it.id }

        val items = pick(
            a.items + b.items, TombKind.ITEM, { it.key }, { it.at },
            compareBy<SyncItem> { it.at }.thenBy { it.bought }.thenBy { it.author }.thenBy { it.name }.thenBy { it.createdAt }.thenBy { it.quantity }.thenBy { it.purchaseEvent }.thenBy { it.weightGrams ?: -1 },
        )

        val products = pick(
            a.products + b.products, TombKind.PRODUCT, { it.id.toString() }, { it.updatedAt },
            compareBy<com.mok.buylist.data.StoredLentaProduct> { it.updatedAt }.thenBy { it.toString() },
        )

        val activeProductIds = products.mapTo(HashSet()) { it.id }
        // Подтверждённые коды объединяются по паре (productId, code).
        // Один код у двух товаров остаётся неоднозначным и требует выбора в UI.
        val productBarcodes = (a.productBarcodes + b.productBarcodes +
            products.mapNotNull { p ->
                p.ean.takeIf { it.isNotBlank() }?.let {
                    com.mok.buylist.data.StoredProductBarcode(p.id, it)
                }
            }).filter { it.productId in activeProductIds &&
            it.code.length in setOf(8, 12, 13, 14) && it.code.all { digit -> digit in '0'..'9' } }
            .groupBy { it.productId to it.code }
            .values.map { rows ->
                rows.maxWith(compareBy<com.mok.buylist.data.StoredProductBarcode> { it.updatedAt }
                    .thenBy { it.deleted })
            }.sortedWith(compareBy<com.mok.buylist.data.StoredProductBarcode> { it.productId }.thenBy { it.code })

        val hidden = pick(
            a.hidden + b.hidden, TombKind.HIDDEN, { it.key }, { it.at },
            compareBy<SyncHidden> { it.at },
        )

        val history = mergeHistory(a.history + b.history, tombAt, keptTombs)

        // Удалённая запись архива не возвращается ни при каких часах: у архива нет «повторного создания».
        val archiveTombs = tombAt.filterKeys { it.first == TombKind.ARCHIVE }
        val archiveCandidates = (a.archives + b.archives)
            .filter { (TombKind.ARCHIVE to it.uid) !in archiveTombs }
            .groupBy { it.uid }
            .values
            .map { group -> mergeArchiveVersions(group) }
        val joinedArchives = joinOverlappingTrips(archiveCandidates)
        // Сохраняем независимые покупки; дубли событий объединяются по ID.
        val archives = joinedArchives.sortedBy { it.uid }
        for ((id, t) in archiveTombs) keptTombs += SyncTomb(TombKind.ARCHIVE, id.second, t)
        // Архивы, слитые в один поход, получают устойчивые надгробия для
        // неканонических UID: иначе старый телефон вернёт дубли обратно.
        val joinedUids = archives.mapTo(HashSet()) { it.uid }
        for (duplicate in archiveCandidates) {
            if (duplicate.uid !in joinedUids) {
                keptTombs += SyncTomb(TombKind.ARCHIVE, duplicate.uid, duplicate.completedAt)
            }
        }

        // Persist tombstoned records to avoid resurrection during offline sync.
        val customProducts = (a.customProducts + b.customProducts)
            .groupBy { it.id }.values.map { group ->
                group.maxWith(compareBy<com.mok.buylist.data.CustomProduct> { it.updatedAt }
                    .thenBy { it.deleted }.thenBy { it.toString() })
            }.sortedBy { it.id }
        val stores = (a.stores + b.stores)
            .groupBy { it.id }.values.map { group ->
                group.maxWith(compareBy<com.mok.buylist.data.ShoppingStore> { it.updatedAt }
                    .thenBy { it.deleted }.thenBy { it.toString() })
            }.sortedBy { it.id }

        val activeCustomIds = customProducts.filterNot { it.deleted }.mapTo(HashSet()) { it.id }
        val customProductBarcodes = (a.customProductBarcodes + b.customProductBarcodes)
            .filter { it.productId in activeCustomIds }
            .groupBy { it.productId to it.code }.values.map { group ->
                group.maxWith(compareBy<com.mok.buylist.data.CustomProductBarcode> { it.updatedAt }
                    .thenBy { it.deleted })
            }.sortedWith(compareBy<com.mok.buylist.data.CustomProductBarcode> { it.productId }.thenBy { it.code })

        val storeProducts = (a.storeProducts + b.storeProducts)
            .groupBy { it.storeId to it.productKey }.values.map { group ->
                group.maxWith(compareBy<com.mok.buylist.data.StoreProduct> { it.updatedAt }
                    .thenBy { it.deleted }.thenBy { it.toString() })
            }.sortedWith(compareBy<com.mok.buylist.data.StoreProduct> { it.storeId }
                .thenBy { it.productKey })

        return Snapshot(
            users = users,
            items = items,
            history = history,
            hidden = hidden,
            archives = archives,
            tombs = keptTombs.sortedWith(compareBy<SyncTomb> { it.kind.code }.thenBy { it.key }),
            products = products,
            productBarcodes = productBarcodes,
            customProducts = customProducts,
            stores = stores,
            customProductBarcodes = customProductBarcodes,
            storeProducts = storeProducts,
        )
    }

    /**
     * Объединяем архивы одного похода, если они содержат хотя бы одно и то же
     * событие покупки и завершены в пределах двух минут. Наличие одинакового
     * продукта/покупателя/дня НЕ считается подтверждением дубля.
     *
     * Удалённые архивы уже отфильтрованы вызывающим кодом. Объединение
     * детерминировано: результирующий uid принадлежит самому раннему архиву.
     * Исходные записи без общего event.id не затрагиваются.
     */
    private fun joinOverlappingTrips(source: List<SyncArchive>): List<SyncArchive> {
        if (source.size < 2) return source
        val ordered = source.sortedWith(compareBy<SyncArchive> { it.completedAt }.thenBy { it.uid })
        val parent = IntArray(ordered.size) { it }
        fun root(index: Int): Int {
            var cursor = index
            while (parent[cursor] != cursor) {
                parent[cursor] = parent[parent[cursor]]
                cursor = parent[cursor]
            }
            return cursor
        }
        fun union(first: Int, second: Int) {
            val a = root(first)
            val b = root(second)
            if (a != b) parent[b] = a
        }
        // Index each purchase ID once per archive. Connecting consecutive
        // occurrences is sufficient to build the same transitive components.
        val lastSeen = HashMap<String, Int>()
        for ((index, archive) in ordered.withIndex()) {
            val ids = archive.purchaseEvents.mapNotNull { encoded ->
                decodePurchaseEvent(encoded)?.id?.takeIf(String::isNotBlank)
            }.toSet()
            for (id in ids) {
                val previous = lastSeen.put(id, index) ?: continue
                if (archive.completedAt - ordered[previous].completedAt <= TRIP_JOIN_WINDOW_MS) {
                    union(previous, index)
                }
            }
        }
        return ordered.indices.groupBy(::root).values.map { indices ->
            if (indices.size == 1) ordered[indices.single()]
            else {
                val group = indices.map(ordered::get)
                val earliest = group.minWith(compareBy<SyncArchive> { it.completedAt }.thenBy { it.uid })
                mergeArchiveVersions(group).copy(
                    uid = earliest.uid,
                    completedAt = earliest.completedAt,
                    tripMillis = group.maxOf { it.tripMillis },
                    visits = group.maxOf { it.visits },
                )
            }
        }
    }

    private const val TRIP_JOIN_WINDOW_MS = 2L * 60 * 1000

    /**
     * Одинаковый uid архива может встречаться с разными составами после обмена.
     * Выбор только одной версии стирал покупки из другой. Объединяем события
     * по их устойчивому id; для старых записей без id используем позицию.
     */
    private fun mergeArchiveVersions(group: List<SyncArchive>): SyncArchive {
        if (group.size == 1) return group.single()
        val base = group.maxWith(archiveOrder)
        data class Entry(val name: String, val buyer: String, val quantity: Int, val event: String)
        val events = linkedMapOf<String, Entry>()
        for (archive in group.sortedWith(archiveOrder)) {
            val occurrences = HashMap<String, Int>()
            archive.names.forEachIndexed { index, name ->
                val event = archive.purchaseEvents.getOrElse(index) { "" }
                val buyer = archive.buyers.getOrElse(index) { "" }
                val purchaseId = decodePurchaseEvent(event)?.id?.takeIf { it.isNotEmpty() }
                // No stable ID exists for older purchases. Do not discard a
                // different quantity or a second known buyer as a "duplicate".
                val quantity = archive.quantities.getOrElse(index) { 1 }
                val legacyKey = "${nameKeyOf(name)}:$buyer:$quantity"
                val ordinal = occurrences.getOrDefault(legacyKey, 0)
                occurrences[legacyKey] = ordinal + 1
                val key = if (purchaseId != null) "purchase:$purchaseId"
                    else {
                        val exact = "legacy:$legacyKey:$ordinal"
                        val anonymous = "legacy:${nameKeyOf(name)}::$quantity:$ordinal"
                        if (buyer.isBlank()) {
                            events.keys.firstOrNull { key ->
                                key.startsWith("legacy:${nameKeyOf(name)}:") &&
                                    key.endsWith(":$quantity:$ordinal")
                            } ?: anonymous
                        } else if (events[anonymous]?.buyer.isNullOrBlank()) {
                            if (anonymous in events) anonymous else exact
                        } else exact
                    }
                val candidate = Entry(name, buyer, archive.quantities.getOrElse(index) { 1 }, event)
                val old = events[key]
                // Известного покупателя нельзя затирать пустым полем автора.
                // При конфликте двух известных покупателей выбираем результат
                // детерминированно; реального автора без ID события не угадать.
                if (old == null ||
                    (candidate.buyer.isNotEmpty() && old.buyer.isEmpty()) ||
                    (candidate.buyer.isNotEmpty() == old.buyer.isNotEmpty() &&
                        ((decodePurchaseEvent(candidate.event)?.revisedAt ?: 0) >
                            (decodePurchaseEvent(old.event)?.revisedAt ?: 0) ||
                            ((decodePurchaseEvent(candidate.event)?.revisedAt ?: 0) ==
                                (decodePurchaseEvent(old.event)?.revisedAt ?: 0) &&
                                candidate.toString() > old.toString())))) {
                    events[key] = candidate
                }
            }
        }
        val selected = events.toSortedMap().values.toList()
        // Незавершённые позиции при сохранении похода — это отдельный снимок.
        // Нельзя выбирать одну из двух версий по длине: так терялись товары.
        val remaining = mergeRemainingEvents(group.map { it.remainingEvents })
        return base.copy(
            names = selected.map { it.name },
            buyers = selected.map { it.buyer },
            quantities = selected.map { it.quantity },
            purchaseEvents = selected.map { it.event },
            remainingEvents = remaining,
        )
    }

    /** Объединяет сохранённые в архиве незавершённые позиции двух устройств.
     * Формат v1: префикс `v1|` и строки сериализованных событий покупок.
     * Не меняет кодирование самих событий, не назначает нового автора и не
     * создаёт второй экземпляр одного события при повторном обмене.
     */
    private fun mergeRemainingEvents(versions: List<String>): String {
        val nonEmpty = versions.filter { it.isNotBlank() }
        if (nonEmpty.isEmpty()) return ""
        if (nonEmpty.any { !it.startsWith("v1|") }) {
            // Неизвестную версию нельзя безопасно разбирать или объединять.
            return nonEmpty.maxWith(compareBy<String> { it.length }.thenBy { it })
        }
        val byKey = sortedMapOf<String, String>()
        for (version in nonEmpty) {
            val payload = version.removePrefix("v1|")
            if (payload.isEmpty()) continue
            for (line in payload.split('\n')) {
                if (line.isBlank()) continue
                val event = decodePurchaseEvent(line)
                val key = event?.productId?.takeIf { it > 0 }?.let { "product:$it" }
                    ?: event?.id?.takeIf { it.isNotEmpty() }?.let { "event:$it" }
                    ?: "legacy:$line"
                val old = byKey[key]
                // Детеминированный выбор среди версий одного товара.
                val at = event?.revisedAt ?: 0L
                val oldAt = old?.let(::decodePurchaseEvent)?.revisedAt ?: 0L
                if (old == null || at > oldAt || (at == oldAt && line > old)) byKey[key] = line
            }
        }
        return "v1|" + byKey.values.joinToString("\n")
    }

    // Детерминированный выбор при разных данных записи с одним uid.
    private val archiveOrder = compareBy<SyncArchive> { it.completedAt }
        .thenBy { it.purchaseEvents.maxOfOrNull { event -> decodePurchaseEvent(event)?.revisedAt ?: 0L } ?: 0L }
        .thenBy { it.names.size }
        .thenBy { it.names.joinToString("\u0000") }
        .thenBy { it.buyers.joinToString("\u0000") }
        .thenBy { it.quantities.joinToString(",") }
        .thenBy { it.purchaseEvents.joinToString("\u0000") }
        .thenBy { it.remainingEvents }
        .thenBy { it.completedAt }.thenBy { it.tripMillis }.thenBy { it.visits }

    /**
     * История подсказок: счётчик растёт, поэтому берём максимум, но учитываем только записи новее надгробия,
     * иначе удалённый продукт вернул бы себе старый счётчик.
     */
    private fun mergeHistory(
        all: List<SyncHistory>,
        tombAt: Map<Pair<TombKind, String>, Long>,
        keptTombs: MutableList<SyncTomb>,
    ): List<SyncHistory> {
        val result = HashMap<String, SyncHistory>()
        for (h in all) {
            val t = tombAt[TombKind.HISTORY to h.key]
            if (t != null && h.lastUsedAt <= t) continue
            val current = result[h.key]
            result[h.key] = if (current == null) {
                h
            } else {
                val latest = if (h.lastUsedAt > current.lastUsedAt || (h.lastUsedAt == current.lastUsedAt && h.name > current.name)) h else current
                SyncHistory(
                    name = latest.name,
                    useCount = maxOf(h.useCount, current.useCount),
                    lastUsedAt = maxOf(h.lastUsedAt, current.lastUsedAt),
                )
            }
        }
        for ((id, t) in tombAt) if (id.first == TombKind.HISTORY && id.second !in result) {
            keptTombs += SyncTomb(TombKind.HISTORY, id.second, t)
        }
        return result.values.sortedBy { it.key }
    }

    /** Отпечаток полного содержимого архива, включая покупателей, количества и события. */
    private fun archiveDigest(archive: SyncArchive): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(SyncCodec.encode(Snapshot(archives = listOf(archive))))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** Отпечатки записей архива в [s]: по ним видно, что у телефона уже есть. */
    fun helloOf(s: Snapshot): Hello = Hello(s.archives.map { ArchiveStamp(archivePrefix(it.uid), archiveDigest(it)) })

    /** Не пересылаем архивы, которые уже есть у получателя. */
    fun forPeer(s: Snapshot, peer: Hello): Snapshot {
        val known = peer.archives.filter { it.digest.isNotEmpty() }.mapTo(HashSet()) { it.prefix to it.digest }
        return s.copy(archives = s.archives.filter { (archivePrefix(it.uid) to archiveDigest(it)) !in known })
    }

    fun archivePrefix(uid: String): Long =
        uid.take(12).toLongOrNull(16) ?: (uid.hashCode().toLong() and 0xFFFFFFFFFFFFL)
}
