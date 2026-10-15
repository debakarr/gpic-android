package com.gpic.android.data.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class AgeRuleTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun ms(y: Int, mo: Int, d: Int, h: Int = 12) =
        LocalDateTime.of(y, mo, d, h, 0).atZone(zone).toInstant().toEpochMilli()

    @Test fun calendarUnits() {
        val now = ms(2026, 3, 31)
        assertEquals(ms(2026, 3, 21), AgeRule(10, AgeUnit.DAYS).cutoffMs(now, zone))
        assertEquals(ms(2026, 3, 17), AgeRule(2, AgeUnit.WEEKS).cutoffMs(now, zone))
        // Month arithmetic clamps to the last day, not "30 days".
        assertEquals(ms(2026, 2, 28), AgeRule(1, AgeUnit.MONTHS).cutoffMs(now, zone))
        assertEquals(ms(2024, 3, 31), AgeRule(2, AgeUnit.YEARS).cutoffMs(now, zone))
        assertEquals(now, AgeRule(0, AgeUnit.DAYS).cutoffMs(now, zone))
    }

    @Test fun takenIsEarliestKnownTime() {
        assertEquals(100L, takenMs(200L, 100L))
        assertEquals(100L, takenMs(100L, 200L))
        assertEquals(200L, takenMs(200L, null))
        assertEquals(200L, takenMs(200L, 0L))
        assertEquals(0L, takenMs(0L, null))
    }

    @Test fun olderThanCutoff() {
        assertTrue(isOlderThan(99L, 100L))
        assertFalse(isOlderThan(100L, 100L))
        assertFalse(isOlderThan(0L, 100L)) // unknown date never counts as old
    }

    @Test fun exifOnlyReadWhenItCanChangeTheAnswer() {
        assertFalse(needsExif(modifiedMs = 50L, cutoffMs = 100L)) // already old by mtime
        assertTrue(needsExif(modifiedMs = 150L, cutoffMs = 100L)) // recently copied, maybe taken long ago
        assertTrue(needsExif(modifiedMs = 0L, cutoffMs = 100L))
    }

    @Test fun description() {
        assertEquals("1 day", AgeRule(1, AgeUnit.DAYS).description)
        assertEquals("6 months", AgeRule(6, AgeUnit.MONTHS).description)
    }
}

class ExifDateTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    @Test fun parsesLocalTimeInDeviceZone() {
        val expected = LocalDateTime.of(2024, 5, 6, 7, 8, 9).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, parseExifDate("2024:05:06 07:08:09", null, zone))
    }

    @Test fun honoursRecordedOffset() {
        val expected = java.time.OffsetDateTime.of(2024, 5, 6, 7, 8, 9, 0, java.time.ZoneOffset.ofHours(-4)).toInstant().toEpochMilli()
        assertEquals(expected, parseExifDate("2024:05:06 07:08:09", "-04:00", zone))
    }

    @Test fun rejectsBlankOrZeroed() {
        assertEquals(null, parseExifDate(null, null, zone))
        assertEquals(null, parseExifDate("0000:00:00 00:00:00", null, zone))
        assertEquals(null, parseExifDate("    :  :     :  :  ", null, zone))
        assertEquals(null, parseExifDate("garbage", null, zone))
    }
}
