package com.mok.buylist.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NoticePayloadTest {
    @Test fun productUpdateSeparatesTitleFromDetails() {
        val notice = noticePayload(1, "Данные товара обновлены · штрихкод отсутствует · ручная цена сохранена")
        assertEquals("Товар обновлён", notice.title)
        assertEquals("Штрихкод не найден.\nВаша цена не изменена.", notice.message)
        assertEquals(NoticeStyle.WARNING, notice.style)
    }

    @Test fun batchUpdateKeepsCounts() {
        val notice = noticePayload(2, "Данные каталога обновлены: 9 из 10 · ошибок: 1")
        assertEquals("Каталог обновлён", notice.title)
        assertEquals("Обновлено 9 из 10\nОшибок: 1", notice.message)
        assertEquals(NoticeStyle.WARNING, notice.style)
    }

    @Test fun stoppedBatchKeepsCounts() {
        val notice = noticePayload(3, "Обновление остановлено: 38 из 128")
        assertEquals("Обновление остановлено", notice.title)
        assertEquals("Обновлено 38 из 128", notice.message)
        assertEquals(NoticeStyle.INFO, notice.style)
    }

    @Test fun batchDetailsUseSeparateLines() {
        val notice = noticePayload(5, "Данные каталога обновлены: 9 из 10 · ошибок: 1 · без штрихкода: 3 · без новой цены: 2")
        assertEquals("Обновлено 9 из 10\nОшибок: 1\nБез штрихкода: 3\nБез новой цены: 2", notice.message)
    }

    @Test fun missingPriceUsesReadableSentence() {
        val notice = noticePayload(6, "Данные товара обновлены · цена недоступна")
        assertEquals("Новая цена недоступна.", notice.message)
    }

    @Test fun legacyMessagesHaveFallback() {
        val notice = noticePayload(4, "Не удалось обновить товар")
        assertEquals("Внимание", notice.title)
        assertEquals("Не удалось обновить товар", notice.message)
        assertEquals(NoticeStyle.WARNING, notice.style)
    }
}
