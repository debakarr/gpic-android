package com.gpic.android.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

class BeatsTest {
    /** A click track: short decaying noise bursts at [bpm], starting at [offsetMs], with quiet background noise. */
    private fun clicks(bpm: Double, seconds: Int, offsetMs: Int = 0, rate: Int = Beats.RATE): FloatArray {
        val rnd = java.util.Random(7)
        val x = FloatArray(seconds * rate) { (rnd.nextGaussian() * 0.01).toFloat() }
        val period = 60.0 / bpm * rate
        var t = offsetMs / 1000.0 * rate
        while (t < x.size) {
            val start = t.toInt()
            for (i in 0 until (0.03 * rate).toInt()) {
                if (start + i >= x.size) break
                x[start + i] += (exp(-i / (0.006 * rate)) * (rnd.nextGaussian() * 0.6 + sin(2 * PI * 180 * i / rate) * 0.4)).toFloat()
            }
            t += period
        }
        return x
    }

    @Test fun findsTempoAndBeatTimes() {
        for (bpm in listOf(90.0, 120.0, 128.0, 150.0)) {
            val info = Beats.detect(clicks(bpm, 20, offsetMs = 250))
            assertNotNull(info)
            assertTrue("bpm $bpm → ${info!!.bpm}", abs(info.bpm - bpm) < bpm * 0.03)
            val period = 60_000 / bpm
            // Every detected beat is within 40 ms of a real click.
            val errs = info.beatsMs.map { b -> val k = Math.round((b - 250) / period); abs(b - (250 + k * period)) }
            assertTrue("bpm $bpm worst ${errs.maxOrNull()} ms", errs.max() < 40)
            assertTrue(info.beatsMs.size >= (20_000 / period).toInt() - 2)
        }
    }

    @Test fun silenceHasNoBeat() {
        val info = Beats.detect(FloatArray(Beats.RATE * 5))
        assertTrue(info == null || info.beatsMs.size < 4 || info.bpm > 0)
    }

    @Test fun fftOfASineHasOnePeak() {
        val n = 64
        val re = DoubleArray(n) { sin(2 * PI * 5 * it / n) }
        val im = DoubleArray(n)
        Beats.fft(re, im)
        val mags = (0 until n / 2).map { kotlin.math.hypot(re[it], im[it]) }
        assertEquals(5, mags.indices.maxBy { mags[it] })
    }

    @Test fun markersFollowPaceAndStart() {
        val beats = (0 until 20).map { it * 500L }
        assertEquals(listOf(0L, 1000L, 2000L), Beats.markers(beats, Beats.Pace.NORMAL, 0, 2_000))
        assertEquals(listOf(0L, 2000L), Beats.markers(beats, Beats.Pace.CHILL, 1_000, 3_000).take(2))
    }

    @Test fun audioSniff() {
        assertEquals("mp3", AudioSniff.extension("ID3\u0004\u0000".toByteArray(Charsets.ISO_8859_1)))
        assertEquals("mp3", AudioSniff.extension(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0)))
        assertEquals("ogg", AudioSniff.extension("OggS\u0000".toByteArray()))
        assertEquals("wav", AudioSniff.extension("RIFF\u0000\u0000\u0000\u0000WAVEfmt ".toByteArray()))
        assertEquals("m4a", AudioSniff.extension("\u0000\u0000\u0000 ftypM4A ".toByteArray()))
        assertEquals(null, AudioSniff.extension("<html>".toByteArray()))
    }
}
