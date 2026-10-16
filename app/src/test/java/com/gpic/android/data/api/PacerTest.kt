package com.gpic.android.data.api

import org.junit.Assert.assertEquals
import org.junit.Test

class PacerTest {
    @Test fun spacesCallsAtTheRate() {
        var t = 1_000L
        val p = Pacer(initialPerSec = 4.0, minPerSec = 1.0, maxPerSec = 8.0, now = { t })
        assertEquals(0, p.reserve())
        assertEquals(250, p.reserve())
        assertEquals(500, p.reserve())
        t += 2_000 // idle: no debt carried over
        assertEquals(0, p.reserve())
    }

    @Test fun halvesOnLimitAndCreepsBack() {
        val p = Pacer(initialPerSec = 8.0, minPerSec = 1.0, maxPerSec = 9.0, now = { 0L })
        p.onRateLimited(); assertEquals(4.0, p.perSec, 0.0)
        p.onRateLimited(); p.onRateLimited(); p.onRateLimited(); assertEquals(1.0, p.perSec, 0.0)
        repeat(60) { p.onSuccess() }
        assertEquals(1.5, p.perSec, 0.0)
        repeat(60 * 40) { p.onSuccess() }
        assertEquals(9.0, p.perSec, 0.0)
    }
}
