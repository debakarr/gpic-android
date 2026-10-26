package com.gpic.android.editor

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.image.BitmapFactoryImageDecoder
import androidx.media3.transformer.Composition
import androidx.media3.transformer.CompositionPlayer
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Renders transitions through Media3 on a device or emulator (the part shader can't run in JVM tests) and checks the
 * frames around each cut: dissolves mix both pictures, slides show both side by side, moves never go black. The
 * frames are saved to look at: adb pull /sdcard/Android/data/com.gpic.android/files/transition-frames
 * Run with ./gradlew connectedDebugAndroidTest (a few minutes on an emulator).
 */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class TransitionRenderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val frames = File(context.getExternalFilesDir(null), "transition-frames").apply { mkdirs() }

    private val red = Color.rgb(220, 30, 30)
    private val green = Color.rgb(30, 200, 30)
    private val blue = Color.rgb(30, 60, 220)
    private val yellow = Color.rgb(230, 210, 30)

    /** A colour field with a white grid and ring, so moves and blur show. */
    private fun photo(name: String, color: Int, w: Int = 1280, h: Int = 720): VideoInfo {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(color)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE; strokeWidth = 5f; style = Paint.Style.STROKE }
        for (x in 0..w step 160) c.drawLine(x.toFloat(), 0f, x.toFloat(), h.toFloat(), p)
        for (y in 0..h step 160) c.drawLine(0f, y.toFloat(), w.toFloat(), y.toFloat(), p)
        c.drawCircle(w / 2f, h / 2f, h / 5f, p)
        val f = File(context.cacheDir, "$name.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return VideoProbe.probe(context, Uri.fromFile(f)).copy(name = f.name)
    }

    private fun export(clips: List<VideoInfo>, plan: EditPlan, name: String, shortSide: Int = 360): File {
        val built = CompositionBuilder(LutStore(context)).build(clips, plan, shortSide, withText = false)
        val out = File(context.cacheDir, "$name.mp4").apply { delete() }
        val done = CountDownLatch(1)
        var error: Exception? = null
        Handler(Looper.getMainLooper()).post {
            Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) = done.countDown()
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        error = exportException
                        done.countDown()
                    }
                })
                .build()
                .start(built.composition, out.absolutePath)
        }
        assertTrue("export of $name timed out", done.await(15, TimeUnit.MINUTES))
        error?.let { throw it }
        return out
    }

    private class Frames(file: File) : AutoCloseable {
        private val r = MediaMetadataRetriever().apply { setDataSource(file.absolutePath) }
        val durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        fun at(ms: Long): Bitmap = r.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST)!!
        override fun close() = r.release()
    }

    /** Mean red, green, blue and brightest channel (0..255) over a region given as fractions of the frame. */
    private fun mean(b: Bitmap, x0: Float = 0f, x1: Float = 1f, y0: Float = 0f, y1: Float = 1f): FloatArray {
        val out = FloatArray(4)
        var n = 0
        for (y in (y0 * b.height).toInt() until (y1 * b.height).toInt() step 4) for (x in (x0 * b.width).toInt() until (x1 * b.width).toInt() step 4) {
            val c = b.getPixel(x, y)
            val r = Color.red(c); val g = Color.green(c); val bl = Color.blue(c)
            out[0] += r; out[1] += g; out[2] += bl; out[3] += maxOf(r, g, bl); n++
        }
        for (i in out.indices) out[i] /= n.coerceAtLeast(1)
        return out
    }

    private fun shot(f: Frames, ms: Long, name: String): Bitmap = f.at(ms).also { b ->
        File(frames, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun notDark(b: Bitmap, what: String) {
        assertTrue("$what is dark: ${mean(b).toList()}", mean(b)[3] > 100)
        for ((x, y) in listOf(0f to 0f, 0.9f to 0f, 0f to 0.9f, 0.9f to 0.9f)) {
            assertTrue("$what has a dark corner at $x,$y", mean(b, x, x + 0.1f, y, y + 0.1f)[3] > 60)
        }
    }

    @Test fun photoTransitionsBlendMoveAndNeverGoBlack() {
        val clips = listOf(photo("red", red), photo("green", green), photo("blue", blue), photo("yellow", yellow))
        val tour = listOf(Transition.DISSOLVE, Transition.SLIDE_LEFT, Transition.ZOOM_IN, Transition.WHIP_LEFT, Transition.SPIN,
            Transition.FADE_BLACK, Transition.FADE_WHITE, Transition.DISSOLVE_BLUR, Transition.GLITCH, Transition.NONE)
        val parts = tour.mapIndexed { i, t -> Segment(0, 2_000, i % 4, motion = SmartEdit.PHOTO_MOVES[i % 4], transition = t, transitionMs = 800) }
        val file = export(clips, EditPlan(segments = parts, aspect = Aspect.WIDE, canvas = Canvas.BLUR), "photo-tour")
        Frames(file).use { f ->
            assertEquals(20_000.0, f.durationMs.toDouble(), 150.0)
            for ((i, t) in tour.dropLast(1).withIndex()) {
                val cut = (i + 1) * 2_000L
                for (dt in listOf(-300L, -40L, 0L, 40L, 200L, 400L, 600L)) shot(f, cut + dt, "photo_${i}_${t.name}_${if (dt < 0) "m" else "p"}${kotlin.math.abs(dt)}")
            }
            // Dissolve red → green: both at once halfway, each alone at the ends.
            mean(shot(f, 2_400, "check_dissolve_mid")).let { m -> assertTrue("dissolve mid ${m.toList()}", m[0] > 70 && m[1] > 70 && m[0] / m[1] in 0.6f..1.6f) }
            mean(f.at(2_034)).let { m -> assertTrue("dissolve start ${m.toList()}", m[0] > 1.8f * m[1]) }
            mean(f.at(2_766)).let { m -> assertTrue("dissolve end ${m.toList()}", m[1] > 1.8f * m[0]) }
            // Slide left, green → blue: halfway the old frame fills the left, the new one the right, nothing black between.
            val slide = shot(f, 4_400, "check_slide_mid")
            mean(slide, 0f, 0.4f).let { m -> assertTrue("slide left side ${m.toList()}", m[1] > m[2] && m[3] > 100) }
            mean(slide, 0.6f, 1f).let { m -> assertTrue("slide right side ${m.toList()}", m[2] > m[1] && m[3] > 100) }
            // Moves at their fastest, right at the cut: never black, no dark corners.
            for ((cut, name) in listOf(6_000L to "zoom", 8_000L to "whip", 10_000L to "spin", 18_000L to "glitch")) {
                notDark(f.at(cut - 34), "$name before the cut")
                notDark(f.at(cut + 34), "$name after the cut")
            }
            assertTrue("fade isn't black at the cut", mean(shot(f, 12_000, "check_fade_cut"))[3] < 40)
            mean(shot(f, 14_000, "check_flash_cut")).let { m -> assertTrue("flash isn't white ${m.toList()}", m[0] > 200 && m[1] > 200 && m[2] > 200) }
            // Blur dissolve yellow → red: green falls from yellow's to red's while both show.
            mean(shot(f, 16_200, "check_blur_mid")).let { m -> assertTrue("blur dissolve mid ${m.toList()}", m[1] in 50f..190f && m[0] > 150) }
        }
    }

    /**
     * The preview player (not Transformer) through dissolves, a slide and a zoom, then seeks into the middle of
     * them, where the previous part's frame was never drawn (those play as plain cuts): no errors, frames keep coming.
     */
    @Test fun previewPlaysThroughTransitionsAndSeeks() {
        val clips = listOf(photo("p-red", red), photo("p-green", green), photo("p-blue", blue))
        val parts = listOf(
            Segment(0, 1_500, 0, motion = Motion.ZOOM_IN, transition = Transition.DISSOLVE, transitionMs = 600),
            Segment(0, 1_500, 1, motion = Motion.PAN_LEFT, transition = Transition.SLIDE_LEFT, transitionMs = 600),
            Segment(0, 1_500, 2, transition = Transition.ZOOM_IN),
            Segment(0, 1_500, 0, transition = Transition.DISSOLVE_BLUR),
            Segment(0, 1_500, 1),
        )
        val built = CompositionBuilder(LutStore(context)).build(clips, EditPlan(segments = parts, canvas = Canvas.BLUR), 480, withText = false, useProxies = true)
        val main = Handler(Looper.getMainLooper())
        val reader = ImageReader.newInstance(built.width, built.height, PixelFormat.RGBA_8888, 4)
        val frames = java.util.concurrent.atomic.AtomicInteger()
        reader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close(); frames.incrementAndGet() }, main)
        var error: Exception? = null
        var ended = CountDownLatch(1)
        lateinit var player: CompositionPlayer
        main.post {
            player = CompositionPlayer.Builder(context)
                .setImageDecoderFactory(BitmapFactoryImageDecoder.Factory(context).setMaxOutputSize(1440))
                .build()
            player.addListener(object : Player.Listener {
                override fun onPlayerError(e: PlaybackException) { error = e; ended.countDown() }
                override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) ended.countDown() }
            })
            player.setVideoSurface(reader.surface, androidx.media3.common.util.Size(built.width, built.height))
            player.setComposition(built.composition)
            player.prepare()
            player.play()
        }
        try {
            assertTrue("preview didn't finish", ended.await(2, TimeUnit.MINUTES))
            error?.let { throw it }
            val played = frames.get()
            assertTrue("only $played frames", played > 60)
            for (seekMs in listOf(1_700L, 3_200L, 4_400L, 6_100L)) {
                ended = CountDownLatch(1)
                main.post { player.seekTo(seekMs); player.play() }
                assertTrue("preview didn't finish after a seek to $seekMs", ended.await(2, TimeUnit.MINUTES))
                error?.let { throw it }
            }
            assertTrue(frames.get() > played)
        } finally {
            main.post { player.release(); reader.close() }
        }
    }

    @Test fun videoTransitionsMixDecodedFrames() {
        // Real decoded video (not bitmaps): photos rendered to 3 s clips first.
        fun video(name: String, color: Int): VideoInfo {
            val still = photo("$name-still", color)
            val file = export(listOf(still), EditPlan(segments = listOf(Segment(0, 3_000, 0))), "$name-clip", shortSide = 720)
            return VideoProbe.probe(context, Uri.fromFile(file)).copy(name = file.name)
        }
        val clips = listOf(video("v-red", red), video("v-green", green), video("v-blue", blue))
        assertTrue(clips.none { it.isImage })
        val parts = listOf(
            Segment(0, 3_000, 0, transition = Transition.DISSOLVE, transitionMs = 800),
            Segment(0, 3_000, 1, transition = Transition.SLIDE_UP, transitionMs = 800),
            Segment(500, 2_500, 2, transition = Transition.ZOOM_OUT, transitionMs = 800),
            Segment(0, 2_000, 0),
        )
        val file = export(clips, EditPlan(segments = parts), "video-tour")
        Frames(file).use { f ->
            assertEquals(10_000.0, f.durationMs.toDouble(), 150.0)
            mean(shot(f, 3_400, "video_dissolve_mid")).let { m -> assertTrue("video dissolve ${m.toList()}", m[0] > 70 && m[1] > 70 && m[0] / m[1] in 0.6f..1.6f) }
            // Slide up, green → blue: the old frame goes up (top half), the new one comes from below.
            val slide = shot(f, 6_400, "video_slide_mid")
            mean(slide, 0f, 1f, 0f, 0.4f).let { m -> assertTrue("slide top ${m.toList()}", m[1] > m[2]) }
            mean(slide, 0f, 1f, 0.6f, 1f).let { m -> assertTrue("slide bottom ${m.toList()}", m[2] > m[1]) }
            notDark(shot(f, 7_966, "video_zoomout_before"), "zoom out before the cut")
            notDark(shot(f, 8_034, "video_zoomout_after"), "zoom out after the cut")
        }
    }
}
