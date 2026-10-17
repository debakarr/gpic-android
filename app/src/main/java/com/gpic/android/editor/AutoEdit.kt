package com.gpic.android.editor

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** A one-tap look, LightCut-style: how long each cut is, how it moves between parts, and how it looks and sounds. */
enum class EditStyle(
    val label: String,
    val blurb: String,
    val cutMs: Long,
    /** Cut length in beats when there's music with detected beats. */
    val beatsPerCut: Int,
    val aspect: Aspect,
    val filter: Filter?,
    val transition: Transition,
    val motion: Motion,
    val cameraVolume: Float,
    val musicVolume: Float,
) {
    ACTION("Action", "Quick cuts on the beat, punchy colour", 1_500, 2, Aspect.ORIGINAL, Filter.PUNCH, Transition.ZOOM_IN, Motion.NONE, 0.3f, 1.0f),
    TRAVEL("Travel", "3 s moments, soft fades, warm colour", 3_000, 4, Aspect.ORIGINAL, Filter.GOLDEN, Transition.FADE_BLACK, Motion.ZOOM_IN, 0.4f, 0.9f),
    CINEMATIC("Cinematic", "Long shots, 21:9, film colour", 4_500, 8, Aspect.CINEMA, Filter.FILM, Transition.DISSOLVE_BLUR, Motion.PAN_RIGHT, 0.2f, 1.0f),
    REEL("Reel", "Vertical 9:16, fast cuts, vivid", 2_000, 2, Aspect.VERTICAL, Filter.VIVID, Transition.SLIDE_LEFT, Motion.NONE, 0.3f, 1.0f),
    VLOG("Vlog", "Keeps the talking, light fades", 5_000, 8, Aspect.ORIGINAL, Filter.WARM, Transition.FADE_WHITE, Motion.NONE, 1.0f, 0.4f),
}

/** How "interesting" each moment of one clip is: one score per [stepMs], from frame-to-frame change and colour. */
class ClipScores(val durationMs: Long, val stepMs: Long, val scores: FloatArray)

/**
 * Picks the parts of several clips for a montage of about [targetMs]: the liveliest windows of [style]'s cut
 * length, shared between clips by length (every clip gets at least one when there's room), in clip order and in
 * time order within a clip. Pure, so it's unit-tested; the scores come from [VideoProbe.motionScores].
 */
object AutoEdit {
    fun pick(clips: List<ClipScores>, style: EditStyle, targetMs: Long, cutMs: Long = style.cutMs): List<Segment> {
        if (clips.isEmpty()) return emptyList()
        val cut = cutMs.coerceAtLeast(EditPlan.MIN_SEGMENT_MS)
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

    /**
     * Cut length for [style] on a song at [bpm]: a whole number of beats, close to the style's own length.
     * Without a tempo it's the style's length.
     */
    fun beatCutMs(style: EditStyle, bpm: Float): Long {
        if (bpm <= 0f) return style.cutMs
        val beat = 60_000.0 / bpm
        val beats = max(1, (style.cutMs / beat).roundToInt().let { if (it <= 0) style.beatsPerCut else it })
        return (beat * beats).roundToLong()
    }

    /**
     * The plan for [style]: the picked parts plus its look, transitions, motion and audio mix. With beats, the
     * music starts on a beat so cuts of [beatCutMs] land on the beat.
     */
    fun plan(base: EditPlan, parts: List<Segment>, style: EditStyle): EditPlan {
        val music = base.music
        val hasMusic = music != null
        return base.copy(
            segments = parts.mapIndexed { i, s ->
                s.copy(
                    speed = 1f, curve = SpeedCurve.NONE, look = null,
                    motion = style.motion,
                    transition = if (i == parts.lastIndex) Transition.NONE else style.transition,
                )
            },
            aspect = style.aspect,
            canvas = if (style.aspect == Aspect.ORIGINAL) base.canvas else Canvas.BLUR,
            look = base.look.copy(filter = style.filter, filterAmount = 0.8f),
            keepOriginalAudio = true,
            originalVolume = if (hasMusic) style.cameraVolume else 1f,
            music = music?.let { m ->
                // Start the song on its first strong beat so the first cut lands on one.
                val first = m.beatsMs.firstOrNull { it >= m.startMs } ?: m.startMs
                m.copy(volume = style.musicVolume, startMs = if (m.beatsMs.isNotEmpty()) first else m.startMs)
            },
        )
    }
}
