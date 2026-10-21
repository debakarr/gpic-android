package com.gpic.android.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.TextPaint
import java.io.File

/**
 * Generated frames: intro/end card backgrounds (a soft, darkened copy of the video's best frame, so the card
 * matches the footage) and the storyboard sheet sent to the Claude app. Text goes on top as editable text items.
 */
object Cards {
    /** [source] cover-cropped to [w]×[h], heavily blurred (down- and up-scaling) and darkened. */
    fun background(source: Bitmap, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val s = maxOf(w.toFloat() / source.width, h.toFloat() / source.height)
        val sw = (w / s).toInt().coerceAtMost(source.width)
        val sh = (h / s).toInt().coerceAtMost(source.height)
        val src = Rect((source.width - sw) / 2, (source.height - sh) / 2, (source.width + sw) / 2, (source.height + sh) / 2)
        // Blur: draw tiny, then scale back up with filtering, twice.
        val tiny = Bitmap.createBitmap(maxOf(4, w / 24), maxOf(4, h / 24), Bitmap.Config.ARGB_8888)
        Canvas(tiny).drawBitmap(source, src, Rect(0, 0, tiny.width, tiny.height), Paint(Paint.FILTER_BITMAP_FLAG))
        val mid = Bitmap.createScaledBitmap(tiny, maxOf(8, w / 8), maxOf(8, h / 8), true)
        c.drawBitmap(mid, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        tiny.recycle(); mid.recycle()
        c.drawColor(Color.argb(110, 0, 0, 0))
        return out
    }

    fun saveJpeg(bmp: Bitmap, file: File, quality: Int = 90) {
        file.parentFile?.mkdirs()
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
    }

    /** A grid of labelled frames ("C2 0:03") for the Claude app, at most [cols] across; cells of [aspect] (w/h). */
    fun storyboard(frames: List<Pair<String, Bitmap>>, cols: Int = 4, cell: Int = 256, aspect: Float = 16f / 9f): Bitmap {
        val rows = (frames.size + cols - 1) / cols
        val ch = (cell / aspect.coerceIn(0.4f, 3f)).toInt()
        val out = Bitmap.createBitmap(cols * cell, maxOf(1, rows) * (ch + 28), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.BLACK)
        val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 20f }
        for ((i, f) in frames.withIndex()) {
            val x = (i % cols) * cell
            val y = (i / cols) * (ch + 28)
            val b = f.second
            val s = minOf(cell.toFloat() / b.width, ch.toFloat() / b.height)
            val dw = b.width * s; val dh = b.height * s
            c.drawBitmap(b, null, RectF(x + (cell - dw) / 2, y + (ch - dh) / 2, x + (cell + dw) / 2, y + (ch + dh) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
            c.drawText(f.first, x + 6f, y + ch + 22f, label)
        }
        return out
    }

    /**
     * One frame of the edit as it exports, for Claude to check its work: [src] (the clip's frame, upright) turned
     * and mirrored like the part, coloured with [look], fitted to [w]×[h] by [canvas], with the texts visible at
     * output time [outMs] drawn on top (animation state included). Transitions and motion aren't drawn.
     */
    fun resultFrame(src: Bitmap, part: Segment, look: Look, camera: CubeLut?, canvas: com.gpic.android.editor.Canvas, w: Int, h: Int, texts: List<TextItem>, outMs: Long): Bitmap {
        var pic = src
        if (part.rotation != 0 || part.mirror) {
            val m = android.graphics.Matrix().apply { postRotate(part.rotation.toFloat()); if (part.mirror) postScale(-1f, 1f) }
            pic = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        }
        if (!look.colourNeutral) {
            pic = pic.copy(Bitmap.Config.ARGB_8888, true)
            val px = IntArray(pic.width * pic.height)
            pic.getPixels(px, 0, pic.width, 0, 0, pic.width, pic.height)
            LookMath.applyToPixels(look, camera, px)
            pic.setPixels(px, 0, pic.width, 0, 0, pic.width, pic.height)
        }
        val out = when (canvas) {
            com.gpic.android.editor.Canvas.BLUR -> background(pic, w, h)
            else -> Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        }
        val c = Canvas(out)
        val s = if (canvas == com.gpic.android.editor.Canvas.CROP) maxOf(w.toFloat() / pic.width, h.toFloat() / pic.height)
        else minOf(w.toFloat() / pic.width, h.toFloat() / pic.height)
        val dw = pic.width * s; val dh = pic.height * s
        c.drawBitmap(pic, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        if (look.vignette > 0f) {
            val shade = Paint().apply {
                shader = android.graphics.RadialGradient(w / 2f, h / 2f, maxOf(w, h) * 0.75f,
                    intArrayOf(Color.TRANSPARENT, Color.argb((look.vignette * 200).toInt(), 0, 0, 0)), floatArrayOf(0.45f, 1f), android.graphics.Shader.TileMode.CLAMP)
            }
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), shade)
        }
        for (t in texts) {
            val st = TextAnimMath.at(t, outMs)
            if (!st.visible || st.alpha <= 0.02f) continue
            val d = TextRender.draw(t, w, h, st.chars)
            val bw = d.bitmap.width * st.scale; val bh = d.bitmap.height * st.scale
            val cy = d.cy + st.dy * h
            c.drawBitmap(d.bitmap, null, RectF(d.cx - bw / 2, cy - bh / 2, d.cx + bw / 2, cy + bh / 2),
                Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (st.alpha * 255).toInt() })
            d.bitmap.recycle()
        }
        return out
    }
}
