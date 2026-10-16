package com.gpic.android.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CubeLutTest {
    private fun cube(size: Int, color: (r: Int, g: Int, b: Int) -> Triple<Float, Float, Float>, header: String = ""): String {
        val sb = StringBuilder("# comment\nTITLE \"Test\"\n$header LUT_3D_SIZE $size\n")
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) { // red changes fastest
            val (x, y, z) = color(r, g, b)
            sb.append("$x $y $z\n")
        }
        return sb.toString()
    }

    @Test fun indexesRedGreenBlueWithRedFastest() {
        // Output = (r, 0, 0): only the red input survives, so the [r][g][b] order is visible.
        val lut = CubeLut.parse(cube(3, { r, _, _ -> Triple(r / 2f, 0f, 0f) }))
        assertEquals("Test", lut.title)
        assertEquals(3, lut.size)
        assertEquals(CubeLut.argb(1f, 0f, 0f), lut.cube[2][0][0])
        assertEquals(CubeLut.argb(0f, 0f, 0f), lut.cube[0][2][2])
        assertEquals(CubeLut.argb(0.5f, 0f, 0f), lut.cube[1][2][0])
    }

    @Test fun domainRescales() {
        val lut = CubeLut.parse(cube(2, { r, g, b -> Triple(r * 2f, g * 2f, b * 2f) }, header = "DOMAIN_MIN 0 0 0\nDOMAIN_MAX 2 2 2\n"))
        assertEquals(CubeLut.argb(1f, 1f, 1f), lut.cube[1][1][1])
        assertEquals(0xFF000000.toInt(), lut.cube[0][0][0])
    }

    @Test fun rejectsBrokenFiles() {
        fun bad(text: String, hint: String) = try {
            CubeLut.parse(text); fail("accepted: $hint")
        } catch (e: IllegalArgumentException) {
            assertTrue("${e.message} should mention $hint", e.message!!.contains(hint, ignoreCase = true))
        }
        bad("LUT_1D_SIZE 1024\n0 0 0\n", "1D")
        bad("0 0 0\n", "before LUT_3D_SIZE")
        bad("LUT_3D_SIZE 2\n0 0 0\n1 1 1\n", "Expected 8")
        bad("hello\n", "No LUT_3D_SIZE")
        bad("LUT_3D_SIZE 200\n", "size")
    }

    @Test fun builtInLookKeepsBlackAndWhiteAndAddsSaturation() {
        val lut = CubeLut.flatToVivid(17)
        val black = lut.cube[0][0][0]
        val white = lut.cube[16][16][16]
        assertTrue((black and 0xFF) < 10)
        assertTrue((white and 0xFF) > 245)
        // A muted red gets more saturated: red channel up relative to green.
        val c = lut.cube[10][6][6]
        val r = c shr 16 and 0xFF
        val g = c shr 8 and 0xFF
        assertTrue("r=$r g=$g", r - g > (10 - 6) * 255 / 16)
    }
}
