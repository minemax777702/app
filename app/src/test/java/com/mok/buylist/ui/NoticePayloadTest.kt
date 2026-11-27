package com.mok.buylist.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NoticePayloadTest {
    @Test fun productUpdateSeparatesTitleFromDetails() {
        val notice = noticePayload(1, "Данные товара обновлены · штрихкод отсутствует · ручная цена сохранена")
        assertEquals("Товар обновлён", notice.title)
        assertEquals("Штрихкод отсутствует · ручная цена сохранена", notice.message)
        assertEquals(NoticeStyle.WARNING, notice.style)
    }

    @Test fun batchUpdateKeepsCounts() {
        val notice = noticePayload(2, "Данные каталога обновлены: 9 из 10 · ошибок: 1")
        assertEquals("Каталог обновлён", notice.title)
        assertEquals("Обновлено 9 из 10 · ошибок: 1", notice.message)
        assertEquals(NoticeStyle.WARNING, notice.style)
    }

    @Test fun stoppedBatchKeepsCounts() {
        val notice = noticePayload(3, "Обновление остановлено: 38 из 128")
        assertEquals("Обновление остановлено", notice.title)
        assertEquals("Обновлено 38 из 128", notice.message)
        assertEquals(NoticeStyle.INFO, notice.style)
    }

    @Test fun legacyMessagesHaveFallback() {
        val notice = noticePayload(4, "Не удалось обновить товар")
        assertEquals("Внимание", notice.title)
        assertEquals("Не удалось обновить товар", notice.message)
        assertEquals(NoticeStyle.WARNING, notice.style)
    }
}
