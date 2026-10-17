package com.gpic.android.editor

import kotlin.math.pow

/**
 * How a part looks: a camera LUT (e.g. DJI D-Log M → Rec.709), then a filter preset at some strength, then
 * the Adjust sliders. All three are baked into one 3D LUT ([LookMath.bake]), so the GPU does a single lookup
 * per pixel however many sliders are moved. Vignette is the one spatial setting (an overlay, see CompositionBuilder).
 * Slider values are -1..1 (0 = unchanged) unless noted.
 */
data class Look(
    /** Id from [LutStore], or null. */
    val lutId: String? = null,
    val filter: Filter? = null,
    /** 0..1. */
    val filterAmount: Float = 1f,
    val exposure: Float = 0f,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val vibrance: Float = 0f,
    /** Warm (+) / cool (-). */
    val temperature: Float = 0f,
    /** Magenta (+) / green (-). */
    val tint: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    /** 0..1: lifts the blacks (matte look). */
    val fade: Float = 0f,
    /** 0..1: darkens the corners. */
    val vignette: Float = 0f,
) {
    /** Nothing to bake (a vignette alone is an overlay, not a colour change). */
    val colourNeutral: Boolean get() = lutId == null && (filter == null || filterAmount <= 0f) && adjustNeutral
    val adjustNeutral: Boolean get() =
        listOf(exposure, brightness, contrast, saturation, vibrance, temperature, tint, highlights, shadows, fade).all { it == 0f }

    fun sanitized(lutIds: Set<String>) = copy(
        lutId = lutId?.takeIf { it in lutIds },
        filterAmount = filterAmount.clampOr(0f, 1f, 1f),
        exposure = exposure.clampOr(-1f, 1f), brightness = brightness.clampOr(-1f, 1f),
        contrast = contrast.clampOr(-1f, 1f), saturation = saturation.clampOr(-1f, 1f),
        vibrance = vibrance.clampOr(-1f, 1f), temperature = temperature.clampOr(-1f, 1f),
        tint = tint.clampOr(-1f, 1f), highlights = highlights.clampOr(-1f, 1f),
        shadows = shadows.clampOr(-1f, 1f), fade = fade.clampOr(0f, 1f), vignette = vignette.clampOr(0f, 1f),
    )

    companion object {
        /** The Adjust sliders, for the UI and Claude: name, getter, setter, range. */
        val ADJUSTMENTS: List<Adjustment> = listOf(
            Adjustment("exposure", "Exposure", { it.exposure }, { l, v -> l.copy(exposure = v) }),
            Adjustment("brightness", "Brightness", { it.brightness }, { l, v -> l.copy(brightness = v) }),
            Adjustment("contrast", "Contrast", { it.contrast }, { l, v -> l.copy(contrast = v) }),
            Adjustment("saturation", "Saturation", { it.saturation }, { l, v -> l.copy(saturation = v) }),
            Adjustment("vibrance", "Vibrance", { it.vibrance }, { l, v -> l.copy(vibrance = v) }),
            Adjustment("temperature", "Temp", { it.temperature }, { l, v -> l.copy(temperature = v) }),
            Adjustment("tint", "Tint", { it.tint }, { l, v -> l.copy(tint = v) }),
            Adjustment("highlights", "Highlights", { it.highlights }, { l, v -> l.copy(highlights = v) }),
            Adjustment("shadows", "Shadows", { it.shadows }, { l, v -> l.copy(shadows = v) }),
            Adjustment("fade", "Fade", { it.fade }, { l, v -> l.copy(fade = v) }, min = 0f),
            Adjustment("vignette", "Vignette", { it.vignette }, { l, v -> l.copy(vignette = v) }, min = 0f),
        )
    }
}

class Adjustment(
    val key: String,
    val label: String,
    val get: (Look) -> Float,
    val set: (Look, Float) -> Look,
    val min: Float = -1f,
)

private fun Float.clampOr(lo: Float, hi: Float, default: Float = 0f) = if (isFinite()) coerceIn(lo, hi) else default

/** Filter presets: GPic's own colour functions (no bundled third-party looks). */
enum class Filter(val label: String) {
    VIVID("Vivid"), PUNCH("Punch"), WARM("Warm"), COOL("Cool"), GOLDEN("Golden"), TEAL_ORANGE("Teal & orange"),
    FILM("Film"), FADED("Faded"), MUTED("Muted"), FOREST("Forest"), OCEAN("Ocean"), RETRO("Retro"),
    MONO("Mono"), NOIR("Noir"), SEPIA("Sepia");
}

/** The colour maths behind [Look]: pure, so it's unit-tested and shared by the preview thumbnails and the GPU LUT. */
object LookMath {
    const val BAKE_SIZE = 33

    private fun luma(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b
    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    /** Applies [f] to [c] (r, g, b in 0..1) in place. */
    fun filter(f: Filter, c: FloatArray) {
        var r = c[0]; var g = c[1]; var b = c[2]
        val l = luma(r, g, b)
        fun sat(k: Float) { val y = luma(r, g, b); r = y + (r - y) * k; g = y + (g - y) * k; b = y + (b - y) * k }
        fun con(k: Float, pivot: Float = 0.5f) { r = (r - pivot) * k + pivot; g = (g - pivot) * k + pivot; b = (b - pivot) * k + pivot }
        fun lift(k: Float) { r = r * (1 - k) + k; g = g * (1 - k) + k; b = b * (1 - k) + k }
        when (f) {
            Filter.VIVID -> { sat(1.3f); con(1.08f) }
            Filter.PUNCH -> { con(1.25f); sat(1.15f) }
            Filter.WARM -> { r = r * 1.07f + 0.015f; g *= 1.01f; b *= 0.9f }
            Filter.COOL -> { r *= 0.92f; g *= 1.0f; b = b * 1.07f + 0.015f }
            Filter.GOLDEN -> { r += 0.09f * l; g += 0.045f * l; b -= 0.07f * l; sat(1.05f) }
            Filter.TEAL_ORANGE -> {
                val sh = 1 - smooth(0.1f, 0.6f, l); val hi = smooth(0.4f, 0.9f, l)
                r += -0.07f * sh + 0.08f * hi; g += 0.02f * sh + 0.02f * hi; b += 0.08f * sh - 0.08f * hi
                con(1.06f)
            }
            Filter.FILM -> { con(1.1f); lift(0.05f); sat(0.85f); r += 0.015f; b -= 0.01f }
            Filter.FADED -> { con(0.85f); lift(0.1f); sat(0.85f) }
            Filter.MUTED -> { sat(0.6f); con(0.95f) }
            Filter.FOREST -> { g = g * 1.06f + 0.01f; r *= 0.97f; b *= 0.94f; sat(1.1f) }
            Filter.OCEAN -> { b = b * 1.08f + 0.02f; g = g * 1.02f; r *= 0.9f; sat(1.08f) }
            Filter.RETRO -> {
                r = (r - 0.5f) * 1.15f + 0.53f; b = (b - 0.5f) * 0.85f + 0.45f
                g += 0.03f * (1 - smooth(0f, 0.5f, l))
            }
            Filter.MONO -> { r = l; g = l; b = l }
            Filter.NOIR -> { val y = ((l - 0.5f) * 1.45f + 0.47f); r = y; g = y; b = y }
            Filter.SEPIA -> {
                val y = l
                r = y * 1.07f + 0.06f; g = y * 0.92f + 0.03f; b = y * 0.72f
            }
        }
        c[0] = r.coerceIn(0f, 1f); c[1] = g.coerceIn(0f, 1f); c[2] = b.coerceIn(0f, 1f)
    }

    /** The Adjust sliders, in a fixed order (exposure first, fade last). */
    fun adjust(look: Look, c: FloatArray) {
        var r = c[0]; var g = c[1]; var b = c[2]
        if (look.exposure != 0f) { val k = 2f.pow(look.exposure * 1.5f); r *= k; g *= k; b *= k }
        if (look.brightness != 0f) { val d = look.brightness * 0.25f; r += d; g += d; b += d }
        if (look.contrast != 0f) { val k = 1 + look.contrast * 0.6f; r = (r - 0.5f) * k + 0.5f; g = (g - 0.5f) * k + 0.5f; b = (b - 0.5f) * k + 0.5f }
        if (look.highlights != 0f || look.shadows != 0f) {
            val l = luma(r, g, b).coerceIn(0f, 1f)
            val d = look.highlights * 0.25f * smooth(0.45f, 1f, l) + look.shadows * 0.25f * (1 - smooth(0f, 0.55f, l))
            r += d; g += d; b += d
        }
        if (look.temperature != 0f) { r += look.temperature * 0.08f; b -= look.temperature * 0.08f }
        if (look.tint != 0f) { g -= look.tint * 0.06f; r += look.tint * 0.02f; b += look.tint * 0.02f }
        if (look.saturation != 0f || look.vibrance != 0f) {
            val y = luma(r, g, b)
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
            val s = if (mx > 1e-4f) ((mx - mn) / mx).coerceIn(0f, 1f) else 0f
            val k = (1 + look.saturation) * (1 + look.vibrance * (1 - s))
            r = y + (r - y) * k; g = y + (g - y) * k; b = y + (b - y) * k
        }
        if (look.fade != 0f) { val k = look.fade * 0.15f; r = r * (1 - k) + k; g = g * (1 - k) + k; b = b * (1 - k) + k }
        c[0] = r.coerceIn(0f, 1f); c[1] = g.coerceIn(0f, 1f); c[2] = b.coerceIn(0f, 1f)
    }

    /** Camera LUT → filter (mixed by its amount) → Adjust, on one colour in place. */
    fun apply(look: Look, camera: CubeLut?, c: FloatArray) {
        if (camera != null) sample(camera, c)
        val f = look.filter
        if (f != null && look.filterAmount > 0f) {
            val r0 = c[0]; val g0 = c[1]; val b0 = c[2]
            filter(f, c)
            val a = look.filterAmount
            c[0] = r0 + (c[0] - r0) * a; c[1] = g0 + (c[1] - g0) * a; c[2] = b0 + (c[2] - b0) * a
        }
        adjust(look, c)
    }

    /** The whole look as one `[r][g][b]` ARGB cube for Media3's SingleColorLut. */
    fun bake(look: Look, camera: CubeLut?, size: Int = BAKE_SIZE): Array<Array<IntArray>> {
        val c = FloatArray(3)
        return Array(size) { r ->
            Array(size) { g ->
                IntArray(size) { b ->
                    c[0] = r / (size - 1f); c[1] = g / (size - 1f); c[2] = b / (size - 1f)
                    apply(look, camera, c)
                    CubeLut.argb(c[0], c[1], c[2])
                }
            }
        }
    }

    /** Trilinear lookup in [lut], in place. */
    fun sample(lut: CubeLut, c: FloatArray) {
        val n = lut.size - 1
        val fr = c[0].coerceIn(0f, 1f) * n; val fg = c[1].coerceIn(0f, 1f) * n; val fb = c[2].coerceIn(0f, 1f) * n
        val r0 = fr.toInt().coerceAtMost(n - 1); val g0 = fg.toInt().coerceAtMost(n - 1); val b0 = fb.toInt().coerceAtMost(n - 1)
        val dr = fr - r0; val dg = fg - g0; val db = fb - b0
        for (ch in 0..2) {
            val shift = 16 - ch * 8
            fun v(r: Int, g: Int, b: Int) = ((lut.cube[r][g][b] shr shift) and 0xFF) / 255f
            val c00 = v(r0, g0, b0) * (1 - dr) + v(r0 + 1, g0, b0) * dr
            val c10 = v(r0, g0 + 1, b0) * (1 - dr) + v(r0 + 1, g0 + 1, b0) * dr
            val c01 = v(r0, g0, b0 + 1) * (1 - dr) + v(r0 + 1, g0, b0 + 1) * dr
            val c11 = v(r0, g0 + 1, b0 + 1) * (1 - dr) + v(r0 + 1, g0 + 1, b0 + 1) * dr
            val c0 = c00 * (1 - dg) + c10 * dg
            val c1 = c01 * (1 - dg) + c11 * dg
            c[ch] = c0 * (1 - db) + c1 * db
        }
    }

    /** Applies the look to ARGB pixels (filter thumbnails); alpha is kept. */
    fun applyToPixels(look: Look, camera: CubeLut?, px: IntArray) {
        val c = FloatArray(3)
        for (i in px.indices) {
            val p = px[i]
            c[0] = (p shr 16 and 0xFF) / 255f; c[1] = (p shr 8 and 0xFF) / 255f; c[2] = (p and 0xFF) / 255f
            apply(look, camera, c)
            px[i] = (p and -0x1000000) or (CubeLut.argb(c[0], c[1], c[2]) and 0xFFFFFF)
        }
    }
}
