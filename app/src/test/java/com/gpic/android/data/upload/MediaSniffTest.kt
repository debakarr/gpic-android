package com.gpic.android.data.upload

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MediaSniffTest {
    private fun bytes(vararg v: Int) = ByteArray(16).also { v.forEachIndexed { i, x -> it[i] = x.toByte() } }
    private fun ascii(at: Int, s: String, base: ByteArray = ByteArray(16)) = base.also { s.forEachIndexed { i, c -> it[at + i] = c.code.toByte() } }

    @Test fun realFilesPass() {
        assertNull(MediaSniff.mismatch("a.JPG", bytes(0xFF, 0xD8, 0xFF, 0xE1)))
        assertNull(MediaSniff.mismatch("a.png", bytes(0x89, 'P'.code, 'N'.code, 'G'.code)))
        assertNull(MediaSniff.mismatch("a.webp", ascii(8, "WEBP", ascii(0, "RIFF"))))
        assertNull(MediaSniff.mismatch("a.mp4", ascii(4, "ftyp")))
        assertNull(MediaSniff.mismatch("a.mov", ascii(4, "moov")))
        assertNull(MediaSniff.mismatch("a.heic", ascii(4, "ftyp")))
        assertNull(MediaSniff.mismatch("a.gif", ascii(0, "GIF89a")))
    }

    @Test fun mismatchesAreCaught() {
        assertNotNull(MediaSniff.mismatch("x.webp", ascii(0, "RIFF")))           // RIFF but not WEBP (e.g. a WAV)
        assertNotNull(MediaSniff.mismatch("x.jpg", ascii(0, "<html>")))
        assertNotNull(MediaSniff.mismatch("x.png", bytes(0xFF, 0xD8, 0xFF)))      // a JPEG named .png
        assertNotNull(MediaSniff.mismatch("x.mp4", ByteArray(3)))                 // too short
    }

    @Test fun uncheckedFormatsAreLeftAlone() {
        assertNull(MediaSniff.mismatch("x.dng", ByteArray(16)))
        assertNull(MediaSniff.mismatch("x.mts", ByteArray(16)))
        assertNull(MediaSniff.mismatch("x.ORF", ascii(0, "IIRO")))
    }
}
