package com.gpic.android.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartEditTest {
    private fun embed(seed: Int) = FloatArray(80) { i -> if (i % 7 == seed % 7) 1f else 0.05f }

    /** One sample every 500 ms with the given motion and look. */
    private fun frames(fromMs: Long, n: Int, dx: Float = 0f, dy: Float = 0f, sharp: Float = 0.01f, action: Float = 0.2f,
                       luma: Float = 0.5f, scene: Int = 0, jitter: Boolean = false, faces: Int = 0) =
        (0 until n).map { i ->
            val jx = if (jitter) (if (i % 2 == 0) 0.06f else -0.06f) else 0f
            FrameStat(fromMs + i * 500L + 250, luma, 0.2f, sharp, 0.5f, dx + jx, dy, action, if (i == 0) 0.6f else 0.02f, faces, if (faces > 0) 0.2f else 0f, embed(scene))
        }

    @Test fun splitsAtCutsAndCameraMoves() {
        // 4 s still, then a 4 s pan left, then a hard cut to another still scene.
        val f = frames(0, 8) + frames(4_000, 8, dx = 0.05f, scene = 0).mapIndexed { i, s -> if (i == 0) FrameStat(s.tMs, s.luma, s.contrast, s.sharpness, s.colorful, s.dx, s.dy, s.action, 0.02f, 0, 0f, s.embed) else s } +
            frames(8_000, 8, scene = 3)
        val shots = SmartEdit.shots(ClipAnalysis(0, 12_000, 30f, f))
        assertEquals(3, shots.size)
        assertEquals(listOf(CameraMove.STILL, CameraMove.PAN_LEFT, CameraMove.STILL), shots.map { it.move })
        assertEquals(0L, shots.first().startMs)
        assertEquals(12_000L, shots.last().endMs)
    }

    @Test fun shakyBlurryAndDarkAreFlagged() {
        val shaky = SmartEdit.shots(ClipAnalysis(0, 4_000, 30f, frames(0, 8, dx = 0.03f, jitter = true))).single()
        assertTrue(shaky.shaky)
        val blurry = SmartEdit.shots(ClipAnalysis(1, 4_000, 30f, frames(0, 8, sharp = 0.001f))).single()
        assertTrue(blurry.blurry)
        val dark = SmartEdit.shots(ClipAnalysis(2, 4_000, 30f, frames(0, 8, luma = 0.05f))).single()
        assertTrue(dark.dark)
        val good = SmartEdit.shots(ClipAnalysis(3, 4_000, 30f, frames(0, 8))).single()
        assertFalse(good.shaky || good.blurry || good.dark)
        assertTrue(good.score > shaky.score && good.score > blurry.score)
    }

    @Test fun nearDuplicatesShareAGroup() {
        val a = SmartEdit.shots(ClipAnalysis(0, 4_000, 30f, frames(0, 8, scene = 1))).single()
        val b = SmartEdit.shots(ClipAnalysis(1, 4_000, 30f, frames(0, 8, scene = 1))).single()
        val c = SmartEdit.shots(ClipAnalysis(2, 4_000, 30f, frames(0, 8, scene = 4))).single()
        assertEquals(2, SmartEdit.group(listOf(a, b, c)))
        assertEquals(a.group, b.group)
    }

    @Test fun picksGoodShotsFirstInStoryOrderWithMatchCuts() {
        val clips = listOf(
            ClipAnalysis(0, 6_000, 30f, frames(0, 12, dx = 0.05f, scene = 1)),       // smooth pan left
            ClipAnalysis(1, 6_000, 30f, frames(0, 12, jitter = true, dx = 0.03f, scene = 2)), // shaky
            ClipAnalysis(2, 6_000, 30f, frames(0, 12, scene = 3, faces = 1)),         // still, a face
        )
        val r = SmartEdit.pick(clips, targetMs = 4_000, cutMs = 2_000, arrange = Arrange.STORY, style = EditStyle.TRAVEL)
        assertEquals(2, r.parts.size)
        assertEquals(listOf(0, 2), r.parts.map { it.clip }) // the shaky clip is left out, story order kept
        assertEquals(Transition.WHIP_RIGHT, r.parts.first().transition) // the pan continues into the cut
        assertEquals(Transition.NONE, r.parts.last().transition)
        assertTrue(r.parts.all { it.durationMs == 2_000L })
        assertEquals(1, r.shaky)
    }

    @Test fun highFrameRateShotsPlayInSlowMotion() {
        val r = SmartEdit.pick(listOf(ClipAnalysis(0, 8_000, 120f, frames(0, 16))), targetMs = 2_000, cutMs = 2_000, arrange = Arrange.BEST_FIRST, style = EditStyle.ACTION)
        val p = r.parts.single()
        assertEquals(0.5f, p.speed)
        assertEquals(1_000L, p.durationMs) // 1 s of clip fills a 2 s slot
        assertEquals(1, r.slowMo)
    }

    @Test fun styleTransitionComesEveryFewCutsNotOnEveryOne() {
        val clips = (0 until 6).map { ClipAnalysis(it, 6_000, 30f, frames(0, 12, scene = it)) }
        val r = SmartEdit.pick(clips, targetMs = 18_000, cutMs = 3_000, arrange = Arrange.STORY, style = EditStyle.TRAVEL)
        assertEquals(6, r.parts.size)
        assertEquals(listOf(Transition.NONE, Transition.DISSOLVE, Transition.NONE, Transition.DISSOLVE, Transition.NONE, Transition.NONE),
            r.parts.map { it.transition })
        assertTrue(r.parts.all { it.motion == EditStyle.TRAVEL.motion }) // still shots get the style's slow move
        assertEquals(900L, r.parts[1].transitionMs) // long cuts get long dissolves
    }

    @Test fun photosTakeTurnsWithMovesAndDissolveIntoEachOther() {
        val photos = (0 until 5).map { i -> ClipAnalysis(i, VideoProbe.IMAGE_MAX_MS, 30f, frames(0, 1, scene = i), isPhoto = true) }
        val action = SmartEdit.pick(photos, targetMs = 15_000, cutMs = 3_000, arrange = Arrange.STORY, style = EditStyle.ACTION)
        assertEquals(SmartEdit.PHOTO_MOVES + SmartEdit.PHOTO_MOVES.first(), action.parts.map { it.motion })
        assertEquals(listOf(Transition.NONE, Transition.NONE, Transition.NONE, Transition.ZOOM_IN, Transition.NONE), action.parts.map { it.transition })
        val travel = SmartEdit.pick(photos, targetMs = 15_000, cutMs = 3_000, arrange = Arrange.STORY, style = EditStyle.TRAVEL)
        assertEquals(List(4) { Transition.DISSOLVE } + Transition.NONE, travel.parts.map { it.transition })
    }

    @Test fun longClipBurstsJudgeShakeWithinABurstOnly() {
        // Long clips are sampled in bursts of 4 frames 0.5 s apart, 5 s between bursts.
        fun burst(start: Long, dx: Float, jitter: Boolean) = (0 until 4).map { k ->
            val jx = if (jitter) (if (k % 2 == 0) 0.06f else -0.06f) else 0f
            FrameStat(start + k * 500, 0.5f, 0.2f, 0.01f, 0.5f, dx + jx, 0f, 0.2f, 0.02f, 0, 0f, embed(0), gap = k == 0)
        }
        // Smooth pans that change direction between bursts: not shake.
        val steady = (0 until 6).flatMap { b -> burst(b * 5_000L, if (b % 2 == 0) 0.05f else -0.05f, jitter = false) }
        assertTrue(SmartEdit.shots(ClipAnalysis(0, 30_000, 30f, steady)).none { it.shaky })
        val shaky = (0 until 6).flatMap { b -> burst(b * 5_000L, 0.03f, jitter = true) }
        assertTrue(SmartEdit.shots(ClipAnalysis(1, 30_000, 30f, shaky)).any { it.shaky })
    }

    @Test fun claudeShotListCoversALongClipEndToEnd() {
        fun shot(i: Int, quality: Float, clip: Int = 0) = Shot(clip, i * 1_000L, i * 1_000L + 1_000, CameraMove.STILL, quality, 0.3f, false, false, false,
            0, false, embed(i), 0.01f, listOf(i * 1_000L + 500 to 0.3f))
        // A long clip whose best shots are all in its first half, and a short clip.
        val shots = (0 until 300).map { shot(it, if (it < 150) 0.9f else 0.4f) } + (0 until 30).map { shot(it, 0.5f, clip = 1) }
        val kept = SmartEdit.limitShots(shots, 120)
        assertTrue(kept.size <= 120)
        assertTrue(kept.any { it.clip == 0 && it.startMs > 250_000 })
        assertTrue(kept.any { it.clip == 1 })
        assertEquals(kept, kept.sortedWith(compareBy({ it.clip }, { it.startMs })))
        assertEquals(shots.take(100), SmartEdit.limitShots(shots.take(100), 120))
    }

    @Test fun longClipsAreSampledInBurstsHalfASecondApart() {
        val short = VideoAnalyzer.sampleTimes(60_000)
        assertEquals(120, short.size)
        assertEquals(250L, short.first())
        assertTrue(short.zipWithNext().all { (a, b) -> b - a == 500L })
        val long = VideoAnalyzer.sampleTimes(20 * 60_000L)
        assertEquals(480, long.size)
        assertEquals(long, long.sorted())
        long.chunked(4).forEach { b -> assertTrue(b.zipWithNext().all { (x, y) -> y - x == 500L }) }
        // From the start of the clip to its end, not just its first minutes.
        assertTrue(long.first() < 30_000 && long.last() > 20 * 60_000L - 30_000 && long.last() < 20 * 60_000L)
    }

    @Test fun windowFollowsTheAction() {
        val f = frames(0, 20).mapIndexed { i, s -> FrameStat(s.tMs, s.luma, s.contrast, s.sharpness, s.colorful, 0f, 0f, if (i in 14..16) 1f else 0.05f, s.change, 0, 0f, s.embed) }
        val shot = SmartEdit.shots(ClipAnalysis(0, 10_000, 30f, f)).single()
        val (s, e) = SmartEdit.window(shot, 2_000, 0)
        assertTrue("$s..$e", s <= 7_250 && e >= 8_250)
    }
}
