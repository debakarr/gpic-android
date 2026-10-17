package com.gpic.android.editor

import kotlin.math.max
import kotlin.math.min

/** One slot of a template: how many beats it lasts and what happens in it. */
data class TemplateSlot(
    val beats: Int,
    val transition: Transition = Transition.NONE,
    val curve: SpeedCurve = SpeedCurve.NONE,
    val motion: Motion = Motion.NONE,
)

/**
 * LightCut/CapCut-style templates: a music mood, a rhythm of beat-length slots (repeated to the chosen length) with
 * their own transitions, speed ramps and camera moves, a look, a shape and optional title cards. GPic's own designs;
 * the shots are chosen by [SmartEdit] and the music by [MusicPicker] (Openverse), all on the phone.
 */
enum class VideoTemplate(
    val label: String,
    val blurb: String,
    /** Openverse search for matching music. */
    val musicQuery: String,
    val aspect: Aspect,
    val canvas: Canvas,
    val filter: Filter?,
    val filterAmount: Float,
    val slots: List<TemplateSlot>,
    val title: String?,
    val endCard: Boolean,
    val defaultSec: Int,
    /** Two colours for the template's card in the gallery (ARGB). */
    val colors: Pair<Long, Long>,
) {
    BEAT_DROP("Beat Drop", "Fast cuts on every beat, zoom hits, vertical", "electronic dance", Aspect.VERTICAL, Canvas.BLUR, Filter.PUNCH, 0.8f,
        listOf(TemplateSlot(2, Transition.ZOOM_IN), TemplateSlot(2, Transition.ZOOM_IN), TemplateSlot(1), TemplateSlot(1), TemplateSlot(1, Transition.FADE_WHITE),
            TemplateSlot(1), TemplateSlot(4, Transition.ZOOM_OUT, SpeedCurve.FLASH_IN)), null, false, 15, 0xFF7F00FF to 0xFFE100FF),
    WEEKEND_VLOG("Weekend Vlog", "Easy-going cuts, warm colour, title and end card", "happy acoustic", Aspect.ORIGINAL, Canvas.BLUR, Filter.WARM, 0.7f,
        listOf(TemplateSlot(4, Transition.FADE_WHITE), TemplateSlot(4), TemplateSlot(4, Transition.SLIDE_LEFT), TemplateSlot(8, Transition.FADE_WHITE)),
        "Weekend", true, 30, 0xFFFF9966 to 0xFFFF5E62),
    TRAVEL_DIARY("Travel Diary", "Long, soft moments with slow camera moves", "cinematic travel", Aspect.WIDE, Canvas.BLUR, Filter.GOLDEN, 0.8f,
        listOf(TemplateSlot(8, Transition.DISSOLVE_BLUR, motion = Motion.PAN_RIGHT), TemplateSlot(8, Transition.DISSOLVE_BLUR, motion = Motion.ZOOM_IN),
            TemplateSlot(4, Transition.SLIDE_LEFT), TemplateSlot(8, Transition.DISSOLVE_BLUR, motion = Motion.PAN_LEFT)),
        "Travel Diary", true, 30, 0xFF2193B0 to 0xFF6DD5ED),
    ACTION_HERO("Action Hero", "Hard-hitting cuts with slow-motion hero moments", "epic rock", Aspect.ORIGINAL, Canvas.CROP, Filter.PUNCH, 0.9f,
        listOf(TemplateSlot(2, Transition.ZOOM_IN), TemplateSlot(2), TemplateSlot(4, Transition.ZOOM_IN, SpeedCurve.HERO), TemplateSlot(2, Transition.SLIDE_LEFT),
            TemplateSlot(2), TemplateSlot(4, Transition.FADE_BLACK, SpeedCurve.BULLET)), null, false, 30, 0xFFED213A to 0xFF93291E),
    CINEMATIC("Cinematic", "21:9 widescreen, film colour, slow fades", "cinematic ambient", Aspect.CINEMA, Canvas.CROP, Filter.FILM, 1f,
        listOf(TemplateSlot(8, Transition.FADE_BLACK, motion = Motion.ZOOM_IN), TemplateSlot(8, Transition.FADE_BLACK, motion = Motion.PAN_RIGHT)),
        null, false, 30, 0xFF232526 to 0xFF414345),
    MEMORIES("Memories", "Gentle photo-and-video slideshow with soft zooms", "piano", Aspect.ORIGINAL, Canvas.BLUR, Filter.FADED, 0.7f,
        listOf(TemplateSlot(4, Transition.FADE_BLACK, motion = Motion.ZOOM_IN), TemplateSlot(4, Transition.FADE_BLACK, motion = Motion.PAN_RIGHT),
            TemplateSlot(4, Transition.FADE_BLACK, motion = Motion.ZOOM_OUT), TemplateSlot(4, Transition.FADE_BLACK, motion = Motion.PAN_LEFT)),
        "Memories", true, 30, 0xFFDA4453 to 0xFF89216B),
    PARTY("Party", "Flashes and spins, vertical, bright colour", "party dance", Aspect.VERTICAL, Canvas.BLUR, Filter.VIVID, 1f,
        listOf(TemplateSlot(1, Transition.FADE_WHITE), TemplateSlot(1), TemplateSlot(2, Transition.SPIN), TemplateSlot(1, Transition.FADE_WHITE), TemplateSlot(1),
            TemplateSlot(2, Transition.ZOOM_IN)), null, false, 15, 0xFFFC466B to 0xFF3F5EFB),
    CALM_NATURE("Calm Nature", "Slow, wide shots and blur dissolves", "ambient nature", Aspect.WIDE, Canvas.BLUR, Filter.FOREST, 0.6f,
        listOf(TemplateSlot(8, Transition.DISSOLVE_BLUR, motion = Motion.PAN_LEFT), TemplateSlot(8, Transition.DISSOLVE_BLUR, motion = Motion.ZOOM_IN)),
        null, true, 30, 0xFF56AB2F to 0xFFA8E063),
    FOOD_STORY("Food Story", "Square, warm and snappy", "lofi", Aspect.SQUARE, Canvas.CROP, Filter.WARM, 0.9f,
        listOf(TemplateSlot(2, Transition.SLIDE_UP), TemplateSlot(2, Transition.SLIDE_UP), TemplateSlot(2), TemplateSlot(4, Transition.ZOOM_IN, motion = Motion.ZOOM_IN)),
        null, false, 15, 0xFFF7971E to 0xFFFFD200),
    ROAD_TRIP("Road Trip", "Retro colour, sideways slides, 16:9", "indie rock", Aspect.WIDE, Canvas.BLUR, Filter.RETRO, 0.8f,
        listOf(TemplateSlot(4, Transition.SLIDE_LEFT), TemplateSlot(4, Transition.SLIDE_LEFT), TemplateSlot(2), TemplateSlot(2, Transition.ZOOM_IN), TemplateSlot(4, Transition.SLIDE_RIGHT)),
        "Road Trip", false, 30, 0xFF4568DC to 0xFFB06AB3),
    NOIR("Noir", "Black and white, 21:9, long takes", "jazz", Aspect.CINEMA, Canvas.CROP, Filter.NOIR, 1f,
        listOf(TemplateSlot(4, Transition.FADE_BLACK), TemplateSlot(8, Transition.FADE_BLACK, motion = Motion.ZOOM_IN)),
        null, true, 30, 0xFF000000 to 0xFF434343);

    /**
     * Slot lengths (output ms) for about [targetMs], repeating the rhythm, at [beatMs] a beat (500 ms = 120 BPM when
     * the song's tempo isn't known). Slots shorter than [minMs] are stretched to whole beats that reach it.
     */
    fun slotPlan(targetMs: Long, beatMs: Long, minMs: Long = 700): List<Pair<TemplateSlot, Long>> {
        val beat = beatMs.coerceIn(250, 1500)
        val out = mutableListOf<Pair<TemplateSlot, Long>>()
        var total = 0L
        var i = 0
        while (total < targetMs && out.size < 80) {
            val s = slots[i % slots.size]
            var ms = s.beats * beat
            while (ms < minMs) ms += beat
            out += s to ms
            total += ms
            i++
        }
        return out
    }
}

/** Picks a music mood from what's in the clips, for automatic music. Pure. */
object MusicPicker {
    /**
     * A search for Openverse: lots of action → energetic; bright and colourful → happy; dark or calm with little
     * colour → ambient; camera moving through wide scenes → cinematic; only photos → acoustic.
     */
    fun mood(analyses: List<ClipAnalysis>, photosOnly: Boolean): String {
        val frames = analyses.flatMap { it.frames }
        if (frames.isEmpty()) return if (photosOnly) "acoustic" else "upbeat"
        val luma = frames.map { it.luma }.average()
        val colour = frames.map { it.colorful }.average()
        val action = frames.map { it.action }.average()
        val motion = frames.map { kotlin.math.hypot(it.dx, it.dy).toDouble() }.average()
        val faces = frames.count { it.faces > 0 }.toDouble() / frames.size
        return when {
            photosOnly && colour > 0.45 && luma > 0.45 -> "happy acoustic"
            photosOnly -> "piano"
            action > 0.35 -> "energetic"
            luma < 0.28 -> "ambient"
            motion > 0.04 && colour < 0.5 -> "cinematic"
            faces > 0.3 -> "happy"
            colour > 0.5 && luma > 0.45 -> "happy upbeat"
            colour < 0.25 -> "chill"
            else -> "upbeat"
        }
    }

    /**
     * Best track for a [videoMs] edit: long enough to cover it (so it doesn't repeat) but not a 10-minute mix,
     * licence that needs no share-alike first; [skip] tracks already tried ("another track").
     */
    fun choose(tracks: List<OpenTrack>, videoMs: Long, skip: Set<String> = emptySet()): OpenTrack? {
        val pool = tracks.filter { it.id !in skip && it.durationMs in 20_000..420_000 }
        fun rank(t: OpenTrack): Int {
            var r = 0
            if (t.durationMs >= videoMs) r += 4
            if (t.durationMs >= videoMs && t.durationMs <= max(videoMs * 6, 240_000)) r += 2
            if ("sa" !in t.license) r += 1
            return r
        }
        return pool.sortedWith(compareByDescending<OpenTrack> { rank(it) }.thenBy { pool.indexOf(it) }).firstOrNull()
    }

    /** Clip time needed to fill [slotMs] of output with [curve] at [speed] (a curve's average speed). */
    fun sourceMs(slotMs: Long, speed: Float, curve: SpeedCurve): Long {
        // Each of the N equal steps plays its share of the clip at speed × multiplier, so the clip time that fills
        // the slot is the slot times the harmonic mean of those speeds.
        val n = Timeline.CURVE_STEPS
        val harmonic = n / (0 until n).sumOf { 1.0 / max(0.1f, min(10f, speed * curve.at((it + 0.5f) / n))) }
        return (slotMs * harmonic).toLong()
    }
}
