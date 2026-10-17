package com.gpic.android.editor

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

private val ids = AtomicLong(System.nanoTime())
/** Ids for parts and texts: stable across edits so the timeline can keep a selection. */
fun newId(): Long = ids.incrementAndGet()

/** Switching between two parts, at the end of the part that has it. Effects run on both sides of the cut. */
enum class Transition(val label: String) {
    NONE("None"), FADE_BLACK("Fade"), FADE_WHITE("Flash"), DISSOLVE_BLUR("Blur"), ZOOM_IN("Zoom in"), ZOOM_OUT("Zoom out"),
    SLIDE_LEFT("Slide left"), SLIDE_RIGHT("Slide right"), SLIDE_UP("Slide up"), SLIDE_DOWN("Slide down"), SPIN("Spin"),
}

/** Slow camera move over a whole part (Ken Burns), like LightCut's Zoom. */
enum class Motion(val label: String) { NONE("None"), ZOOM_IN("Zoom in"), ZOOM_OUT("Zoom out"), PAN_LEFT("Pan left"), PAN_RIGHT("Pan right") }

/**
 * Speed ramps over a part, CapCut-style: (fraction of the part, speed multiplier) points, joined by straight lines.
 * The multiplier applies on top of the part's own speed.
 */
enum class SpeedCurve(val label: String, val points: List<Pair<Float, Float>>) {
    NONE("Normal", listOf(0f to 1f, 1f to 1f)),
    MONTAGE("Montage", listOf(0f to 2f, 0.25f to 2f, 0.35f to 0.5f, 0.55f to 0.5f, 0.65f to 2f, 1f to 2f)),
    HERO("Hero", listOf(0f to 1.5f, 0.35f to 1.5f, 0.45f to 0.3f, 0.6f to 0.3f, 0.7f to 1.5f, 1f to 1.5f)),
    BULLET("Bullet", listOf(0f to 2f, 0.4f to 2f, 0.5f to 0.25f, 0.7f to 0.25f, 0.8f to 2f, 1f to 2f)),
    JUMP_CUT("Jump cut", listOf(0f to 1f, 0.45f to 1f, 0.5f to 4f, 0.6f to 4f, 0.65f to 1f, 1f to 1f)),
    FLASH_IN("Flash in", listOf(0f to 0.5f, 1f to 3f)),
    FLASH_OUT("Flash out", listOf(0f to 3f, 1f to 0.5f));

    /** Multiplier at fraction [f] of the part. */
    fun at(f: Float): Float {
        val x = f.coerceIn(0f, 1f)
        for (i in 1 until points.size) {
            val (x1, y1) = points[i]
            val (x0, y0) = points[i - 1]
            if (x <= x1) return if (x1 > x0) y0 + (y1 - y0) * (x - x0) / (x1 - x0) else y1
        }
        return points.last().second
    }
}

/** A part of one source clip ([clip] = index into the editor's clip list) on the main track, in that clip's time. */
data class Segment(
    val startMs: Long,
    val endMs: Long,
    val clip: Int = 0,
    val speed: Float = 1f,
    val curve: SpeedCurve = SpeedCurve.NONE,
    /** Camera sound of this part, 0..2 (on top of the master volume). */
    val volume: Float = 1f,
    /** Null = the whole video's look. */
    val look: Look? = null,
    val motion: Motion = Motion.NONE,
    /** Extra clockwise rotation: 0, 90, 180 or 270. */
    val rotation: Int = 0,
    val mirror: Boolean = false,
    /** Into the next part. */
    val transition: Transition = Transition.NONE,
    val transitionMs: Long = DEFAULT_TRANSITION_MS,
    val id: Long = newId(),
) {
    val durationMs: Long get() = endMs - startMs

    /** How long this part plays: its clip time through the speed and any curve. */
    val outputMs: Long get() = Timeline.outputMs(this)

    companion object {
        const val DEFAULT_TRANSITION_MS = 600L
    }
}

enum class Aspect(val label: String, val ratio: Float?) {
    ORIGINAL("Original", null),
    VERTICAL("9:16", 9f / 16f),
    WIDE("16:9", 16f / 9f),
    SQUARE("1:1", 1f),
    FOUR_FIVE("4:5", 4f / 5f),
    FOUR_THREE("4:3", 4f / 3f),
    CINEMA("21:9", 21f / 9f),
}

/** What fills the frame around a picture that doesn't match the chosen shape. */
enum class Canvas(val label: String) { CROP("Fill (crop)"), BLACK("Black bars"), BLUR("Blurred") }

enum class TextStyle(val label: String) { PLAIN("Plain"), OUTLINE("Outline"), SHADOW("Shadow"), BOX("Box"), NEON("Neon") }

/** Android's built-in font families, so nothing extra ships with the app. */
enum class TextFont(val label: String, val family: String, val bold: Boolean) {
    SANS_BOLD("Bold", "sans-serif", true),
    SANS("Regular", "sans-serif", false),
    CONDENSED("Condensed", "sans-serif-condensed", true),
    BLACK("Heavy", "sans-serif-black", false),
    SERIF("Serif", "serif", true),
    MONO("Mono", "monospace", true),
    SCRIPT("Script", "cursive", true),
    CASUAL("Casual", "casual", false),
}

enum class TextAnim(val label: String) { NONE("None"), FADE("Fade"), POP("Pop"), SLIDE_UP("Slide up"), TYPEWRITER("Typewriter") }

/** A title, caption or sticker (emoji) on top of the video, in output time. */
data class TextItem(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    /** Centre, as a fraction of the frame. */
    val x: Float = 0.5f,
    val y: Float = 0.8f,
    /** Text height as a fraction of the frame's short side. */
    val size: Float = 0.08f,
    val color: Int = 0xFFFFFFFF.toInt(),
    val style: TextStyle = TextStyle.OUTLINE,
    val font: TextFont = TextFont.SANS_BOLD,
    val animIn: TextAnim = TextAnim.FADE,
    val animOut: TextAnim = TextAnim.FADE,
    val sticker: Boolean = false,
    val id: Long = newId(),
)

/** Background music: a file GPic keeps (Openverse download, picked file, or audio taken from a clip). */
data class MusicTrack(
    val uri: String,
    val title: String,
    /** Attribution to show with the video (Creative Commons), when it came from Openverse. */
    val credit: String? = null,
    /** Length of the song (0 = unknown); the music is repeated to cover a longer video. */
    val durationMs: Long = 0,
    val volume: Float = 0.8f,
    /** Where in the track to start. */
    val startMs: Long = 0,
    val fadeInMs: Long = 0,
    val fadeOutMs: Long = 1500,
    /** Beat times in the track (ms), when detected. */
    val beatsMs: List<Long> = emptyList(),
    val bpm: Float = 0f,
)

/**
 * Everything the editor does to the clips. The controls edit it directly; Claude returns one (as JSON) that is
 * checked with [sanitized] before it touches the controls; drafts are saved as [toJson].
 */
data class EditPlan(
    /** Parts on the main track, in playback order (may jump between clips). Empty = every clip in full, in order. */
    val segments: List<Segment> = emptyList(),
    /** The whole video's look; a part with its own [Segment.look] uses that instead. */
    val look: Look = Look(),
    val keepOriginalAudio: Boolean = true,
    /** Camera sound master volume, 0..2. */
    val originalVolume: Float = 1f,
    val music: MusicTrack? = null,
    val texts: List<TextItem> = emptyList(),
    val aspect: Aspect = Aspect.ORIGINAL,
    val canvas: Canvas = Canvas.CROP,
    /** Export: short side cap (0 = source, else 720/1080/1440/2160) and frame rate (0 = source). */
    val maxHeight: Int = 0,
    val fps: Int = 0,
) {
    /** The parts that play, with "empty" spelled out as every clip in full. */
    fun parts(durations: List<Long>): List<Segment> =
        segments.ifEmpty { durations.mapIndexed { i, d -> Segment(0, d, i, id = -(i + 1L)) } }

    fun outputMs(durations: List<Long>): Long = parts(durations).sumOf { it.outputMs }
    fun outputMs(durationMs: Long): Long = outputMs(listOf(durationMs))

    /** The look a part plays with. */
    fun lookOf(s: Segment): Look = s.look ?: look

    fun sanitized(durationMs: Long, lutIds: Set<String>): EditPlan = sanitized(listOf(durationMs), lutIds)

    /**
     * A plan the exporter can always run: parts of unknown clips dropped, ranges clamped into their clip, slivers
     * dropped; numbers clamped; unknown LUTs dropped; texts clamped into the video.
     */
    fun sanitized(durations: List<Long>, lutIds: Set<String>): EditPlan {
        val segs = segments
            .filter { it.clip in durations.indices }
            .map { s ->
                val d = durations[s.clip]
                s.copy(
                    startMs = s.startMs.coerceIn(0, d), endMs = s.endMs.coerceIn(0, d),
                    speed = s.speed.finiteOr(1f).coerceIn(MIN_SPEED, MAX_SPEED),
                    volume = s.volume.finiteOr(1f).coerceIn(0f, 2f),
                    look = s.look?.sanitized(lutIds),
                    rotation = s.rotation.takeIf { it in setOf(0, 90, 180, 270) } ?: 0,
                    transitionMs = s.transitionMs.coerceIn(200, 2000),
                )
            }
            .filter { it.durationMs >= MIN_SEGMENT_MS }
            .take(MAX_SEGMENTS)
            .let { list -> if (list.isEmpty()) list else list.dropLast(1) + list.last().copy(transition = Transition.NONE) }
        val total = (if (segs.isEmpty()) durations.mapIndexed { i, d -> Segment(0, d, i) } else segs).sumOf { it.outputMs }
        return copy(
            segments = segs,
            look = look.sanitized(lutIds),
            originalVolume = originalVolume.finiteOr(1f).coerceIn(0f, 2f),
            music = music?.let { m ->
                m.copy(
                    volume = m.volume.finiteOr(0.8f).coerceIn(0f, 2f), startMs = m.startMs.coerceAtLeast(0),
                    fadeInMs = m.fadeInMs.coerceIn(0, 10_000), fadeOutMs = m.fadeOutMs.coerceIn(0, 10_000),
                )
            },
            texts = texts.mapNotNull { t ->
                val start = t.startMs.coerceIn(0, maxOf(0, total - MIN_SEGMENT_MS))
                val end = t.endMs.coerceIn(start + MIN_SEGMENT_MS, maxOf(start + MIN_SEGMENT_MS, total))
                t.copy(
                    text = t.text.take(MAX_TEXT), startMs = start, endMs = end,
                    x = t.x.finiteOr(0.5f).coerceIn(0f, 1f), y = t.y.finiteOr(0.8f).coerceIn(0f, 1f),
                    size = t.size.finiteOr(0.07f).coerceIn(0.02f, 0.4f),
                ).takeIf { it.text.isNotBlank() }
            }.take(MAX_TEXTS),
            maxHeight = maxHeight.takeIf { it in RESOLUTIONS } ?: 0,
            fps = fps.takeIf { it in FRAME_RATES } ?: 0,
        )
    }

    // ---- JSON (drafts; Claude uses the same field names, in seconds) ----------------------------------------

    fun toJson(): JSONObject = JSONObject()
        .put("segments", JSONArray(segments.map { segmentJson(it) }))
        .put("look", lookJson(look))
        .put("keep_original_audio", keepOriginalAudio)
        .put("original_volume", r3(originalVolume))
        .put("music", music?.let { m ->
            JSONObject().put("uri", m.uri).put("title", m.title).put("credit", m.credit ?: JSONObject.NULL).put("duration_ms", m.durationMs)
                .put("volume", r3(m.volume)).put("start_s", m.startMs / 1000.0)
                .put("fade_in_s", m.fadeInMs / 1000.0).put("fade_out_s", m.fadeOutMs / 1000.0)
                .put("beats_ms", JSONArray(m.beatsMs)).put("bpm", r3(m.bpm))
        } ?: JSONObject.NULL)
        .put("texts", JSONArray(texts.map { textJson(it) }))
        .put("aspect", aspect.name)
        .put("canvas", canvas.name)
        .put("max_height", maxHeight)
        .put("fps", fps)

    companion object {
        const val MIN_SEGMENT_MS = 300L
        const val MAX_SEGMENTS = 80
        const val MAX_TEXTS = 30
        const val MAX_TEXT = 200
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 4f
        const val NO_LUT = "none"
        val RESOLUTIONS = listOf(0, 720, 1080, 1440, 2160)
        val FRAME_RATES = listOf(0, 24, 25, 30, 50, 60)

        fun segmentJson(s: Segment): JSONObject = JSONObject()
            .put("clip", s.clip).put("start_s", s.startMs / 1000.0).put("end_s", s.endMs / 1000.0)
            .put("speed", r3(s.speed)).put("curve", s.curve.name).put("volume", r3(s.volume))
            .put("look", s.look?.let { lookJson(it) } ?: JSONObject.NULL)
            .put("motion", s.motion.name).put("rotation", s.rotation).put("mirror", s.mirror)
            .put("transition", s.transition.name).put("transition_s", s.transitionMs / 1000.0)

        fun lookJson(l: Look): JSONObject = JSONObject()
            .put("lut", l.lutId ?: NO_LUT)
            .put("filter", l.filter?.name ?: "NONE")
            .put("filter_amount", r3(l.filterAmount))
            .apply { Look.ADJUSTMENTS.forEach { a -> put(a.key, r3(a.get(l))) } }

        fun textJson(t: TextItem): JSONObject = JSONObject()
            .put("text", t.text).put("start_s", t.startMs / 1000.0).put("end_s", t.endMs / 1000.0)
            .put("x", r3(t.x)).put("y", r3(t.y)).put("size", r3(t.size))
            .put("color", "#%06X".format(t.color and 0xFFFFFF)).put("style", t.style.name).put("font", t.font.name)
            .put("anim_in", t.animIn.name).put("anim_out", t.animOut.name).put("sticker", t.sticker)

        /** Reads what [toJson] or Claude wrote; missing fields keep [base]'s values. Call [sanitized] after. */
        fun fromJson(o: JSONObject, base: EditPlan = EditPlan()): EditPlan {
            val segs = o.optJSONArray("segments")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { segmentFrom(it, base.segments.getOrNull(i)) } }
            } ?: base.segments
            return EditPlan(
                segments = segs,
                look = o.optJSONObject("look")?.let { lookFrom(it, base.look) } ?: base.look,
                keepOriginalAudio = o.optBoolean("keep_original_audio", base.keepOriginalAudio),
                originalVolume = o.optDouble("original_volume", r3(base.originalVolume)).toFloat(),
                music = when {
                    !o.has("music") -> base.music
                    o.isNull("music") -> null
                    else -> o.optJSONObject("music")?.let { m ->
                        val b = base.music
                        MusicTrack(
                            uri = m.optString("uri").ifEmpty { b?.uri ?: return@let null },
                            title = m.optString("title").ifEmpty { b?.title ?: "Music" },
                            credit = if (m.has("credit") && !m.isNull("credit")) m.optString("credit") else b?.credit,
                            durationMs = m.optLong("duration_ms", b?.durationMs ?: 0),
                            volume = m.optDouble("volume", (b?.volume ?: 0.8f).toDouble()).toFloat(),
                            startMs = (m.optDouble("start_s", (b?.startMs ?: 0) / 1000.0) * 1000).toLong(),
                            fadeInMs = (m.optDouble("fade_in_s", (b?.fadeInMs ?: 0) / 1000.0) * 1000).toLong(),
                            fadeOutMs = (m.optDouble("fade_out_s", (b?.fadeOutMs ?: 1500) / 1000.0) * 1000).toLong(),
                            beatsMs = m.optJSONArray("beats_ms")?.let { a -> (0 until a.length()).map { a.optLong(it) } } ?: b?.beatsMs.orEmpty(),
                            bpm = m.optDouble("bpm", (b?.bpm ?: 0f).toDouble()).toFloat(),
                        )
                    }
                },
                texts = o.optJSONArray("texts")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { textFrom(it, base.texts.getOrNull(i)) } }
                } ?: base.texts,
                aspect = enumOr(o.optString("aspect"), base.aspect),
                canvas = enumOr(o.optString("canvas"), base.canvas),
                maxHeight = o.optInt("max_height", base.maxHeight),
                fps = o.optInt("fps", base.fps),
            )
        }

        private fun segmentFrom(s: JSONObject, b: Segment?) = Segment(
            startMs = (s.optDouble("start_s", 0.0) * 1000).toLong(),
            endMs = (s.optDouble("end_s", 0.0) * 1000).toLong(),
            clip = s.optInt("clip", 0),
            speed = s.optDouble("speed", 1.0).toFloat(),
            curve = enumOr(s.optString("curve"), SpeedCurve.NONE),
            volume = s.optDouble("volume", 1.0).toFloat(),
            look = s.optJSONObject("look")?.let { lookFrom(it, b?.look ?: Look()) },
            motion = enumOr(s.optString("motion"), Motion.NONE),
            rotation = s.optInt("rotation", 0),
            mirror = s.optBoolean("mirror", false),
            transition = enumOr(s.optString("transition"), Transition.NONE),
            transitionMs = (s.optDouble("transition_s", Segment.DEFAULT_TRANSITION_MS / 1000.0) * 1000).toLong(),
        )

        private fun lookFrom(o: JSONObject, base: Look): Look {
            var l = base.copy(
                lutId = if (o.has("lut")) o.optString("lut").takeIf { it.isNotEmpty() && it != NO_LUT } else base.lutId,
                filter = if (o.has("filter")) Filter.entries.firstOrNull { it.name == o.optString("filter") } else base.filter,
                filterAmount = o.optDouble("filter_amount", r3(base.filterAmount)).toFloat(),
            )
            Look.ADJUSTMENTS.forEach { a -> if (o.has(a.key)) l = a.set(l, o.optDouble(a.key, 0.0).toFloat()) }
            return l
        }

        private fun textFrom(t: JSONObject, b: TextItem?): TextItem {
            val base = b ?: TextItem("", 0, 0)
            return base.copy(
                text = t.optString("text", base.text),
                startMs = (t.optDouble("start_s", base.startMs / 1000.0) * 1000).toLong(),
                endMs = (t.optDouble("end_s", base.endMs / 1000.0) * 1000).toLong(),
                x = t.optDouble("x", r3(base.x)).toFloat(),
                y = t.optDouble("y", r3(base.y)).toFloat(),
                size = t.optDouble("size", r3(base.size)).toFloat(),
                color = t.optString("color").removePrefix("#").toLongOrNull(16)?.let { (it.toInt() and 0xFFFFFF) or (0xFF shl 24) } ?: base.color,
                style = enumOr(t.optString("style"), base.style),
                font = enumOr(t.optString("font"), base.font),
                animIn = enumOr(t.optString("anim_in"), base.animIn),
                animOut = enumOr(t.optString("anim_out"), base.animOut),
                sticker = t.optBoolean("sticker", base.sticker),
                id = b?.id ?: newId(),
            )
        }

        private inline fun <reified E : Enum<E>> enumOr(name: String, default: E): E =
            enumValues<E>().firstOrNull { it.name == name } ?: default

        private fun Float.finiteOr(d: Float) = if (isFinite()) this else d
    }
}

/** Floats as JSON numbers without float noise (0.8f → 0.8, not 0.800000011920929). */
internal fun r3(f: Float): Double = Math.round(f * 1000.0) / 1000.0
