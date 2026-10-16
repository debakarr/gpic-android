package com.gpic.android.editor

import org.json.JSONArray
import org.json.JSONObject

/** A part of the source video to keep, in source time. */
data class Segment(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
}

enum class Aspect(val label: String, val ratio: Float?) {
    ORIGINAL("Original", null),
    WIDE("16:9", 16f / 9f),
    VERTICAL("9:16", 9f / 16f),
    SQUARE("1:1", 1f),
}

/**
 * Everything the editor can do to one video. The controls edit it directly, and Claude returns one
 * (as JSON) that is checked with [sanitized] before it touches the controls.
 */
data class EditPlan(
    /** Parts to keep, in order. Empty = the whole video. */
    val segments: List<Segment> = emptyList(),
    val speed: Float = 1f,
    /** Id from [LutStore], or null for no colour LUT. */
    val lutId: String? = null,
    /** -0.5..0.5, 0 = unchanged. */
    val brightness: Float = 0f,
    /** -0.5..0.5, 0 = unchanged. */
    val contrast: Float = 0f,
    /** -50..50, 0 = unchanged. */
    val saturation: Float = 0f,
    val keepOriginalAudio: Boolean = true,
    /** 0..2. */
    val originalVolume: Float = 1f,
    /** Background music, when a track is chosen: volume 0..2 and where in the track to start. */
    val musicVolume: Float = 0.8f,
    val musicStartMs: Long = 0,
    val aspect: Aspect = Aspect.ORIGINAL,
    /** Output height cap: 0 = keep, else 720 / 1080. */
    val maxHeight: Int = 0,
    /** Extra clockwise rotation: 0, 90, 180 or 270. */
    val rotation: Int = 0,
) {
    /** Output length before speed is applied. */
    fun keptMs(durationMs: Long): Long = if (segments.isEmpty()) durationMs else segments.sumOf { it.durationMs }

    fun outputMs(durationMs: Long): Long = (keptMs(durationMs) / speed).toLong()

    /**
     * A plan the exporter can always run: ranges clamped into the video, sorted, overlaps merged and
     * slivers dropped; numbers clamped; an unknown LUT dropped.
     */
    fun sanitized(durationMs: Long, lutIds: Set<String>): EditPlan {
        val segs = segments
            .map { Segment(it.startMs.coerceIn(0, durationMs), it.endMs.coerceIn(0, durationMs)) }
            .filter { it.durationMs >= MIN_SEGMENT_MS }
            .sortedBy { it.startMs }
            .fold(mutableListOf<Segment>()) { acc, s ->
                val last = acc.lastOrNull()
                if (last != null && s.startMs <= last.endMs) acc[acc.lastIndex] = Segment(last.startMs, maxOf(last.endMs, s.endMs))
                else acc += s
                acc
            }
            .take(MAX_SEGMENTS)
        return copy(
            // One range covering everything is the same as "whole video".
            segments = if (segs.size == 1 && segs[0].startMs == 0L && segs[0].endMs == durationMs) emptyList() else segs,
            speed = speed.takeIf { it.isFinite() }?.coerceIn(MIN_SPEED, MAX_SPEED) ?: 1f,
            lutId = lutId?.takeIf { it in lutIds },
            brightness = brightness.finiteOr(0f).coerceIn(-0.5f, 0.5f),
            contrast = contrast.finiteOr(0f).coerceIn(-0.5f, 0.5f),
            saturation = saturation.finiteOr(0f).coerceIn(-50f, 50f),
            originalVolume = originalVolume.finiteOr(1f).coerceIn(0f, 2f),
            musicVolume = musicVolume.finiteOr(0.8f).coerceIn(0f, 2f),
            musicStartMs = musicStartMs.coerceAtLeast(0),
            maxHeight = maxHeight.takeIf { it in setOf(0, 720, 1080) } ?: 0,
            rotation = rotation.takeIf { it in setOf(0, 90, 180, 270) } ?: 0,
        )
    }

    /** Same field names as [schema], in seconds. */
    fun toJson(): JSONObject = JSONObject()
        .put("segments", JSONArray(segments.map { JSONObject().put("start_s", it.startMs / 1000.0).put("end_s", it.endMs / 1000.0) }))
        .put("speed", speed.toDouble())
        .put("lut", lutId ?: NO_LUT)
        .put("brightness", brightness.toDouble())
        .put("contrast", contrast.toDouble())
        .put("saturation", saturation.toDouble())
        .put("keep_original_audio", keepOriginalAudio)
        .put("original_volume", originalVolume.toDouble())
        .put("music_volume", musicVolume.toDouble())
        .put("music_start_s", musicStartMs / 1000.0)
        .put("aspect", aspect.name)
        .put("max_height", maxHeight)
        .put("rotation", rotation)

    companion object {
        const val MIN_SEGMENT_MS = 300L
        const val MAX_SEGMENTS = 50
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 4f
        const val NO_LUT = "none"

        /** Reads what Claude (or [toJson]) wrote; missing fields keep [base]'s values. Call [sanitized] after. */
        fun fromJson(o: JSONObject, base: EditPlan = EditPlan()): EditPlan {
            val segs = o.optJSONArray("segments")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val s = arr.optJSONObject(i) ?: return@mapNotNull null
                    Segment((s.optDouble("start_s", 0.0) * 1000).toLong(), (s.optDouble("end_s", 0.0) * 1000).toLong())
                }
            } ?: base.segments
            return EditPlan(
                segments = segs,
                speed = o.optDouble("speed", base.speed.toDouble()).toFloat(),
                lutId = if (o.has("lut")) o.optString("lut").takeIf { it.isNotEmpty() && it != NO_LUT } else base.lutId,
                brightness = o.optDouble("brightness", base.brightness.toDouble()).toFloat(),
                contrast = o.optDouble("contrast", base.contrast.toDouble()).toFloat(),
                saturation = o.optDouble("saturation", base.saturation.toDouble()).toFloat(),
                keepOriginalAudio = o.optBoolean("keep_original_audio", base.keepOriginalAudio),
                originalVolume = o.optDouble("original_volume", base.originalVolume.toDouble()).toFloat(),
                musicVolume = o.optDouble("music_volume", base.musicVolume.toDouble()).toFloat(),
                musicStartMs = (o.optDouble("music_start_s", base.musicStartMs / 1000.0) * 1000).toLong(),
                aspect = Aspect.entries.firstOrNull { it.name == o.optString("aspect") } ?: base.aspect,
                maxHeight = o.optInt("max_height", base.maxHeight),
                rotation = o.optInt("rotation", base.rotation),
            )
        }

        /**
         * JSON schema for Claude's structured output: the whole plan plus an `explanation` for the user.
         * Ranges live in the descriptions (strict schemas don't take min/max); [sanitized] enforces them.
         */
        fun schema(lutIds: List<String>): JSONObject {
            fun num(desc: String) = JSONObject().put("type", "number").put("description", desc)
            val segment = JSONObject().put("type", "object").put("additionalProperties", false)
                .put("properties", JSONObject()
                    .put("start_s", num("Start, seconds into the source video"))
                    .put("end_s", num("End, seconds into the source video")))
                .put("required", JSONArray(listOf("start_s", "end_s")))
            val props = JSONObject()
                .put("explanation", JSONObject().put("type", "string")
                    .put("description", "One or two sentences for the user: what you changed and why, or what you couldn't do"))
                .put("segments", JSONObject().put("type", "array").put("items", segment)
                    .put("description", "Parts of the source to keep, in playback order. Empty array = whole video"))
                .put("speed", num("Playback speed, 0.25 to 4. 1 = normal"))
                .put("lut", JSONObject().put("type", "string").put("enum", JSONArray(lutIds + NO_LUT))
                    .put("description", "Colour LUT id, or \"none\""))
                .put("brightness", num("-0.5 to 0.5, 0 = unchanged"))
                .put("contrast", num("-0.5 to 0.5, 0 = unchanged"))
                .put("saturation", num("-50 to 50, 0 = unchanged"))
                .put("keep_original_audio", JSONObject().put("type", "boolean"))
                .put("original_volume", num("Camera audio volume, 0 to 2. 1 = unchanged"))
                .put("music_volume", num("Background music volume, 0 to 2; ignored without a music track"))
                .put("music_start_s", num("Where in the music track to start, seconds"))
                .put("aspect", JSONObject().put("type", "string").put("enum", JSONArray(Aspect.entries.map { it.name }))
                    .put("description", "Output frame shape; non-ORIGINAL crops to fill"))
                .put("max_height", JSONObject().put("type", "integer").put("enum", JSONArray(listOf(0, 720, 1080)))
                    .put("description", "Output height cap in pixels; 0 = keep the source size"))
                .put("rotation", JSONObject().put("type", "integer").put("enum", JSONArray(listOf(0, 90, 180, 270)))
                    .put("description", "Extra clockwise rotation in degrees"))
            val names = props.keys().asSequence().toList()
            return JSONObject().put("type", "object").put("additionalProperties", false)
                .put("properties", props).put("required", JSONArray(names))
        }

        private fun Float.finiteOr(d: Float) = if (isFinite()) this else d
    }
}
