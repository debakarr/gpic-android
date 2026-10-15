package com.gpic.android.data.albums

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A photo/video in the library that GPic uploaded or matched (see [MediaIndex]). */
data class IndexedMedia(
    val mediaKey: String,
    val name: String,
    /** Capture time (EXIF, else file time), epoch ms; 0 = unknown. */
    val takenMs: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    /** Local copy for thumbnails; may be gone (deleted after upload). */
    val localUri: String? = null,
    /** Album GPic put it in, if any: such items aren't suggested again. */
    val album: String? = null,
)

data class Suggestion(
    /** Stable while the group's first and last items don't change; used to dismiss it. */
    val id: String,
    val items: List<IndexedMedia>,
) {
    val startMs: Long get() = items.first().takenMs
    val endMs: Long get() = items.last().takenMs
    val withLocation: Int get() = items.count { it.lat != null }
}

/**
 * Groups photos into "events" purely from capture times, on the phone: a new group starts after a
 * long quiet gap. Overnight gaps (evening to morning, up to ~15 h) keep a multi-day trip together; a group that still sprawls
 * past [maxSpanMs] (someone who shoots every day) is re-split at shorter gaps.
 */
object AlbumSuggester {
    const val HOUR = 3_600_000L
    const val DAY = 24 * HOUR

    fun suggest(
        items: Collection<IndexedMedia>,
        minSize: Int = 5,
        dismissed: Set<String> = emptySet(),
        gapMs: Long = 18 * HOUR,
        maxSpanMs: Long = 7 * DAY,
        splitGapMs: Long = 3 * HOUR,
    ): List<Suggestion> {
        val pool = items.filter { it.album == null && it.takenMs > 0 }
            .distinctBy { it.mediaKey }
            .sortedBy { it.takenMs }
        return split(pool, gapMs)
            .flatMap { if (it.last().takenMs - it.first().takenMs > maxSpanMs) split(it, splitGapMs) else listOf(it) }
            .filter { it.size >= minSize.coerceAtLeast(2) }
            .map { Suggestion("${it.first().mediaKey}|${it.last().mediaKey}", it) }
            .filter { it.id !in dismissed }
            .sortedByDescending { it.endMs }
    }

    private fun split(sorted: List<IndexedMedia>, gap: Long): List<List<IndexedMedia>> {
        val out = mutableListOf<MutableList<IndexedMedia>>()
        for (m in sorted) {
            val cur = out.lastOrNull()
            if (cur == null || m.takenMs - cur.last().takenMs > gap) out += mutableListOf(m) else cur += m
        }
        return out
    }

    /** Default album name: the dates only (no place lookup, which would send coordinates to a server). */
    fun title(s: Suggestion, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val a = Instant.ofEpochMilli(s.startMs).atZone(zone).toLocalDate()
        val b = Instant.ofEpochMilli(s.endMs).atZone(zone).toLocalDate()
        fun f(p: String) = DateTimeFormatter.ofPattern(p, locale)
        return when {
            a == b -> a.format(f("EEE, d MMM yyyy"))
            a.year == b.year && a.month == b.month -> "${a.dayOfMonth}–${b.format(f("d MMM yyyy"))}"
            a.year == b.year -> "${a.format(f("d MMM"))} – ${b.format(f("d MMM yyyy"))}"
            else -> "${a.format(f("d MMM yyyy"))} – ${b.format(f("d MMM yyyy"))}"
        }
    }
}
