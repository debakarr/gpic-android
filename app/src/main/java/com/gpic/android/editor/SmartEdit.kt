package com.gpic.android.editor

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * What [VideoAnalyzer] measures on one sampled frame. Motion is relative to the previous sample, as a fraction of
 * the frame (dx > 0: the picture moved right, i.e. the camera panned left).
 */
class FrameStat(
    val tMs: Long,
    /** Mean brightness 0..1 and its spread (contrast). */
    val luma: Float,
    val contrast: Float,
    /** Edge energy (variance of the Laplacian on a small grey frame); higher = sharper. */
    val sharpness: Float,
    /** 0..1 (Hasler–Süsstrunk colourfulness, scaled). */
    val colorful: Float,
    val dx: Float,
    val dy: Float,
    /** Change left over after removing the camera's motion: things moving in the shot. 0..1. */
    val action: Float,
    /** Colour-histogram distance to the previous sample, 0..1: a hard cut is ~0.5+. */
    val change: Float,
    val faces: Int,
    /** Largest face width as a fraction of the frame width. */
    val faceSize: Float,
    /** Small descriptor (colour histogram + tiny grey image), for near-duplicate detection. */
    val embed: FloatArray,
    /**
     * Far from the previous sample (long clips are sampled in short bursts): [dx]/[dy]/[action] are the next sample's,
     * and the change of motion into this one says nothing about shake. [change] is still against the previous sample.
     */
    val gap: Boolean = false,
)

class ClipAnalysis(val clip: Int, val durationMs: Long, val fps: Float, val frames: List<FrameStat>, val isPhoto: Boolean = false)

enum class CameraMove(val label: String) {
    STILL("Still"), PAN_LEFT("Pan left"), PAN_RIGHT("Pan right"), TILT_UP("Tilt up"), TILT_DOWN("Tilt down"), MOVING("Moving");
}

/** One continuous shot inside a clip, with LightCut-style attributes. */
class Shot(
    val clip: Int,
    val startMs: Long,
    val endMs: Long,
    val move: CameraMove,
    /** 0..1: sharpness, exposure, colour, contrast. */
    val quality: Float,
    /** 0..1: things happening, faces. */
    val interest: Float,
    val shaky: Boolean,
    var blurry: Boolean,
    val dark: Boolean,
    val faces: Int,
    val slowMo: Boolean,
    val embed: FloatArray,
    /** Mean sharpness as measured (for comparing shots across clips). */
    val sharpAbs: Float,
    /** Per-sample interest (for the best window inside the shot) and the sample step. */
    val curve: List<Pair<Long, Float>>,
    val photo: Boolean = false,
) {
    val durationMs: Long get() = endMs - startMs
    val score: Float get() = 0.55f * quality + 0.45f * interest - (if (shaky) 0.25f else 0f) - (if (blurry) 0.3f else 0f) - (if (dark) 0.2f else 0f)
    var group: Int = -1
}

enum class Arrange(val label: String) { STORY("Story order"), BEST_FIRST("Best first") }

/** The result of a smart edit: the parts, plus what was found, for the user to check. */
class SmartResult(val parts: List<Segment>, val shots: Int, val shaky: Int, val blurry: Int, val duplicates: Int, val slowMo: Int, val used: Int)

/**
 * LightCut's one-tap edit, done on the phone without ML models: split each clip into shots (hard cuts and changes
 * of camera motion), score them (sharpness, exposure, colour, action, faces; shaky/blurry/dark penalised), group
 * near-duplicates so a montage doesn't repeat itself, then fill slots of beat-length cuts with the best shots,
 * using the liveliest window of each. Pure, so it's unit-tested; the measurements come from [VideoAnalyzer].
 */
object SmartEdit {
    private const val CUT = 0.42f
    private const val STILL_SPEED = 0.012f
    private const val MIN_SHOT_MS = 1_000L

    fun moveOf(s: FrameStat): CameraMove {
        val sp = hypot(s.dx, s.dy)
        if (sp < STILL_SPEED) return CameraMove.STILL
        return if (abs(s.dx) >= abs(s.dy)) (if (s.dx > 0) CameraMove.PAN_LEFT else CameraMove.PAN_RIGHT)
        else (if (s.dy > 0) CameraMove.TILT_UP else CameraMove.TILT_DOWN)
    }

    /** Splits a clip into shots at hard cuts and at lasting changes of camera motion. */
    fun shots(a: ClipAnalysis): List<Shot> {
        val f = a.frames
        if (f.isEmpty()) return emptyList()
        // Motion class per sample, smoothed (majority of 3) so one odd sample doesn't split a shot.
        val raw = f.map(::moveOf)
        val moves = raw.indices.map { i ->
            val w = listOfNotNull(raw.getOrNull(i - 1), raw[i], raw.getOrNull(i + 1))
            w.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
        }
        // The usual spacing (long clips come in bursts with gaps between them).
        val step = if (f.size > 1) f.zipWithNext { p, q -> q.tMs - p.tMs }.sorted().let { it[it.size / 2] } else a.durationMs
        val bounds = mutableListOf(0)
        for (i in 1 until f.size) {
            val sinceLast = f[i].tMs - f[bounds.last()].tMs
            val hardCut = f[i].change > CUT
            val moveChange = moves[i] != moves[i - 1] && sinceLast >= MIN_SHOT_MS &&
                // the new motion has to last at least ~1 s
                (i until min(f.size, i + max(1, (MIN_SHOT_MS / max(step, 1)).toInt()))).all { moves[it] == moves[i] }
            if ((hardCut && sinceLast >= 400) || moveChange) bounds += i
        }
        bounds += f.size
        // Scale-free sharpness: relative to this clip's sharper frames, with an absolute floor for all-blurry clips.
        val sharpRef = f.map { it.sharpness }.sorted().let { it[(it.size * 0.9).toInt().coerceAtMost(it.size - 1)] }.coerceAtLeast(1e-6f)
        val actionRef = f.map { it.action }.maxOrNull()?.coerceAtLeast(0.02f) ?: 1f
        val out = mutableListOf<Shot>()
        for (b in 0 until bounds.size - 1) {
            val s = bounds[b]
            val e = bounds[b + 1]
            val fr = f.subList(s, e)
            val start = if (s == 0) 0L else fr.first().tMs - step / 2
            val end = if (e == f.size) a.durationMs else f[e].tMs - step / 2
            if (end - start < 300) continue
            val sharp = fr.map { min(1f, it.sharpness / sharpRef) }.average().toFloat()
            val exposure = fr.map { 1f - min(1f, abs(it.luma - 0.48f) * 2.2f) }.average().toFloat()
            val contrast = fr.map { min(1f, it.contrast * 4f) }.average().toFloat()
            val colour = fr.map { it.colorful }.average().toFloat()
            val quality = (0.4f * sharp + 0.25f * exposure + 0.15f * contrast + 0.2f * colour).coerceIn(0f, 1f)
            val faces = fr.maxOf { it.faces }
            val faceSize = fr.maxOf { it.faceSize }
            val curve = fr.map { it.tMs to (min(1f, it.action / actionRef) * 0.7f + (if (it.faces > 0) 0.3f else 0f)) }
            val interest = (curve.map { it.second }.average().toFloat() + (if (faces > 0) 0.15f + min(0.15f, faceSize) else 0f)).coerceIn(0f, 1f)
            // Shake: the camera's motion jumping around from sample to sample, rather than moving smoothly (only
            // between neighbouring samples: across a gap the camera has simply done something else).
            val jumps = fr.zipWithNext().filter { (p, q) -> !p.gap && !q.gap }.map { (p, q) -> hypot(q.dx - p.dx, q.dy - p.dy) }
            val jitter = if (jumps.size >= 2) jumps.average().toFloat() else 0f
            val luma = fr.map { it.luma }.average().toFloat()
            val embed = average(fr.map { it.embed })
            val moveCounts = moves.subList(s, e).groupingBy { it }.eachCount()
            val dominant = moveCounts.maxByOrNull { it.value }!!.key
            val move = if (jitter > 0.05f && dominant != CameraMove.STILL) CameraMove.MOVING else dominant
            out += Shot(
                a.clip, start, end, move, quality, interest,
                shaky = jitter > 0.06f, blurry = sharp < 0.35f || fr.map { it.sharpness }.average() < ABS_BLUR, dark = luma < 0.12f,
                faces = faces, slowMo = a.fps >= 100f, embed = embed, sharpAbs = fr.map { it.sharpness }.average().toFloat(), curve = curve,
                photo = a.isPhoto,
            )
        }
        return mergeShort(out)
    }

    /** Below this (Laplacian variance on a 96 px grey frame) a picture is soft, whatever the clip's own scale. */
    private const val ABS_BLUR = 0.0015f

    /** Very short shots join the previous one (a flash or a cut inside a pan isn't worth its own slot). */
    private fun mergeShort(shots: List<Shot>): List<Shot> {
        val out = mutableListOf<Shot>()
        for (s in shots) {
            val last = out.lastOrNull()
            if (last != null && s.durationMs < MIN_SHOT_MS && last.clip == s.clip) {
                out[out.lastIndex] = Shot(last.clip, last.startMs, s.endMs, last.move, (last.quality + s.quality) / 2, max(last.interest, s.interest),
                    last.shaky, last.blurry, last.dark, max(last.faces, s.faces), last.slowMo, last.embed, last.sharpAbs, last.curve + s.curve, last.photo)
            } else out += s
        }
        return out
    }

    private fun average(vs: List<FloatArray>): FloatArray {
        if (vs.isEmpty()) return FloatArray(0)
        val n = vs.first().size
        val out = FloatArray(n)
        for (v in vs) for (i in 0 until min(n, v.size)) out[i] += v[i] / vs.size
        return out
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        var d = 0f; var na = 0f; var nb = 0f
        for (i in 0 until min(a.size, b.size)) { d += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na <= 0f || nb <= 0f) 0f else d / sqrt(na * nb)
    }

    /**
     * At most [max] of [shots] (for Claude's shot list): each clip keeps a share by its number of shots, taken as the
     * best shot of each stretch of it, so a long clip is described from start to end, not just its first minutes.
     * In the given (clip, time) order.
     */
    fun limitShots(shots: List<Shot>, max: Int): List<Shot> {
        if (shots.size <= max) return shots
        val kept = HashSet<Shot>()
        for (list in shots.groupBy { it.clip }.values) {
            val quota = max(1, (max.toLong() * list.size / shots.size).toInt())
            list.chunked((list.size + quota - 1) / quota).forEach { stretch -> stretch.maxByOrNull { it.score }?.let { kept += it } }
        }
        return shots.filter { it in kept }.take(max)
    }

    /** Puts near-identical shots (same scene filmed twice, a long still shot cut in two) in one group. */
    fun group(shots: List<Shot>, similar: Float = 0.96f): Int {
        var groups = 0
        for ((i, s) in shots.withIndex()) {
            val match = shots.subList(0, i).firstOrNull { cosine(it.embed, s.embed) >= similar }
            s.group = match?.group ?: groups++
        }
        return groups
    }

    /**
     * Transition that continues the camera's motion out of a shot (a "match cut": the picture keeps moving the way the
     * camera did), else [fallback].
     */
    fun transitionFor(move: CameraMove, fallback: Transition): Transition = when (move) {
        CameraMove.PAN_LEFT -> Transition.WHIP_RIGHT
        CameraMove.PAN_RIGHT -> Transition.WHIP_LEFT
        CameraMove.TILT_UP -> Transition.SLIDE_DOWN
        CameraMove.TILT_DOWN -> Transition.SLIDE_UP
        else -> fallback
    }

    /** Photos take turns with these moves, so a slideshow doesn't zoom the same way every time. */
    val PHOTO_MOVES = listOf(Motion.ZOOM_IN, Motion.PAN_RIGHT, Motion.ZOOM_OUT, Motion.PAN_LEFT)

    /** A transition about a third of its part (shorter on quick beats, up to 0.9 s on long ones). */
    fun transitionMsFor(partMs: Long): Long = (partMs * 35 / 100).coerceIn(250, 900)

    /**
     * Picks shots for about [targetMs] of [cutMs] slots. Shaky, blurry and dark shots are used only when nothing
     * better is left; one shot per near-duplicate group until every group has had a turn; a long, good shot can
     * give a second slot. High-frame-rate shots play at half speed (LightCut's slow shots). The style's transition
     * goes on every few cuts only (a transition on every cut looks cheap); still shots get its slow move.
     */
    fun pick(analyses: List<ClipAnalysis>, targetMs: Long, cutMs: Long, arrange: Arrange, style: EditStyle): SmartResult {
        val cut0 = cutMs.coerceAtLeast(EditPlan.MIN_SEGMENT_MS)
        val n = max(1, ((targetMs + cut0 / 2) / cut0).toInt())
        val every = style.transitionEvery.coerceAtLeast(1)
        val slots = List(n) { i -> TemplateSlot(0, if ((i + 1) % every == 0) style.transition else Transition.NONE, motion = style.motion) to cut0 }
        return pickSlots(analyses, slots, arrange, followCamera = true, photoTransition = style.photoTransition, varyPhotos = true)
    }

    /**
     * Fills template slots (output length each, with their transition, speed ramp and camera move) with the best
     * shots, in [arrange] order. A panning shot cuts with a whip that carries its motion on when [followCamera] (not
     * right after another transition). Photos fill any slot up to 10 s with a slow move (the slot's, or taking turns
     * when it has none or [varyPhotos]), and a hard cut between two photos becomes [photoTransition].
     */
    fun pickSlots(analyses: List<ClipAnalysis>, slots: List<Pair<TemplateSlot, Long>>, arrange: Arrange, followCamera: Boolean = true,
                  photoTransition: Transition = Transition.NONE, varyPhotos: Boolean = false): SmartResult {
        val shots = analyses.flatMap { shots(it) }
        // Across clips: a shot far softer than the project's typical one is blurry even if its own clip is all soft.
        if (shots.size >= 3) {
            val median = shots.map { it.sharpAbs }.sorted()[shots.size / 2]
            shots.forEach { if (it.sharpAbs < 0.25f * median) it.blurry = true }
        }
        val groups = group(shots)
        val cut = slots.map { it.second }.average().toLong().coerceAtLeast(EditPlan.MIN_SEGMENT_MS)
        val wanted = slots.size
        val usable = shots.filter { it.durationMs >= min(cut, 800L) }
        val good = usable.filter { !it.shaky && !it.blurry && !it.dark }
        val goodRanked = good.sortedByDescending { it.score }
        val weakRanked = (usable - good.toSet()).sortedByDescending { it.score }
        // Rounds: the best good shot of each group, the other good shots, a second window of long good shots, and
        // only then shaky/blurry/dark ones (best first) when the clips have nothing better.
        val chosen = mutableListOf<Pair<Shot, Int>>() // shot, window index
        val usedGroups = HashSet<Int>()
        for (s in goodRanked) { if (chosen.size >= wanted) break; if (usedGroups.add(s.group)) chosen += s to 0 }
        for (s in goodRanked) { if (chosen.size >= wanted) break; if (chosen.none { it.first === s }) chosen += s to 0 }
        for (s in goodRanked.filter { it.durationMs >= cut * 2 && !it.photo }) { if (chosen.size >= wanted) break; chosen += s to 1 }
        for (s in weakRanked) { if (chosen.size >= wanted) break; chosen += s to 0 }
        val ordered = when (arrange) {
            Arrange.STORY -> chosen.sortedWith(compareBy({ it.first.clip }, { it.first.startMs }, { it.second }))
            Arrange.BEST_FIRST -> chosen
        }
        val parts = mutableListOf<Segment>()
        var photos = 0
        for ((i, pick) in ordered.withIndex()) {
            val (shot, win) = pick
            val (slot, slotMs) = slots[i]
            // High-frame-rate shots play at half speed (not under a ramp, which has its own slow part).
            val speed = if (shot.slowMo && slot.curve == SpeedCurve.NONE && !shot.photo) 0.5f else 1f
            val need = if (shot.photo) slotMs.coerceAtMost(VideoProbe.IMAGE_MAX_MS) else MusicPicker.sourceMs(slotMs, speed, slot.curve)
            val sourceLen = min(shot.durationMs, need).coerceAtLeast(EditPlan.MIN_SEGMENT_MS)
            val (s, e) = window(shot, sourceLen, win)
            val pans = followCamera && shot.move in PANS && !shot.photo
            val afterTransition = parts.lastOrNull()?.transition?.let { it != Transition.NONE } == true
            val transition = when {
                i == ordered.lastIndex -> Transition.NONE
                pans && !(slot.transition == Transition.NONE && afterTransition) -> transitionFor(shot.move, slot.transition)
                slot.transition != Transition.NONE -> slot.transition
                shot.photo && ordered[i + 1].first.photo -> photoTransition
                else -> Transition.NONE
            }
            val motion = when {
                shot.photo -> if (slot.motion != Motion.NONE && !varyPhotos) slot.motion else PHOTO_MOVES[photos++ % PHOTO_MOVES.size]
                shot.move in PANS -> Motion.NONE // the camera already moves
                else -> slot.motion
            }
            parts += Segment(s, e, shot.clip, speed = speed, curve = if (shot.photo) SpeedCurve.NONE else slot.curve, transition = transition,
                transitionMs = if (transition == Transition.NONE) Segment.DEFAULT_TRANSITION_MS else transitionMsFor(slotMs), motion = motion)
        }
        return SmartResult(parts, shots.size, shots.count { it.shaky }, shots.count { it.blurry }, shots.size - groups,
            parts.count { it.speed < 1f }, chosen.size)
    }

    private val PANS = setOf(CameraMove.PAN_LEFT, CameraMove.PAN_RIGHT, CameraMove.TILT_UP, CameraMove.TILT_DOWN)

    /** The liveliest [len] window of [shot] (the second-best non-overlapping one for [index] 1), in clip time. */
    internal fun window(shot: Shot, len: Long, index: Int): Pair<Long, Long> {
        if (shot.durationMs <= len) return shot.startMs to shot.endMs
        val candidates = shot.curve.map { (t, _) -> (t - len / 2).coerceIn(shot.startMs, shot.endMs - len) }.distinct()
        fun scoreAt(s: Long) = shot.curve.filter { it.first in s..(s + len) }.map { it.second }.average().let { if (it.isNaN()) 0.0 else it }
        val best = candidates.maxByOrNull(::scoreAt) ?: shot.startMs
        if (index == 0) return best to best + len
        val second = candidates.filter { it + len <= best || it >= best + len }.maxByOrNull(::scoreAt)
            ?: return best to best + len
        return second to second + len
    }
}
