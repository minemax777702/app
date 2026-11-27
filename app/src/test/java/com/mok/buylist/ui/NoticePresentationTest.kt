package com.mok.buylist.ui

import org.junit.Assert.*
import org.junit.Test

class NoticePresentationTest {
    private fun notice(id: Long) = NoticePayload(id, "Title", "Message", NoticeStyle.INFO)

    @Test fun readingIsNotInterruptedByNewNotifications() {
        val pinned = NoticePresentation().enqueue(notice(1)).toggle(1).enqueue(notice(2)).enqueue(notice(3))
        assertTrue(pinned.expanded)
        assertEquals(1L, pinned.current?.id)
        assertEquals(listOf(2L, 3L), pinned.pending.map { it.id })
        val next = pinned.dismiss(1)
        assertFalse(next.expanded)
        assertEquals(2L, next.current?.id)
        assertEquals(3L, next.dismiss(2).current?.id)
    }

    @Test fun togglingCollapsesAndOldTimersCannotDismissNewNotices() {
        val collapsed = NoticePresentation().enqueue(notice(1)).toggle(1).toggle(1)
        assertFalse(collapsed.expanded)
        val newer = collapsed.enqueue(notice(2))
        assertEquals(newer, newer.dismiss(1))
        assertNull(newer.dismiss(2).current)
    }

    @Test fun queueIsBoundedWithoutChangingPinnedMessage() {
        var state = NoticePresentation().enqueue(notice(1)).toggle(1)
        for (id in 2L..30L) state = state.enqueue(notice(id))
        assertEquals(1L, state.current?.id)
        assertEquals(MAX_PENDING_NOTICES, state.pending.size)
        assertEquals(30L, state.pending.last().id)
    }
}
