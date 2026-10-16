package com.gpic.android.data.upload

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class JpegHeadTest {
    private fun seg(marker: Int, payload: Int) =
        byteArrayOf(0xFF.toByte(), marker.toByte(), ((payload + 2) shr 8).toByte(), (payload + 2).toByte()) + ByteArray(payload)

    private val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    private val sos = byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 8, 1, 2, 3, 4, 5, 6)

    @Test fun completeWhenScanStartIsInBuffer() {
        val b = soi + seg(0xE1, 300) + seg(0xDB, 64) + sos + ByteArray(1000)
        assertTrue(isJpeg(b))
        assertTrue(jpegMetadataComplete(b))
    }

    @Test fun incompleteWhenASegmentRunsPastTheBuffer() {
        val full = soi + seg(0xE1, 60_000) + seg(0xE2, 60_000) + sos
        assertFalse(jpegMetadataComplete(full.copyOf(100_000)))
        assertTrue(jpegMetadataComplete(full))
    }

    @Test fun fillBytesAndBadMarkers() {
        assertTrue(jpegMetadataComplete(soi + byteArrayOf(0xFF.toByte()) + seg(0xE0, 10) + sos))
        assertFalse(jpegMetadataComplete(soi + byteArrayOf(0x12, 0x34, 0, 0, 0, 0)))
        assertFalse(isJpeg(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte())))
    }

    @Test fun readHeadStopsAtMaxOrEnd() {
        val data = ByteArray(5000) { it.toByte() }
        // A stream that hands out at most 7 bytes per read, like some content providers.
        fun trickle(): InputStream = object : ByteArrayInputStream(data) {
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(len, 7))
        }
        assertArrayEquals(data.copyOf(1234), readHead(trickle(), 1234))
        assertEquals(5000, readHead(trickle(), 10_000).size)
    }
}
