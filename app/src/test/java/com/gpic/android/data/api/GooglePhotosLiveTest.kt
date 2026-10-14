package com.gpic.android.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64
import kotlin.random.Random

/**
 * Live end-to-end check against Google Photos. Uploads ONE random image to the account, so it only
 * runs with an explicit opt-in on top of the token (direnv may export G_TOKEN on every run):
 *   GPIC_LIVE_TEST=1 G_TOKEN='<photos.native line>' ./gradlew testDebugUnitTest --rerun --tests '*Live*'
 * (--rerun: env vars aren't Test-task inputs, so Gradle would otherwise reuse a cached skip.)
 *
 * The first upload attempt is cut off mid-body on purpose, so the retry has to ask the server how
 * much it holds and resume from the right byte — the path the off-by-one used to break.
 */
class GooglePhotosLiveTest {
    private val auth = System.getenv("G_TOKEN").orEmpty()

    @Test
    fun `upload with a forced mid-stream drop, then dedup finds it`() {
        assumeTrue("set GPIC_LIVE_TEST=1 to run (uploads an image)", System.getenv("GPIC_LIVE_TEST") == "1")
        assumeTrue("G_TOKEN must hold a photos.native auth line", auth.contains("photos.native"))
        val api = GooglePhotosApi(auth)

        // A real JPEG of random noise: valid media (Photos rejects junk at commit), unique per run.
        val img = java.awt.image.BufferedImage(640, 480, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 480) for (x in 0 until 640) img.setRGB(x, y, Random.nextInt(0xFFFFFF))
        val data = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(img, "jpg", it) }.toByteArray()
        println("test image: ${data.size} bytes")
        val sha1 = MessageDigest.getInstance("SHA-1").digest(data)
        assertEquals("fresh random file must not already exist", "", api.findMediaByHash(sha1))

        val uploadId = api.getUploadToken(Base64.getEncoder().encodeToString(sha1), data.size.toLong())
        val offsets = mutableListOf<Long>()
        var calls = 0
        val token = api.uploadStream(
            openStream = { offset ->
                offsets += offset
                val s = ByteArrayInputStream(data, offset.toInt(), data.size - offset.toInt())
                // First attempt dies halfway through, like a dropped connection.
                if (calls++ == 0) CutAfter(s, data.size / 2) else s
            },
            fileSize = data.size.toLong(),
            uploadId = uploadId,
        )
        println("upload attempts started at offsets: $offsets")
        assertTrue("expected a retry after the forced drop", offsets.size >= 2)

        val mediaKey = api.commitUpload(token, "gpic-live-test-${System.currentTimeMillis()}.jpg", sha1, System.currentTimeMillis() / 1000)
        assertTrue("commit returned a media key", mediaKey.isNotEmpty())
        println("committed; media key length ${mediaKey.length}")

        // Dedup: the same bytes are now found by hash.
        val found = api.findMediaByHash(sha1)
        println("hash lookup after upload: ${if (found.isEmpty()) "not found" else "found"}")
        assertEquals(mediaKey, found)
    }

    private class CutAfter(private val inner: InputStream, private var left: Int) : InputStream() {
        override fun read(): Int = if (left-- <= 0) throw IOException("simulated drop") else inner.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) throw IOException("simulated drop")
            val n = inner.read(b, off, minOf(len, left))
            if (n > 0) left -= n
            return n
        }
    }
}
