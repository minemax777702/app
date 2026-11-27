package com.mok.buylist.data

private val searchToken = Regex("""[\p{L}\p{N}]+""")
private val quotedName = Regex("""«[^»]*»|"[^"]*"|„[^“]*“|“[^”]*”""")
private val ingredientMarkers = setOf("с", "со", "из", "без", "на", "для", "под", "в", "во")

private fun searchWords(value: String): List<String> =
    searchToken.findAll(value.lowercase().replace('ё', 'е')).map { it.value }.toList()

// Product nouns, not brands or exclusions specific to a search phrase.
// Explicit inflections avoid treating adjectives ("молочный") as product types.
private val productTypeByWord = listOf(
    "молоко молока молоку молоком молоке",
    "конфеты конфета конфет конфету конфетами конфетой конфетах",
    "вафли вафля вафель вафлю вафлями вафлях",
    "печенье печенья печеньем печенью",
    "шоколад шоколада шоколадом шоколаде",
    "батончик батончики батончиков батончиком",
    "торт торты торта тортов тортом",
    "пирожное пирожные пирожных пирожного",
    "мороженое мороженого мороженым",
    "зефир зефира зефиром",
    "мармелад мармелада мармеладом",
    "пряник пряники пряников пряником",
    "кекс кексы кексов кекса",
    "рулет рулеты рулетов рулета",
    "десерт десерты десертов десерта",
    "пудинг пудинги пудингов",
    "трубочки трубочка трубочек",
    "круассан круассаны круассанов",
    "булочка булочки булочек",
    "сыр сыра сыром сыре сыры сыров",
    "сырок сырки сырка сырков",
    "творог творога творогом",
    "йогурт йогурты йогурта йогуртов",
    "кефир кефира кефиром",
    "ряженка ряженки ряженкой",
    "сметана сметаны сметаной",
    "сливки сливок сливками",
    "масло масла маслом масле",
    "напиток напитки напитка напитков",
    "коктейль коктейли коктейля коктейлей",
    "сок соки сока соков",
    "вода воды водой",
    "чай чая чаем",
    "кофе",
    "какао",
    "сироп сиропы сиропа сиропов сиропом",
    "топпинг топпинги топпинга топпингов",
    "вино вина вином вине вин",
    "ликер ликеры ликера ликеров ликером",
    "пиво пива пивом",
    "сидр сидра сидром",
    "шампанское шампанского",
    "компот компоты компота",
    "лимонад лимонады лимонада",
    "нектар нектары нектара",
    "джем джемы джема",
    "варенье варенья",
    "паста пасты пастой",
    "концентрат концентраты концентрата",
    "экстракт экстракты экстракта",
    "ароматизатор ароматизаторы ароматизатора ароматизаторов",
    "хлеб хлеба хлебом",
    "батон батоны батона",
    "сухарики сухариков сухарик",
    "чипсы чипсов",
    "крекер крекеры крекеров",
    "хлебцы хлебцев",
    "каша каши кашей",
    "хлопья хлопьев",
    "мюсли",
    "гранола гранолы",
    "смесь смеси смесей",
    "мука муки мукой",
    "сахар сахара сахаром",
    "соль соли солью",
    "рис риса рисом",
    "макароны макарон",
    "лапша лапши лапшой",
    "колбаса колбасы колбасой",
    "сосиски сосисок сосиска",
    "сардельки сарделек",
    "ветчина ветчины",
    "мясо мяса мясом",
    "котлеты котлета котлет",
    "пельмени пельменей",
    "паштет паштеты паштета",
    "салат салаты салата",
    "соус соусы соуса",
    "майонез майонеза",
    "кетчуп кетчупа",
    "яйца яйцо яиц",
    "яблоко яблоки яблок яблока",
    "банан бананы бананов",
    "груша груши груш",
    "картофель картофеля",
    "томат томаты томатов помидор помидоры помидоров",
    "огурец огурцы огурцов",
    "морковь моркови",
    "шампунь шампуни шампуня",
    "мыло мыла мылом",
    "крем кремы крема кремов",
    "гель гели геля гелей",
    "порошок порошки порошка",
    "корм корма кормов",
    "аромадиффузор аромадиффузоры аромадиффузора аромадиффузоров аромодиффузор аромодиффузоры",
    "диффузор диффузоры диффузора диффузоров",
    "освежитель освежители освежителя освежителей",
    "аромат ароматов аромата ароматы ароматом",
    "свеча свечи свечей свечу",
    "отдушка отдушки отдушек",
    "парфюм парфюма",
    "духи духов духами",
    "спрей спреи спрея",
    "лосьон лосьоны лосьона",
    "бальзам бальзамы бальзама",
    "маска маски масок",
    "скраб скрабы скраба",
    "пенка пенки пенок",
    "вспениватель вспениватели вспенивателя вспенивателей",
    "капучинатор капучинаторы капучинатора капучинаторов",
    "миксер миксеры миксера миксеров",
    "венчик венчики венчика венчиков",
    "кофеварка кофеварки кофеварок",
    "кофемашина кофемашины кофемашин",
    "дозатор дозаторы дозатора дозаторов",
    "кувшин кувшины кувшина",
    "молочник молочники молочника",
    "контейнер контейнеры контейнера",
    "фильтр фильтры фильтра фильтров",
    "пакет пакеты пакетов пакета",
    "средство средства средств",
    "игрушка игрушки игрушек",
).flatMap { forms ->
    val words = forms.split(' ')
    words.map { it to words.first() }
}.toMap()

private fun primaryWords(words: List<String>): List<String> {
    val index = words.indexOfFirst { it in ingredientMarkers }
    return if (index > 0) words.take(index) else words
}

private fun productType(word: String, allowPrefix: Boolean): String? {
    productTypeByWord[word]?.let { return it }
    if (!allowPrefix || word.length < 2) return null
    return productTypeByWord.asSequence()
        .filter { (form, _) -> form.startsWith(word) }
        .map { it.value }.distinct().take(2).toList().singleOrNull()
}

private fun primaryProductType(words: List<String>, allowPrefix: Boolean): String? {
    val main = primaryWords(words)
    return main.indices.firstNotNullOfOrNull { i ->
        val word = main[i]
        // A conventional confectionery name, not a literal type of milk.
        if (word == "молоко" && main.getOrNull(i - 1) == "птичье") null
        // In catalog titles the standalone product is normally named "молоко",
        // not a dependent noun such as "молока" in "вспениватель молока".
        else if (!allowPrefix && word in setOf("молока", "молоку", "молоком", "молоке")) null
        else productType(word, allowPrefix && i == words.lastIndex)
    }
}

private fun titleProductType(name: String): String? {
    val unquoted = searchWords(quotedName.replace(name, " "))
    return primaryProductType(unquoted.ifEmpty { searchWords(name) }, allowPrefix = false)
}

/** Prefer the primary product noun. A named product may contain another product
 * as an ingredient, flavor or marketing name. Unknown product nouns retain
 * ordinary word matching, so new/unlisted categories and brands remain searchable.
 */
internal fun matchesProductName(name: String, query: String): Boolean {
    val wanted = searchWords(query)
    if (wanted.isEmpty()) return query.isBlank()
    val words = searchWords(name)
    if (words.isEmpty()) return false

    val requestedType = primaryProductType(wanted, allowPrefix = true)
    val actualType = titleProductType(name)
    if (requestedType != null && actualType != requestedType) return false
    // An ambiguous prefix (e.g. "мол" for milk and milk jugs) must still
    // match a primary product type, not an ingredient or marketing name.
    val lastTerm = wanted.last()
    if (requestedType == null && lastTerm.length >= 2 && lastTerm !in productTypeByWord) {
        val candidateTypes = productTypeByWord.asSequence()
            .filter { (form, _) -> form.startsWith(lastTerm) }
            .map { it.value }.toSet()
        if (candidateTypes.size > 1 && actualType !in candidateTypes) return false
    }

    fun matches(index: Int, word: String): Boolean {
        val term = wanted[index]
        val type = productTypeByWord[term]
        return word == term ||
            (type != null && productTypeByWord[word] == type) ||
            (index == wanted.lastIndex && term.length >= 2 && word.startsWith(term))
    }
    val substantive = wanted.indices.filter { i ->
        wanted[i] !in ingredientMarkers && wanted[i].any { it.isLetter() }
    }
    val anchors = substantive.ifEmpty { wanted.indices.toList() }
    if (anchors.none { i -> primaryWords(words).any { matches(i, it) } }) return false
    return wanted.indices.all { i -> words.any { matches(i, it) } }
}

internal fun isBarcodeSearch(query: String): Boolean {
    val value = query.trim()
    return value.length in 8..14 && value.all { it in '0'..'9' }
}

internal fun matchesProductSearch(name: String, pack: String, query: String): Boolean =
    matchesProductName(name, query) ||
        (query.any(Char::isDigit) && query.none(Char::isLetter) && matchesProductName(pack, query))
