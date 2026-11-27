package com.mok.buylist.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeStyleTest {
    @Test fun successesUseShortDuration() {
        assertEquals(NoticeStyle.SUCCESS, noticeStyle("Добавлено в список"))
        assertEquals(NoticeStyle.SUCCESS, noticeStyle("Данные товара обновлены"))
    }

    @Test fun errorsAndPartialResultsHavePriorityOverSuccess() {
        assertEquals(NoticeStyle.WARNING, noticeStyle("Не удалось сохранить копию"))
        assertEquals(NoticeStyle.WARNING, noticeStyle("Данные товара обновлены · штрихкод отсутствует"))
        assertEquals(NoticeStyle.WARNING, noticeStyle("Данные каталога обновлены: 2 из 3 · ошибок: 1"))
        assertTrue(NoticeStyle.WARNING.durationMillis > NoticeStyle.SUCCESS.durationMillis)
    }

    @Test fun neutralMessagesAreNotErrors() {
        assertEquals(NoticeStyle.INFO, noticeStyle("Уже в списке"))
    }
}
