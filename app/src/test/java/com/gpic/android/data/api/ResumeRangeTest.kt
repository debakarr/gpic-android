package com.gpic.android.data.api

import org.junit.Assert.assertEquals
import org.junit.Test

class ResumeRangeTest {
    @Test fun `range names last byte held, inclusive`() =
        assertEquals(1000L, nextByteFromRange("bytes=0-999"))

    @Test fun `single byte received resumes at 1`() =
        assertEquals(1L, nextByteFromRange("bytes=0-0"))

    @Test fun `no header means nothing arrived`() =
        assertEquals(0L, nextByteFromRange(null))

    @Test fun `malformed header restarts from 0`() =
        assertEquals(0L, nextByteFromRange("garbage"))
}
