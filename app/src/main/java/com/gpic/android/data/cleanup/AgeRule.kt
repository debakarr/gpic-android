package com.gpic.android.data.cleanup

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

enum class AgeUnit(val label: String) { DAYS("Days"), WEEKS("Weeks"), MONTHS("Months"), YEARS("Years") }

/** "Older than [amount] [unit]": calendar arithmetic, so 1 month before 31 March is 28/29 February. */
data class AgeRule(val amount: Int = 30, val unit: AgeUnit = AgeUnit.DAYS) {
    fun cutoffMs(nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Long {
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        val n = amount.coerceAtLeast(0).toLong()
        val cutoff = when (unit) {
            AgeUnit.DAYS -> now.minusDays(n)
            AgeUnit.WEEKS -> now.minusWeeks(n)
            AgeUnit.MONTHS -> now.minusMonths(n)
            AgeUnit.YEARS -> now.minusYears(n)
        }
        return cutoff.toInstant().toEpochMilli()
    }

    val description: String
        get() = "$amount ${if (amount == 1) unit.label.lowercase().dropLast(1) else unit.label.lowercase()}"

    companion object {
        const val MAX_AMOUNT = 9999
    }
}

/**
 * When a file was taken: the earlier of its EXIF capture time and its modified time. A copied or
 * restored file gets a fresh modified time, but nothing is captured after it was last written.
 * 0 = unknown; such files are never treated as old (they'd be deleted on a guess).
 */
fun takenMs(modifiedMs: Long, exifMs: Long?): Long = listOfNotNull(
    modifiedMs.takeIf { it > 0 }, exifMs?.takeIf { it > 0 },
).minOrNull() ?: 0L

fun isOlderThan(takenMs: Long, cutoffMs: Long): Boolean = takenMs in 1 until cutoffMs

/**
 * Whether a file's EXIF date must be read to decide it: only when its modified time alone doesn't
 * already put it before the cutoff (EXIF can only make it older).
 */
fun needsExif(modifiedMs: Long, cutoffMs: Long): Boolean = !isOlderThan(modifiedMs, cutoffMs)

/**
 * EXIF "yyyy:MM:dd HH:mm:ss" (camera local time) → epoch ms. Uses the EXIF offset ("+05:30") when the
 * camera recorded one, else [zone]. Null for blank/zeroed/garbled values.
 */
fun parseExifDate(dateTime: String?, offset: String?, zone: ZoneId = ZoneId.systemDefault()): Long? {
    val dt = dateTime?.trim()?.takeIf { it.length >= 19 && !it.startsWith("0000") } ?: return null
    return try {
        val local = java.time.LocalDateTime.parse(dt.substring(0, 19), java.time.format.DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"))
        val zoned = offset?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { local.atOffset(java.time.ZoneOffset.of(it)) }.getOrNull() }
            ?.toInstant() ?: local.atZone(zone).toInstant()
        zoned.toEpochMilli()
    } catch (_: Exception) {
        null
    }
}
