package com.gpic.android.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoEditTest {
    private fun clip(seconds: Int, peakAt: Int? = null) =
        ClipScores(seconds * 1000L, 1000, FloatArray(seconds) { i -> if (peakAt != null && i in peakAt..peakAt + 2) 1f else 0.1f })

    @Test fun picksTheLivelyWindowInTimeOrder() {
        val parts = AutoEdit.windows(clip(60, peakAt = 40), n = 2, cut = 3_000)
        assertEquals(2, parts.size)
        assertTrue("the peak is used: $parts", parts.any { (s, e) -> s <= 40_000 && e >= 42_000 })
        assertTrue(parts.zipWithNext().all { (a, b) -> a.second <= b.first })
    }

    @Test fun sharesCutsBetweenClipsByLengthAndKeepsClipOrder() {
        val parts = AutoEdit.pick(listOf(clip(120), clip(40), clip(40)), EditStyle.TRAVEL, targetMs = 30_000)
        val perClip = parts.groupingBy { it.clip }.eachCount()
        assertEquals(setOf(0, 1, 2), perClip.keys)
        assertTrue("longest clip gets most: $perClip", perClip.getValue(0) >= perClip.getValue(1))
        assertEquals(parts.map { it.clip }, parts.map { it.clip }.sorted())
        assertTrue(parts.all { it.durationMs == EditStyle.TRAVEL.cutMs })
        val total = parts.sumOf { it.durationMs }
        assertTrue("about 30 s: $total", total in 24_000..36_000)
    }

    @Test fun shortClipsAreUsedWholeAndTinyTargetsKeepTheBestClip() {
        val whole = AutoEdit.pick(listOf(clip(1)), EditStyle.CINEMATIC, 15_000)
        assertEquals(listOf(Triple(0, 0L, 1_000L)), whole.map { Triple(it.clip, it.startMs, it.endMs) })
        val one = AutoEdit.pick(listOf(clip(30), clip(30, peakAt = 10)), EditStyle.CINEMATIC, 4_000)
        assertEquals(listOf(1), one.map { it.clip })
    }

    @Test fun beatCutsAreWholeBeats() {
        // 120 BPM = 500 ms a beat: Action's 1.5 s becomes 3 beats; Travel's 3 s becomes 6.
        assertEquals(1_500, AutoEdit.beatCutMs(EditStyle.ACTION, 120f))
        assertEquals(3_000, AutoEdit.beatCutMs(EditStyle.TRAVEL, 120f))
        // 100 BPM = 600 ms: 1.5 s rounds to 2 or 3 beats, either way a multiple of 600.
        assertEquals(0, AutoEdit.beatCutMs(EditStyle.ACTION, 100f) % 600)
        assertEquals(EditStyle.REEL.cutMs, AutoEdit.beatCutMs(EditStyle.REEL, 0f))
    }

    @Test fun styleSetsLookTransitionsAndMix() {
        val music = MusicTrack("u", "song", beatsMs = listOf(300, 800, 1300), startMs = 0)
        val parts = listOf(Segment(0, 2_000, 0), Segment(0, 2_000, 1))
        val p = AutoEdit.plan(EditPlan(music = music), parts, EditStyle.REEL)
        assertEquals(Aspect.VERTICAL, p.aspect)
        assertEquals(Canvas.BLUR, p.canvas)
        assertEquals(Filter.VIVID, p.look.filter)
        assertEquals(listOf(EditStyle.REEL.transition, Transition.NONE), p.segments.map { it.transition })
        assertEquals(EditStyle.REEL.cameraVolume, p.originalVolume)
        assertEquals(300L, p.music!!.startMs) // starts on the first beat
        assertEquals(1f, AutoEdit.plan(EditPlan(), parts, EditStyle.REEL).originalVolume)
    }
}
