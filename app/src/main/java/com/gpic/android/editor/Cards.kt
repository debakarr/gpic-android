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

    /** A grid of labelled frames ("C2 0:03") for the Claude app, at most [cols] across. */
    fun storyboard(frames: List<Pair<String, Bitmap>>, cols: Int = 4, cell: Int = 256): Bitmap {
        val rows = (frames.size + cols - 1) / cols
        val ch = cell * 9 / 16
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
}
