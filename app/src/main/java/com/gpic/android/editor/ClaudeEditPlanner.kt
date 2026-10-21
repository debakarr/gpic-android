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
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import org.json.JSONObject
import java.time.Duration

class ClaudeException(message: String) : Exception(message)

/** One turn of a conversation with Claude: text plus JPEG images (user turns only). */
class Turn(val user: Boolean, val text: String, val images: List<ByteArray> = emptyList())

/**
 * Claude edits the video with the user's own API key. The prompt and the reply format are the same as for the
 * Claude app ([chatPrompt], [ClaudeEdits]); a conversation lets GPic show Claude the result and ask it to check
 * ([reviewPrompt]). The video itself never leaves the phone: only the text and, when allowed, small frames.
 */
class ClaudeEditPlanner(apiKey: String, private val model: String = MODEL) {
    private val client = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        // Thinking plus a few images can take a while; the default 10 min is more than a phone user waits.
        .timeout(Duration.ofMinutes(3))
        .maxRetries(2)
        .build()

    /** Claude's reply text to the last user turn of [turns]. */
    fun ask(turns: List<Turn>): String {
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .system(SYSTEM)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build())
            .apply {
                // If a safety classifier declines, retry server-side on Anthropic's recommended model instead of
                // failing. Haiku 5.5 has no server-side fallback, so it's asked only of the others.
                if (!model.startsWith("claude-haiku")) {
                    putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                    putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                }
                for (t in turns) {
                    if (!t.user) { addAssistantMessage(t.text); continue }
                    val blocks = mutableListOf<ContentBlockParam>()
                    for (img in t.images) blocks += ContentBlockParam.ofImage(
                        Base64ImageSource.builder().data(Base64.encodeToString(img, Base64.NO_WRAP)).mediaType(Base64ImageSource.MediaType.IMAGE_JPEG).build()
                    )
                    blocks += ContentBlockParam.ofText(t.text)
                    addUserMessageOfBlockParams(blocks)
                }
            }
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
        return response.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
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

        /**
         * Claude's old reply shape (the whole plan) → a plan: per-part extras it can't see (rotation, own looks) are
         * kept by position, and anything it left out stays as it was.
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

        /** What Claude is told about the clips, shots, music and the edit so far (shared by both prompts). */
        private fun context(clips: List<VideoInfo>, current: EditPlan, durations: List<Long>, shotRefs: List<ShotRef>, shots: String?): String = buildString {
            appendLine("Clips:")
            clips.forEachIndexed { i, v ->
                appendLine("clip $i: ${if (v.isImage) "photo (can show up to 10 s)" else "video ${"%.1f".format(v.durationMs / 1000.0)} s"}, " +
                    "${v.displayWidth}x${v.displayHeight}" + (v.frameRate?.let { ", ${it.toInt()} fps" } ?: "") + (if (v.hasAudio) ", has sound" else "") +
                    (if (v.fromDji) ", DJI camera" else "") + (if (v.looksFlat == true) ", looks flat/log" else ""))
            }
            if (shots != null) {
                appendLine("Shots found on the phone (S#: clip, time range in that clip, camera, scores 0-1, flags):")
                appendLine(shots)
            }
            val m = current.music
            if (m != null && m.beatsMs.isNotEmpty()) {
                appendLine("Music beats at output seconds: " + m.beatsMs.map { (it - m.startMs) / 1000.0 }.filter { it >= 0 }.take(60).joinToString { "%.2f".format(it) })
            }
            appendLine("Current edit:")
            append(ClaudeEdits.summary(current, durations, shotRefs))
        }

        /**
         * The whole job as one message (pasted into the Claude app, or the first API turn): what GPic can do, how to
         * cut well, the reply format, the clips, shots and current edit, and the request.
         */
        fun chatPrompt(
            request: String, clips: List<VideoInfo>, current: EditPlan, luts: List<LutInfo>, withStoryboard: Boolean,
            shots: String? = null, shotRefs: List<ShotRef> = emptyList(),
        ): String = buildString {
            appendLine("I'm editing a video in GPic, an Android video editor. Edit it for my request below by sending a list of edits.")
            appendLine()
            appendLine(ABOUT)
            appendLine()
            appendLine(ClaudeEdits.CRAFT)
            appendLine()
            appendLine(ClaudeEdits.guide(luts.map { it.id }))
            if (luts.isNotEmpty()) appendLine("LUTs: " + luts.joinToString("; ") { "${it.id} = ${it.title}, for ${it.description}" } + ". " + LUT_ADVICE)
            appendLine()
            if (withStoryboard) appendLine("The attached image(s) show the best moment of the best shots, labelled with the shot, clip and time (S4 C1 0:03 = shot 4, clip 1 at 3 s).")
            else appendLine("You can't see the video: plan from the shot list and say so if the request depends on what happens in it.")
            appendLine(context(clips, current, clips.map { it.durationMs }, shotRefs, shots))
            appendLine()
            append("My request: ").append(request.trim())
        }

        /**
         * Asking Claude to check the edit it made, with the result board attached (a frame from the middle of each
         * part as it will export). Self-contained, because a share to the Claude app starts a new chat.
         */
        fun reviewPrompt(
            request: String, clips: List<VideoInfo>, current: EditPlan, luts: List<LutInfo>, problems: List<String>,
            shots: String? = null, shotRefs: List<ShotRef> = emptyList(), fresh: Boolean = true,
        ): String = buildString {
            if (fresh) {
                appendLine("I'm editing a video in GPic, an Android video editor. It was edited for my request below; now check how it came out.")
                appendLine()
                appendLine(ABOUT)
                appendLine()
                appendLine(ClaudeEdits.CRAFT)
                appendLine()
                appendLine(ClaudeEdits.guide(luts.map { it.id }))
                appendLine()
            } else appendLine("GPic applied your edits. Here's how it came out.")
            appendLine("The attached image is the edit as it plays now: one frame from the middle of each part, labelled with the part (P#) and " +
                "its output time, with the colour, shape and texts as they export (transitions and motion aren't shown).")
            if (problems.isNotEmpty()) {
                appendLine("These edits couldn't be done:")
                problems.forEach { appendLine("- $it") }
            }
            appendLine("Look at it critically: does it do what I asked; are these the right moments; is a text cut off, hard to read or " +
                "over a face; is the crop cutting off the subject; is a part dark, blurry or a repeat of the one before? Fix what's wrong " +
                "with edits in the same JSON format. If it's good, send \"edits\": [] and say so in explanation.")
            appendLine()
            appendLine(context(clips, current, clips.map { it.durationMs }, shotRefs, if (fresh) shots else null))
            if (fresh) { appendLine(); append("My request was: ").append(request.trim()) }
        }

        /**
         * Reads a reply (pasted from the Claude app, or from the API): the ```json block, or else the first balanced
         * {...}. An "edits" list is applied with [refs]; an old whole-plan reply still works. Throws
         * [ClaudeException] with a readable reason when there's no usable JSON.
         */
        fun parseReply(text: String, current: EditPlan, refs: EditRefs, durations: List<Long>, lutIds: Set<String>): ClaudeEdit {
            val json = extractJson(text) ?: throw ClaudeException("Couldn't find Claude's JSON in what was pasted. Copy Claude's whole reply.")
            val o = runCatching { JSONObject(json) }.getOrElse { throw ClaudeException("Claude's JSON isn't valid (${it.message?.take(80)}). Ask it to send the JSON again.") }
            val note = o.optString("explanation").ifBlank { text.substringAfterLast("```").trim().take(300) }
            val ops = ClaudeEdits.ops(o)
            if (ops != null) return ClaudeEdits.apply(ops, current, refs, durations, lutIds, note.ifBlank { if (ops.isEmpty()) "Claude is happy with the edit." else "Applied Claude's edits." })
            val plan = fromClaude(o, current).sanitized(durations, lutIds)
            return ClaudeEdit(plan, note.ifBlank { "Applied Claude's plan." }, emptyList(), emptyList(), 1, emptyList())
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

        private val ABOUT = """
            GPic can: play parts of the clips in any order (cutting = leaving parts out); set each part's speed or a speed
            ramp, its camera volume, a transition into the next part and a slow zoom/pan; colour the whole video (camera
            LUT, filter, sliders, vignette); add titles, captions and emoji stickers with animations; find free music
            and cut on its beat; change the output shape and what fills around a clip of another shape; rebuild the
            edit from a template. Photos can show for up to 10 s. It can't stabilise, reverse, key out a background
            or generate footage: do what you can and say plainly in explanation what you couldn't do.
        """.trimIndent()
        private const val LUT_ADVICE = "A D-Log M LUT on footage shot in normal colour looks wrong: use it only when I ask or the footage looks flat/log."
        private const val SYSTEM = "You are a skilled short-video editor working inside GPic, an Android app. You edit by replying with " +
            "JSON edits exactly in the format the user's message describes. Keep explanations short and plain."
    }
}
