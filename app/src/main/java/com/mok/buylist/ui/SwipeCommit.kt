package com.mok.buylist.ui

internal const val SWIPE_REMOVE_FRACTION = 0.55f

/** Negative = remove, positive = buy; a short or invalid drag never commits. */
internal fun resolveSwipeCommit(offset: Float, width: Int): Int {
    if (width <= 0 || !offset.isFinite()) return 0
    val threshold = width * SWIPE_REMOVE_FRACTION
    return when {
        offset <= -threshold -> -1
        offset >= threshold -> 1
        else -> 0
    }
}
