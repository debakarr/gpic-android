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
class ClaudeEditPlanner(apiKey: String) {
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
        musicName: String?,
        frames: List<Frame>,
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
                    .put("shot_on_dji_camera", video.fromDji)
                    .put("middle_frame_looks_flat_like_log", video.looksFlat ?: JSONObject.NULL)
            }))
            .put("current_plan", current.toJson())
            .put("luts", JSONArray(luts.map { JSONObject().put("id", it.id).put("title", it.title).put("use_for", it.description) }))
            .put("music_track", musicName ?: JSONObject.NULL)
        val blocks = mutableListOf<ContentBlockParam>()
        blocks += ContentBlockParam.ofText("Video and current edit:\n$context")
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
            .model(MODEL)
            .maxTokens(16000L)
            .system(SYSTEM + if (frames.isEmpty()) NO_FRAMES else WITH_FRAMES)
            .outputConfig(
                OutputConfig.builder()
                    .effort(OutputConfig.Effort.MEDIUM)
                    .format(
                        JsonOutputFormat.builder()
                            .schema(JsonOutputFormat.Schema.builder().apply {
                                val schema = EditPlan.schema(luts.map { it.id })
                                schema.keys().forEach { k -> putAdditionalProperty(k, JsonValue.from(plain(schema.get(k)))) }
                            }.build())
                            .build()
                    )
                    .build()
            )
            // If a safety classifier declines, retry server-side on Anthropic's recommended model instead of failing.
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .addUserMessageOfBlockParams(blocks)
            .build()

        val response = try {
            client.messages().create(params)
        } catch (e: UnauthorizedException) {
            throw ClaudeException("Claude didn't accept the API key. Tap “API key” to check or replace it.")
        } catch (e: PermissionDeniedException) {
            throw ClaudeException("This API key isn't allowed to use $MODEL.")
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
        val plan = EditPlan.fromJson(json, base = current).sanitized(clips.map { it.durationMs }, luts.mapTo(HashSet()) { it.id })
        return PlannedEdit(plan, json.optString("explanation").ifBlank { "Updated the edit." })
    }

    private fun serviceMessage(e: AnthropicServiceException): String =
        runCatching { JSONObject(e.body().toString()).optJSONObject("error")?.optString("message") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: (e.message ?: "unknown error")

    companion object {
        const val MODEL = "claude-opus-5-5"
        /** Frames sent when the user lets Claude look, shared across all clips; each ≤ 512 px on its long side. */
        const val FRAME_COUNT = 24

        /** How many of [FRAME_COUNT] each clip gets: by length, at least 2 a clip. */
        fun framesPerClip(durations: List<Long>): List<Int> {
            val total = durations.sum().coerceAtLeast(1)
            return durations.map { d -> maxOf(2, (FRAME_COUNT * d / total).toInt()) }
        }

        private val SYSTEM = """
            You plan video edits inside GPic, an Android app, from one or more clips. The user says what they want;
            you return the complete edit plan as JSON matching the schema, starting from current_plan and changing
            only what the request asks for.
            The app can only: play parts of the clips in the order you list them (a montage may use any clip, in any
            order; cutting = leaving parts out), change the speed of the whole output, fade each part in and out
            through black, apply one colour LUT, adjust brightness/contrast/saturation, keep or mute the camera audio
            and set its volume, mix in the one music track the user picked (volume and start offset), crop to an
            aspect ratio, cap the output height, and rotate. For anything else (titles, other transitions,
            stabilisation, per-part speed, beat matching, music the user hasn't picked) do what you can and say
            plainly in explanation what you couldn't do.
            Times are seconds within the part's clip and must stay within 0..that clip's duration_s.
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
