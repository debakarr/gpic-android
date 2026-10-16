package com.gpic.android.editor

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** A one-tap look, LightCut-style: how long each cut is and how the result looks and sounds. */
enum class EditStyle(
    val label: String,
    val blurb: String,
    val cutMs: Long,
    val aspect: Aspect,
    val contrast: Float,
    val saturation: Float,
    val fades: Boolean,
    val cameraVolume: Float,
    val musicVolume: Float,
) {
    ACTION("Action", "Quick 1.5 s cuts, punchy colour", 1_500, Aspect.ORIGINAL, 0.15f, 15f, false, 0.3f, 1.0f),
    TRAVEL("Travel", "3 s moments, soft fades", 3_000, Aspect.ORIGINAL, 0.05f, 10f, true, 0.4f, 0.9f),
    CINEMATIC("Cinematic", "Long 4.5 s shots, 16:9, muted colour", 4_500, Aspect.WIDE, 0.12f, -12f, true, 0.2f, 1.0f),
    REEL("Reel", "Vertical 9:16, 2 s cuts", 2_000, Aspect.VERTICAL, 0.1f, 12f, false, 0.3f, 1.0f),
}

/** How "interesting" each moment of one clip is: one score per [stepMs], from frame-to-frame change and colour. */
class ClipScores(val durationMs: Long, val stepMs: Long, val scores: FloatArray)

/**
 * Picks the parts of several clips for a montage of about [targetMs]: the liveliest windows of [style]'s cut
 * length, shared between clips by length (every clip gets at least one when there's room), in clip order and in
 * time order within a clip. Pure, so it's unit-tested; the scores come from [VideoProbe.motionScores].
 */
object AutoEdit {
    fun pick(clips: List<ClipScores>, style: EditStyle, targetMs: Long): List<Segment> {
        if (clips.isEmpty()) return emptyList()
        val cut = style.cutMs
        val usable = clips.withIndex().filter { it.value.durationMs >= EditPlan.MIN_SEGMENT_MS }
        if (usable.isEmpty()) return emptyList()
        val total = usable.sumOf { it.value.durationMs }
        // A clip shorter than one cut is used whole; otherwise cuts are spread by clip length.
        val wanted = max(1, ceil(targetMs.toDouble() / cut).toInt())
        val shares = usable.associate { (i, c) ->
            i to (wanted * c.durationMs.toDouble() / total).roundToInt().coerceAtLeast(if (wanted >= usable.size) 1 else 0)
                .coerceAtMost(max(1, (c.durationMs / cut).toInt()))
        }.toMutableMap()
        // Fewer cuts than clips: keep the clips with the best single moment.
        if (wanted < usable.size) {
            val best = usable.sortedByDescending { (_, c) -> c.scores.maxOrNull() ?: 0f }.take(wanted).map { it.index }.toSet()
            shares.keys.forEach { shares[it] = if (it in best) 1 else 0 }
        }
        return usable.flatMap { (i, c) -> windows(c, shares[i] ?: 0, cut).map { (s, e) -> Segment(s, e, i) } }
    }

    /** The [n] best non-overlapping windows of [cut] ms in [c], in time order. */
    internal fun windows(c: ClipScores, n: Int, cut: Long): List<Pair<Long, Long>> {
        if (n <= 0) return emptyList()
        if (c.durationMs <= cut) return listOf(0L to c.durationMs)
        val steps = max(1, (cut / c.stepMs).toInt())
        // Score of a window starting at step k = mean of its steps (edges excluded: the very start/end of a
        // clip is usually the camera being switched on or off).
        val lastStart = ((c.durationMs - cut) / c.stepMs).toInt()
        val candidates = (0..lastStart).map { k ->
            val from = k.coerceAtMost(c.scores.size - 1)
            val to = (k + steps).coerceAtMost(c.scores.size)
            val mean = if (to > from) (from until to).map { c.scores[it] }.average().toFloat() else 0f
            val edge = if (k == 0 || k == lastStart) 0.85f else 1f
            k to mean * edge
        }.sortedByDescending { it.second }
        val taken = mutableListOf<Pair<Long, Long>>()
        for ((k, _) in candidates) {
            val start = k * c.stepMs
            val end = minOf(c.durationMs, start + cut)
            if (taken.none { (s, e) -> start < e && end > s }) taken += start to end
            if (taken.size == n) break
        }
        return taken.sortedBy { it.first }
    }

    /** The plan for [style]: the picked parts plus its look and audio mix. */
    fun plan(base: EditPlan, parts: List<Segment>, style: EditStyle, hasMusic: Boolean): EditPlan = base.copy(
        segments = parts,
        speed = 1f,
        aspect = style.aspect,
        contrast = style.contrast,
        saturation = style.saturation,
        fades = style.fades,
        keepOriginalAudio = true,
        originalVolume = if (hasMusic) style.cameraVolume else 1f,
        musicVolume = style.musicVolume,
    )
}
