package com.mok.buylist.data

import org.junit.Assert.*
import org.junit.Test

class ProductSearchMatchTest {
    @Test fun ingredientOnlyMatchesAreExcluded() {
        assertFalse(matchesProductName("Печенье со сгущённым молоком", "молоко"))
        assertFalse(matchesProductName("Шоколад с молоком", "молоко"))
        assertFalse(matchesProductName("Конфеты со вкусом молока", "молоко"))
        assertTrue(matchesProductName("Молоко пастеризованное 1 л", "молоко"))
        assertTrue(matchesProductName("Сгущённое молоко", "молоко"))
        assertTrue(matchesProductName("Молоко без лактозы", "молоко"))
        assertTrue(matchesProductName("Молоко 1,4 л", "молоко 1,4"))
    }

    @Test fun prefixAndExplicitQualifierQueriesRemainAvailable() {
        assertTrue(matchesProductName("Молоко пастеризованное", "мол"))
        assertTrue(matchesProductName("Печенье со сгущённым молоком", "печенье"))
        assertTrue(matchesProductName("Печенье со сгущённым молоком", "печенье со сгущенным молоком"))
        assertFalse(matchesProductName("Сухарики с сыром", "сыр"))
        assertTrue(matchesProductName("Сыр Российский", "сыр"))
        assertFalse(matchesProductName("Кисель", "ель"))
    }
}
