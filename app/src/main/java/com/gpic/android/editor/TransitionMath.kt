package com.gpic.android.editor

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** x' = a·x + b·y + tx, y' = c·x + d·y + ty. Pure (android.graphics.Matrix is only a stub in JVM tests). */
data class Affine(val a: Float = 1f, val b: Float = 0f, val c: Float = 0f, val d: Float = 1f, val tx: Float = 0f, val ty: Float = 0f) {
    /** This after [o]: (this * o)(p) = this(o(p)). */
    operator fun times(o: Affine) = Affine(
        a * o.a + b * o.c, a * o.b + b * o.d,
        c * o.a + d * o.c, c * o.b + d * o.d,
        a * o.tx + b * o.ty + tx, c * o.tx + d * o.ty + ty,
    )

    fun inverse(): Affine {
        val det = a * d - b * c
        val ia = d / det; val ib = -b / det; val ic = -c / det; val id = a / det
        return Affine(ia, ib, ic, id, -(ia * tx + ib * ty), -(ic * tx + id * ty))
    }

    fun x(px: Float, py: Float) = a * px + b * py + tx
    fun y(px: Float, py: Float) = c * px + d * py + ty

    /** Column-major, for a GLSL mat3 applied to (u, v, 1). */
    fun toMat3() = floatArrayOf(a, c, 0f, b, d, 0f, tx, ty, 1f)

    companion object {
        fun scale(s: Float) = Affine(a = s, d = s)
        fun translate(x: Float, y: Float) = Affine(tx = x, ty = y)
        fun rotate(radians: Float): Affine {
            val co = cos(radians); val si = sin(radians)
            return Affine(co, -si, si, co)
        }
    }
}

/** The slow move over a part ([Motion]): scale ≥ 1 so no edge shows, pans inside the zoomed frame. */
object KenBurns {
    /** Scale and offset in NDC (the frame spans -1..1 both ways) at fraction [f] of the part. */
    fun at(motion: Motion, f: Float): Triple<Float, Float, Float> = when (motion) {
        Motion.ZOOM_IN -> Triple(1f + 0.15f * f, 0f, 0f)
        Motion.ZOOM_OUT -> Triple(1.15f - 0.15f * f, 0f, 0f)
        Motion.PAN_LEFT -> Triple(1.15f, 0.13f - 0.26f * f, 0f)
        Motion.PAN_RIGHT -> Triple(1.15f, -0.13f + 0.26f * f, 0f)
        Motion.NONE -> Triple(1f, 0f, 0f)
    }

    /** Past the part's end (its last frame lingers under a dissolve): the move goes on while it still covers the frame. */
    fun continued(motion: Motion, f: Float): Triple<Float, Float, Float> {
        val (s0, x, y) = at(motion, f)
        val s = max(1f, s0)
        return Triple(s, x.coerceIn(1f - s, s - 1f), y.coerceIn(1f - s, s - 1f))
    }

    /** As a transform of the frame, x in units of the frame height so turns keep their shape. */
    fun frame(motion: Motion, f: Float, aspect: Float, continued: Boolean = false): Affine {
        val (s, x, y) = if (continued) continued(motion, f) else at(motion, f)
        return Affine.translate(x * aspect, y) * Affine.scale(s)
    }
}

/** Mixes the outgoing part's last frame into the incoming part, after the cut (it needs both pictures at once). */
val Transition.blends: Boolean get() = this == Transition.DISSOLVE || this == Transition.DISSOLVE_BLUR || isSlide
val Transition.isSlide: Boolean
    get() = this == Transition.SLIDE_LEFT || this == Transition.SLIDE_RIGHT || this == Transition.SLIDE_UP || this == Transition.SLIDE_DOWN
/** Moves the whole picture on both sides of the cut, fastest at the cut. */
val Transition.moves: Boolean
    get() = this == Transition.ZOOM_IN || this == Transition.ZOOM_OUT || this == Transition.SPIN ||
        this == Transition.WHIP_LEFT || this == Transition.WHIP_RIGHT

/**
 * What happens at one part's two cuts, in its own output time (µs): the transition from the previous part and how
 * long it plays on this side, the transition into the next one, and the previous part's move and length (its last
 * frame keeps that move while it dissolves away).
 */
class PartEdges(
    val motion: Motion = Motion.NONE,
    val inT: Transition = Transition.NONE,
    val inUs: Long = 0,
    val outT: Transition = Transition.NONE,
    val outUs: Long = 0,
    val prevMotion: Motion = Motion.NONE,
    val prevDurationUs: Long = 0,
) {
    /** The next part blends with this part's last frame, so the shader keeps it. */
    val keepsLastFrame: Boolean get() = outT.blends
    val any: Boolean get() = inT != Transition.NONE || outT != Transition.NONE
}

/** One frame's instructions for the part shader ([PartFx]). Sampling transforms map output uv to source uv. */
class FxState(
    /** The part's picture at the shutter's opening and closing; motion blur averages [taps] samples between them. */
    val live0: Affine,
    val live1: Affine,
    /** The previous part's last frame, drawn over the part with [prevAlpha] (0 = not drawn). */
    val prev0: Affine,
    val prev1: Affine,
    val prevAlpha: Float,
    /** The previous frame covers only its own (moving) rectangle, as in a slide; else all of the frame. */
    val prevClipped: Boolean,
    val taps: Int,
    /** Colour: rgb · k + white. */
    val k: Float,
    val white: Float,
    /** 0..1: colour split and jumping bands; [seed] picks the bands. */
    val glitch: Float,
    val seed: Float,
)

/**
 * Transitions without a second video track (old phones can't decode two clips at once). Moves (zoom, whip, spin)
 * run on both sides of the cut and are fastest at it, so the motion carries through the cut instead of stopping,
 * with motion blur from a virtual shutter and mirrored edges instead of black. Dissolves, blur and slides need both
 * pictures: the shader keeps the outgoing part's last frame and mixes it into the start of the incoming part (still
 * moving the way it was). Parts play back to back either way, so transitions never change the length. Pure.
 */
object TransitionMath {
    /** Motion blur integrates the picture's move over this long (a bit more than a frame: a strong, smooth smear). */
    const val SHUTTER_US = 45_000L
    const val MAX_TAPS = 16
    private val ZOOM_LOG = ln(2.2f)
    private const val SPIN_RAD = (PI / 2).toFloat()
    /** How far a whip throws the picture on each side, in half frame widths. */
    private const val WHIP = 1.2f

    /** The edges of every part (each part's transition is into the next one). */
    fun edges(parts: List<Segment>): List<PartEdges> = parts.indices.map { i ->
        val s = parts[i]
        val prev = parts.getOrNull(i - 1)
        val next = parts.getOrNull(i + 1)
        PartEdges(
            motion = s.motion,
            inT = prev?.transition ?: Transition.NONE,
            inUs = if (prev != null) incomingUs(prev, s) else 0,
            outT = if (next != null) s.transition else Transition.NONE,
            outUs = if (next != null) outgoingUs(s, next) else 0,
            prevMotion = prev?.motion ?: Motion.NONE,
            prevDurationUs = (prev?.outputMs ?: 0) * 1000,
        )
    }

    /** How long [a]'s transition plays at the start of [b]: all of it for a dissolve or slide (≤ 45% of b), else half. */
    fun incomingUs(a: Segment, b: Segment): Long = when {
        a.transition == Transition.NONE -> 0
        a.transition == Transition.DISSOLVE || a.transition.isSlide -> min(a.transitionMs, b.outputMs * 45 / 100).coerceAtLeast(0) * 1000
        else -> halfUs(a, b)
    }

    /** How long [a]'s transition plays at its own end (nothing for a dissolve or slide: they happen after the cut). */
    fun outgoingUs(a: Segment, b: Segment): Long = when {
        a.transition == Transition.NONE || a.transition == Transition.DISSOLVE || a.transition.isSlide -> 0
        else -> halfUs(a, b)
    }

    /** Half the transition on each side of the cut, never more than a third of either part. */
    private fun halfUs(a: Segment, b: Segment): Long = minOf(a.transitionMs / 2, a.outputMs / 3, b.outputMs / 3).coerceAtLeast(0) * 1000

    /**
     * The frame at [localUs] into a part of [durationUs], [width]×[height]. [prevReady]: the previous part's last frame
     * is there to mix in (it isn't after a seek into the transition in the preview: then it's a plain cut).
     */
    fun state(e: PartEdges, localUs: Long, durationUs: Long, width: Int, height: Int, prevReady: Boolean): FxState {
        val aspect = width.toFloat() / height.coerceAtLeast(1)
        val toFrame = Affine(a = 2 * aspect, d = 2f, tx = -aspect, ty = -1f)
        val toUv = toFrame.inverse()
        fun sampler(forward: Affine) = toUv * forward.inverse() * toFrame
        val t0 = localUs - SHUTTER_US / 2
        val t1 = localUs + SHUTTER_US / 2
        val blending = prevReady && e.inT.blends && e.inUs > 0 && localUs < e.inUs
        val live0 = sampler(liveAt(e, t0, durationUs, aspect, blending))
        val live1 = sampler(liveAt(e, t1, durationUs, aspect, blending))
        val prev0 = if (blending) sampler(prevAt(e, t0, aspect)) else live0
        val prev1 = if (blending) sampler(prevAt(e, t1, aspect)) else live1
        val y = if (e.inUs > 0) localUs.toFloat() / e.inUs else 1f
        val prevAlpha = when {
            !blending -> 0f
            e.inT.isSlide -> 1f
            else -> 1f - smooth(y)
        }
        var k = 1f
        var white = 0f
        var glitch = 0f
        fun shade(t: Transition, toCut: Float, incoming: Boolean) {
            if (toCut <= 0f) return
            val c = toCut.coerceAtMost(1f)
            when (t) {
                Transition.FADE_BLACK -> k *= 1f - smooth(c)
                // The flash builds up into the cut and dies away after it.
                Transition.FADE_WHITE -> white = max(white, if (incoming) c * c else c.pow(1.6f))
                Transition.GLITCH -> glitch = max(glitch, c.pow(1.5f))
                else -> {}
            }
        }
        if (e.inUs > 0) shade(e.inT, 1f - localUs.toFloat() / e.inUs, incoming = true)
        if (e.outUs > 0) shade(e.outT, 1f - (durationUs - localUs).toFloat() / e.outUs, incoming = false)
        k *= 1f - white
        val moved = max(shift(live0, live1, width, height), if (blending) shift(prev0, prev1, width, height) else 0f)
        val taps = if (glitch > 0f) 1 else ceil(moved / 1.5f).toInt().coerceIn(1, MAX_TAPS)
        return FxState(live0, live1, prev0, prev1, prevAlpha, blending && e.inT.isSlide, taps, k, white, glitch, (localUs / 50_000).toFloat())
    }

    /** 0..1: how blurred the part is at [localUs] (for the Blur transition, strongest at the cut). */
    fun blur(e: PartEdges, localUs: Long, durationUs: Long): Float {
        var b = 0f
        if (e.inT == Transition.DISSOLVE_BLUR && e.inUs > 0) b = max(b, 1f - smooth(localUs.toFloat() / e.inUs))
        if (e.outT == Transition.DISSOLVE_BLUR && e.outUs > 0) b = max(b, 1f - smooth((durationUs - localUs).toFloat() / e.outUs))
        return b
    }

    /** The part's picture (its own move, the slide bringing it in, the moves at its cuts), frame space → output. */
    private fun liveAt(e: PartEdges, t: Long, durationUs: Long, aspect: Float, blending: Boolean): Affine {
        var m = KenBurns.frame(e.motion, (t.toFloat() / durationUs.coerceAtLeast(1)).coerceIn(0f, 1f), aspect)
        if (blending && e.inT.isSlide) {
            val p = slide(t.toFloat() / e.inUs)
            val (dx, dy) = slideVector(e.inT, aspect)
            m = Affine.translate((1 - p) * dx, (1 - p) * dy) * m
        }
        if (e.inT.moves && e.inUs > 0) m = move(e.inT, rush(1f - t.toFloat() / e.inUs), incoming = true, aspect) * m
        if (e.outT.moves && e.outUs > 0) m = move(e.outT, rush(1f - (durationUs - t).toFloat() / e.outUs), incoming = false, aspect) * m
        return m
    }

    /** The previous part's last frame: its move carried on, pushed out by a slide. */
    private fun prevAt(e: PartEdges, t: Long, aspect: Float): Affine {
        val f = if (e.prevDurationUs > 0) (e.prevDurationUs + max(0L, t)).toFloat() / e.prevDurationUs else 1f
        var m = KenBurns.frame(e.prevMotion, f, aspect, continued = true)
        if (e.inT.isSlide) {
            val p = slide(t.toFloat() / e.inUs)
            val (dx, dy) = slideVector(e.inT, aspect)
            m = Affine.translate(-p * dx, -p * dy) * m
        }
        return m
    }

    /**
     * A move of the whole picture at amount [m] (0 = none, 1 = at the cut). Both sides keep going the same way: the
     * outgoing part zooms/turns/whips into the cut, the incoming one comes out of it from the opposite extreme.
     */
    private fun move(t: Transition, m: Float, incoming: Boolean, aspect: Float): Affine {
        if (m <= 0f) return Affine()
        val side = if (incoming) -1f else 1f
        return when (t) {
            Transition.ZOOM_IN -> Affine.scale(exp(side * m * ZOOM_LOG))
            Transition.ZOOM_OUT -> Affine.scale(exp(-side * m * ZOOM_LOG))
            Transition.SPIN -> Affine.scale(1f + 0.45f * m) * Affine.rotate(side * m * SPIN_RAD)
            Transition.WHIP_LEFT -> Affine.translate(-side * m * WHIP * aspect, 0f) * Affine.scale(1f + 0.12f * m)
            Transition.WHIP_RIGHT -> Affine.translate(side * m * WHIP * aspect, 0f) * Affine.scale(1f + 0.12f * m)
            else -> Affine()
        }
    }

    /** Where the incoming picture starts for a slide (it moves in the slide's direction; the old frame goes ahead of it). */
    private fun slideVector(t: Transition, aspect: Float): Pair<Float, Float> = when (t) {
        Transition.SLIDE_LEFT -> 2 * aspect to 0f
        Transition.SLIDE_RIGHT -> -2 * aspect to 0f
        Transition.SLIDE_UP -> 0f to -2f
        else -> 0f to 2f
    }

    /** Slow start, fastest at the cut ([x] = 1); keeps going past it, so the shutter can look ahead. */
    internal fun rush(x: Float): Float = if (x <= 0f) 0f else x * x * x

    internal fun smooth(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3 - 2 * c) }

    /** Ease in and out (cubic): a slide starts and lands gently and is fastest halfway. */
    internal fun slide(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        return if (c < 0.5f) 4 * c * c * c else 1 - (-2 * c + 2).let { it * it * it } / 2
    }

    /** Largest move in pixels between two samplings of the frame (corners and centre). */
    private fun shift(a: Affine, b: Affine, w: Int, h: Int): Float {
        var best = 0f
        for ((u, v) in CORNERS) best = max(best, hypot((b.x(u, v) - a.x(u, v)) * w, (b.y(u, v) - a.y(u, v)) * h))
        return best
    }

    private val CORNERS = listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f, 0.5f to 0.5f)
}
