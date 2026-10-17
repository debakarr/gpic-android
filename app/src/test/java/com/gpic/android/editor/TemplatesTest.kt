package com.gpic.android.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TemplatesTest {
    private fun frame(t: Long, luma: Float = 0.5f, colour: Float = 0.5f, action: Float = 0.2f, dx: Float = 0f) =
        FrameStat(t, luma, 0.2f, 0.02f, colour, dx, 0f, action, 0.02f, 0, 0f, FloatArray(80) { (t / 500 % 7).toFloat() + it % 3 })

    @Test fun slotPlanRepeatsTheRhythmToTheTarget() {
        val plan = VideoTemplate.BEAT_DROP.slotPlan(targetMs = 15_000, beatMs = 500)
        val total = plan.sumOf { it.second }
        assertTrue("$total", total in 15_000..17_000)
        assertEquals(VideoTemplate.BEAT_DROP.slots[0], plan[0].first)
        assertEquals(VideoTemplate.BEAT_DROP.slots[0], plan[VideoTemplate.BEAT_DROP.slots.size].first)
        // One-beat slots at a fast tempo are stretched to at least 0.7 s, in whole beats.
        assertTrue(VideoTemplate.PARTY.slotPlan(10_000, 300).all { it.second >= 700 && it.second % 300 == 0L })
    }

    @Test fun curveSourceLengthFillsTheSlot() {
        for (c in SpeedCurve.entries) {
            val src = MusicPicker.sourceMs(4_000, 1f, c)
            val out = Segment(0, src, curve = c).outputMs
            assertTrue("$c: $src → $out", abs(out - 4_000) < 120)
        }
        assertEquals(1_000L, MusicPicker.sourceMs(2_000, 0.5f, SpeedCurve.NONE))
    }

    @Test fun photosFillLongSlotsWithMotion() {
        val photos = (0 until 3).map { i -> ClipAnalysis(i, VideoProbe.IMAGE_MAX_MS, 30f, listOf(frame(0, colour = 0.3f + i * 0.2f)), isPhoto = true) }
        val slots = VideoTemplate.MEMORIES.slotPlan(12_000, 600)
        val r = SmartEdit.pickSlots(photos, slots, Arrange.STORY)
        assertEquals(3, r.parts.size)
        assertTrue(r.parts.all { it.durationMs == slots[0].second })
        assertTrue(r.parts.all { it.motion != Motion.NONE })
    }

    @Test fun moodFollowsTheFootage() {
        fun clip(vararg f: FrameStat) = ClipAnalysis(0, 5_000, 30f, f.toList())
        assertEquals("energetic", MusicPicker.mood(listOf(clip(frame(0, action = 0.6f), frame(500, action = 0.6f))), false))
        assertEquals("ambient", MusicPicker.mood(listOf(clip(frame(0, luma = 0.15f), frame(500, luma = 0.15f))), false))
        assertEquals("piano", MusicPicker.mood(listOf(clip(frame(0, colour = 0.2f))), true))
        assertEquals("happy acoustic", MusicPicker.mood(listOf(clip(frame(0, luma = 0.6f, colour = 0.6f))), true))
    }

    @Test fun choosesATrackThatCoversTheVideo() {
        fun t(id: String, ms: Long, lic: String = "by") = OpenTrack(id, id, "a", lic, "4.0", "", "https://x/$id", "", ms, "jamendo", emptyList(), "")
        val tracks = listOf(t("short", 15_000), t("mix", 900_000), t("sa", 120_000, "by-sa"), t("good", 150_000))
        assertEquals("good", MusicPicker.choose(tracks, 30_000)!!.id)
        assertEquals("sa", MusicPicker.choose(tracks, 30_000, skip = setOf("good"))!!.id)
    }
}
