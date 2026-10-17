package com.gpic.android.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TimelineTest {
    private val durations = listOf(10_000L, 6_000L)

    @Test fun outputLengthFollowsSpeedAndCurves() {
        assertEquals(4_000, Segment(0, 8_000, speed = 2f).outputMs)
        assertEquals(8_000, Segment(0, 2_000, speed = 0.25f).outputMs)
        // A ramp that averages > 1× makes the part shorter; Hero's slow middle makes it longer than at 1.5×.
        val hero = Segment(0, 6_000, curve = SpeedCurve.HERO).outputMs
        assertTrue("$hero", hero in 4_100..9_000)
        assertTrue(Segment(0, 6_000, curve = SpeedCurve.FLASH_IN).outputMs < 6_000)
    }

    @Test fun clipTimeAndOutputOffsetAreInverse() {
        for (s in listOf(Segment(1_000, 7_000, speed = 1.5f), Segment(2_000, 9_000, curve = SpeedCurve.BULLET), Segment(0, 5_000, curve = SpeedCurve.FLASH_OUT, speed = 0.5f))) {
            for (out in listOf(0L, 300L, s.outputMs / 3, s.outputMs / 2, s.outputMs - 1)) {
                val clip = Timeline.clipTime(s, out)
                // Clip times are whole ms, so at 0.25× the round trip can be off by a few ms.
                assertTrue("$s @$out", abs(Timeline.outputOffset(s, clip) - out) <= 5)
            }
            assertEquals(s.endMs, Timeline.clipTime(s, s.outputMs + 5_000))
        }
    }

    @Test fun locateFindsThePartUnderThePlayhead() {
        val parts = listOf(Segment(0, 4_000, 0), Segment(1_000, 3_000, 1, speed = 2f), Segment(5_000, 7_000, 0))
        assertEquals(listOf(0L, 4_000L, 5_000L, 7_000L), Timeline.starts(parts))
        assertEquals(0 to 2_000L, Timeline.locate(parts, 2_000))
        assertEquals(1 to 2_000L, Timeline.locate(parts, 4_500))
        assertEquals(2 to 6_000L, Timeline.locate(parts, 6_000))
        assertEquals(2, Timeline.locate(parts, 99_000)!!.first)
    }

    @Test fun splitMakesTwoPartsThatTogetherAreTheOriginal() {
        val plan = EditPlan(segments = listOf(Segment(0, 10_000, 0, transition = Transition.FADE_BLACK), Segment(0, 6_000, 1)))
        val (split, newId) = Timeline.split(plan, durations, 4_000)!!
        assertEquals(3, split.segments.size)
        val (a, b) = split.segments
        assertEquals(0L to 4_000L, a.startMs to a.endMs)
        assertEquals(4_000L to 10_000L, b.startMs to b.endMs)
        assertEquals(newId, b.id)
        // The transition stays on the cut it was on (after the original part).
        assertEquals(Transition.NONE, a.transition)
        assertEquals(Transition.FADE_BLACK, b.transition)
        assertNull(Timeline.split(plan, durations, 100)) // too close to an edge
    }

    @Test fun editsWorkOnImplicitWholeClips() {
        val plan = EditPlan()
        val m = Timeline.materialize(plan, durations)
        val dup = Timeline.duplicate(m, durations, m.segments[0].id)
        assertEquals(3, dup.first.segments.size)
        val moved = Timeline.move(m, durations, m.segments[1].id, 0)
        assertEquals(listOf(1, 0), moved.segments.map { it.clip })
        val del = Timeline.delete(m, durations, m.segments[0].id)
        assertEquals(listOf(1), del.segments.map { it.clip })
        assertEquals(1, Timeline.delete(del, durations, del.segments[0].id).segments.size) // never empty
    }

    @Test fun trimStaysInsideTheClipAndLongEnough() {
        val m = Timeline.materialize(EditPlan(), durations)
        val id = m.segments[1].id
        val t = Timeline.trim(m, durations, id, startMs = -500, endMs = 99_000).segments[1]
        assertEquals(0L to 6_000L, t.startMs to t.endMs)
        val t2 = Timeline.trim(m, durations, id, startMs = 5_900).segments[1]
        assertTrue(t2.durationMs >= EditPlan.MIN_SEGMENT_MS)
    }

    @Test fun snapToBeatsMovesCutsOntoBeats() {
        val plan = EditPlan(segments = listOf(Segment(0, 2_300, 0), Segment(0, 1_900, 1), Segment(3_000, 6_000, 0)))
        val beats = (0..40).map { it * 500L }
        val snapped = Timeline.snapToBeats(plan, durations, beats)
        val starts = Timeline.starts(snapped.segments)
        assertEquals(2_500L, starts[1])
        assertEquals(4_500L, starts[2])
        assertNotNull(snapped.segments.last())
        assertEquals(plan.segments.last().durationMs, snapped.segments.last().durationMs) // the last part keeps its length
    }

    @Test fun curveStepsCoverThePart() {
        val s = Segment(1_000, 5_800, curve = SpeedCurve.MONTAGE)
        val steps = Timeline.speedSteps(s)
        assertEquals(Timeline.CURVE_STEPS, steps.size)
        assertEquals(0L, steps.first().first)
        assertTrue(steps.all { it.second in 0.1f..10f })
    }
}
