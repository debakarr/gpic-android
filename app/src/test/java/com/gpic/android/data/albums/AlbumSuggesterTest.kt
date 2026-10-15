package com.gpic.android.data.albums

import com.gpic.android.data.albums.AlbumSuggester.DAY
import com.gpic.android.data.albums.AlbumSuggester.HOUR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class AlbumSuggesterTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(y: Int, mo: Int, d: Int, h: Int, min: Int = 0) =
        LocalDateTime.of(y, mo, d, h, min).atZone(zone).toInstant().toEpochMilli()
    private var n = 0
    private fun m(ms: Long, album: String? = null) = IndexedMedia("k${n++}", "p$n.jpg", ms, album = album)

    @Test fun overnightGapsKeepATripTogether() {
        val trip = (0 until 3).flatMap { day -> (0 until 4).map { i -> m(at(2025, 3, 3 + day, 9 + i * 3)) } }
        val lone = listOf(m(at(2025, 4, 1, 12)))
        val s = AlbumSuggester.suggest(trip + lone, minSize = 5)
        assertEquals(1, s.size)
        assertEquals(12, s[0].items.size)
    }

    @Test fun smallGroupsAndAlbumedItemsAreLeftOut() {
        val small = (0 until 4).map { m(at(2025, 5, 1, 10 + it)) }
        val done = (0 until 6).map { m(at(2025, 6, 1, 10 + it), album = "June") }
        assertTrue(AlbumSuggester.suggest(small + done, minSize = 5).isEmpty())
        assertEquals(1, AlbumSuggester.suggest(small, minSize = 3).size)
    }

    @Test fun dailyShooterIsResplitAtShorterGaps() {
        // A photo every 8 h for 10 days chains into one 10-day run; re-split at 3 h gives singles.
        val chain = (0 until 30).map { m(at(2025, 7, 1, 0) + it * 8 * HOUR) }
        assertTrue(AlbumSuggester.suggest(chain, minSize = 5).isEmpty())
        // ...but a burst inside it (6 photos within an hour) still surfaces.
        val burst = (0 until 6).map { m(at(2025, 7, 5, 14) + it * 10 * 60_000L) }
        val s = AlbumSuggester.suggest(chain + burst, minSize = 5)
        assertEquals(1, s.size)
        assertTrue(s[0].items.containsAll(burst))
    }

    @Test fun dismissedAndNewestFirst() {
        val a = (0 until 5).map { m(at(2024, 1, 1, 10 + it)) }
        val b = (0 until 5).map { m(at(2025, 1, 1, 10 + it)) }
        val s = AlbumSuggester.suggest(a + b, minSize = 5)
        assertEquals(listOf(b.first().mediaKey, a.first().mediaKey), s.map { it.items.first().mediaKey })
        assertEquals(1, AlbumSuggester.suggest(a + b, minSize = 5, dismissed = setOf(s[0].id)).size)
    }

    @Test fun duplicateKeysCountOnce() {
        val one = m(at(2025, 2, 2, 10))
        val copies = List(6) { one.copy(name = "copy$it.jpg") }
        assertTrue(AlbumSuggester.suggest(copies, minSize = 2).isEmpty())
    }

    @Test fun titles() {
        fun t(a: Long, b: Long) = AlbumSuggester.title(Suggestion("x", listOf(m(a), m(b))), zone, Locale.UK)
        assertEquals("Mon, 3 Mar 2025", t(at(2025, 3, 3, 9), at(2025, 3, 3, 20)))
        assertEquals("3–5 Mar 2025", t(at(2025, 3, 3, 9), at(2025, 3, 5, 20)))
        assertEquals("28 Feb – 2 Mar 2025", t(at(2025, 2, 28, 9), at(2025, 3, 2, 20)))
        assertEquals("30 Dec 2024 – 2 Jan 2025", t(at(2024, 12, 30, 9), at(2025, 1, 2, 20)))
        assertTrue(DAY > 0)
    }
}
