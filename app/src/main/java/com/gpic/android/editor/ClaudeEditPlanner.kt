package com.gpic.android.editor

import android.util.Base64
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration

/** What Claude sent back: the new plan (already sanitized) and its note for the user. */
class PlannedEdit(val plan: EditPlan, val explanation: String)

class ClaudeException(message: String) : Exception(message)

/**
 * Turns "cut the boring start, add the music, make it pop" into an [EditPlan] with Claude, using the user's
 * own API key. Sends the video's metadata, the current plan, the LUT list and the request; frames only when
 * the user ticks "let Claude see frames". The video file itself never leaves the phone.
 */
class ClaudeEditPlanner(apiKey: String, private val model: String = MODEL) {
    private val client = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        // Thinking plus a few images can take a while; the default 10 min is more than a phone user waits.
        .timeout(Duration.ofMinutes(3))
        .maxRetries(2)
        .build()

    fun plan(
        request: String,
        clips: List<VideoInfo>,
        current: EditPlan,
        luts: List<LutInfo>,
        frames: List<Frame>,
        shots: String? = null,
    ): PlannedEdit {
        val context = JSONObject()
            .put("clips", JSONArray(clips.mapIndexed { i, video ->
                JSONObject()
                    .put("clip", i)
                    .put("name", video.name)
                    .put("duration_s", video.durationMs / 1000.0)
                    .put("size_px", "${video.displayWidth}x${video.displayHeight}")
                    .put("frame_rate", video.frameRate ?: JSONObject.NULL)
                    .put("has_audio", video.hasAudio)
                    .put("is_photo", video.isImage)
                    .put("shot_on_dji_camera", video.fromDji)
                    .put("middle_frame_looks_flat_like_log", video.looksFlat ?: JSONObject.NULL)
            }))
            .put("current_plan", forClaude(current))
            .put("output_length_s", current.outputMs(clips.map { it.durationMs }) / 1000.0)
            .put("luts", JSONArray(luts.map { JSONObject().put("id", it.id).put("title", it.title).put("use_for", it.description) }))
            .put("music_track", current.music?.let { m ->
                JSONObject().put("title", m.title).put("bpm", m.bpm.takeIf { it > 0 }?.let { r3(it) } ?: JSONObject.NULL)
                    .put("song_length_s", m.durationMs / 1000.0)
                    .put("beats_in_output_s", JSONArray(m.beatsMs.map { (it - m.startMs) / 1000.0 }.filter { it >= 0 }.take(200)))
            } ?: JSONObject.NULL)
        val blocks = mutableListOf<ContentBlockParam>()
        blocks += ContentBlockParam.ofText("Video and current edit:\n$context")
        if (shots != null) blocks += ContentBlockParam.ofText("Shots found on the phone (S<n>: clip, time range in that clip, camera, scores 0-1, flags):\n$shots")
        for (f in frames) {
            blocks += ContentBlockParam.ofText("Clip ${f.clip}, frame at ${"%.1f".format(f.timeMs / 1000.0)} s:")
            blocks += ContentBlockParam.ofImage(
                Base64ImageSource.builder()
                    .data(Base64.encodeToString(f.jpeg, Base64.NO_WRAP))
                    .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                    .build()
            )
        }
        blocks += ContentBlockParam.ofText("Request: ${request.trim()}")

        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .system(SYSTEM + if (frames.isEmpty()) NO_FRAMES else WITH_FRAMES)
            .outputConfig(
                OutputConfig.builder()
                    .effort(OutputConfig.Effort.MEDIUM)
                    .format(
                        JsonOutputFormat.builder()
                            .schema(JsonOutputFormat.Schema.builder().apply {
                                val schema = schema(luts.map { it.id })
                                schema.keys().forEach { k -> putAdditionalProperty(k, JsonValue.from(plain(schema.get(k)))) }
                            }.build())
                            .build()
                    )
                    .build()
            )
            .apply {
                // If a safety classifier declines, retry server-side on Anthropic's recommended model instead of
                // failing. Haiku 5.5 has no server-side fallback, so it's asked only of the others.
                if (!model.startsWith("claude-haiku")) {
                    putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                    putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                }
            }
            .addUserMessageOfBlockParams(blocks)
            .build()

        val response = try {
            client.messages().create(params)
        } catch (e: UnauthorizedException) {
            throw ClaudeException("Claude didn't accept the API key. Tap “API key” to check or replace it.")
        } catch (e: PermissionDeniedException) {
            throw ClaudeException("This API key isn't allowed to use $model.")
        } catch (e: RateLimitException) {
            throw ClaudeException("Claude is rate-limiting this key. Try again in a minute.")
        } catch (e: BadRequestException) {
            throw ClaudeException("Claude rejected the request: ${serviceMessage(e)}")
        } catch (e: AnthropicServiceException) {
            throw ClaudeException("Claude returned an error (HTTP ${e.statusCode()}): ${serviceMessage(e)}")
        } catch (e: AnthropicIoException) {
            throw ClaudeException("Couldn't reach Claude. Check the internet connection.")
        }

        when (response.stopReason().orElse(null)) {
            StopReason.REFUSAL -> throw ClaudeException(
                "Claude declined this request" + (response.stopDetails().flatMap { it.explanation() }.orElse(null)?.let { ": $it" } ?: ".")
            )
            StopReason.MAX_TOKENS -> throw ClaudeException("Claude's answer was cut off. Try a shorter request.")
            else -> {}
        }
        val text = response.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
        val json = runCatching { JSONObject(text) }.getOrElse { throw ClaudeException("Claude's answer wasn't a usable plan.") }
        val plan = fromClaude(json, current).sanitized(clips.map { it.durationMs }, luts.mapTo(HashSet()) { it.id })
        return PlannedEdit(plan, json.optString("explanation").ifBlank { "Updated the edit." })
    }

    private fun serviceMessage(e: AnthropicServiceException): String =
        runCatching { JSONObject(e.body().toString()).optJSONObject("error")?.optString("message") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: (e.message ?: "unknown error")

    companion object {
        const val MODEL = "claude-opus-5-5"
        /** Models offered for API-key requests: id and label. Haiku is the cheap one for trying things out. */
        val MODELS = listOf(
            "claude-opus-5-5" to "Opus 5.5 (best plans)",
            "claude-sonnet-5-5" to "Sonnet 5.5",
            "claude-haiku-5-5" to "Haiku 5.5 (cheapest)",
        )
        /** Frames sent when the user lets Claude look, shared across all clips; each ≤ 512 px on its long side. */
        const val FRAME_COUNT = 24

        /** How many of [FRAME_COUNT] each clip gets: by length, at least 2 a clip. */
        fun framesPerClip(durations: List<Long>): List<Int> {
            val total = durations.sum().coerceAtLeast(1)
            return durations.map { d -> maxOf(2, (FRAME_COUNT * d / total).toInt()) }
        }

        /** The plan in the schema's shape (what Claude starts from). */
        private fun forClaude(p: EditPlan): JSONObject {
            val full = p.toJson()
            val m = p.music
            return JSONObject()
                .put("segments", JSONArray(p.segments.map { s ->
                    JSONObject().put("clip", s.clip).put("start_s", s.startMs / 1000.0).put("end_s", s.endMs / 1000.0)
                        .put("speed", r3(s.speed)).put("curve", s.curve.name).put("volume", r3(s.volume))
                        .put("transition", s.transition.name).put("motion", s.motion.name)
                }))
                .put("look", full.getJSONObject("look"))
                .put("texts", full.getJSONArray("texts"))
                .put("keep_original_audio", p.keepOriginalAudio).put("original_volume", r3(p.originalVolume))
                .put("music_volume", r3(m?.volume ?: 0.8f)).put("music_start_s", (m?.startMs ?: 0) / 1000.0)
                .put("music_fade_in_s", (m?.fadeInMs ?: 0) / 1000.0).put("music_fade_out_s", (m?.fadeOutMs ?: 1500) / 1000.0)
                .put("aspect", p.aspect.name).put("canvas", p.canvas.name)
        }

        /**
         * Claude's answer → a plan: per-part extras it can't see (rotation, own looks) are kept by position, and
         * anything it left out stays as it was (a chat reply may give only the fields it changed).
         */
        internal fun fromClaude(o: JSONObject, current: EditPlan): EditPlan {
            val plan = EditPlan.fromJson(JSONObject()
                .apply { o.optJSONArray("segments")?.let { put("segments", it) } }
                .apply { o.optJSONObject("look")?.let { put("look", it) } }
                .apply { o.optJSONArray("texts")?.let { put("texts", it) } }
                .put("keep_original_audio", o.optBoolean("keep_original_audio", current.keepOriginalAudio))
                .put("original_volume", o.optDouble("original_volume", r3(current.originalVolume)))
                .put("aspect", o.optString("aspect", current.aspect.name))
                .put("canvas", o.optString("canvas", current.canvas.name)), base = if (o.has("texts")) current.copy(texts = emptyList()) else current)
            return plan.copy(
                segments = plan.segments.mapIndexed { i, s ->
                    current.segments.getOrNull(i)?.let { old -> s.copy(rotation = old.rotation, mirror = old.mirror, look = old.look) } ?: s
                },
                music = current.music?.copy(
                    volume = o.optDouble("music_volume", r3(current.music.volume)).toFloat(),
                    startMs = (o.optDouble("music_start_s", current.music.startMs / 1000.0) * 1000).toLong(),
                    fadeInMs = (o.optDouble("music_fade_in_s", current.music.fadeInMs / 1000.0) * 1000).toLong(),
                    fadeOutMs = (o.optDouble("music_fade_out_s", current.music.fadeOutMs / 1000.0) * 1000).toLong(),
                ),
            )
        }

        /**
         * JSON schema for Claude's structured output: the whole plan plus an `explanation` for the user. Strict
         * schemas take no min/max, so ranges live in the descriptions and [EditPlan.sanitized] enforces them.
         */
        fun schema(lutIds: List<String>): JSONObject {
            fun num(desc: String) = JSONObject().put("type", "number").put("description", desc)
            fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)
            fun bool(desc: String) = JSONObject().put("type", "boolean").put("description", desc)
            fun enumOf(values: List<String>, desc: String) = JSONObject().put("type", "string").put("enum", JSONArray(values)).put("description", desc)
            fun obj(props: JSONObject) = JSONObject().put("type", "object").put("additionalProperties", false)
                .put("properties", props).put("required", JSONArray(props.keys().asSequence().toList()))
            val segment = obj(JSONObject()
                .put("clip", JSONObject().put("type", "integer").put("description", "Index of the clip (0 for the first)"))
                .put("start_s", num("Start, seconds into that clip"))
                .put("end_s", num("End, seconds into that clip"))
                .put("speed", num("Playback speed of this part, 0.25 to 4; 1 = normal"))
                .put("curve", enumOf(SpeedCurve.entries.map { it.name }, "Speed ramp over the part (NONE = constant). HERO/BULLET slow down the middle, MONTAGE alternates, FLASH_IN speeds up, FLASH_OUT slows down"))
                .put("volume", num("Camera sound of this part, 0 to 2; 1 = unchanged"))
                .put("transition", enumOf(Transition.entries.map { it.name }, "Transition into the next part (ignored on the last)"))
                .put("motion", enumOf(Motion.entries.map { it.name }, "Slow zoom/pan over the part (Ken Burns); good for photos and static shots")))
            val look = obj(JSONObject()
                .put("lut", enumOf(lutIds + EditPlan.NO_LUT, "Camera LUT id, or \"none\""))
                .put("filter", enumOf(listOf("NONE") + Filter.entries.map { it.name }, "Colour filter preset"))
                .put("filter_amount", num("Filter strength 0 to 1"))
                .apply { Look.ADJUSTMENTS.forEach { a -> put(a.key, num("${a.label}: ${if (a.min == 0f) "0 to 1" else "-1 to 1"}, 0 = unchanged")) } })
            val text = obj(JSONObject()
                .put("text", str("The words (or one emoji for a sticker)"))
                .put("start_s", num("When it appears, seconds of the OUTPUT video"))
                .put("end_s", num("When it disappears, seconds of the output video"))
                .put("x", num("Centre, 0 = left edge, 1 = right edge"))
                .put("y", num("Centre, 0 = top, 1 = bottom (titles ~0.45, captions ~0.85)"))
                .put("size", num("Text height as a fraction of the frame's short side, 0.03 to 0.2 (stickers up to 0.35)"))
                .put("color", str("#RRGGBB"))
                .put("style", enumOf(TextStyle.entries.map { it.name }, "Text look"))
                .put("font", enumOf(TextFont.entries.map { it.name }, "Font"))
                .put("anim_in", enumOf(TextAnim.entries.map { it.name }, "Entrance"))
                .put("anim_out", enumOf(TextAnim.entries.map { it.name }, "Exit"))
                .put("sticker", bool("True for an emoji sticker")))
            return obj(JSONObject()
                .put("explanation", str("One or two sentences for the user: what you changed and why, or what you couldn't do"))
                .put("segments", JSONObject().put("type", "array").put("items", segment)
                    .put("description", "Parts on the main track in playback order; may use any clip and jump between clips"))
                .put("look", look)
                .put("texts", JSONObject().put("type", "array").put("items", text).put("description", "Titles, captions and emoji stickers"))
                .put("keep_original_audio", bool("Keep the camera sound"))
                .put("original_volume", num("Camera sound master volume, 0 to 2"))
                .put("music_volume", num("Music volume 0 to 2; ignored without a music track"))
                .put("music_start_s", num("Where in the song to start, seconds"))
                .put("music_fade_in_s", num("Music fade-in at the start, seconds"))
                .put("music_fade_out_s", num("Music fade-out at the end, seconds"))
                .put("aspect", enumOf(Aspect.entries.map { it.name }, "Output shape"))
                .put("canvas", enumOf(Canvas.entries.map { it.name }, "When a clip's shape differs from the output: CROP fills, BLACK letterboxes, BLUR puts it on a blurred copy")))
        }

        /**
         * The same job as an API request, as one message for the Claude app (works on a Pro/Max plan, no API key):
         * the instructions, a plain-language field list instead of the JSON schema, the clips and the current edit,
         * and the request. The reply is pasted back and read by [parseReply].
         */
        fun chatPrompt(request: String, clips: List<VideoInfo>, current: EditPlan, luts: List<LutInfo>, withStoryboard: Boolean, shots: String? = null): String {
            val clipsText = clips.mapIndexed { i, v ->
                "clip $i: ${if (v.isImage) "photo (can show up to 10 s)" else "video ${"%.1f".format(v.durationMs / 1000.0)} s"}, " +
                    "${v.displayWidth}x${v.displayHeight}" + (if (v.hasAudio) ", has sound" else "") + (if (v.fromDji) ", DJI camera" else "") +
                    (if (v.looksFlat == true) ", looks flat/log" else "")
            }.joinToString("\n")
            val m = current.music
            return buildString {
                appendLine("I'm editing a video in GPic (an Android video editor). Plan the edit and reply with ONE JSON object in a ```json code block, then one or two sentences for me.")
                appendLine()
                appendLine(SYSTEM.substringBefore("Segment times").trim())
                appendLine()
                appendLine("JSON fields (leave out any you don't change):")
                appendLine("- explanation: string")
                appendLine("- segments: [{clip, start_s, end_s, speed (0.25-4), curve (${SpeedCurve.entries.joinToString("|") { it.name }}), volume (0-2), transition (${Transition.entries.joinToString("|") { it.name }}), motion (${Motion.entries.joinToString("|") { it.name }})}] in playback order; times are seconds within that clip")
                appendLine("- look: {lut (${(luts.map { it.id } + EditPlan.NO_LUT).joinToString("|")}), filter (NONE|${Filter.entries.joinToString("|") { it.name }}), filter_amount 0-1, ${Look.ADJUSTMENTS.joinToString(", ") { "${it.key} ${if (it.min == 0f) "0..1" else "-1..1"}" }}}")
                appendLine("- texts: [{text, start_s, end_s (seconds of the OUTPUT video), x 0-1, y 0-1 (titles ~0.45, captions ~0.85), size 0.03-0.2, color \"#RRGGBB\", style (${TextStyle.entries.joinToString("|") { it.name }}), font (${TextFont.entries.joinToString("|") { it.name }}), anim_in, anim_out (${TextAnim.entries.joinToString("|") { it.name }}), sticker true/false}]")
                appendLine("- keep_original_audio, original_volume 0-2, music_volume 0-2, music_start_s, music_fade_in_s, music_fade_out_s")
                appendLine("- aspect (${Aspect.entries.joinToString("|") { it.name }}), canvas (${Canvas.entries.joinToString("|") { it.name }})")
                appendLine()
                appendLine("Clips:")
                appendLine(clipsText)
                if (shots != null) {
                    appendLine("Shots found on the phone (S<n>: clip, time range in that clip, camera, scores 0-1, flags; prefer good, steady shots):")
                    appendLine(shots)
                }
                if (withStoryboard) appendLine("The attached image(s) show the best moment of the best shots, labelled with the shot, clip and time (S4 C1 0:03 = shot 4, clip 1 at 3 s).")
                if (m != null) {
                    appendLine("Music: ${m.title}" + (if (m.bpm > 0) ", ${m.bpm.toInt()} BPM" else "") +
                        (if (m.beatsMs.isNotEmpty()) ", beats at output seconds: " + m.beatsMs.map { (it - m.startMs) / 1000.0 }.filter { it >= 0 }.take(60).joinToString { "%.2f".format(it) } else ""))
                }
                appendLine("Current edit (output ${"%.1f".format(current.outputMs(clips.map { it.durationMs }) / 1000.0)} s):")
                appendLine(forClaude(current).toString())
                appendLine()
                append("My request: ").append(request.trim())
            }
        }

        /**
         * Reads a pasted chat reply: the ```json block, or else the first balanced {...}. Throws [ClaudeException]
         * with a readable reason when there's no usable JSON.
         */
        fun parseReply(text: String, current: EditPlan, durations: List<Long>, lutIds: Set<String>): PlannedEdit {
            val json = extractJson(text) ?: throw ClaudeException("Couldn't find a JSON plan in what was pasted. Copy Claude's whole reply.")
            val o = runCatching { JSONObject(json) }.getOrElse { throw ClaudeException("Claude's plan isn't valid JSON (${it.message?.take(80)}). Ask it to send the JSON again.") }
            val plan = fromClaude(o, current).sanitized(durations, lutIds)
            val note = o.optString("explanation").ifBlank { text.substringAfterLast("```").trim().take(300) }.ifBlank { "Applied Claude's plan." }
            return PlannedEdit(plan, note)
        }

        internal fun extractJson(text: String): String? {
            Regex("```(?:json)?\\s*([\\s\\S]*?)```").findAll(text).map { it.groupValues[1].trim() }.firstOrNull { it.startsWith("{") }?.let { return it }
            val start = text.indexOf('{')
            if (start < 0) return null
            var depth = 0
            var inString = false
            var escape = false
            for (i in start until text.length) {
                val ch = text[i]
                if (inString) {
                    if (escape) escape = false else if (ch == '\\') escape = true else if (ch == '"') inString = false
                    continue
                }
                when (ch) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) return text.substring(start, i + 1) }
                }
            }
            return null
        }

        private val SYSTEM = """
            You plan video edits inside GPic, an Android app, from one or more clips. The user says what they want;
            you return the complete edit plan as JSON matching the schema, starting from current_plan and changing
            only what the request asks for.
            The app can: play parts of the clips in the order you list them (a montage may use any clip, in any
            order; cutting = leaving parts out); set each part's speed or a speed ramp, its camera volume, a
            transition into the next part and a slow zoom/pan; colour the whole video (camera LUT, filter preset,
            exposure/contrast/saturation/temperature and similar sliders, vignette); add titles, captions and emoji
            stickers with animations; mix the one music track the user picked (volume, start, fades); choose the output
            shape and what fills around a clip of another shape. Photos (is_photo) can be shown for up to 10 s.
            For anything else (stabilisation, reversing, chroma key, music the user hasn't picked) do what you can and
            say plainly in explanation what you couldn't do.
            Segment times are seconds within the part's clip and must stay within 0..that clip's duration_s. Text
            times are seconds of the output video (output_length_s long). When music has beats_in_output_s, cutting
            so each part ends on one of those times makes the edit feel on the beat (part output length = clip
            length / speed).
            LUTs: use each one only for what its use_for says. A D-Log M LUT on footage shot in normal colour looks
            wrong, so apply one when the user asks for it or the footage is clearly flat/log (a DJI clip whose frames
            look grey and washed out); otherwise leave lut as it is.
            explanation is shown to the user: one or two plain sentences.
        """.trimIndent()
        private const val WITH_FRAMES = "\nFrames from the video are attached with their timestamps; use them to find the moments the user means."
        private const val NO_FRAMES = "\nYou can't see the video. If the request depends on what happens in it, plan from the timing alone and say so."

        /** org.json → plain Maps/Lists so [JsonValue.from] can take it. */
        private fun plain(v: Any?): Any? = when (v) {
            is JSONObject -> v.keys().asSequence().associateWith { plain(v.get(it)) }
            is JSONArray -> (0 until v.length()).map { plain(v.get(it)) }
            JSONObject.NULL -> null
            else -> v
        }
    }
}
