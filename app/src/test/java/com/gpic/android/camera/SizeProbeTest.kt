package com.gpic.android.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SizeProbeTest {
    /** A camera serving a [size]-byte file: 206 below it, 416 past it. Counts the reads. */
    private class Camera(val size: Long, val rangeWorks: Boolean = true) {
        var reads = 0
        fun exists(at: Long): Boolean? { reads++; return if (!rangeWorks) null else at in 0 until size }
    }

    private val big = 6_312_345_678L // 08:06 of 4K60
    private val wrapped = big % (1L shl 32)

    @Test fun headLengthIsCheckedWithTwoReads() {
        val cam = Camera(big)
        assertEquals(big, SizeProbe.resolve(listOf(0, big, 0), cam::exists))
        assertEquals(2, cam.reads)
    }

    @Test fun zeroFromTheManifestIsFoundWithoutHelp() {
        val cam = Camera(11_900_000_123L)
        assertEquals(11_900_000_123L, SizeProbe.resolve(listOf(0, 0, 0), cam::exists))
        assertTrue("took ${cam.reads} reads", cam.reads < 50)
    }

    @Test fun aWrappedSizeIsRejected() {
        // The manifest's u32 wrapped, and so did the server's headers: the byte after it still exists.
        val cam = Camera(big)
        assertEquals(big, SizeProbe.resolve(listOf(wrapped, wrapped, wrapped), cam::exists))
    }

    @Test fun theRightCandidateWinsOverAWrongOne() {
        assertEquals(big, SizeProbe.resolve(listOf(wrapped, big), Camera(big)::exists))
    }

    @Test fun smallFilesAndExactPowersOfTwo() {
        assertEquals(1L, SizeProbe.resolve(listOf(0), Camera(1)::exists))
        assertEquals(1L shl 32, SizeProbe.resolve(listOf(0), Camera(1L shl 32)::exists))
        assertEquals(1L shl 30, SizeProbe.resolve(listOf(0), Camera(1L shl 30)::exists))
    }

    @Test fun withoutRangeReadsTheServersAnswerIsUsed() {
        assertEquals(big, SizeProbe.resolve(listOf(0, big, 0), Camera(big, rangeWorks = false)::exists))
        assertEquals(0L, SizeProbe.resolve(listOf(0, 0, 0), Camera(big, rangeWorks = false)::exists))
    }

    @Test fun contentRangeTotal() {
        assertEquals(big, SizeProbe.rangeTotal("bytes 0-0/$big"))
        assertNull(SizeProbe.rangeTotal("bytes 0-0/*"))
        assertNull(SizeProbe.rangeTotal(null))
    }
}
