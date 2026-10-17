package com.gpic.android.editor

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream

data class VideoInfo(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val frameRate: Float?,
    val mime: String?,
    val hasAudio: Boolean,
    val bitrate: Long?,
    /** HDR / log transfer from the container, when the platform reports it (API 30+). */
    val colorTransfer: Int?,
    /** The middle frame looks flat (low contrast and saturation), like D-Log M before grading. Null = unknown. */
    val looksFlat: Boolean?,
    /** A photo used as a clip: it can be shown for up to [durationMs] (see [VideoProbe.IMAGE_MAX_MS]). */
    val isImage: Boolean = false,
    /** Small H.264 copy for the preview, thumbnails and analysis ([Proxies]); export always reads [uri]. */
    val proxy: Uri? = null,
    val proxyDurationMs: Long = 0,
) {
    /** What the editor reads for anything but the export. */
    val editUri: Uri get() = proxy ?: uri
    val isHevc: Boolean get() = mime?.contains("hevc", true) == true || mime?.contains("h265", true) == true
    /** Display size after the container's rotation. */
    val displayWidth: Int get() = if (rotation % 180 == 0) width else height
    val displayHeight: Int get() = if (rotation % 180 == 0) height else width
    val fromDji: Boolean get() = name.startsWith("DJI_", ignoreCase = true)
}

/** A sampled frame for Claude: which clip, when in it, and a small JPEG. */
class Frame(val timeMs: Long, val jpeg: ByteArray, val clip: Int = 0)

object VideoProbe {
    /** A photo can be shown for up to this long; it starts at [IMAGE_DEFAULT_MS]. */
    const val IMAGE_MAX_MS = 10_000L
    const val IMAGE_DEFAULT_MS = 3_000L

    fun probe(context: Context, uri: Uri): VideoInfo {
        val (name, size) = nameAndSize(context, uri)
        val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
        if (type?.startsWith("image/") == true) return probeImage(context, uri, name, size, type)
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            fun int(key: Int) = r.extractMetadata(key)?.toIntOrNull()
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: throw IllegalArgumentException("Not a video Android can read")
            val transfer = if (Build.VERSION.SDK_INT >= 30) int(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER) else null
            val middle = runCatching { frameAt(r, duration * 500, 96) }.getOrNull()
            return VideoInfo(
                uri = uri, name = name, sizeBytes = size, durationMs = duration,
                width = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: 0,
                height = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: 0,
                rotation = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: 0,
                // The capture-rate tag is often missing; frames ÷ duration is what the file really holds.
                frameRate = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
                    ?: if (Build.VERSION.SDK_INT >= 28) r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull()
                        ?.takeIf { duration > 0 }?.let { it * 1000f / duration } else null,
                mime = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
                hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes",
                bitrate = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull(),
                colorTransfer = transfer,
                looksFlat = middle?.let { looksFlat(it) },
            )
        } finally {
            r.release()
        }
    }

    private fun probeImage(context: Context, uri: Uri, name: String, size: Long, type: String): VideoInfo {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
        require(opts.outWidth > 0 && opts.outHeight > 0) { "Not a photo Android can read" }
        val rotation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { s ->
                when (android.media.ExifInterface(s).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                    6 -> 90; 3 -> 180; 8 -> 270; else -> 0
                }
            }
        }.getOrNull() ?: 0
        return VideoInfo(
            uri = uri, name = name, sizeBytes = size, durationMs = IMAGE_MAX_MS, width = opts.outWidth, height = opts.outHeight,
            rotation = rotation, frameRate = null, mime = type, hasAudio = false, bitrate = null, colorTransfer = null,
            looksFlat = null, isImage = true,
        )
    }

    /** One frame (or the photo) at [timeMs], longest side [maxSide] px; for timeline thumbnails. */
    fun thumbnail(context: Context, info: VideoInfo, timeMs: Long, maxSide: Int): Bitmap? = runCatching {
        if (info.isImage) {
            val opts = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = Integer.highestOneBit((maxOf(info.width, info.height) / maxSide).coerceAtLeast(1))
            }
            context.contentResolver.openInputStream(info.uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }?.let { b ->
                val m = android.graphics.Matrix().apply { if (info.rotation != 0) postRotate(info.rotation.toFloat()) }
                val scale = maxSide.toFloat() / maxOf(b.width, b.height)
                m.postScale(scale, scale)
                Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true).also { if (it !== b) b.recycle() }
            }
        } else {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, info.editUri)
                frameAt(r, timeMs * 1000, maxSide)
            } finally {
                r.release()
            }
        }
    }.getOrNull()

    /** [count] frames spread over the video (never the very first/last), longest side [maxSide] px. */
    fun frames(context: Context, uri: Uri, durationMs: Long, count: Int, maxSide: Int = 512, clip: Int = 0): List<Frame> {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            return (0 until count).mapNotNull { i ->
                val t = durationMs * (2 * i + 1) / (2 * count)
                val bmp = runCatching { frameAt(r, t * 1000, maxSide) }.getOrNull() ?: return@mapNotNull null
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
                bmp.recycle()
                Frame(t, out.toByteArray(), clip)
            }
        } finally {
            r.release()
        }
    }

    /**
     * How lively each moment of a clip is, for Quick edit: tiny frames (48 px) every [ClipScores.stepMs]
     * (at least 1 s, at most 120 samples a clip), scored by change from the previous frame plus colourfulness,
     * with very dark frames played down. Slow-ish on old phones (~0.1 s a frame), so it reports progress.
     */
    fun motionScores(context: Context, uri: Uri, durationMs: Long, onFrame: (Int, Int) -> Unit = { _, _ -> }): ClipScores {
        val step = maxOf(1_000L, durationMs / MAX_SAMPLES)
        val n = maxOf(1, (durationMs / step).toInt())
        val scores = FloatArray(n)
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            var prev: IntArray? = null
            for (i in 0 until n) {
                val bmp = runCatching { frameAt(r, (i * step + step / 2) * 1000, 48) }.getOrNull()
                if (bmp == null) { onFrame(i + 1, n); continue }
                val px = IntArray(bmp.width * bmp.height)
                bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                bmp.recycle()
                scores[i] = frameScore(px, prev)
                prev = px
                onFrame(i + 1, n)
            }
        } finally {
            r.release()
        }
        val top = scores.maxOrNull()?.takeIf { it > 0f } ?: 1f
        for (i in scores.indices) scores[i] /= top
        return ClipScores(durationMs, step, scores)
    }

    /** Change from [prev] (mean absolute luma difference) weighted 0.7, colourfulness 0.3; dark frames x0.3. */
    internal fun frameScore(px: IntArray, prev: IntArray?): Float {
        var diff = 0f
        var sat = 0f
        var luma = 0f
        for ((i, c) in px.withIndex()) {
            val r = (c shr 16 and 0xFF) / 255f
            val g = (c shr 8 and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
            luma += y
            val mx = maxOf(r, g, b)
            sat += if (mx > 0f) (mx - minOf(r, g, b)) / mx else 0f
            if (prev != null && prev.size == px.size) {
                val p = prev[i]
                val py = 0.2126f * (p shr 16 and 0xFF) / 255f + 0.7152f * (p shr 8 and 0xFF) / 255f + 0.0722f * (p and 0xFF) / 255f
                diff += kotlin.math.abs(y - py)
            }
        }
        val count = px.size.coerceAtLeast(1)
        val motion = (diff / count * 4f).coerceAtMost(1f)
        val score = 0.7f * motion + 0.3f * (sat / count)
        return if (luma / count < 0.08f) score * 0.3f else score
    }

    private const val MAX_SAMPLES = 120L

    private fun frameAt(r: MediaMetadataRetriever, timeUs: Long, maxSide: Int): Bitmap? {
        val opt = MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        if (Build.VERSION.SDK_INT >= 27) {
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: maxSide
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: maxSide
            val scale = maxSide.toFloat() / maxOf(w, h, 1)
            return r.getScaledFrameAtTime(timeUs, opt, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
        }
        val full = r.getFrameAtTime(timeUs, opt) ?: return null
        val scale = maxSide.toFloat() / maxOf(full.width, full.height)
        return Bitmap.createScaledBitmap(full, (full.width * scale).toInt().coerceAtLeast(1), (full.height * scale).toInt().coerceAtLeast(1), true)
            .also { if (it !== full) full.recycle() }
    }

    /**
     * Log footage before grading has its tones squeezed into the middle and little colour. A rough test
     * (it can't tell D-Log M from a foggy day), used only to suggest a LUT, never to apply one.
     */
    internal fun looksFlat(bmp: Bitmap): Boolean {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return looksFlat(px)
    }

    internal fun looksFlat(argb: IntArray): Boolean {
        if (argb.isEmpty()) return false
        val luma = FloatArray(argb.size)
        var satSum = 0f
        for ((i, c) in argb.withIndex()) {
            val r = (c shr 16 and 0xFF) / 255f
            val g = (c shr 8 and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            luma[i] = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val mx = maxOf(r, g, b)
            val mn = minOf(r, g, b)
            satSum += if (mx > 0f) (mx - mn) / mx else 0f
        }
        luma.sort()
        val p2 = luma[(luma.size * 0.02f).toInt()]
        val p98 = luma[((luma.size - 1) * 0.98f).toInt()]
        val meanSat = satSum / argb.size
        return p2 > 0.05f && p98 < 0.9f && (p98 - p2) < 0.75f && meanSat < 0.25f
    }

    private fun nameAndSize(context: Context, uri: Uri): Pair<String, Long> = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) (c.getString(0) ?: "video") to (if (c.isNull(1)) 0L else c.getLong(1)) else null
        }
    }.getOrNull() ?: ((uri.lastPathSegment ?: "video") to 0L)
}
