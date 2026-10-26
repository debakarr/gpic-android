package com.gpic.android.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.media.FaceDetector
import android.media.MediaMetadataRetriever
import android.os.Build
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Measures clips for [SmartEdit] on the phone (nothing leaves it): frames every 0.5 s from the edit copy (bursts of
 * them spread over a clip longer than [DENSE_MAX_MS], so motion is always measured between frames 0.5 s apart),
 * 96 px wide for the picture statistics and motion, plus Android's built-in face detector on a 240 px frame about
 * once a second. Results are kept per clip, in memory and in the cache folder (a long clip takes a minute or two).
 */
object VideoAnalyzer {
    private const val W = 96
    private const val STEP_MS = 500L
    /** Up to this long, every 0.5 s (240 samples); longer clips get [MAX_BURSTS] bursts of [BURST] samples. */
    private const val DENSE_MAX_MS = 120_000L
    private const val BURST = 4
    private const val MAX_BURSTS = 120
    private const val MAX_SHIFT = 24
    /** Bump when the measurements change, so old saved results are measured again. */
    private const val VERSION = 2
    private val cache = HashMap<String, ClipAnalysis>()

    private fun key(v: VideoInfo) = "${v.uri}|${v.durationMs}|${v.sizeBytes}"

    fun analyze(context: Context, clip: Int, v: VideoInfo, onProgress: (Float) -> Unit = {}): ClipAnalysis {
        synchronized(cache) { cache[key(v)]?.let { return ClipAnalysis(clip, it.durationMs, it.fps, it.frames, it.isPhoto) } }
        val saved = if (v.isImage) null else savedFile(context, v)
        val result = saved?.let { load(it, clip) }
            ?: (if (v.isImage) analyzeImage(context, clip, v) else analyzeVideo(context, clip, v, onProgress)).also { a -> saved?.let { save(it, a) } }
        synchronized(cache) { cache[key(v)] = result }
        return result
    }

    /**
     * When to sample a clip of [durationMs] (ms). Long clips: bursts 0.5 s apart inside, spread evenly, so shake and
     * camera moves are measured between close frames however long the clip is.
     */
    internal fun sampleTimes(durationMs: Long): List<Long> {
        if (durationMs <= DENSE_MAX_MS) return (0 until max(1, (durationMs / STEP_MS).toInt())).map { it * STEP_MS + STEP_MS / 2 }
        val bursts = (durationMs / 3_000).toInt().coerceIn(40, MAX_BURSTS)
        val span = (BURST - 1) * STEP_MS
        return (0 until bursts).flatMap { b ->
            val centre = durationMs * (2 * b + 1) / (2 * bursts)
            val start = ((centre - span / 2) / STEP_MS * STEP_MS + STEP_MS / 2).coerceIn(STEP_MS / 2, durationMs - span - STEP_MS / 2)
            (0 until BURST).map { start + it * STEP_MS }
        }
    }

    private fun savedFile(context: Context, v: VideoInfo): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(key(v).toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(context.cacheDir, "analysis"), "$hash.bin")
    }

    private fun save(f: File, a: ClipAnalysis) = runCatching {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".part")
        DataOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { o ->
            o.writeInt(VERSION); o.writeLong(a.durationMs); o.writeFloat(a.fps); o.writeInt(a.frames.size)
            for (s in a.frames) {
                o.writeLong(s.tMs)
                for (x in floatArrayOf(s.luma, s.contrast, s.sharpness, s.colorful, s.dx, s.dy, s.action, s.change, s.faceSize)) o.writeFloat(x)
                o.writeInt(s.faces); o.writeBoolean(s.gap); o.writeInt(s.embed.size)
                s.embed.forEach(o::writeFloat)
            }
        }
        if (!tmp.renameTo(f)) tmp.delete()
    }.onFailure { android.util.Log.w("GPicSmart", "couldn't save the analysis: ${it.message}") }

    private fun load(f: File, clip: Int): ClipAnalysis? = if (!f.exists()) null else runCatching {
        DataInputStream(BufferedInputStream(FileInputStream(f))).use { i ->
            if (i.readInt() != VERSION) return@use null
            val duration = i.readLong()
            val fps = i.readFloat()
            val frames = List(i.readInt()) {
                val t = i.readLong()
                val v = FloatArray(9) { i.readFloat() }
                val faces = i.readInt()
                val gap = i.readBoolean()
                val embed = FloatArray(i.readInt()) { i.readFloat() }
                FrameStat(t, v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], faces, v[8], embed, gap)
            }
            ClipAnalysis(clip, duration, fps, frames)
        }
    }.getOrNull()

    private fun analyzeImage(context: Context, clip: Int, v: VideoInfo): ClipAnalysis {
        val bmp = VideoProbe.thumbnail(context, v, 0, 240)
        val stat = bmp?.let { measure(it, null, 0) }
        // A photo is one still "shot" that can be shown for up to 10 s.
        return ClipAnalysis(clip, VideoProbe.IMAGE_MAX_MS, 30f, listOfNotNull(stat), isPhoto = true)
    }

    private fun analyzeVideo(context: Context, clip: Int, v: VideoInfo, onProgress: (Float) -> Unit): ClipAnalysis {
        val times = sampleTimes(v.durationMs)
        val frames = ArrayList<FrameStat>(times.size)
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, v.editUri)
            var prev: Gray? = null
            var lastT = 0L
            // A sample far from the one before (or the first) takes its motion from the next one, which is close.
            var pendingGap = -1
            for ((i, t) in times.withIndex()) {
                val big = frame(r, t, 240, v) ?: continue
                val gap = prev == null || t - lastT > STEP_MS * 3 / 2
                val (stat, gray) = measure(big, prev, t, faces = i % 2 == 0)
                if (!gap && pendingGap >= 0) {
                    frames[pendingGap] = frames[pendingGap].let { g ->
                        FrameStat(g.tMs, g.luma, g.contrast, g.sharpness, g.colorful, stat.dx, stat.dy, stat.action, g.change, g.faces, g.faceSize, g.embed, gap = true)
                    }
                    pendingGap = -1
                }
                frames += if (gap) FrameStat(stat.tMs, stat.luma, stat.contrast, stat.sharpness, stat.colorful, 0f, 0f, 0f, stat.change,
                    stat.faces, stat.faceSize, stat.embed, gap = true) else stat
                if (gap) pendingGap = frames.lastIndex
                prev = gray
                lastT = t
                big.recycle()
                onProgress((i + 1f) / times.size)
            }
        } finally {
            r.release()
        }
        if (frames.isNotEmpty()) android.util.Log.i("GPicSmart", "${v.name}: ${frames.size} samples, sharp mean %.5f min %.5f, luma %.2f, motion %.3f, jitter %.3f, faces %d".format(
            frames.map { it.sharpness }.average(), frames.minOf { it.sharpness }, frames.map { it.luma }.average(),
            frames.map { kotlin.math.hypot(it.dx, it.dy) }.average(),
            frames.zipWithNext().filter { (p, q) -> !p.gap && !q.gap }.map { (p, q) -> kotlin.math.hypot(q.dx - p.dx, q.dy - p.dy) }.average().let { if (it.isNaN()) 0.0 else it },
            frames.count { it.faces > 0 }))
        return ClipAnalysis(clip, v.durationMs, v.frameRate ?: 30f, frames)
    }

    private fun frame(r: MediaMetadataRetriever, tMs: Long, maxSide: Int, v: VideoInfo): Bitmap? = runCatching {
        val w = v.displayWidth.coerceAtLeast(1)
        val h = v.displayHeight.coerceAtLeast(1)
        val s = maxSide.toFloat() / max(w, h)
        // Edit copies have a keyframe every 0.5 s, so the nearest one is close enough (and fast). Originals can
        // have one every few seconds: then every sample would be the same frame, so decode the exact one.
        val opt = if (v.proxy != null) MediaMetadataRetriever.OPTION_CLOSEST_SYNC else MediaMetadataRetriever.OPTION_CLOSEST
        if (Build.VERSION.SDK_INT >= 27) {
            // The retriever applies the clip's rotation, so ask for the displayed shape.
            val raw = r.getScaledFrameAtTime(tMs * 1000, opt, (v.width * s).toInt().coerceAtLeast(2), (v.height * s).toInt().coerceAtLeast(2))
            raw
        } else r.getFrameAtTime(tMs * 1000, opt)?.let { Bitmap.createScaledBitmap(it, (w * s).toInt().coerceAtLeast(2), (h * s).toInt().coerceAtLeast(2), true) }
    }.getOrNull()

    /** Grey 96 px image of a frame (for motion between samples). */
    private class Gray(val w: Int, val h: Int, val px: FloatArray, val hist: FloatArray) {
        val cols = FloatArray(w).also { c -> for (y in 0 until h) for (x in 0 until w) c[x] += px[y * w + x] / h }
        val rows = FloatArray(h).also { r -> for (y in 0 until h) for (x in 0 until w) r[y] += px[y * w + x] / w }
    }

    private fun measure(big: Bitmap, prev: Gray?, tMs: Long) = measure(big, prev, tMs, faces = true).first

    private fun measure(big: Bitmap, prev: Gray?, tMs: Long, faces: Boolean): Pair<FrameStat, Gray> {
        val h = max(2, big.height * W / max(1, big.width))
        val small = Bitmap.createScaledBitmap(big, W, h, true)
        val argb = IntArray(W * h)
        small.getPixels(argb, 0, W, 0, 0, W, h)
        if (small !== big) small.recycle()
        val gray = FloatArray(argb.size)
        var sum = 0f
        var sumSq = 0f
        var rg = 0f; var yb = 0f; var rg2 = 0f; var yb2 = 0f
        val hist = FloatArray(64)
        for ((i, c) in argb.withIndex()) {
            val r = (c shr 16 and 0xFF); val g = (c shr 8 and 0xFF); val b = (c and 0xFF)
            val y = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
            gray[i] = y; sum += y; sumSq += y * y
            val a = (r - g) / 255f; val bb = (0.5f * (r + g) - b) / 255f
            rg += a; yb += bb; rg2 += a * a; yb2 += bb * bb
            hist[(r shr 6) * 16 + (g shr 6) * 4 + (b shr 6)] += 1f / argb.size
        }
        val n = argb.size.toFloat()
        val mean = sum / n
        val std = sqrt(max(0f, sumSq / n - mean * mean))
        val sdRg = sqrt(max(0f, rg2 / n - (rg / n) * (rg / n))); val sdYb = sqrt(max(0f, yb2 / n - (yb / n) * (yb / n)))
        val colorful = min(1f, (sqrt(sdRg * sdRg + sdYb * sdYb) + 0.3f * sqrt((rg / n) * (rg / n) + (yb / n) * (yb / n))) * 2.2f)
        // Sharpness: variance of the 4-neighbour Laplacian.
        var lap = 0f; var lap2 = 0f; var cnt = 0
        for (y in 1 until h - 1) for (x in 1 until W - 1) {
            val i = y * W + x
            val l = 4 * gray[i] - gray[i - 1] - gray[i + 1] - gray[i - W] - gray[i + W]
            lap += l; lap2 += l * l; cnt++
        }
        val sharp = if (cnt > 0) lap2 / cnt - (lap / cnt) * (lap / cnt) else 0f
        val g = Gray(W, h, gray, hist)
        var dx = 0f; var dy = 0f; var action = 0f; var change = 0f
        if (prev != null && prev.w == g.w && prev.h == g.h) {
            val sx = bestShift(prev.cols, g.cols)
            val sy = bestShift(prev.rows, g.rows)
            // The picture moved right/down by (sx, sy): the camera panned left / tilted up.
            dx = sx.toFloat() / g.w; dy = sy.toFloat() / g.h
            action = residual(prev, g, sx, sy)
            var d = 0f
            for (i in hist.indices) d += abs(hist[i] - prev.hist[i])
            change = d / 2
        }
        // Tiny 4×4 grey image + colour histogram: enough to spot the same scene twice.
        val tiny = FloatArray(16)
        for (y in 0 until h) for (x in 0 until W) tiny[(y * 4 / h) * 4 + x * 4 / W] += gray[y * W + x] / (n / 16)
        val embed = hist + tiny.map { it * 0.25f }
        var faceCount = 0
        var faceSize = 0f
        if (faces) runCatching {
            // FaceDetector wants RGB_565 and an even width.
            val fw = big.width / 2 * 2
            val bmp565 = Bitmap.createBitmap(fw, big.height, Bitmap.Config.RGB_565)
            Canvas(bmp565).drawBitmap(big, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            val found = arrayOfNulls<FaceDetector.Face>(4)
            faceCount = FaceDetector(fw, big.height, 4).findFaces(bmp565, found)
            faceSize = found.filterNotNull().filter { it.confidence() >= 0.3f }.maxOfOrNull { it.eyesDistance() * 2.5f / fw } ?: 0f
            if (faceSize == 0f) faceCount = 0
            bmp565.recycle()
        }
        return FrameStat(tMs, mean, std, sharp, colorful, dx, dy, action, change, faceCount, faceSize, embed) to g
    }

    /**
     * Shift (in px) of profile [b] relative to [a] that lines them up best (mean absolute difference). Zooms and
     * strongly repeating patterns can mislead it (a zoom can read as shake).
     */
    private fun bestShift(a: FloatArray, b: FloatArray): Int {
        val n = a.size
        val ma = a.average().toFloat(); val mb = b.average().toFloat()
        var best = 0
        var bestErr = Float.MAX_VALUE
        val maxS = min(MAX_SHIFT, n / 3)
        for (s in -maxS..maxS) {
            var err = 0f; var c = 0
            for (i in 0 until n) { val j = i - s; if (j in 0 until n) { err += abs((b[i] - mb) - (a[j] - ma)); c++ } }
            if (c == 0) continue
            if (err / c < bestErr) { bestErr = err / c; best = s }
        }
        return best
    }

    /** Mean difference after shifting [a] by (sx, sy): what moved on its own. */
    private fun residual(a: Gray, b: Gray, sx: Int, sy: Int): Float {
        var d = 0f; var c = 0
        for (y in 0 until b.h) for (x in 0 until b.w) {
            val ax = x - sx; val ay = y - sy
            if (ax in 0 until a.w && ay in 0 until a.h) { d += abs(b.px[y * b.w + x] - a.px[ay * a.w + ax]); c++ }
        }
        return if (c > 0) min(1f, d / c * 4f) else 0f
    }
}
