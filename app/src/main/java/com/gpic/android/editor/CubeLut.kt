package com.gpic.android.editor

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A 3D colour lookup table: [size]³ ARGB colours, indexed `[r][g][b]` (the order Media3's
 * `SingleColorLut.createFromCube` expects). Pure Kotlin so the parser is unit-tested on the JVM.
 */
class CubeLut(val title: String, val size: Int, val cube: Array<Array<IntArray>>) {

    companion object {
        /** Media3 uploads the LUT as one N × N² texture; 65 (the largest common size) is ~4 M pixels. */
        const val MAX_SIZE = 65

        /**
         * Parses an Adobe/Resolve `.cube` file (3D only). Data lines are "R G B" floats with red changing
         * fastest. DOMAIN_MIN/MAX rescale to 0..1. Throws [IllegalArgumentException] with a readable reason.
         */
        fun parse(text: String, fallbackTitle: String = "LUT"): CubeLut {
            var title = fallbackTitle
            var size = 0
            var min = floatArrayOf(0f, 0f, 0f)
            var max = floatArrayOf(1f, 1f, 1f)
            var values: FloatArray? = null
            var n = 0
            for (raw in text.lineSequence()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val parts = line.split(Regex("\\s+"))
                when (parts[0].uppercase()) {
                    "TITLE" -> title = line.substringAfter(' ').trim().trim('"').ifEmpty { title }
                    "LUT_1D_SIZE" -> throw IllegalArgumentException("This is a 1D LUT; only 3D .cube LUTs are supported")
                    "LUT_3D_SIZE" -> {
                        size = parts.getOrNull(1)?.toIntOrNull() ?: throw IllegalArgumentException("Bad LUT_3D_SIZE line")
                        require(size in 2..MAX_SIZE) { "LUT size $size isn't supported (2–$MAX_SIZE)" }
                        values = FloatArray(size * size * size * 3)
                    }
                    "DOMAIN_MIN" -> min = floats(parts, "DOMAIN_MIN")
                    "DOMAIN_MAX" -> max = floats(parts, "DOMAIN_MAX")
                    "LUT_3D_INPUT_RANGE" -> {
                        val lo = parts.getOrNull(1)?.toFloatOrNull() ?: 0f
                        val hi = parts.getOrNull(2)?.toFloatOrNull() ?: 1f
                        min = floatArrayOf(lo, lo, lo); max = floatArrayOf(hi, hi, hi)
                    }
                    else -> {
                        val first = parts[0].toFloatOrNull() ?: continue // other keywords: ignore
                        val v = values ?: throw IllegalArgumentException("Colour data before LUT_3D_SIZE")
                        require(parts.size >= 3) { "Bad colour line: $line" }
                        require(n < size * size * size) { "More colour lines than LUT_3D_SIZE³" }
                        v[n * 3] = first
                        v[n * 3 + 1] = parts[1].toFloatOrNull() ?: throw IllegalArgumentException("Bad colour line: $line")
                        v[n * 3 + 2] = parts[2].toFloatOrNull() ?: throw IllegalArgumentException("Bad colour line: $line")
                        n++
                    }
                }
            }
            val v = values ?: throw IllegalArgumentException("No LUT_3D_SIZE line: not a 3D .cube file")
            require(n == size * size * size) { "Expected ${size * size * size} colour lines, found $n" }
            val cube = Array(size) { Array(size) { IntArray(size) } }
            for (i in 0 until n) {
                val r = i % size
                val g = (i / size) % size
                val b = i / (size * size)
                cube[r][g][b] = argb(
                    norm(v[i * 3], min[0], max[0]),
                    norm(v[i * 3 + 1], min[1], max[1]),
                    norm(v[i * 3 + 2], min[2], max[2]),
                )
            }
            return CubeLut(title, size, cube)
        }

        /**
         * GPic's own "flat → vivid" look for log-style footage when no camera LUT is installed: a gentle
         * S-curve on luma plus a saturation lift. An approximation, not a colour-science conversion.
         */
        fun flatToVivid(size: Int = 33): CubeLut {
            val cube = Array(size) { Array(size) { IntArray(size) } }
            for (r in 0 until size) for (g in 0 until size) for (b in 0 until size) {
                val rgb = floatArrayOf(r / (size - 1f), g / (size - 1f), b / (size - 1f))
                val y = 0.2126f * rgb[0] + 0.7152f * rgb[1] + 0.0722f * rgb[2]
                val y2 = sCurve(y)
                val out = FloatArray(3) { c ->
                    // Saturation x1.45 around the new luma, then the contrast curve's lift.
                    (y2 + (rgb[c] - y) * 1.45f).coerceIn(0f, 1f)
                }
                cube[r][g][b] = argb(out[0], out[1], out[2])
            }
            return CubeLut("Flat → vivid (GPic, approximate)", size, cube)
        }

        /** Identity table; handy for tests. */
        fun identity(size: Int = 17): CubeLut {
            val cube = Array(size) { r -> Array(size) { g -> IntArray(size) { b -> argb(r / (size - 1f), g / (size - 1f), b / (size - 1f)) } } }
            return CubeLut("Identity", size, cube)
        }

        private fun sCurve(x: Float): Float {
            // Lift blacks out of the log floor, then a smoothstep-style contrast curve with a little gamma.
            val t = ((x - 0.06f) / 0.86f).coerceIn(0f, 1f)
            val s = t * t * (3 - 2 * t)
            return (0.65f * s + 0.35f * t).pow(1.08f)
        }

        private fun floats(parts: List<String>, what: String) = FloatArray(3) {
            parts.getOrNull(it + 1)?.toFloatOrNull() ?: throw IllegalArgumentException("Bad $what line")
        }

        private fun norm(x: Float, lo: Float, hi: Float) = if (hi > lo) ((x - lo) / (hi - lo)).coerceIn(0f, 1f) else x.coerceIn(0f, 1f)

        fun argb(r: Float, g: Float, b: Float): Int =
            (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)

        private fun channel(x: Float) = (x.coerceIn(0f, 1f) * 255f).roundToInt()
    }
}
