package com.mok.buylist.data

private val searchToken = Regex("""[\p{L}\p{N}]+""")
private val ingredientMarkers = setOf("с", "со", "из", "без", "на", "для", "под", "в", "во")

private fun searchWords(value: String): List<String> =
    searchToken.findAll(value.lowercase().replace('ё', 'е')).map { it.value }.toList()

/** Match words rather than arbitrary substrings. A query must refer to the main
 * product, not only to an ingredient, flavor, filling or accompanying product.
 * Explicit multiword requests can still match qualifiers after the main name.
 */
internal fun matchesProductName(name: String, query: String): Boolean {
    val wanted = searchWords(query)
    if (wanted.isEmpty()) return query.isBlank()
    val words = searchWords(name)
    if (words.isEmpty()) return false
    val qualifier = words.indexOfFirst { it in ingredientMarkers }.let { if (it > 0) it else words.size }
    fun matches(index: Int, word: String): Boolean =
        word == wanted[index] ||
            (index == wanted.lastIndex && wanted[index].length >= 2 && word.startsWith(wanted[index]))
    // Require at least one substantive search word in the main title.
    // A numeric modifier or preposition alone must not qualify an unrelated item.
    val substantive = wanted.indices.filter { i ->
        wanted[i] !in ingredientMarkers && wanted[i].any { it.isLetter() }
    }
    val anchors = substantive.ifEmpty { wanted.indices.toList() }
    if (anchors.none { i -> words.take(qualifier).any { matches(i, it) } }) return false
    return wanted.indices.all { i -> words.any { matches(i, it) } }
}

internal fun isBarcodeSearch(query: String): Boolean {
    val value = query.trim()
    return value.length in 8..14 && value.all { it in '0'..'9' }
}

internal fun matchesProductSearch(name: String, pack: String, query: String): Boolean =
    matchesProductName(name, query) ||
        (query.any(Char::isDigit) && query.none(Char::isLetter) && matchesProductName(pack, query))
