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

    @Test fun mainProductTypeExcludesMarketingNames() {
        assertFalse(matchesProductName("Конфеты Птичье молоко", "Молоко"))
        assertFalse(matchesProductName("Вафли Топлёное молоко", "Молоко"))
        assertFalse(matchesProductName("Конфеты «Молоко»", "мол"))
        assertFalse(matchesProductName("«Птичье молоко» конфеты", "молоко"))
        assertFalse(matchesProductName("Птичье молоко конфеты", "молоко"))
        assertFalse(matchesProductName("Напиток молоко кокосовое", "молоко"))
        assertTrue(matchesProductName("Молоко топлёное 1 л", "Молоко"))
        assertTrue(matchesProductName("Топлёное молоко 1 л", "Молоко"))
        assertTrue(matchesProductName("Простоквашино молоко 1 л", "молоко"))
        assertTrue(matchesProductName("Молоко «Простоквашино» 1 л", "простоквашино"))
        assertTrue(matchesProductName("Конфеты Птичье молоко", "Конфеты молоко"))
        assertTrue(matchesProductName("Конфеты с молоком", "Конфеты молоко"))
        assertTrue(matchesProductName("Вафли Топлёное молоко", "вафли молоко"))
        assertTrue(matchesProductName("«Птичье молоко» конфеты", "конфеты молоко"))
        assertTrue(matchesProductName("Птичье молоко конфеты", "конфеты молоко"))
        assertTrue(matchesProductName("Конфеты Птичье молоко", "птичье молоко"))
        assertFalse(matchesProductName("Чипсы Сыр", "сыр"))
        assertTrue(matchesProductName("Чипсы Сыр", "чипсы сыр"))
        assertTrue(matchesProductName("Гречка ядрица", "греч"))
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
