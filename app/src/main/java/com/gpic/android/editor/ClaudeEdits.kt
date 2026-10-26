package com.gpic.android.editor

import org.json.JSONObject

/** A shot as a prompt named it (S<n>): where it is and its liveliest moment. */
data class ShotRef(val clip: Int, val startMs: Long, val endMs: Long, val peakMs: Long, val label: String = "")

/**
 * What the labels in one prompt point to: P<n> = the n-th part's id, T<n> = the n-th text's id, S<n> = a shot,
 * as they were when the prompt was built. Ids don't move when an earlier edit in the same reply removes a part,
 * or when the user edits between sharing and pasting; a label whose part is gone is reported, not guessed.
 */
class EditRefs(val parts: List<Long>, val texts: List<Long>, val shots: List<ShotRef>) {
    /**
     * Saved with the project, so a reply still reads right after Android closed GPic while the user was in the
     * Claude app. Part and text ids aren't saved in drafts, so they're stored by what they are (clip range, text
     * and start) and matched back to the reloaded plan by [fromJson].
     */
    fun toJson(plan: EditPlan): JSONObject = JSONObject()
        .put("parts", org.json.JSONArray(parts.map { id -> plan.segments.firstOrNull { it.id == id }?.let { s -> "${s.clip}:${s.startMs}:${s.endMs}" } ?: "" }))
        .put("texts", org.json.JSONArray(texts.map { id -> plan.texts.firstOrNull { it.id == id }?.let { t -> "${t.startMs}:${t.text}" } ?: "" }))
        .put("shots", org.json.JSONArray(shots.map { org.json.JSONArray(listOf(it.clip, it.startMs, it.endMs, it.peakMs)) }))

    companion object {
        fun of(plan: EditPlan, shots: List<ShotRef>) = EditRefs(plan.segments.map { it.id }, plan.texts.map { it.id }, shots)

        /** [toJson] read back against [plan]: each saved part/text gets the id of the one that matches it (in order), or none. */
        fun fromJson(o: JSONObject, plan: EditPlan): EditRefs {
            fun strings(key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            val usedParts = HashSet<Long>()
            val parts = strings("parts").map { key ->
                plan.segments.firstOrNull { s -> s.id !in usedParts && "${s.clip}:${s.startMs}:${s.endMs}" == key }?.id?.also { usedParts += it } ?: -1L
            }
            val usedTexts = HashSet<Long>()
            val texts = strings("texts").map { key ->
                plan.texts.firstOrNull { t -> t.id !in usedTexts && "${t.startMs}:${t.text}" == key }?.id?.also { usedTexts += it } ?: -1L
            }
            val shots = o.optJSONArray("shots")?.let { a ->
                (0 until a.length()).mapNotNull { i -> a.optJSONArray(i)?.let { s -> ShotRef(s.optInt(0), s.optLong(1), s.optLong(2), s.optLong(3)) } }
            }.orEmpty()
            return EditRefs(parts, texts, shots)
        }
    }
}

/** Edits that need the phone's help after the plan is applied (network, analysis), in the order given. */
sealed interface FollowUp {
    data class FindMusic(val query: String) : FollowUp
    data class UseTemplate(val template: VideoTemplate) : FollowUp
    data object CutOnBeats : FollowUp
}

/**
 * A reply applied: the new plan, Claude's note, what couldn't be done (shown, and sent back on review), the
 * follow-ups to run and the edits themselves (a template rebuilds the track, then the other edits go on top).
 */
class ClaudeEdit(
    val plan: EditPlan, val explanation: String, val problems: List<String>, val followUps: List<FollowUp>, val applied: Int,
    val ops: List<JSONObject>,
)

/**
 * Claude edits the way a person would at the timeline, not by rewriting the whole plan: a list of small edits
 * that name shots, parts and texts by label (S3, P2, T1), applied in order as one undo step. An edit that can't
 * be done (unknown label, bad value) is skipped and reported, so one slip doesn't cost the rest. Pure, tested.
 */
object ClaudeEdits {
    /** Default length of a montage part when Claude doesn't say, output time. */
    const val DEFAULT_PART_MS = 2_500L
    /** Ops that rebuild or edit the main track; after a template they'd undo it, so they're dropped then. */
    val TRACK_OPS = setOf("montage", "set_part", "remove_part", "move_part", "template")

    /** The reply's edits, or null when it's an old whole-plan reply. */
    fun ops(reply: JSONObject): List<JSONObject>? {
        val arr = reply.optJSONArray("edits") ?: return null
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    fun opName(o: JSONObject): String =
        (o.optString("op").ifEmpty { o.optString("do").ifEmpty { o.optString("type").ifEmpty { o.optString("action") } } }).trim().lowercase()

    /**
     * Applies [ops] to [current] (parts written out, see [Timeline.materialize]) in order. Labels resolve through
     * [refs]. The result is [EditPlan.sanitized]; follow-ups (music, template, beats) are for the caller to run.
     */
    fun apply(ops: List<JSONObject>, current: EditPlan, refs: EditRefs, durations: List<Long>, lutIds: Set<String>, explanation: String,
              photos: Set<Int> = emptySet()): ClaudeEdit {
        var plan = current
        val problems = mutableListOf<String>()
        val follow = mutableListOf<FollowUp>()
        var applied = 0
        for ((i, o) in ops.withIndex()) {
            val name = opName(o)
            val tag = "edit ${i + 1} ($name${o.optString("part").takeIf { it.isNotEmpty() }?.let { " $it" } ?: ""}${o.optString("text_id").takeIf { it.isNotEmpty() }?.let { " $it" } ?: ""})"
            val issues = mutableListOf<String>()
            val next: EditPlan? = runCatching {
                when (name) {
                    "montage" -> montage(o, plan, refs, durations, issues, photos)
                    "set_part" -> setPart(o, plan, refs, durations, issues)
                    "remove_part" -> part(o, plan, refs, issues)?.let { id ->
                        if (plan.segments.size <= 1) { issues += "it's the only part left"; null }
                        else plan.copy(segments = plan.segments.filter { it.id != id })
                    }
                    "move_part" -> part(o, plan, refs, issues)?.let { id ->
                        val to = o.optInt("to", -1)
                        if (to < 1) { issues += "\"to\" must be a position from 1 to ${plan.segments.size}"; null }
                        else Timeline.move(plan, durations, id, to - 1)
                    }
                    "add_text" -> {
                        if (o.optString("text").isBlank()) { issues += "no text"; null }
                        else {
                            checkEnums(o, TEXT_ENUMS, issues)
                            val t = EditPlan.textFrom(o, TextItem("", 0, 2_500, y = 0.45f, size = 0.08f))
                            plan.copy(texts = plan.texts + t.copy(endMs = if (o.has("end_s")) t.endMs else t.startMs + 2_500))
                        }
                    }
                    "set_text" -> text(o, plan, refs, issues)?.let { id ->
                        checkEnums(o, TEXT_ENUMS, issues)
                        plan.copy(texts = plan.texts.map { if (it.id == id) EditPlan.textFrom(o, it) else it })
                    }
                    "remove_text" -> text(o, plan, refs, issues)?.let { id -> plan.copy(texts = plan.texts.filter { it.id != id }) }
                    "look" -> {
                        checkEnums(o, mapOf("filter" to listOf("NONE") + Filter.entries.map { it.name }), issues)
                        val lut = o.optString("lut")
                        if (lut.isNotEmpty() && lut != EditPlan.NO_LUT && lut !in lutIds) issues += "no LUT \"$lut\" (have: ${(lutIds + EditPlan.NO_LUT).joinToString()})"
                        val clean = JSONObject(o.toString()).apply {
                            if (has("filter")) put("filter", normal(optString("filter")))
                            if (lut.isNotEmpty() && lut != EditPlan.NO_LUT && lut !in lutIds) remove("lut")
                            if (has("filter") && normal(optString("filter")) !in Filter.entries.map { it.name } + "NONE") remove("filter")
                        }
                        plan.copy(look = EditPlan.lookFrom(clean, plan.look))
                    }
                    "audio" -> audio(o, plan, issues, musicComing = ops.any { opName(it) == "find_music" })
                    "format" -> {
                        checkEnums(o, mapOf("aspect" to Aspect.entries.map { it.name }, "canvas" to Canvas.entries.map { it.name }), issues)
                        plan.copy(aspect = enumOf(o.optString("aspect"), plan.aspect), canvas = enumOf(o.optString("canvas"), plan.canvas))
                    }
                    "find_music" -> {
                        val q = o.optString("query").ifBlank { o.optString("mood") }.trim()
                        if (q.isEmpty()) { issues += "no query"; null } else { follow += FollowUp.FindMusic(q.take(60)); plan }
                    }
                    "cut_on_beats" -> { follow += FollowUp.CutOnBeats; plan }
                    "template" -> {
                        val n = normal(o.optString("name"))
                        val t = VideoTemplate.entries.firstOrNull { it.name == n || normal(it.label) == n }
                        if (t == null) { issues += "no template \"${o.optString("name")}\" (have: ${VideoTemplate.entries.joinToString { it.name }})"; null }
                        else { follow += FollowUp.UseTemplate(t); plan }
                    }
                    "" -> { issues += "no \"op\""; null }
                    else -> { issues += "unknown op (use ${OPS.joinToString()})"; null }
                }
            }.getOrElse { e -> issues += "failed: ${e.message}"; null }
            if (next != null) { plan = next; applied++ }
            issues.forEach { problems += "$tag: $it" }
        }
        return ClaudeEdit(plan.sanitized(durations, lutIds), explanation, problems, follow, applied, ops)
    }

    private val OPS = listOf("montage", "set_part", "remove_part", "move_part", "add_text", "set_text", "remove_text", "look", "audio", "format", "find_music", "cut_on_beats", "template")
    private val TEXT_ENUMS = mapOf(
        "style" to TextStyle.entries.map { it.name }, "font" to TextFont.entries.map { it.name },
        "anim_in" to TextAnim.entries.map { it.name }, "anim_out" to TextAnim.entries.map { it.name },
    )
    private val PART_ENUMS = mapOf(
        "curve" to SpeedCurve.entries.map { it.name }, "transition" to Transition.entries.map { it.name }, "motion" to Motion.entries.map { it.name },
    )

    /** "zoom in" / "Zoom-In" → "ZOOM_IN". */
    internal fun normal(s: String) = s.trim().uppercase().replace(Regex("[\\s-]+"), "_")

    private inline fun <reified E : Enum<E>> enumOf(raw: String, default: E): E =
        if (raw.isBlank()) default else enumValues<E>().firstOrNull { it.name == normal(raw) } ?: default

    /** Reports enum fields with a value GPic doesn't have, and rewrites the rest to their exact names in place. */
    private fun checkEnums(o: JSONObject, allowed: Map<String, List<String>>, issues: MutableList<String>) {
        for ((key, values) in allowed) {
            if (!o.has(key)) continue
            val v = normal(o.optString(key))
            if (v in values) o.put(key, v) else { issues += "$key \"${o.optString(key)}\" isn't one of ${values.joinToString("|")}"; o.remove(key) }
        }
    }

    private fun label(o: JSONObject, key: String): Int? {
        val raw = o.opt(key) ?: return null
        return raw.toString().trim().trimStart('P', 'p', 'T', 't', 'S', 's', '#').toIntOrNull()
    }

    private fun part(o: JSONObject, plan: EditPlan, refs: EditRefs, issues: MutableList<String>): Long? {
        val n = label(o, "part") ?: run { issues += "no \"part\" (P1…P${refs.parts.size})"; return null }
        val id = refs.parts.getOrNull(n - 1) ?: run { issues += "there's no P$n (P1…P${refs.parts.size})"; return null }
        if (plan.segments.none { it.id == id }) { issues += "P$n is no longer on the timeline (removed or replaced by a montage)"; return null }
        return id
    }

    private fun text(o: JSONObject, plan: EditPlan, refs: EditRefs, issues: MutableList<String>): Long? {
        val n = label(o, "text_id") ?: label(o, "id") ?: run { issues += "no \"text_id\" (T1…T${refs.texts.size})"; return null }
        val id = refs.texts.getOrNull(n - 1) ?: run { issues += "there's no T$n"; return null }
        if (plan.texts.none { it.id == id }) { issues += "T$n was removed"; return null }
        return id
    }

    /** Speed/curve/volume/transition/motion and clip-time trims, for one part or "all". */
    private fun setPart(o: JSONObject, plan: EditPlan, refs: EditRefs, durations: List<Long>, issues: MutableList<String>): EditPlan? {
        val all = o.optString("part").trim().equals("all", ignoreCase = true)
        val id = if (all) null else part(o, plan, refs, issues) ?: return null
        checkEnums(o, PART_ENUMS, issues)
        return plan.copy(segments = plan.segments.map { s ->
            if (!all && s.id != id) return@map s
            var p = partFields(o, s)
            if (!all && (o.has("start_s") || o.has("end_s"))) {
                val d = durations.getOrElse(s.clip) { s.endMs }
                val start = ms(o, "start_s", s.startMs).coerceIn(0, d)
                val end = ms(o, "end_s", s.endMs).coerceIn(0, d)
                if (end - start < EditPlan.MIN_SEGMENT_MS) issues += "start_s..end_s is too short or outside clip ${s.clip} (0..${secs(d)} s)"
                else p = p.copy(startMs = start, endMs = end)
            }
            p
        })
    }

    private fun partFields(o: JSONObject, s: Segment) = s.copy(
        speed = if (o.has("speed")) o.optDouble("speed", s.speed.toDouble()).toFloat() else s.speed,
        curve = enumOf(o.optString("curve"), s.curve),
        volume = if (o.has("volume")) o.optDouble("volume", s.volume.toDouble()).toFloat() else s.volume,
        transition = enumOf(o.optString("transition"), s.transition),
        motion = enumOf(o.optString("motion"), s.motion),
    )

    /**
     * Replaces the main track with shots in order. A shot part is [DEFAULT_PART_MS] (or length_s) of output, cut
     * around the shot's liveliest moment (or from start_s) and kept inside the shot; a plain clip range works too.
     * Parts of [photos] (clip indices) without a motion get changing slow moves.
     */
    private fun montage(o: JSONObject, plan: EditPlan, refs: EditRefs, durations: List<Long>, issues: MutableList<String>, photos: Set<Int>): EditPlan? {
        val items = o.optJSONArray("shots") ?: o.optJSONArray("parts") ?: o.optJSONArray("items")
            ?: run { issues += "no \"shots\" list"; return null }
        val parts = mutableListOf<Segment>()
        var photoNo = 0
        for (k in 0 until items.length()) {
            val it = items.optJSONObject(k) ?: JSONObject().put("shot", items.opt(k))
            val where = "shot ${k + 1}"
            checkEnums(it, PART_ENUMS, issues)
            val speed = it.optDouble("speed", 1.0).toFloat().takeIf { v -> v.isFinite() }?.coerceIn(EditPlan.MIN_SPEED, EditPlan.MAX_SPEED) ?: 1f
            val curve = enumOf(it.optString("curve"), SpeedCurve.NONE)
            val wantOut = if (it.has("length_s")) ms(it, "length_s", DEFAULT_PART_MS).coerceAtLeast(EditPlan.MIN_SEGMENT_MS) else null
            val n = label(it, "shot")
            val range: Pair<Int, Pair<Long, Long>>? = if (n != null) {
                val shot = refs.shots.getOrNull(n) ?: run { issues += "$where: there's no S$n (S0…S${refs.shots.size - 1})"; null }
                shot?.let { s ->
                    val need = MusicPicker.sourceMs(wantOut ?: DEFAULT_PART_MS, speed, curve)
                    val len = minOf(need, s.endMs - s.startMs)
                    val wantStart = if (it.has("start_s")) ms(it, "start_s", s.startMs) else s.peakMs - len / 2
                    val start = wantStart.coerceIn(s.startMs, s.endMs - len)
                    s.clip to (start to start + len)
                }
            } else if (it.has("clip")) {
                val c = it.optInt("clip", -1)
                val d = durations.getOrNull(c)
                if (d == null) { issues += "$where: there's no clip $c"; null }
                else {
                    val start = ms(it, "start_s", 0).coerceIn(0, d)
                    val end = (if (it.has("end_s")) ms(it, "end_s", d) else start + MusicPicker.sourceMs(wantOut ?: DEFAULT_PART_MS, speed, curve)).coerceIn(0, d)
                    if (end - start < EditPlan.MIN_SEGMENT_MS) { issues += "$where: range too short or outside clip $c (0..${secs(d)} s)"; null } else c to (start to end)
                }
            } else { issues += "$where: needs \"shot\" (S#) or \"clip\""; null }
            range ?: continue
            val (clip, r) = range
            // Keep a clip's rotation/mirror the user set on any of its old parts.
            val like = plan.segments.firstOrNull { s -> s.clip == clip }
            parts += partFields(it, Segment(r.first, r.second, clip, rotation = like?.rotation ?: 0, mirror = like?.mirror ?: false))
                .copy(speed = speed, curve = curve)
                // A photo Claude gave no move would sit still: it gets the slideshow's moves in turn.
                .let { p -> if (clip in photos && !it.has("motion")) p.copy(motion = SmartEdit.PHOTO_MOVES[photoNo++ % SmartEdit.PHOTO_MOVES.size]) else p }
        }
        if (parts.isEmpty()) { issues += "no usable shots"; return null }
        return plan.copy(segments = parts)
    }

    /** Music settings for a track the same reply asks find_music for are applied again once it's there (see the caller). */
    private fun audio(o: JSONObject, plan: EditPlan, issues: MutableList<String>, musicComing: Boolean = false): EditPlan {
        val musicKeys = listOf("music_volume", "music_start_s", "music_fade_in_s", "music_fade_out_s")
        if (plan.music == null && !musicComing && musicKeys.any { o.has(it) }) issues += "there's no music yet (use find_music first); music fields ignored"
        return plan.copy(
            keepOriginalAudio = if (o.has("keep_original_audio")) o.optBoolean("keep_original_audio", plan.keepOriginalAudio) else plan.keepOriginalAudio,
            originalVolume = if (o.has("original_volume")) o.optDouble("original_volume", 1.0).toFloat() else plan.originalVolume,
            music = plan.music?.let { m ->
                m.copy(
                    volume = if (o.has("music_volume")) o.optDouble("music_volume", 0.8).toFloat() else m.volume,
                    startMs = ms(o, "music_start_s", m.startMs), fadeInMs = ms(o, "music_fade_in_s", m.fadeInMs), fadeOutMs = ms(o, "music_fade_out_s", m.fadeOutMs),
                )
            },
        )
    }

    private fun ms(o: JSONObject, key: String, default: Long): Long =
        o.optDouble(key, Double.NaN).takeIf { !it.isNaN() && !it.isInfinite() }?.let { (it * 1000).toLong() } ?: default

    private fun secs(ms: Long) = "%.1f".format(ms / 1000.0)

    // ---- what Claude reads ----------------------------------------------------------------------------------

    /**
     * The edit as a compact list (like a timeline read-out): output length, shape, look and audio, then each part
     * with its label, output time, clip range, shot and only the settings that aren't at their default, then texts.
     */
    fun summary(plan: EditPlan, durations: List<Long>, shots: List<ShotRef>): String = buildString {
        val parts = plan.parts(durations)
        val starts = Timeline.starts(parts)
        appendLine("Output ${secs(starts.last())} s, aspect ${plan.aspect.name}, canvas ${plan.canvas.name}")
        val l = plan.look
        val lookBits = listOfNotNull(
            l.lutId?.let { "lut $it" }, l.filter?.let { "filter ${it.name} ${r3(l.filterAmount)}" },
        ) + Look.ADJUSTMENTS.filter { it.get(l) != 0f }.map { "${it.key} ${r3(it.get(l))}" }
        appendLine("Look: " + lookBits.joinToString().ifEmpty { "natural (no filter)" })
        val m = plan.music
        appendLine("Audio: camera sound " + (if (plan.keepOriginalAudio) "on, volume ${r3(plan.originalVolume)}" else "off") + "; " +
            (m?.let { "music \"${it.title}\"" + (if (it.bpm > 0) ", ${it.bpm.toInt()} BPM" else "") + ", volume ${r3(it.volume)}, starts ${secs(it.startMs)} s in the song, fades ${secs(it.fadeInMs)}/${secs(it.fadeOutMs)} s" } ?: "no music"))
        appendLine("Parts (P#: output time ← clip, time in clip):")
        parts.forEachIndexed { i, s ->
            val shot = shots.indexOfFirst { it.clip == s.clip && (s.startMs + s.endMs) / 2 in it.startMs..it.endMs }
            val extras = listOfNotNull(
                if (s.speed != 1f) "speed ${r3(s.speed)}" else null, if (s.curve != SpeedCurve.NONE) "curve ${s.curve.name}" else null,
                if (s.volume != 1f) "volume ${r3(s.volume)}" else null, if (s.motion != Motion.NONE) "motion ${s.motion.name}" else null,
                if (s.transition != Transition.NONE && i < parts.lastIndex) "transition ${s.transition.name}" else null,
                if (s.look != null) "own look" else null, if (s.rotation != 0) "rotated ${s.rotation}" else null,
            )
            appendLine("P${i + 1}: ${secs(starts[i])}–${secs(starts[i + 1])} s ← clip ${s.clip} ${secs(s.startMs)}–${secs(s.endMs)} s" +
                (if (shot >= 0) " (S$shot)" else "") + extras.joinToString("") { ", $it" })
        }
        if (plan.texts.isEmpty()) appendLine("Texts: none")
        else {
            appendLine("Texts (T#: output time):")
            plan.texts.forEachIndexed { i, t ->
                appendLine("T${i + 1}: ${secs(t.startMs)}–${secs(t.endMs)} s \"${t.text.take(60)}\" x ${r3(t.x)} y ${r3(t.y)} size ${r3(t.size)} ${t.style.name}" +
                    (if (t.sticker) " sticker" else "") + " #%06X".format(t.color and 0xFFFFFF))
            }
        }
    }.trimEnd()

    /** The reply format with one example per edit; plain text so it reads the same in the Claude app and the API. */
    fun guide(lutIds: List<String>): String = """
        Reply with ONE JSON object in a ```json code block, then nothing else:
        {"explanation": "one or two sentences for me", "edits": [ ...edits, applied in order... ]}
        Edits (leave out fields you don't change; seconds everywhere):
        - {"op":"montage","shots":[{"shot":"S3","length_s":2},{"shot":"S7","length_s":1.5,"speed":0.5,"transition":"ZOOM_IN"}]}
          Replaces the main track with these shots in this order. GPic cuts each around the shot's liveliest moment
          (or from "start_s", seconds in its clip) and keeps it inside the shot. length_s is how long it plays.
          Per shot also: speed 0.25-4, curve, volume 0-2, motion, transition (into the next one).
          A range that isn't a listed shot: {"clip":0,"start_s":3,"end_s":5}.
        - {"op":"set_part","part":"P2","speed":0.5,"curve":"HERO","transition":"FADE_BLACK"}   ("part":"all" changes every part;
          start_s/end_s trim it, seconds in its clip)
        - {"op":"remove_part","part":"P4"}
        - {"op":"move_part","part":"P4","to":1}
        - {"op":"add_text","text":"Goa 2026","start_s":0,"end_s":2.5,"y":0.45,"size":0.1,"style":"BOX","anim_in":"POP"}
          (output seconds; x/y = centre 0-1; size 0.03-0.2 of the short side; color "#RRGGBB"; "sticker":true for one emoji)
        - {"op":"set_text","text_id":"T1","size":0.12,"y":0.2}
        - {"op":"remove_text","text_id":"T2"}
        - {"op":"look","filter":"WARM","filter_amount":0.7,"contrast":0.15}   (sliders -1..1, 0 = unchanged; fade, vignette 0..1)
        - {"op":"audio","original_volume":0.3,"music_volume":0.9,"music_fade_out_s":2}   (also keep_original_audio, music_start_s, music_fade_in_s)
        - {"op":"format","aspect":"VERTICAL","canvas":"BLUR"}
        - {"op":"find_music","query":"upbeat acoustic"}   GPic downloads a matching free track (Openverse) and finds its beat.
        - {"op":"cut_on_beats"}   moves every cut onto the music's beat; put it last.
        - {"op":"template","name":"BEAT_DROP"}   rebuilds the whole edit from a GPic template; after it only look, format,
          audio and text edits make sense.
        Values:
        curve: ${SpeedCurve.entries.joinToString("|") { it.name }} (HERO/BULLET slow the middle; FLASH_IN speeds up)
        transition: ${Transition.entries.joinToString("|") { it.name }}
        motion: ${Motion.entries.joinToString("|") { it.name }} (slow Ken Burns move; good on photos and still shots)
        filter: NONE|${Filter.entries.joinToString("|") { it.name }}
        lut: ${(lutIds + EditPlan.NO_LUT).joinToString("|")}
        sliders: ${Look.ADJUSTMENTS.joinToString(", ") { it.key }}
        style: ${TextStyle.entries.joinToString("|") { it.name }}; font: ${TextFont.entries.joinToString("|") { it.name }}; anim_in/anim_out: ${TextAnim.entries.joinToString("|") { it.name }}
        aspect: ${Aspect.entries.joinToString("|") { it.name }}; canvas: ${Canvas.entries.joinToString("|") { it.name }}
        template: ${VideoTemplate.entries.joinToString("|") { it.name }}
    """.trimIndent()

    /** How a good short edit is cut; the same advice LightCut/CapCut templates follow. */
    val CRAFT = """
        How to cut it well:
        - Open on the strongest, sharpest shot; end on a calm or wide one. Order shots so the story flows (usually in time order).
        - Lively edits: parts of 1-3 s; calm ones 3-6 s. Vary lengths a little. With music, make parts a whole number of beats.
        - Use good, steady shots. Skip ones flagged shaky, blurry or dark unless nothing else shows that moment.
        - Don't use two shots from the same look-alike group back to back.
        - High frame rate shots make good slow motion (speed 0.5, or curve HERO/BULLET on the best action).
        - Transitions: mostly NONE (hard cuts, on the beat). DISSOLVE between photos and calm scenes; now and then
          ZOOM_IN, WHIP_LEFT/WHIP_RIGHT (following a pan) or GLITCH on action; FADE_BLACK for the ending. A transition
          on every cut feels cheap. Photos: give each a motion, and vary it.
        - Text: short (5 words or fewer), size 0.06-0.12, y between 0.15 and 0.85 so it stays on screen in every aspect;
          not over faces. Captions at y about 0.85. Give each text at least 1.5 s.
    """.trimIndent()
}
