package com.mok.buylist.ui

/** Only the pop-up queue is bounded; the persistent journal is managed separately. */
internal const val MAX_PENDING_NOTICES = 10

internal data class NoticePresentation(
    val current: NoticePayload? = null,
    val expanded: Boolean = false,
    val pending: List<NoticePayload> = emptyList(),
) {
    fun enqueue(notice: NoticePayload): NoticePresentation =
        if (current != null && (expanded || pending.isNotEmpty())) {
            copy(pending = (pending + notice).takeLast(MAX_PENDING_NOTICES))
        } else {
            NoticePresentation(current = notice)
        }

    fun toggle(id: Long): NoticePresentation =
        if (current?.id == id) copy(expanded = !expanded) else this

    fun dismiss(id: Long): NoticePresentation =
        if (current?.id == id) {
            NoticePresentation(current = pending.firstOrNull(), pending = pending.drop(1))
        } else this
}
