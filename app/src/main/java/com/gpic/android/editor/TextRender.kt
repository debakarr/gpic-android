package com.gpic.android.editor

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Draws a [TextItem] into a bitmap sized for a [frameW] × [frameH] frame. The export (Media3 overlay) and the
 * editor's preview (Compose) draw the same bitmap, so text looks and sits the same in both.
 */
object TextRender {
    class Drawn(val bitmap: Bitmap, /** Where the bitmap's centre goes, in frame pixels. */ val cx: Float, val cy: Float)

    fun typeface(font: TextFont): Typeface = Typeface.create(font.family, if (font.bold) Typeface.BOLD else Typeface.NORMAL)

    /** [chars] < text length draws only the first [chars] characters (typewriter), keeping the full layout. */
    fun draw(item: TextItem, frameW: Int, frameH: Int, chars: Int = Int.MAX_VALUE): Drawn {
        val short = min(frameW, frameH).toFloat()
        val px = (item.size * short).coerceAtLeast(8f)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = px
            typeface = typeface(item.font)
            color = item.color
        }
        val maxWidth = (frameW * 0.9f).toInt().coerceAtLeast(1)
        val full = item.text
        val shown = if (chars >= full.length) full else full.substring(0, chars.coerceAtLeast(0))
        // Lay out the full text so a typewriter reveal doesn't reflow; draw only what's revealed.
        val layoutFull = layout(full, paint, maxWidth)
        val pad = (px * 0.35f).let { if (item.style == TextStyle.BOX) it * 1.4f else it }
        val w = ceil(layoutFull.width() + pad * 2).toInt().coerceAtLeast(2)
        val h = ceil(layoutFull.height + pad * 2).toInt().coerceAtLeast(2)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (item.style == TextStyle.BOX) {
            val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (luma(item.color) > 0.6f) 0xCC000000.toInt() else 0xE6FFFFFF.toInt() }
            c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), px * 0.3f, px * 0.3f, box)
        }
        val shownLayout = layout(shown, paint, maxWidth)
        c.save()
        c.translate(pad + (layoutFull.width() - layoutFull.width()) / 2f, pad)
        when (item.style) {
            TextStyle.OUTLINE -> {
                val stroke = TextPaint(paint).apply {
                    style = Paint.Style.STROKE; strokeWidth = max(2f, px * 0.12f); strokeJoin = Paint.Join.ROUND
                    color = if (luma(item.color) > 0.5f) Color.BLACK else Color.WHITE
                }
                layout(shown, stroke, maxWidth).draw(c)
            }
            TextStyle.SHADOW -> {
                val sh = TextPaint(paint).apply { color = 0xAA000000.toInt(); maskFilter = BlurMaskFilter(px * 0.12f, BlurMaskFilter.Blur.NORMAL) }
                c.save(); c.translate(px * 0.06f, px * 0.08f); layout(shown, sh, maxWidth).draw(c); c.restore()
            }
            TextStyle.NEON -> {
                val glow = TextPaint(paint).apply { maskFilter = BlurMaskFilter(px * 0.3f, BlurMaskFilter.Blur.NORMAL) }
                layout(shown, glow, maxWidth).draw(c)
                layout(shown, glow, maxWidth).draw(c)
            }
            else -> {}
        }
        if (item.style == TextStyle.NEON) paint.color = blendToWhite(item.color, 0.55f)
        shownLayout.draw(c)
        c.restore()
        return Drawn(bmp, item.x * frameW, item.y * frameH)
    }

    @Suppress("DEPRECATION")
    private fun layout(text: String, paint: TextPaint, maxWidth: Int): StaticLayout {
        val natural = text.lines().maxOfOrNull { ceil(paint.measureText(it)).toInt() } ?: 1
        val width = natural.coerceIn(1, maxWidth)
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()
    }

    private fun StaticLayout.width(): Float = (0 until lineCount).maxOfOrNull { getLineWidth(it) }?.coerceAtLeast(1f) ?: 1f

    private fun luma(c: Int) = (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f

    private fun blendToWhite(c: Int, k: Float) = Color.rgb(
        (Color.red(c) + (255 - Color.red(c)) * k).toInt(), (Color.green(c) + (255 - Color.green(c)) * k).toInt(),
        (Color.blue(c) + (255 - Color.blue(c)) * k).toInt(),
    )
}

/** How a text looks at a moment: shared by the export overlay and the preview, so animations match. Pure. */
object TextAnimMath {
    const val ANIM_MS = 450L

    class State(val visible: Boolean, val alpha: Float, val scale: Float, /** Upward shift, fraction of frame height. */ val dy: Float, val chars: Int)

    fun at(item: TextItem, tMs: Long): State {
        if (tMs < item.startMs || tMs > item.endMs) return State(false, 0f, 1f, 0f, 0)
        val len = item.endMs - item.startMs
        val anim = min(ANIM_MS, len / 3)
        val inP = if (anim > 0) ((tMs - item.startMs).toFloat() / anim).coerceIn(0f, 1f) else 1f
        val outP = if (anim > 0) ((item.endMs - tMs).toFloat() / anim).coerceIn(0f, 1f) else 1f
        var alpha = 1f
        var scale = 1f
        var dy = 0f
        var chars = item.text.length
        fun applyAnim(a: TextAnim, p: Float) {
            val e = 1 - (1 - p) * (1 - p) // ease out
            when (a) {
                TextAnim.NONE -> {}
                TextAnim.FADE -> alpha *= p
                TextAnim.POP -> { scale *= if (p < 1f) 0.5f + 0.6f * e - 0.1f * e * e else 1f; alpha *= min(1f, p * 2) }
                TextAnim.SLIDE_UP -> { dy += (1 - e) * -0.06f; alpha *= p }
                TextAnim.TYPEWRITER -> chars = min(chars, (item.text.length * p).toInt())
            }
        }
        // Typewriter needs longer than the usual animation: a word per ~0.12 s, at most half the text's time.
        if (item.animIn == TextAnim.TYPEWRITER) {
            val tw = min(len / 2, item.text.length * 45L).coerceAtLeast(1)
            chars = (item.text.length * ((tMs - item.startMs).toFloat() / tw).coerceIn(0f, 1f)).toInt()
        } else applyAnim(item.animIn, inP)
        if (item.animOut != TextAnim.TYPEWRITER) applyAnim(item.animOut, outP)
        return State(true, alpha.coerceIn(0f, 1f), scale, dy, chars)
    }
}
