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
        val step = if (f.size > 1) (f.last().tMs - f.first().tMs) / (f.size - 1) else a.durationMs
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
            // Shake: the camera's motion jumping around from sample to sample, rather than moving smoothly.
            val jitter = if (fr.size >= 3) fr.zipWithNext { p, q -> hypot(q.dx - p.dx, q.dy - p.dy) }.average().toFloat() else 0f
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

    /** Puts near-identical shots (same scene filmed twice, a long still shot cut in two) in one group. */
    fun group(shots: List<Shot>, similar: Float = 0.96f): Int {
        var groups = 0
        for ((i, s) in shots.withIndex()) {
            val match = shots.subList(0, i).firstOrNull { cosine(it.embed, s.embed) >= similar }
            s.group = match?.group ?: groups++
        }
        return groups
    }

    /** Transition that continues the camera's motion out of a shot (a "match cut"), else the style's own. */
    fun transitionFor(move: CameraMove, fallback: Transition): Transition = when (move) {
        CameraMove.PAN_LEFT -> Transition.SLIDE_RIGHT
        CameraMove.PAN_RIGHT -> Transition.SLIDE_LEFT
        CameraMove.TILT_UP -> Transition.SLIDE_DOWN
        CameraMove.TILT_DOWN -> Transition.SLIDE_UP
        else -> fallback
    }

    /**
     * Picks shots for about [targetMs] of [cutMs] slots. Shaky, blurry and dark shots are used only when nothing
     * better is left; one shot per near-duplicate group until every group has had a turn; a long, good shot can
     * give a second slot. High-frame-rate shots play at half speed (LightCut's slow shots).
     */
    fun pick(analyses: List<ClipAnalysis>, targetMs: Long, cutMs: Long, arrange: Arrange, style: EditStyle): SmartResult {
        val cut0 = cutMs.coerceAtLeast(EditPlan.MIN_SEGMENT_MS)
        val n = max(1, ((targetMs + cut0 / 2) / cut0).toInt())
        return pickSlots(analyses, List(n) { TemplateSlot(0, style.transition) to cut0 }, arrange, followCamera = true)
    }

    /**
     * Fills template slots (output length each, with their transition, speed ramp and camera move) with the best
     * shots, in [arrange] order. A slot's transition NONE follows the camera's motion when [followCamera]. Photos fill
     * any slot up to 10 s, with a slow zoom when the slot has no move of its own.
     */
    fun pickSlots(analyses: List<ClipAnalysis>, slots: List<Pair<TemplateSlot, Long>>, arrange: Arrange, followCamera: Boolean = true): SmartResult {
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
        val parts = ordered.mapIndexed { i, (shot, win) ->
            val (slot, slotMs) = slots[i]
            // High-frame-rate shots play at half speed (not under a ramp, which has its own slow part).
            val speed = if (shot.slowMo && slot.curve == SpeedCurve.NONE && !shot.photo) 0.5f else 1f
            val need = if (shot.photo) slotMs.coerceAtMost(VideoProbe.IMAGE_MAX_MS) else MusicPicker.sourceMs(slotMs, speed, slot.curve)
            val sourceLen = min(shot.durationMs, need).coerceAtLeast(EditPlan.MIN_SEGMENT_MS)
            val (s, e) = window(shot, sourceLen, win)
            val transition = when {
                i == ordered.lastIndex -> Transition.NONE
                slot.transition != Transition.NONE && !(followCamera && shot.move in PANS) -> slot.transition
                followCamera -> transitionFor(shot.move, slot.transition)
                else -> slot.transition
            }
            val motion = if (slot.motion == Motion.NONE && shot.photo) (if (i % 2 == 0) Motion.ZOOM_IN else Motion.ZOOM_OUT) else slot.motion
            Segment(s, e, shot.clip, speed = speed, curve = if (shot.photo) SpeedCurve.NONE else slot.curve, transition = transition,
                motion = if (shot.move in PANS && !shot.photo) Motion.NONE else motion)
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
