package com.gpic.android.editor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RadialGradient
import android.graphics.Shader
import android.media.MediaCodecList
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.OverlaySettings
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.ConvolutionFunction1D
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.GaussianBlurWithFrameOverlaid
import androidx.media3.effect.GaussianFunction
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SeparableConvolution
import androidx.media3.effect.SingleColorLut
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Builds the Media3 [Composition] for an [EditPlan]. The live preview (CompositionPlayer) and the export use the
 * same builder, so transitions, motion, colour and music look the same in both; only text is drawn by the
 * preview itself (so it can be dragged), and the preview renders smaller.
 */
@OptIn(UnstableApi::class)
class CompositionBuilder(private val luts: LutStore) {
    /** Baked looks, by look: baking is ~36k colour evaluations, so it's done once per distinct look. */
    private val baked = HashMap<Look, Array<Array<IntArray>>>()

    @Synchronized
    private fun cube(look: Look): Array<Array<IntArray>> = baked.getOrPut(look) {
        LookMath.bake(look, look.lutId?.let { runCatching { luts.load(it) }.getOrNull() })
    }

    class Output(val composition: Composition, val width: Int, val height: Int, val durationMs: Long)

    /**
     * [shortSideCap] limits the frame (the preview uses a small one); [withText] adds the text overlays (export).
     */
    fun build(clips: List<VideoInfo>, plan: EditPlan, shortSideCap: Int, withText: Boolean, useProxies: Boolean = false): Output {
        val parts = plan.parts(clips.map { it.durationMs })
        require(parts.isNotEmpty()) { "Nothing to play" }
        val (w, h) = outputFrame(plan, clips, parts, shortSideCap)
        val starts = Timeline.starts(parts)
        val total = starts.last()
        val keepCamera = plan.keepOriginalAudio && plan.originalVolume > 0f
        val anyCameraAudio = keepCamera && parts.any { clips[it.clip].hasAudio && it.volume > 0f }
        val edges = TransitionMath.edges(parts)
        val shared = Shared()
        val carry = CarryOver()
        val items = parts.mapIndexed { i, seg ->
            val clip = clips[seg.clip]
            // The preview reads the small edit copy when there is one; the export never does.
            val proxy = clip.proxy?.takeIf { useProxies }
            val mediaMs = if (proxy != null && clip.proxyDurationMs > 0) clip.proxyDurationMs else clip.durationMs
            val item = if (clip.isImage) {
                MediaItem.Builder().setUri(clip.uri).setImageDurationMs(seg.durationMs).build()
            } else {
                // A copy can be a few ms shorter than its original: clamp so the part stays inside it.
                val end = minOf(seg.endMs, mediaMs)
                MediaItem.Builder().setUri(proxy ?: clip.uri).setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setStartPositionMs(minOf(seg.startMs, end - 1)).setEndPositionMs(end).build()
                ).build()
            }
            val gain = plan.originalVolume * seg.volume
            val dropAudio = clip.isImage || !keepCamera || !clip.hasAudio || gain <= 0f
            // Camera sound (often mono) is mixed up to stereo with its volume, so stereo music stays stereo; it fades at
            // the cuts (a few ms against clicks, longer under fades and dissolves).
            val e = edges[i]
            val audio: List<AudioProcessor> = if (dropAudio) emptyList() else listOf(
                EditEffects.stereo(gain),
                FadeAudioProcessor(1f, audioFadeUs(e.inT, e.inUs, incoming = true), audioFadeUs(e.outT, e.outUs, incoming = false), seg.outputMs * 1000),
            )
            val video = partEffects(plan, parts[i], e, i, clip, w, h, starts[i], shared, carry)
            EditedMediaItem.Builder(item)
                .setRemoveAudio(dropAudio)
                .setEffects(Effects(audio, video))
                // The preview player needs every item's whole (unclipped) length up front.
                .setDurationUs((if (clip.isImage) seg.durationMs else mediaMs) * 1000)
                .apply {
                    if (clip.isImage) setFrameRate(IMAGE_FPS)
                    else if (seg.speed != 1f || seg.curve != SpeedCurve.NONE) setSpeed(speedProvider(seg))
                }
                .build()
        }
        val sequences = mutableListOf(
            // Declaring audio gives parts without camera sound silence, so the sequence keeps one audio timeline
            // (for the other parts' sound and for the music to mix into).
            EditedMediaItemSequence.Builder(
                if (anyCameraAudio || plan.music != null) setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO) else setOf(C.TRACK_TYPE_VIDEO)
            ).addItems(items).build()
        )
        plan.music?.takeIf { it.volume > 0f }?.let { m -> musicSequence(m, total)?.let { sequences += it } }
        val compositionVideo = buildList<Effect> {
            if (withText && plan.texts.isNotEmpty()) add(OverlayEffect(plan.texts.map { TextItemOverlay(it, w, h) }))
            if (plan.fps > 0 && clips.none { it.isImage }) add(FrameDropEffect.createDefaultFrameDropEffect(plan.fps.toFloat()))
        }
        val composition = Composition.Builder(sequences)
            // The output is H.264 SDR and the looks are SDR, so HLG/HDR10 clips are tone-mapped down.
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .setEffects(Effects(emptyList(), compositionVideo))
            .build()
        return Output(composition, w, h, total)
    }

    /** Song from [MusicTrack.startMs], repeated until it covers [totalMs], faded in and out at the video's ends. */
    private fun musicSequence(m: MusicTrack, totalMs: Long): EditedMediaItemSequence? {
        if (totalMs <= 0 || m.durationMs <= 0) return null
        val songMs = m.durationMs
        val pieces = mutableListOf<Pair<Long, Long>>() // (from, to) in the song
        var left = totalMs
        var from = m.startMs.coerceIn(0, maxOf(0, songMs - 1000))
        while (left > 0 && pieces.size < 50) {
            val to = minOf(songMs, from + left)
            if (to - from < 50) break
            pieces += from to to
            left -= to - from
            from = 0
        }
        if (pieces.isEmpty()) return null
        val items = pieces.mapIndexed { i, (a, b) ->
            val item = MediaItem.Builder().setUri(m.uri).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(a).setEndPositionMs(b).build()
            ).build()
            val fx = FadeAudioProcessor(
                m.volume,
                fadeInUs = if (i == 0) m.fadeInMs * 1000 else 0,
                fadeOutUs = if (i == pieces.lastIndex) m.fadeOutMs * 1000 else 0,
                durationUs = (b - a) * 1000,
            )
            EditedMediaItem.Builder(item).setRemoveVideo(true).setDurationUs(songMs * 1000).setEffects(Effects(listOf(fx), emptyList())).build()
        }
        // Audio only: otherwise the preview player treats it as a second picture to composite.
        return EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO)).addItems(items).build()
    }

    // ---- per-part picture -------------------------------------------------------------------------------------

    /**
     * Effects that don't change over time, one instance per distinct setting for the whole composition. Media3 keeps
     * a pipeline across a cut only when the next part's effect list is equal (the same instances), so parts with the
     * same look and shape play on without rebuilding shaders at the cut. Never shared between compositions: a LUT
     * frees its texture when its program is released.
     */
    private class Shared {
        val luts = HashMap<Look, Effect>()
        val turns = HashMap<Pair<Int, Boolean>, Effect>()
        val fits = HashMap<Any, List<Effect>>()
        val vignettes = HashMap<Float, Effect>()
    }

    private fun partEffects(plan: EditPlan, seg: Segment, e: PartEdges, i: Int, clip: VideoInfo, w: Int, h: Int, startMs: Long,
                            shared: Shared, carry: CarryOver): List<Effect> = buildList {
        val look = plan.lookOf(seg)
        val clock = PartClock(startMs * 1000, seg.outputMs * 1000)
        if (DEBUG_TIMES) add(TimeProbe("part$i", clock))
        if (!look.colourNeutral) add(shared.luts.getOrPut(look) { SingleColorLut.createFromCube(cube(look)) })
        if (seg.rotation != 0 || seg.mirror) {
            add(shared.turns.getOrPut(seg.rotation to seg.mirror) {
                ScaleAndRotateTransformation.Builder().setScale(if (seg.mirror) -1f else 1f, 1f).setRotationDegrees(-seg.rotation.toFloat()).build()
            })
        }
        // Fit into the output frame.
        val (cw, ch) = clip.displayWidth.toFloat() to clip.displayHeight.toFloat()
        val rotated = seg.rotation % 180 != 0
        val srcW = if (rotated) ch else cw
        val srcH = if (rotated) cw else ch
        val sameShape = srcW > 0 && srcH > 0 && kotlin.math.abs(srcW / srcH - w.toFloat() / h) < 0.01f
        when {
            sameShape || plan.canvas == Canvas.CROP -> addAll(shared.fits.getOrPut(Canvas.CROP) {
                listOf(Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
            })
            plan.canvas == Canvas.BLACK -> addAll(shared.fits.getOrPut(Canvas.BLACK) {
                listOf(Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT))
            })
            else -> {
                // Blurred canvas: size the picture to fit the frame; Media3's blur-with-overlay then grows the output
                // by 1/scale (to the frame) with a blurred, stretched copy behind the sharp picture.
                val fit = minOf(w / srcW, h / srcH)
                val fw = ((srcW * fit).toInt() / 2 * 2).coerceIn(2, w)
                val fh = ((srcH * fit).toInt() / 2 * 2).coerceIn(2, h)
                addAll(shared.fits.getOrPut(fw to fh) {
                    listOf(
                        Presentation.createForWidthAndHeight(fw, fh, Presentation.LAYOUT_SCALE_TO_FIT),
                        GaussianBlurWithFrameOverlaid(BLUR_SIGMA * h / 1080f, 3f, fw.toFloat() / w, fh.toFloat() / h),
                        // Rounding can leave it a pixel off; every part must come out exactly w × h.
                        Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_STRETCH_TO_FIT),
                    )
                })
            }
        }
        // Blur goes before the part's pass, so the frame kept for the next part's blur dissolve is already blurred.
        if (e.inT == Transition.DISSOLVE_BLUR || e.outT == Transition.DISSOLVE_BLUR) add(EdgeBlur(e, clock, h))
        if (e.any) add(PartFx(i, e, clock, carry))
        else if (seg.motion != Motion.NONE) add(KenBurnsMotion(seg.motion, clock))
        if (look.vignette > 0f) add(shared.vignettes.getOrPut(look.vignette) { OverlayEffect(listOf(vignette(w, h, look.vignette))) })
    }

    /** Camera sound at a cut: a few ms against clicks; as long as the picture's fade or dissolve under those. */
    private fun audioFadeUs(t: Transition, edgeUs: Long, incoming: Boolean): Long = when {
        t == Transition.FADE_BLACK || t == Transition.FADE_WHITE -> edgeUs
        t.blends -> if (incoming) edgeUs else 80_000L
        t.moves || t == Transition.GLITCH -> 40_000L
        else -> CLICK_FADE_US
    }.coerceIn(CLICK_FADE_US, 1_000_000L)

    private fun speedProvider(seg: Segment): SpeedProvider {
        val steps = Timeline.speedSteps(seg)
        return object : SpeedProvider {
            // Media3 passes times in the clipped item's own timeline (0 = the part's start).
            override fun getSpeed(timeUs: Long): Float {
                val ms = timeUs / 1000
                return steps.lastOrNull { it.first <= ms }?.second ?: steps.first().second
            }

            override fun getNextSpeedChangeTimeUs(timeUs: Long): Long {
                val ms = timeUs / 1000
                return steps.firstOrNull { it.first > ms }?.first?.times(1000) ?: C.TIME_UNSET
            }
        }
    }

    private fun vignette(w: Int, h: Int, amount: Float): TextureOverlay {
        val bw = (w / 4).coerceAtLeast(16)
        val bh = (h / 4).coerceAtLeast(16)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val r = kotlin.math.hypot(bw / 2f, bh / 2f)
        val edge = (amount * 0.85f * 255).toInt().coerceIn(0, 255)
        val paint = android.graphics.Paint().apply {
            shader = RadialGradient(bw / 2f, bh / 2f, r, intArrayOf(0, 0, edge shl 24), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), paint)
        return BitmapOverlay.createStaticBitmapOverlay(bmp, StaticOverlaySettings.Builder().setScale(w.toFloat() / bw, h.toFloat() / bh).build())
    }

    companion object {
        /** Logs the timestamps effects see (tag GPicFx), to check the part clock on a device. */
        @Volatile var DEBUG_TIMES = false
        const val IMAGE_FPS = 30
        /** In the fitted picture's pixels (it's then scaled up ~2–3× behind), so a modest blur looks strong and stays fast. */
        private const val BLUR_SIGMA = 9f
        private const val CLICK_FADE_US = 12_000L

        /**
         * The output size: the chosen shape (or the first part's clip's), short side ≤ [shortSideCap] and the
         * plan's resolution, never above the first clip's own size; even numbers (encoders need them).
         */
        fun outputFrame(plan: EditPlan, clips: List<VideoInfo>, parts: List<Segment>, shortSideCap: Int): Pair<Int, Int> {
            val first = clips[parts.first().clip]
            val rot = parts.first().rotation % 180 != 0
            val w0 = (if (rot) first.displayHeight else first.displayWidth).coerceAtLeast(2)
            val h0 = (if (rot) first.displayWidth else first.displayHeight).coerceAtLeast(2)
            val ratio = plan.aspect.ratio ?: (w0.toFloat() / h0)
            val sourceShort = clips.maxOf { minOf(it.displayWidth, it.displayHeight) }.coerceAtLeast(2)
            val short = listOf(sourceShort, shortSideCap, plan.maxHeight.takeIf { it > 0 } ?: Int.MAX_VALUE).min()
            val (w, h) = if (ratio >= 1f) (short * ratio).toInt() to short else short to (short / ratio).toInt()
            return (w / 2 * 2) to (h / 2 * 2)
        }
    }
}

@OptIn(UnstableApi::class)
object EditEffects {
    /** Mono or stereo in → stereo out, times [gain]. */
    fun stereo(gain: Float): AudioProcessor = androidx.media3.common.audio.ChannelMixingAudioProcessor().apply {
        putChannelMixingMatrix(androidx.media3.common.audio.ChannelMixingMatrix.createForConstantGain(1, 2).scaleBy(gain))
        putChannelMixingMatrix(androidx.media3.common.audio.ChannelMixingMatrix.createForConstantGain(2, 2).scaleBy(gain))
    }
}

/**
 * A part's own clock: output time from its first frame, in µs. Media3 hands effects the frame's presentation time
 * within the composition; [toLocal] turns that into time since the part started.
 */
class PartClock(val startUs: Long, val durationUs: Long) {
    fun toLocal(presentationUs: Long): Long = (presentationUs - startUs).coerceIn(0, durationUs)
}

@OptIn(UnstableApi::class)
private class TimeProbe(val name: String, val clock: PartClock) : RgbMatrix {
    private var last = Long.MIN_VALUE
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        if (last == Long.MIN_VALUE || kotlin.math.abs(presentationTimeUs - last) > 400_000) {
            Log.d("GPicFx", "$name t=$presentationTimeUs start=${clock.startUs} dur=${clock.durationUs}")
            last = presentationTimeUs
        }
        return floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
    }
    override fun isNoOp(inputWidth: Int, inputHeight: Int) = false
}

/** Blur that peaks at the cut (the Blur transition); the part's own pass then dissolves between the blurred frames. */
@OptIn(UnstableApi::class)
private class EdgeBlur(val edges: PartEdges, val clock: PartClock, val frameH: Int) : SeparableConvolution() {
    override fun getConvolution(presentationTimeUs: Long): ConvolutionFunction1D {
        val b = TransitionMath.blur(edges, clock.toLocal(presentationTimeUs), clock.durationUs)
        return GaussianFunction((b * 25f * frameH / 1080f).coerceAtLeast(0.01f), 3f)
    }
}

/** One text item as a Media3 overlay, positioned and animated by output time. */
@OptIn(UnstableApi::class)
private class TextItemOverlay(val item: TextItem, val frameW: Int, val frameH: Int) : BitmapOverlay() {
    private val full = TextRender.draw(item, frameW, frameH)
    private var partial: Pair<Int, Bitmap>? = null
    private var stateAt = TextAnimMath.at(item, item.startMs)

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val st = TextAnimMath.at(item, presentationTimeUs / 1000)
        stateAt = st
        if (st.chars >= item.text.length || !st.visible) return full.bitmap
        val cached = partial
        if (cached != null && cached.first == st.chars) return cached.second
        val b = TextRender.draw(item, frameW, frameH, st.chars).bitmap
        partial = st.chars to b
        return b
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val st = TextAnimMath.at(item, presentationTimeUs / 1000)
        // Overlay anchors are in NDC: x -1..1 left→right, y -1..1 bottom→top.
        val ax = item.x * 2 - 1
        val ay = 1 - (item.y - st.dy) * 2
        return StaticOverlaySettings.Builder()
            .setAlphaScale(if (st.visible) st.alpha else 0f)
            .setScale(st.scale, st.scale)
            .setBackgroundFrameAnchor(ax, ay)
            .build()
    }
}

/**
 * Renders an [EditPlan] with Media3 Transformer (H.264 + AAC, hardware codecs) and saves the result to
 * Movies/GPic. Runs on the main looper, as Transformer requires; [export] suspends until it's done.
 */
@OptIn(UnstableApi::class)
class VideoExporter(private val context: Context, luts: LutStore) {
    private val main = Handler(Looper.getMainLooper())
    private val builder = CompositionBuilder(luts)

    suspend fun export(clips: List<VideoInfo>, plan: EditPlan, onProgress: (Int) -> Unit): Uri {
        val out = File(context.cacheDir, "edit-${System.currentTimeMillis()}.mp4")
        try {
            val built = builder.build(clips, plan, shortSideCap = Encoders.maxShortSide(), withText = true, useProxies = false)
            android.util.Log.i("GPicEditor", "export ${built.width}x${built.height} from originals: ${clips.joinToString { it.name }}")
            render(built, plan, out, onProgress)
            return saveToMovies(out, clips.first().name)
        } finally {
            out.delete()
        }
    }

    private suspend fun render(built: CompositionBuilder.Output, plan: EditPlan, out: File, onProgress: (Int) -> Unit) =
        suspendCancellableCoroutine { cont ->
            val holder = ProgressHolder()
            lateinit var transformer: Transformer
            val poll = object : Runnable {
                override fun run() {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                    main.postDelayed(this, 300)
                }
            }
            main.post {
                val bitrate = Encoders.bitrate(built.width, built.height, plan.fps)
                transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(
                        DefaultEncoderFactory.Builder(context)
                            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                            .setEnableFallback(true)
                            .build()
                    )
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            main.removeCallbacks(poll)
                            onProgress(100)
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            main.removeCallbacks(poll)
                            if (cont.isActive) cont.resumeWithException(IllegalStateException(friendlyError(exportException), exportException))
                        }
                    })
                    .build()
                if (!cont.isActive) return@post
                transformer.start(built.composition, out.absolutePath)
                main.post(poll)
            }
            cont.invokeOnCancellation {
                main.post {
                    main.removeCallbacks(poll)
                    runCatching { transformer.cancel() }
                }
            }
        }

    private fun friendlyError(e: ExportException): String = when (e.errorCode) {
        ExportException.ERROR_CODE_DECODER_INIT_FAILED, ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
            "This phone can't decode this video (${e.errorCodeName}). 4K or 10-bit HEVC clips need a newer phone."
        ExportException.ERROR_CODE_ENCODER_INIT_FAILED, ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED ->
            "This phone's encoder can't make this output (${e.errorCodeName}). Try a lower resolution."
        else -> "Export failed: ${e.errorCodeName}" + (e.message?.let { " ($it)" } ?: "")
    }

    /** Copies the render into Movies/GPic (visible to Google Photos and the gallery) and returns its Uri. */
    private fun saveToMovies(file: File, sourceName: String): Uri {
        val name = sourceName.substringBeforeLast('.') + "_edit_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ".mp4"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/GPic")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values) ?: throw IllegalStateException("Couldn't create the output file in Movies")
        try {
            resolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } }
                ?: throw IllegalStateException("Couldn't write the output file")
            if (Build.VERSION.SDK_INT >= 29) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            }
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}

/** What this phone's H.264 encoder can make, and the bitrates GPic asks for. */
object Encoders {
    @Volatile private var cachedMax = 0

    /** Largest short side (of 720/1080/1440/2160) the AVC encoder supports in 16:9. */
    fun maxShortSide(): Int {
        if (cachedMax > 0) return cachedMax
        val caps = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MimeTypes.VIDEO_H264, true) } }
                .mapNotNull { it.getCapabilitiesForType(MimeTypes.VIDEO_H264).videoCapabilities }
        }.getOrDefault(emptyList())
        val best = listOf(2160, 1440, 1080, 720).firstOrNull { s ->
            val long = s * 16 / 9
            caps.any { c -> runCatching { c.isSizeSupported(long, s) || c.isSizeSupported(s, long) }.getOrDefault(false) }
        } ?: 1080
        cachedMax = best
        return best
    }

    /** Bits per second for a [w]×[h] output (≈ 0.1 bit per pixel per frame at 30 fps). */
    fun bitrate(w: Int, h: Int, fps: Int): Int {
        val f = if (fps > 0) fps else 30
        return (w.toLong() * h * f * 0.1).toLong().coerceIn(2_000_000, 60_000_000).toInt()
    }

    /** Rough file size of an export, for the export sheet. */
    fun estimateBytes(w: Int, h: Int, fps: Int, durationMs: Long): Long =
        (bitrate(w, h, fps).toLong() + 192_000) * durationMs / 8000
}
