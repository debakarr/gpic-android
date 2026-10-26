package com.gpic.android.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln

class TransitionMathTest {
    private val dur = 3_000_000L

    private fun state(e: PartEdges, t: Long, prev: Boolean = false, w: Int = 1920, h: Int = 1080, d: Long = dur) =
        TransitionMath.state(e, t, d, w, h, prev)

    /** Log of the picture's scale at [t] (the sampling shrinks as the picture grows), between shutter open and close. */
    private fun zoom(e: PartEdges, t: Long) = state(e, t).let { (ln(1.0 / it.live0.a) + ln(1.0 / it.live1.a)) / 2 }

    @Test fun zoomIsFastestAtTheCutAndCarriesThroughIt() {
        val (out, inc) = TransitionMath.edges(listOf(Segment(0, 3_000, 0, transition = Transition.ZOOM_IN), Segment(0, 3_000, 1)))
        assertEquals(300_000L, out.outUs)
        assertEquals(300_000L, inc.inUs)
        // Zooming in all the way: into the cut on the old part, out of the opposite extreme on the new one.
        assertTrue(zoom(out, 2_800_000) < zoom(out, 2_950_000))
        assertTrue(zoom(inc, 50_000) < zoom(inc, 250_000))
        assertEquals(0.0, zoom(inc, 1_000_000), 1e-6)
        // The speed carries through the cut instead of stopping at it, and it's highest there.
        val before = (zoom(out, 2_995_000) - zoom(out, 2_975_000)) / 20
        val after = (zoom(inc, 25_000) - zoom(inc, 5_000)) / 20
        assertTrue("$before vs $after", before > 0 && after > 0 && abs(before - after) / before < 0.25)
        val earlier = (zoom(out, 2_860_000) - zoom(out, 2_840_000)) / 20
        assertTrue(earlier < before / 2)
    }

    @Test fun movesBlurMostAtTheCutAndNotAtAllAwayFromIt() {
        val (out, inc) = TransitionMath.edges(listOf(Segment(0, 3_000, 0, transition = Transition.WHIP_LEFT), Segment(0, 3_000, 1)))
        assertTrue(state(out, dur).taps >= 8)
        assertTrue(state(inc, 0).taps >= 8)
        assertEquals(1, state(out, 1_000_000).taps)
        // The picture moves left on both sides: the point shown in the middle comes from further right over time.
        assertTrue(state(out, 2_990_000).live0.x(0.5f, 0.5f) > state(out, 2_900_000).live0.x(0.5f, 0.5f))
        assertTrue(state(inc, 100_000).live0.x(0.5f, 0.5f) > state(inc, 10_000).live0.x(0.5f, 0.5f))
    }

    @Test fun spinTurnsThePictureWithoutStretchingIt() {
        val (out, _) = TransitionMath.edges(listOf(Segment(0, 3_000, 0, transition = Transition.SPIN), Segment(0, 3_000, 1)))
        val g = state(out, 2_950_000).live0
        // In pixels the sampling must be a turn and a scale (no shear), even though the frame is 16:9.
        val a = g.a; val b = g.b * 1920 / 1080; val c = g.c * 1080 / 1920; val d = g.d
        assertTrue(abs(b) > 0.1f)
        assertEquals(a, d, 1e-4f)
        assertEquals(b, -c, 1e-4f)
    }

    @Test fun slidePushesTheLastFrameOutWithTheNewPartRightBehindIt() {
        val edges = TransitionMath.edges(listOf(Segment(0, 3_000, 0, transition = Transition.SLIDE_LEFT, transitionMs = 600), Segment(0, 3_000, 1)))
        val inc = edges[1]
        assertEquals(0L, edges[0].outUs)
        assertTrue(edges[0].keepsLastFrame)
        assertEquals(600_000L, inc.inUs)
        val start = state(inc, 0, prev = true)
        assertEquals(1f, start.prevAlpha)
        assertTrue(start.prevClipped)
        assertEquals(0.3f, start.prev0.x(0.3f, 0.5f), 1e-3f) // still exactly where it was
        val mid = state(inc, 300_000, prev = true)
        for (u in listOf(0f, 0.3f, 0.7f, 1f)) assertEquals(1f, mid.prev0.x(u, 0.5f) - mid.live0.x(u, 0.5f), 1e-3f) // a frame apart, no gap
        assertTrue(mid.prev0.x(0.5f, 0.5f) > 0.7f) // well on its way out to the left
        assertTrue(mid.taps > 4)
        val end = state(inc, 600_000, prev = true)
        assertEquals(0f, end.prevAlpha)
        assertEquals(0.5f, end.live0.x(0.5f, 0.5f), 1e-4f)
        assertEquals(1, end.taps)
        // Without the old frame (a seek into the transition in the preview) it's a plain cut.
        val cut = state(inc, 100_000, prev = false)
        assertEquals(0f, cut.prevAlpha)
        assertEquals(0.5f, cut.live0.x(0.5f, 0.5f), 1e-4f)
    }

    @Test fun dissolveFadesTheLastFrameOutWhileItKeepsMoving() {
        val (out, inc) = TransitionMath.edges(listOf(
            Segment(0, 3_000, 0, motion = Motion.ZOOM_IN, transition = Transition.DISSOLVE, transitionMs = 800), Segment(0, 3_000, 1, motion = Motion.PAN_LEFT),
        ))
        assertTrue(out.keepsLastFrame)
        assertEquals(800_000L, inc.inUs)
        val alphas = (0..8).map { state(inc, it * 100_000L, prev = true).prevAlpha }
        assertEquals(1f, alphas.first(), 1e-4f)
        assertEquals(0f, alphas.last(), 1e-4f)
        assertTrue(alphas.zipWithNext().all { (x, y) -> y <= x })
        assertFalse(state(inc, 0, prev = true).prevClipped)
        // The photo under the dissolve goes on zooming in from where it was.
        val z0 = 1 / state(inc, 0, prev = true).prev0.a
        val z1 = 1 / state(inc, 400_000, prev = true).prev0.a
        assertEquals(1.15f, z0, 0.002f)
        assertTrue(z1 > z0)
    }

    @Test fun aLingeringFrameKeepsItsMoveWithoutShowingEdges() {
        for (m in Motion.entries) {
            val (s1, x1, y1) = KenBurns.at(m, 1f)
            val (s2, x2, y2) = KenBurns.continued(m, 1f)
            assertEquals("$m", s1, s2, 1e-5f); assertEquals("$m", x1, x2, 1e-5f); assertEquals("$m", y1, y2, 1e-5f)
            for (k in 0..20) {
                val (s, x, y) = KenBurns.continued(m, 1f + k * 0.05f)
                assertTrue("$m $k", s >= 1f && abs(x) <= s - 1 + 1e-5f && abs(y) <= s - 1 + 1e-5f)
            }
        }
    }

    @Test fun transitionLengthsStayInsideTheParts() {
        val short = Segment(0, 1_000, 1)
        val dissolve = Segment(0, 4_000, 0, transition = Transition.DISSOLVE, transitionMs = 800)
        assertEquals(450_000L, TransitionMath.incomingUs(dissolve, short)) // 45% of the 1 s part
        assertEquals(0L, TransitionMath.outgoingUs(dissolve, short))
        val zoom = Segment(0, 4_000, 0, transition = Transition.ZOOM_IN, transitionMs = 1_200)
        assertEquals(333_000L, TransitionMath.incomingUs(zoom, short)) // a third of the short part
        assertEquals(333_000L, TransitionMath.outgoingUs(zoom, short))
        assertEquals(600_000L, TransitionMath.incomingUs(zoom, Segment(0, 4_000, 1))) // half of it on each side
        assertEquals(0L, TransitionMath.incomingUs(Segment(0, 4_000), short))
    }

    @Test fun fadeDipsToBlackAndFlashToWhiteAtTheCut() {
        val (out, inc) = TransitionMath.edges(listOf(Segment(0, 2_000, 0, transition = Transition.FADE_BLACK), Segment(0, 2_000, 1)))
        assertEquals(0f, state(out, 2_000_000, d = 2_000_000).k, 1e-3f)
        assertEquals(0f, state(inc, 0, d = 2_000_000).k, 1e-3f)
        assertEquals(1f, state(inc, 1_000_000, d = 2_000_000).k, 1e-6f)
        val (_, flash) = TransitionMath.edges(listOf(Segment(0, 2_000, 0, transition = Transition.FADE_WHITE), Segment(0, 2_000, 1)))
        val atCut = state(flash, 0, d = 2_000_000)
        assertEquals(1f, atCut.white, 1e-3f)
        assertEquals(0f, atCut.k, 1e-3f)
        assertTrue(state(flash, 150_000, d = 2_000_000).white in 0.01f..0.99f)
    }

    @Test fun glitchPeaksAtTheCut() {
        val (out, inc) = TransitionMath.edges(listOf(Segment(0, 2_000, 0, transition = Transition.GLITCH), Segment(0, 2_000, 1)))
        assertEquals(1f, state(inc, 0, d = 2_000_000).glitch, 1e-3f)
        assertTrue(state(out, 1_850_000, d = 2_000_000).glitch in 0.01f..0.99f)
        assertEquals(0f, state(out, 1_000_000, d = 2_000_000).glitch)
    }

    @Test fun aPartWithoutMovesIsDrawnAsItIs() {
        val s = state(PartEdges(), 500_000)
        for ((u, v) in listOf(0f to 0f, 0.3f to 0.8f, 1f to 1f)) {
            assertEquals(u, s.live0.x(u, v), 1e-5f)
            assertEquals(v, s.live0.y(u, v), 1e-5f)
        }
        assertEquals(1, s.taps); assertEquals(1f, s.k); assertEquals(0f, s.white); assertEquals(0f, s.glitch); assertEquals(0f, s.prevAlpha)
    }

    @Test fun kenBurnsInTheShaderMatchesTheMatrixPath() {
        // PAN_LEFT at the start: NDC x' = 1.15 x + 0.13 (KenBurnsMotion), so the centre shows source x (−0.13 / 1.15).
        val s = state(PartEdges(motion = Motion.PAN_LEFT), 0)
        assertEquals(0.5f + (-0.13f / 1.15f) / 2, s.live0.x(0.5f, 0.5f), 1e-4f)
        val z = state(PartEdges(motion = Motion.ZOOM_IN), dur / 2)
        assertEquals(0.5f + 0.5f / 1.075f, (z.live0.x(1f, 1f) + z.live1.x(1f, 1f)) / 2, 1e-3f)
    }

    @Test fun edgesConnectNeighbours() {
        val e = TransitionMath.edges(listOf(
            Segment(0, 3_000, 0, motion = Motion.PAN_LEFT, transition = Transition.DISSOLVE),
            Segment(0, 3_000, 1, transition = Transition.SPIN),
            Segment(0, 3_000, 2, transition = Transition.FADE_BLACK),
        ))
        assertEquals(Transition.NONE, e[0].inT)
        assertEquals(Transition.DISSOLVE, e[0].outT)
        assertTrue(e[0].keepsLastFrame)
        assertEquals(Transition.DISSOLVE, e[1].inT)
        assertEquals(Motion.PAN_LEFT, e[1].prevMotion)
        assertEquals(3_000_000L, e[1].prevDurationUs)
        assertEquals(Transition.SPIN, e[1].outT)
        assertEquals(Transition.NONE, e[2].outT) // nothing comes after the last part
        assertFalse(e[2].keepsLastFrame)
    }

    @Test fun newTransitionsSurviveDrafts() {
        val plan = EditPlan(segments = Transition.entries.map { Segment(0, 1_000, transition = it) })
        assertEquals(Transition.entries.toList(), EditPlan.fromJson(plan.toJson()).segments.map { it.transition })
    }
}
