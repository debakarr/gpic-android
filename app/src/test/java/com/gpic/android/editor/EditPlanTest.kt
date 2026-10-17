package com.gpic.android.editor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditPlanTest {
    private val luts = setOf("dji", LutStore.VIVID_ID)
    private fun spans(p: EditPlan) = p.segments.map { Triple(it.clip, it.startMs, it.endMs) }

    @Test fun segmentsAreClampedSliversDroppedAndOrderKept() {
        val p = EditPlan(segments = listOf(Segment(8_000, 12_000), Segment(-500, 2_000), Segment(5_000, 5_100), Segment(9_000, 99_000)))
            .sanitized(20_000, luts)
        // The user's order stays (parts can be reordered and repeated now).
        assertEquals(listOf(Triple(0, 8_000L, 12_000L), Triple(0, 0L, 2_000L), Triple(0, 9_000L, 20_000L)), spans(p))
        assertEquals(17_000, p.outputMs(20_000))
    }

    @Test fun lastPartHasNoTransition() {
        val p = EditPlan(segments = listOf(Segment(0, 2_000, transition = Transition.FADE_BLACK), Segment(2_000, 4_000, transition = Transition.SPIN)))
            .sanitized(10_000, luts)
        assertEquals(listOf(Transition.FADE_BLACK, Transition.NONE), p.segments.map { it.transition })
    }

    @Test fun numbersAreClampedAndUnknownLutDropped() {
        val p = EditPlan(
            segments = listOf(Segment(0, 10_000, speed = 9f, volume = -1f, rotation = 45, look = Look(lutId = "made-up", exposure = 4f))),
            look = Look(lutId = "dji", contrast = -3f, vignette = 2f, filterAmount = Float.NaN),
            originalVolume = Float.NaN, maxHeight = 480, fps = 29,
        ).sanitized(10_000, luts)
        val s = p.segments.single()
        assertEquals(EditPlan.MAX_SPEED, s.speed)
        assertEquals(0f, s.volume)
        assertEquals(0, s.rotation)
        assertNull(s.look!!.lutId)
        assertEquals(1f, s.look!!.exposure)
        assertEquals("dji", p.look.lutId)
        assertEquals(-1f, p.look.contrast)
        assertEquals(1f, p.look.vignette)
        assertEquals(1f, p.look.filterAmount)
        assertEquals(1f, p.originalVolume)
        assertEquals(0, p.maxHeight)
        assertEquals(0, p.fps)
        assertEquals(2_500, p.outputMs(10_000))
    }

    @Test fun textsAreClampedIntoTheVideo() {
        val p = EditPlan(texts = listOf(TextItem("hi", -500, 99_000, x = 3f, size = 9f), TextItem("  ", 0, 1_000)))
            .sanitized(10_000, luts)
        val t = p.texts.single()
        assertEquals(0L, t.startMs)
        assertEquals(10_000L, t.endMs)
        assertEquals(1f, t.x)
        assertEquals(0.4f, t.size)
    }

    @Test fun jsonRoundTripKeepsEverythingButIds() {
        val plan = EditPlan(
            segments = listOf(
                Segment(1_000, 4_500, 1, speed = 2f, curve = SpeedCurve.HERO, volume = 0.5f, motion = Motion.PAN_LEFT, rotation = 90, mirror = true,
                    transition = Transition.SLIDE_UP, transitionMs = 800, look = Look(filter = Filter.MONO, filterAmount = 0.5f, temperature = 0.3f)),
                Segment(0, 2_000, 0),
            ),
            look = Look(lutId = "dji", exposure = 0.2f, vignette = 0.4f),
            music = MusicTrack("file:///m.mp3", "Song", "\"Song\" by X is licensed under CC BY 4.0", 180_000, 0.7f, 12_000, 500, 2_000, listOf(500, 1000), 120f),
            texts = listOf(TextItem("Goa", 0, 2_000, color = 0xFFFF9800.toInt(), style = TextStyle.NEON, font = TextFont.SCRIPT, animIn = TextAnim.TYPEWRITER)),
            aspect = Aspect.VERTICAL, canvas = Canvas.BLUR, maxHeight = 720, fps = 30,
        )
        val back = EditPlan.fromJson(JSONObject(plan.toJson().toString()))
        fun noIds(p: EditPlan) = p.copy(segments = p.segments.map { it.copy(id = 0) }, texts = p.texts.map { it.copy(id = 0) })
        assertEquals(noIds(plan), noIds(back))
    }

    @Test fun multiClipPartsKeepOrderAndDropUnknownClips() {
        val durations = listOf(10_000L, 20_000L)
        val p = EditPlan(segments = listOf(Segment(15_000, 18_000, 1), Segment(2_000, 4_000, 0), Segment(0, 5_000, 7), Segment(-1, 50_000, 1)))
            .sanitized(durations, luts)
        assertEquals(listOf(Triple(1, 15_000L, 18_000L), Triple(0, 2_000L, 4_000L), Triple(1, 0L, 20_000L)), spans(p))
        assertEquals(25_000, p.outputMs(durations))
        assertEquals(2, EditPlan().parts(durations).size)
        assertTrue(EditPlan().sanitized(durations, luts).segments.isEmpty())
    }
}
