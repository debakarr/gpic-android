package com.gpic.android.editor

import kotlin.math.abs

/**
 * Time on the main track: output time (what the viewer sees, after speed and curves) ↔ a part and the time in
 * its clip; plus the edits that depend on it (split at the playhead, trim, duplicate, move, snap to beats).
 * Pure, so it's unit-tested. Parts play back to back; transitions happen inside the parts' own time, so they
 * never change the length.
 */
object Timeline {
    /** A speed curve is played as this many constant-speed steps (Media3 speed changes are piecewise constant). */
    const val CURVE_STEPS = 24
    private const val MIN_EFFECTIVE = 0.1f
    private const val MAX_EFFECTIVE = 10f

    /** (clip-time offset into the part where the step starts, speed) for each constant-speed step. */
    fun speedSteps(s: Segment): List<Pair<Long, Float>> {
        if (s.curve == SpeedCurve.NONE) return listOf(0L to s.speed)
        val d = s.durationMs
        return (0 until CURVE_STEPS).map { i ->
            val speed = (s.speed * s.curve.at((i + 0.5f) / CURVE_STEPS)).coerceIn(MIN_EFFECTIVE, MAX_EFFECTIVE)
            d * i / CURVE_STEPS to speed
        }
    }

    fun outputMs(s: Segment): Long {
        val steps = speedSteps(s)
        var out = 0.0
        for ((i, step) in steps.withIndex()) {
            val end = steps.getOrNull(i + 1)?.first ?: s.durationMs
            out += (end - step.first) / step.second.toDouble()
        }
        return out.toLong()
    }

    /** Output start of each part, plus the total length as a last entry. */
    fun starts(parts: List<Segment>): List<Long> {
        val out = ArrayList<Long>(parts.size + 1)
        var t = 0L
        for (p in parts) { out += t; t += p.outputMs }
        out += t
        return out
    }

    /** Clip time [clipMs] into part [s] → output time from the part's start. */
    fun outputOffset(s: Segment, clipMs: Long): Long {
        val steps = speedSteps(s)
        val x = (clipMs - s.startMs).coerceIn(0, s.durationMs)
        var out = 0.0
        for ((i, step) in steps.withIndex()) {
            val end = steps.getOrNull(i + 1)?.first ?: s.durationMs
            if (x <= step.first) break
            out += (minOf(end, x) - step.first) / step.second.toDouble()
        }
        return out.toLong()
    }

    /** Output offset into part [s] → time in its clip. */
    fun clipTime(s: Segment, outOffsetMs: Long): Long {
        val steps = speedSteps(s)
        var left = outOffsetMs.toDouble().coerceAtLeast(0.0)
        for ((i, step) in steps.withIndex()) {
            val end = steps.getOrNull(i + 1)?.first ?: s.durationMs
            val stepOut = (end - step.first) / step.second.toDouble()
            if (left <= stepOut || i == steps.lastIndex) {
                return (s.startMs + step.first + (minOf(left, stepOut) * step.second)).toLong().coerceIn(s.startMs, s.endMs)
            }
            left -= stepOut
        }
        return s.endMs
    }

    /** Which part plays at output time [outMs] (the last part at or past the end) and the time in its clip. */
    fun locate(parts: List<Segment>, outMs: Long): Pair<Int, Long>? {
        if (parts.isEmpty()) return null
        val st = starts(parts)
        val i = (parts.indices.lastOrNull { st[it] <= outMs } ?: 0)
        return i to clipTime(parts[i], outMs - st[i])
    }

    /** [plan] with "every clip in full" written out as real parts, so they can be edited one by one. */
    fun materialize(plan: EditPlan, durations: List<Long>): EditPlan =
        if (plan.segments.isNotEmpty()) plan else plan.copy(segments = durations.mapIndexed { i, d -> Segment(0, d, i) })

    /**
     * Splits the part under [outMs] in two. A curve can't be cut in half meaningfully, so both halves play at
     * the part's plain speed. Returns null when the playhead is too close to an edge.
     */
    fun split(plan: EditPlan, durations: List<Long>, outMs: Long): Pair<EditPlan, Long>? {
        val p = materialize(plan, durations)
        val (i, clipMs) = locate(p.segments, outMs) ?: return null
        val s = p.segments[i]
        if (clipMs - s.startMs < EditPlan.MIN_SEGMENT_MS || s.endMs - clipMs < EditPlan.MIN_SEGMENT_MS) return null
        val a = s.copy(endMs = clipMs, curve = SpeedCurve.NONE, transition = Transition.NONE)
        val b = s.copy(startMs = clipMs, curve = SpeedCurve.NONE, id = newId())
        val segs = p.segments.toMutableList().apply { set(i, a); add(i + 1, b) }
        return p.copy(segments = segs) to b.id
    }

    fun delete(plan: EditPlan, durations: List<Long>, id: Long): EditPlan {
        val p = materialize(plan, durations)
        if (p.segments.size <= 1) return p
        return p.copy(segments = p.segments.filter { it.id != id })
    }

    /** Copy of part [id] right after it; returns the copy's id. */
    fun duplicate(plan: EditPlan, durations: List<Long>, id: Long): Pair<EditPlan, Long> {
        val p = materialize(plan, durations)
        val i = p.segments.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return p to id
        val copy = p.segments[i].copy(id = newId())
        return p.copy(segments = p.segments.toMutableList().apply { add(i + 1, copy) }) to copy.id
    }

    /** Moves part [id] to position [to] (clamped). */
    fun move(plan: EditPlan, durations: List<Long>, id: Long, to: Int): EditPlan {
        val p = materialize(plan, durations)
        val i = p.segments.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return p
        val list = p.segments.toMutableList()
        val s = list.removeAt(i)
        list.add(to.coerceIn(0, list.size), s)
        return p.copy(segments = list)
    }

    /** New in/out points for part [id], kept inside its clip and at least [EditPlan.MIN_SEGMENT_MS] long. */
    fun trim(plan: EditPlan, durations: List<Long>, id: Long, startMs: Long? = null, endMs: Long? = null): EditPlan {
        val p = materialize(plan, durations)
        return p.copy(segments = p.segments.map { s ->
            if (s.id != id) return@map s
            val d = durations.getOrElse(s.clip) { s.endMs }
            val start = (startMs ?: s.startMs).coerceIn(0, s.endMs - EditPlan.MIN_SEGMENT_MS)
            val end = (endMs ?: s.endMs).coerceIn(start + EditPlan.MIN_SEGMENT_MS, d)
            s.copy(startMs = start, endMs = end)
        })
    }

    /**
     * Moves each cut onto the nearest beat (output time) by trimming the end of the part before it, never past
     * its clip and never shorter than [EditPlan.MIN_SEGMENT_MS]. [beatsMs] are beats in output time.
     */
    fun snapToBeats(plan: EditPlan, durations: List<Long>, beatsMs: List<Long>): EditPlan {
        if (beatsMs.size < 2) return plan
        val p = materialize(plan, durations)
        val out = mutableListOf<Segment>()
        var t = 0L
        for ((i, s) in p.segments.withIndex()) {
            val natural = t + s.outputMs
            if (i == p.segments.lastIndex) { out += s; break }
            // The nearest beat after the part's start that keeps it long enough, and that the clip can reach.
            val maxOut = t + outputOffset(s.copy(endMs = durations.getOrElse(s.clip) { s.endMs }), durations.getOrElse(s.clip) { s.endMs })
            val target = beatsMs.filter { it >= t + EditPlan.MIN_SEGMENT_MS && it <= maxOut }.minByOrNull { abs(it - natural) }
            val fixed = if (target == null) s else s.copy(endMs = clipTime(s.copy(endMs = durations.getOrElse(s.clip) { s.endMs }), target - t))
            out += fixed
            t += fixed.outputMs
        }
        return p.copy(segments = out)
    }
}
