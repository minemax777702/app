package com.mok.buylist.cloud

/** Reveals a navigation item only, never authenticates an administrator. */
internal class CloudAdminGesture {
    private var count = 0
    private var start = -1L
    private var last = -1L

    fun tap(now: Long): Boolean {
        if (start < 0 || now < last || now - last > 1200 || now - start > 6000) reset()
        if (count == 0) start = now
        last = now
        count++
        if (count < 7) return false
        reset()
        return true
    }

    fun reset() {
        count = 0
        start = -1L
        last = -1L
    }
}
