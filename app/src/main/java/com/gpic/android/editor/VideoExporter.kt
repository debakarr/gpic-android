package com.gpic.android.editor

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SingleColorLut
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Builds the Media3 pieces for an [EditPlan]; also used for the live preview's video effects. */
@OptIn(UnstableApi::class)
object EditEffects {
    /**
     * Picture effects, in order: colour first (LUTs expect camera colours), then geometry. [sourceShortSide]
     * keeps the height cap from upscaling a smaller video.
     */
    fun video(plan: EditPlan, luts: LutStore, sourceShortSide: Int, frame: Pair<Int, Int>? = null): List<Effect> = buildList {
        plan.lutId?.let { add(SingleColorLut.createFromCube(luts.load(it).cube)) }
        if (plan.brightness != 0f) add(Brightness(plan.brightness))
        if (plan.contrast != 0f) add(Contrast(plan.contrast))
        if (plan.saturation != 0f) add(HslAdjustment.Builder().adjustSaturation(plan.saturation).build())
        if (plan.rotation != 0) add(ScaleAndRotateTransformation.Builder().setRotationDegrees(-plan.rotation.toFloat()).build())
        if (frame != null) {
            // Several clips: every part is fitted to one output frame (cropped to the chosen shape, or letterboxed
            // when keeping the first clip's shape) so mixed sizes and orientations play as one video.
            val layout = if (plan.aspect == Aspect.ORIGINAL) Presentation.LAYOUT_SCALE_TO_FIT else Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
            add(Presentation.createForWidthAndHeight(frame.first, frame.second, layout))
        } else {
            plan.aspect.ratio?.let { add(Presentation.createForAspectRatio(it, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)) }
            if (plan.maxHeight > 0 && plan.maxHeight < sourceShortSide) add(Presentation.createForShortSide(plan.maxHeight))
        }
    }

    /** One output size for a multi-clip edit: the first clip's shape (or the chosen one), short side ≤ 1080. */
    fun outputFrame(plan: EditPlan, first: VideoInfo): Pair<Int, Int> {
        val w0 = first.displayWidth.coerceAtLeast(2)
        val h0 = first.displayHeight.coerceAtLeast(2)
        val ratio = plan.aspect.ratio ?: (w0.toFloat() / h0)
        val short = listOf(minOf(w0, h0), 1080, plan.maxHeight.takeIf { it > 0 } ?: Int.MAX_VALUE).min()
        val (w, h) = if (ratio >= 1f) (short * ratio).toInt() to short else short to (short / ratio).toInt()
        return (w / 2 * 2) to (h / 2 * 2)
    }

    /**
     * Fades a part in from black and out to black over [fadeUs]. Counts from the first frame it sees, so it
     * doesn't depend on how the sequence offsets each part's timestamps.
     */
    class FadeThroughBlack(private val partUs: Long, private val fadeUs: Long) : androidx.media3.effect.RgbMatrix {
        private var firstUs = Long.MIN_VALUE

        override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
            if (firstUs == Long.MIN_VALUE) firstUs = presentationTimeUs
            val t = (presentationTimeUs - firstUs).toFloat()
            val k = minOf(t / fadeUs, (partUs - t) / fadeUs, 1f).coerceIn(0f, 1f)
            return floatArrayOf(k, 0f, 0f, 0f, 0f, k, 0f, 0f, 0f, 0f, k, 0f, 0f, 0f, 0f, 1f)
        }

        override fun isNoOp(inputWidth: Int, inputHeight: Int) = false
    }

    fun volume(gain: Float): AudioProcessor = ChannelMixingAudioProcessor().apply {
        for (channels in 1..2) putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(channels, channels).scaleBy(gain))
    }

    fun constantSpeed(speed: Float) = object : SpeedProvider {
        override fun getSpeed(timeUs: Long) = speed
        override fun getNextSpeedChangeTimeUs(timeUs: Long) = androidx.media3.common.C.TIME_UNSET
    }
}

/**
 * Renders an [EditPlan] with Media3 Transformer (H.264 + AAC, hardware codecs) and saves the result to
 * Movies/GPic. Runs on the main looper, as Transformer requires; [export] suspends until it's done.
 */
@OptIn(UnstableApi::class)
class VideoExporter(private val context: Context, private val luts: LutStore) {
    private val main = Handler(Looper.getMainLooper())

    suspend fun export(
        clips: List<VideoInfo>,
        plan: EditPlan,
        music: Uri?,
        onProgress: (Int) -> Unit,
    ): Uri {
        val out = File(context.cacheDir, "edit-${System.currentTimeMillis()}.mp4")
        try {
            render(composition(clips, plan, music), out, onProgress)
            return saveToMovies(out, clips.first().name)
        } finally {
            out.delete()
        }
    }

    private fun composition(videos: List<VideoInfo>, plan: EditPlan, music: Uri?): Composition {
        val parts = plan.parts(videos.map { it.durationMs })
        val keepCamera = plan.keepOriginalAudio && plan.originalVolume > 0f
        val anyAudio = keepCamera && parts.any { videos[it.clip].hasAudio }
        val frame = if (videos.size > 1) EditEffects.outputFrame(plan, videos.first()) else null
        val items = parts.map { seg ->
            val video = videos[seg.clip]
            val item = MediaItem.Builder()
                .setUri(video.uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setStartPositionMs(seg.startMs).setEndPositionMs(seg.endMs).build()
                )
                .build()
            val dropAudio = !keepCamera || !video.hasAudio
            val audio = if (!dropAudio && plan.originalVolume != 1f) listOf(EditEffects.volume(plan.originalVolume)) else emptyList()
            val picture = EditEffects.video(plan, luts, minOf(video.width, video.height), frame) +
                listOfNotNull(EditEffects.FadeThroughBlack((seg.durationMs * 1000 / plan.speed).toLong(), FADE_US).takeIf { plan.fades })
            EditedMediaItem.Builder(item)
                .setRemoveAudio(dropAudio)
                .setEffects(Effects(audio, picture))
                .apply { if (plan.speed != 1f) setSpeed(EditEffects.constantSpeed(plan.speed)) }
                .build()
        }
        val sequences = mutableListOf(
            // Parts without camera sound get silence, so the sequence keeps one audio timeline (for the other
            // parts' sound and for the music to mix into).
            EditedMediaItemSequence.Builder(items)
                .apply { if (anyAudio || music != null) experimentalSetForceAudioTrack(true) }
                .build()
        )
        if (music != null && plan.musicVolume > 0f) {
            val track = MediaItem.Builder().setUri(music)
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(plan.musicStartMs).build())
                .build()
            val edited = EditedMediaItem.Builder(track)
                .setRemoveVideo(true)
                .setEffects(Effects(listOfNotNull(EditEffects.volume(plan.musicVolume).takeIf { plan.musicVolume != 1f }), emptyList()))
                .build()
            // Loops under the whole video; the export ends with the (non-looping) video sequence.
            sequences += EditedMediaItemSequence.Builder(edited).setIsLooping(true).build()
        }
        // The output is H.264 SDR and the LUTs are SDR looks, so HLG/HDR10 clips are tone-mapped down.
        return Composition.Builder(sequences).setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL).build()
    }

    private companion object {
        const val FADE_US = 350_000L
    }

    private suspend fun render(composition: Composition, out: File, onProgress: (Int) -> Unit) =
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
                transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            main.removeCallbacks(poll)
                            onProgress(100)
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            main.removeCallbacks(poll)
                            if (cont.isActive) cont.resumeWithException(
                                IllegalStateException(friendlyError(exportException), exportException)
                            )
                        }
                    })
                    .build()
                if (!cont.isActive) return@post
                transformer.start(composition, out.absolutePath)
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
