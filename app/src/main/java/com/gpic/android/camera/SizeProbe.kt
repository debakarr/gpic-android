package com.gpic.android.camera

/**
 * A camera file's real byte size. The DUML manifest stores sizes as u32, so a video over 4 GB (an Action 4
 * shoots that in ~5 min of 4K60) lists as 0 or as its size modulo 4 GB; trusting either downloads nothing
 * ("Local file missing") or a truncated clip. The camera's HTTP server is asked instead. Pure: the link
 * supplies the answers.
 */
object SizeProbe {
    /**
     * [candidates]: sizes in order of trust (Content-Range total, HEAD Content-Length, manifest); <= 0 = unknown.
     * [exists](offset): a 1-byte Range read — true when a byte is there (206), false past the end (416),
     * null when the camera didn't say (busy, ignored the Range).
     *
     * A candidate holds when its last byte exists and the next one doesn't (or the camera won't say; a
     * wrapped size is caught because the byte after it exists). With none holding, the end is found by
     * doubling then halving (~40 one-byte reads for 12 GB). Returns 0 when nothing works.
     */
    fun resolve(candidates: List<Long>, exists: (Long) -> Boolean?): Long {
        val sizes = candidates.filter { it > 0 }.distinct()
        var lo = -1L // highest offset known to hold a byte
        for (c in sizes) {
            val last = exists(c - 1)
            if (last != true) continue
            lo = maxOf(lo, c - 1)
            if (exists(c) != true) return c
        }
        // Without a working Range read there's no way to check: fall back to the most trusted answer.
        val fallback = sizes.maxOrNull() ?: 0L
        if (lo < 0) {
            if (exists(0) != true) return sizes.firstOrNull() ?: 0L
            lo = 0
        }
        var hi = maxOf(lo + 1, GIB)
        while (true) {
            when (exists(hi)) {
                true -> { lo = hi; hi *= 2 }
                false -> break
                null -> return fallback
            }
            if (hi > MAX_BYTES) return fallback
        }
        while (hi - lo > 1) {
            val mid = lo + (hi - lo) / 2
            when (exists(mid)) {
                true -> lo = mid
                false -> hi = mid
                null -> return fallback
            }
        }
        return hi
    }

    /** "bytes 0-0/6312345678" → 6312345678; null when there's no total. */
    fun rangeTotal(contentRange: String?): Long? =
        contentRange?.substringAfterLast('/', "")?.trim()?.toLongOrNull()?.takeIf { it > 0 }

    private const val GIB = 1L shl 30
    private const val MAX_BYTES = 1L shl 40
}
