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
        assertEquals(listOf(Segment(0, 1_000, 0)), whole)
        val one = AutoEdit.pick(listOf(clip(30), clip(30, peakAt = 10)), EditStyle.CINEMATIC, 4_000)
        assertEquals(listOf(1), one.map { it.clip })
    }

    @Test fun styleSetsTheLook() {
        val p = AutoEdit.plan(EditPlan(), listOf(Segment(0, 2_000, 0)), EditStyle.REEL, hasMusic = true)
        assertEquals(Aspect.VERTICAL, p.aspect)
        assertEquals(EditStyle.REEL.cameraVolume, p.originalVolume)
        assertEquals(1f, AutoEdit.plan(EditPlan(), emptyList(), EditStyle.REEL, hasMusic = false).originalVolume)
    }
}
