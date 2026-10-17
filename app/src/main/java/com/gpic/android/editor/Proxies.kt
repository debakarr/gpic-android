package com.gpic.android.editor

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
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
 * Edit copies ("proxies", like LightCut's "compress HD videos before use"): a 540p, ≤ 30 fps H.264 copy with a
 * keyframe every half second, so previewing, scrubbing, thumbnails and analysis stay smooth on big clips. The
 * export always reads the original, so the result keeps full quality.
 */
@OptIn(UnstableApi::class)
object Proxies {
    const val SHORT_SIDE = 540

    /** Worth a copy: bigger than 720p, faster than 60 fps, or HEVC (often 10-bit, heavy to decode). */
    fun needed(v: VideoInfo): Boolean =
        !v.isImage && (minOf(v.width, v.height) > 720 || (v.frameRate ?: 0f) > 61f || v.isHevc)

    suspend fun make(context: Context, v: VideoInfo, out: File, onProgress: (Int) -> Unit): Uri {
        val main = Handler(Looper.getMainLooper())
        val tmp = File(out.parentFile, out.name + ".part")
        val effects = buildList {
            add(Presentation.createForShortSide(SHORT_SIDE))
            if ((v.frameRate ?: 30f) > 31f) add(FrameDropEffect.createDefaultFrameDropEffect(30f))
        }
        val item = EditedMediaItem.Builder(MediaItem.fromUri(v.uri)).setEffects(Effects(emptyList(), effects)).build()
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(listOf(item)).build())
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()
        suspendCancellableCoroutine { cont ->
            val holder = ProgressHolder()
            lateinit var transformer: Transformer
            val poll = object : Runnable {
                override fun run() {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                    main.postDelayed(this, 400)
                }
            }
            main.post {
                transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(
                        DefaultEncoderFactory.Builder(context)
                            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(2_000_000).setiFrameIntervalSeconds(0.5f).build())
                            .setEnableFallback(true)
                            .build()
                    )
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            main.removeCallbacks(poll)
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            main.removeCallbacks(poll)
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    })
                    .build()
                if (!cont.isActive) return@post
                transformer.start(composition, tmp.absolutePath)
                main.post(poll)
            }
            cont.invokeOnCancellation { main.post { main.removeCallbacks(poll); runCatching { transformer.cancel() }; tmp.delete() } }
        }
        if (!tmp.renameTo(out)) throw IllegalStateException("Couldn't save the edit copy")
        return Uri.fromFile(out)
    }
}
