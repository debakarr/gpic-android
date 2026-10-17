package com.gpic.android.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LookTest {
    private fun rgb(i: Int) = floatArrayOf((i shr 16 and 0xFF) / 255f, (i shr 8 and 0xFF) / 255f, (i and 0xFF) / 255f)

    @Test fun neutralLookBakesToIdentity() {
        val cube = LookMath.bake(Look(), null, size = 9)
        for (r in 0 until 9) for (g in 0 until 9) for (b in 0 until 9) {
            assertEquals(CubeLut.argb(r / 8f, g / 8f, b / 8f), cube[r][g][b])
        }
        assertTrue(Look().colourNeutral)
        assertTrue(Look(vignette = 0.5f).colourNeutral) // vignette is an overlay, not colour
    }

    @Test fun filterAtZeroStrengthChangesNothingAndMonoIsGrey() {
        val c = floatArrayOf(0.8f, 0.3f, 0.1f)
        LookMath.apply(Look(filter = Filter.PUNCH, filterAmount = 0f), null, c)
        assertEquals(0.8f, c[0], 1e-4f); assertEquals(0.3f, c[1], 1e-4f); assertEquals(0.1f, c[2], 1e-4f)
        val m = floatArrayOf(0.8f, 0.3f, 0.1f)
        LookMath.apply(Look(filter = Filter.MONO), null, m)
        assertEquals(m[0], m[1], 1e-4f); assertEquals(m[1], m[2], 1e-4f)
    }

    @Test fun everyFilterAndSliderStaysInRange() {
        val samples = listOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f), floatArrayOf(0.9f, 0.1f, 0.4f), floatArrayOf(0.05f, 0.5f, 0.95f))
        val looks = Filter.entries.map { Look(filter = it) } + Look.ADJUSTMENTS.flatMap { a -> listOf(a.set(Look(), 1f), a.set(Look(), a.min)) }
        for (l in looks) for (s in samples) {
            val c = s.copyOf()
            LookMath.apply(l, null, c)
            assertTrue("$l → ${c.toList()}", c.all { it in 0f..1f })
        }
    }

    @Test fun slidersMoveTheRightWay() {
        fun after(l: Look, c: FloatArray) = c.copyOf().also { LookMath.apply(l, null, it) }
        val mid = floatArrayOf(0.5f, 0.5f, 0.5f)
        assertTrue(after(Look(exposure = 0.5f), mid)[1] > 0.5f)
        val warm = after(Look(temperature = 1f), mid)
        assertTrue(warm[0] > warm[2])
        val sat = after(Look(saturation = 1f), floatArrayOf(0.6f, 0.4f, 0.4f))
        assertTrue(sat[0] - sat[1] > 0.2f)
        assertTrue(after(Look(fade = 1f), floatArrayOf(0f, 0f, 0f))[0] > 0.1f)
    }

    @Test fun cameraLutIsSampledTrilinearly() {
        // A LUT that swaps red and blue.
        val n = 5
        val swap = CubeLut("swap", n, Array(n) { r -> Array(n) { g -> IntArray(n) { b -> CubeLut.argb(b / (n - 1f), g / (n - 1f), r / (n - 1f)) } } })
        val c = floatArrayOf(0.9f, 0.5f, 0.1f)
        LookMath.sample(swap, c)
        assertTrue(abs(c[0] - 0.1f) < 0.01f && abs(c[2] - 0.9f) < 0.01f && abs(c[1] - 0.5f) < 0.01f)
        val baked = LookMath.bake(Look(lutId = "x"), swap, size = 5)
        val top = rgb(baked[4][0][0])
        assertEquals(0f, top[0], 0.01f); assertEquals(1f, top[2], 0.01f)
    }
}
