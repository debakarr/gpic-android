package com.gpic.android.ui.screens.editor

import android.graphics.Bitmap
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpic.android.editor.SpeedCurve
import com.gpic.android.editor.Timeline
import com.gpic.android.editor.Transition
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Editor colours (the editor is always dark, like CapCut and LightCut). */
object EditorColors {
    val Background = Color(0xFF0E0F12)
    val Panel = Color(0xFF17191E)
    val Lane = Color(0xFF1F2228)
    val Accent = Color(0xFF5CE1E6)
    val Text = Color(0xFF8E7CFF)
    val Sticker = Color(0xFFFFB74D)
    val Music = Color(0xFF3DDC84)
    val Muted = Color(0xFF9AA0A6)
}

/**
 * The timeline: time runs left to right under a fixed centre playhead. Drag to scrub (with fling), pinch to
 * zoom, tap a clip/text/music to select it; a selected clip has trim handles; the square on each cut opens its
 * transition.
 */
@Composable
fun EditorTimeline(
    state: EditorUiState,
    thumbs: Map<Int, List<Bitmap?>>,
    thumbStep: (Int) -> Long,
    beatMarks: List<Long>,
    posMs: Long,
    onScrub: (Long) -> Unit,
    onSelect: (Selection) -> Unit,
    onTrim: (id: Long, startMs: Long?, endMs: Long?) -> Unit,
    onTransition: (partId: Long) -> Unit,
    onMoveText: (id: Long, startMs: Long, endMs: Long) -> Unit,
    onAddClip: () -> Unit,
    onAddText: () -> Unit,
    onAddMusic: () -> Unit,
    onToggleSound: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val slop = LocalViewConfiguration.current.touchSlop
    var widthPx by remember { mutableIntStateOf(1) }
    // Pixels per millisecond: starts at ~60 dp a second.
    var pxPerMs by remember { mutableFloatStateOf(with(density) { 60.dp.toPx() } / 1000f) }
    val total = state.outputMs.coerceAtLeast(1)
    val half = widthPx / 2f
    val scope = rememberCoroutineScope()
    var fling by remember { mutableStateOf<Job?>(null) }
    val currentPos by rememberUpdatedState(posMs)
    val scrub by rememberUpdatedState(onScrub)
    val parts = state.parts
    val starts = remember(parts) { Timeline.starts(parts) }
    fun xOf(ms: Long) = half + (ms - posMs) * pxPerMs

    Column(
        modifier
            .background(EditorColors.Background)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .pointerInput(total) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fling?.cancel()
                    val vt = VelocityTracker()
                    var dragged = 0f
                    var dragging = false
                    var zooming = false
                    var pos = currentPos.toFloat()
                    vt.addPosition(down.uptimeMillis, down.position)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            zooming = true
                            val z = ev.calculateZoom()
                            if (z != 1f) pxPerMs = (pxPerMs * z).coerceIn(MIN_PX_PER_MS * density.density, MAX_PX_PER_MS * density.density)
                            ev.changes.forEach { it.consume() }
                            continue
                        }
                        val ch = pressed.first()
                        if (zooming || ch.isConsumed) continue
                        val dx = ch.positionChange().x
                        dragged += dx
                        if (!dragging && abs(dragged) > slop) dragging = true
                        if (dragging) {
                            pos = (pos - dx / pxPerMs).coerceIn(0f, total.toFloat())
                            scrub(pos.toLong())
                            vt.addPosition(ch.uptimeMillis, ch.position)
                            ch.consume()
                        }
                    }
                    if (dragging && !zooming) {
                        val v = -vt.calculateVelocity().x / pxPerMs // ms of timeline per second
                        if (abs(v) > 200) fling = scope.launch {
                            var last = 0f
                            AnimationState(0f, v).animateDecay(exponentialDecay(frictionMultiplier = 2f)) {
                                val d = value - last
                                last = value
                                pos = (pos + d).coerceIn(0f, total.toFloat())
                                scrub(pos.toLong())
                            }
                        }
                    }
                }
            },
    ) {
        Ruler(posMs, pxPerMs, half, total)
        // Main track
        Box(Modifier.fillMaxWidth().height(58.dp)) {
            parts.forEachIndexed { i, p ->
                val x = xOf(starts[i])
                val w = p.outputMs * pxPerMs
                if (x + w < -50 || x > widthPx + 50) return@forEachIndexed
                val clip = state.clips.getOrNull(p.clip)
                val selected = (state.selection as? Selection.Part)?.id == p.id
                Box(
                    Modifier
                        .offset { IntOffset(x.roundToInt(), 0) }
                        // Items can be far wider than the screen: measure them unbounded.
                        .wrapContentWidth(Alignment.Start, unbounded = true)
                        .width(with(density) { (w - 2).coerceAtLeast(2f).toDp() })
                        .fillMaxHeight()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(EditorColors.Lane)
                        .then(if (selected) Modifier.border(2.dp, Color.White, RoundedCornerShape(6.dp)) else Modifier)
                        .clickable { onSelect(if (selected) Selection.None else Selection.Part(p.id)) },
                ) {
                    val list = thumbs[p.clip]
                    val step = clip?.let { thumbStep(p.clip) } ?: 1000L
                    Canvas(Modifier.fillMaxSize()) {
                        val tileW = size.height * 16f / 9f
                        var tx = 0f
                        clipRect {
                            while (tx < size.width) {
                                val outMs = ((tx + tileW / 2) / pxPerMs).toLong()
                                val clipMs = Timeline.clipTime(p, outMs)
                                val bmp = list?.getOrNull((clipMs / step).toInt().coerceIn(0, (list.size - 1).coerceAtLeast(0)))
                                if (bmp != null && !bmp.isRecycled) {
                                    val img = bmp.asImageBitmap()
                                    // Centre-crop the frame into the tile.
                                    val s = maxOf(tileW / img.width, size.height / img.height)
                                    val sw = (tileW / s).toInt().coerceAtMost(img.width)
                                    val sh = (size.height / s).toInt().coerceAtMost(img.height)
                                    drawImage(
                                        img, srcOffset = IntOffset((img.width - sw) / 2, (img.height - sh) / 2), srcSize = IntSize(sw, sh),
                                        dstOffset = IntOffset(tx.toInt(), 0), dstSize = IntSize(tileW.toInt() + 1, size.height.toInt()),
                                    )
                                }
                                tx += tileW
                            }
                        }
                    }
                    // Badges: speed / curve, photo, muted.
                    Row(Modifier.align(Alignment.TopStart).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        if (clip?.isImage == true) Badge { Icon(Icons.Default.Photo, null, Modifier.size(10.dp), tint = Color.White) }
                        if (p.curve != SpeedCurve.NONE) Badge { Text(p.curve.label, fontSize = 9.sp, color = Color.White) }
                        else if (p.speed != 1f) Badge { Text("${trimNum(p.speed)}×", fontSize = 9.sp, color = Color.White) }
                        if (p.volume == 0f && clip?.hasAudio == true) Badge { Icon(Icons.AutoMirrored.Filled.VolumeOff, null, Modifier.size(10.dp), tint = Color.White) }
                    }
                    if (w > 60) Text(
                        clock(p.outputMs), Modifier.align(Alignment.BottomEnd).padding(3.dp).background(Color(0x99000000), RoundedCornerShape(3.dp)).padding(horizontal = 3.dp),
                        fontSize = 9.sp, color = Color.White,
                    )
                }
                if (selected) {
                    TrimHandle(x, left = true) { dx ->
                        val clipDelta = (dx / pxPerMs * p.speed).toLong()
                        onTrim(p.id, p.startMs + clipDelta, null)
                    }
                    TrimHandle(x + w, left = false) { dx ->
                        val clipDelta = (dx / pxPerMs * p.speed).toLong()
                        onTrim(p.id, null, p.endMs + clipDelta)
                    }
                }
            }
            // Transition buttons on the cuts (not next to a selected clip, whose trim handles sit there).
            val selectedId = (state.selection as? Selection.Part)?.id
            parts.dropLast(1).forEachIndexed { i, p ->
                val cx = xOf(starts[i + 1])
                if (cx < -20 || cx > widthPx + 20) return@forEachIndexed
                if (p.id == selectedId || parts[i + 1].id == selectedId) return@forEachIndexed
                Box(
                    Modifier
                        .offset { IntOffset((cx - with(density) { 11.dp.toPx() }).roundToInt(), with(density) { 18.dp.toPx() }.roundToInt()) }
                        .size(22.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (p.transition == Transition.NONE) Color.White else EditorColors.Accent)
                        .clickable { onTransition(p.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (p.transition == Transition.NONE) Box(Modifier.width(2.dp).height(10.dp).background(Color.Black))
                    else Icon(Icons.Default.SwapHoriz, "Transition", Modifier.size(14.dp), tint = Color.Black)
                }
            }
            // Add clips after the end.
            val endX = xOf(total)
            if (endX < widthPx) Box(
                Modifier.offset { IntOffset((endX + with(density) { 8.dp.toPx() }).roundToInt(), with(density) { 12.dp.toPx() }.roundToInt()) }
                    .size(34.dp).clip(RoundedCornerShape(6.dp)).background(Color.White).clickable(onClick = onAddClip),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Add, "Add clips", tint = Color.Black) }
            // Camera sound on/off, pinned to the left like LightCut's "original sound".
            Column(
                Modifier.align(Alignment.CenterStart).padding(start = 6.dp).clip(RoundedCornerShape(6.dp))
                    .background(Color(0xCC17191E)).clickable(onClick = onToggleSound).padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(if (state.plan.keepOriginalAudio) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff, null, Modifier.size(16.dp), tint = Color.White)
                Text(if (state.plan.keepOriginalAudio) "Sound on" else "Muted", fontSize = 8.sp, color = Color.White)
            }
        }
        // Text & sticker lane: overlapping items stack in rows, like CapCut's tracks.
        val rows = remember(state.plan.texts) { textRows(state.plan.texts) }
        val rowCount = (rows.values.maxOrNull() ?: 0) + 1
        Box(Modifier.fillMaxWidth().height(26.dp * rowCount)) {
            if (state.plan.texts.isEmpty()) {
                LaneHint("Add text", Icons.Default.TextFields, EditorColors.Text, maxOf(xOf(0), half), onAddText)
            }
            state.plan.texts.forEach { t ->
                val row = rows[t.id] ?: 0
                val x = xOf(t.startMs)
                val w = (t.endMs - t.startMs) * pxPerMs
                if (x + w < 0 || x > widthPx) return@forEach
                val selected = (state.selection as? Selection.Text)?.id == t.id
                val color = if (t.sticker) EditorColors.Sticker else EditorColors.Text
                Box(
                    Modifier.offset { IntOffset(x.roundToInt(), with(density) { (26.dp * row).roundToPx() }) }
                        .wrapContentWidth(Alignment.Start, unbounded = true)
                        .width(with(density) { w.coerceAtLeast(8f).toDp() }).height(26.dp).padding(vertical = 2.dp)
                        .clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = if (selected) 1f else 0.75f))
                        .then(if (selected) Modifier.border(1.5.dp, Color.White, RoundedCornerShape(4.dp)) else Modifier)
                        .clickable { onSelect(if (selected) Selection.None else Selection.Text(t.id)) }
                        .then(if (selected) Modifier.pointerInput(t.id, pxPerMs) {
                            var acc = 0f
                            detectHorizontalDragGestures(onDragEnd = {
                                val d = (acc / pxPerMs).toLong()
                                if (d != 0L) onMoveText(t.id, (t.startMs + d).coerceAtLeast(0), (t.endMs + d).coerceAtLeast(300))
                                acc = 0f
                            }) { ch, dx -> ch.consume(); acc += dx }
                        } else Modifier)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.CenterStart,
                ) { Text(t.text.replace('\n', ' '), fontSize = 10.sp, color = Color.Black, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (selected) {
                    val top = with(density) { (26.dp * row).roundToPx() }
                    TrimHandle(x, left = true, height = 26.dp, y = top) { dx -> onMoveText(t.id, (t.startMs + (dx / pxPerMs).toLong()).coerceIn(0, t.endMs - 300), t.endMs) }
                    TrimHandle(x + w, left = false, height = 26.dp, y = top) { dx -> onMoveText(t.id, t.startMs, (t.endMs + (dx / pxPerMs).toLong()).coerceAtLeast(t.startMs + 300)) }
                }
            }
        }
        // Music lane
        Box(Modifier.fillMaxWidth().height(30.dp)) {
            val m = state.plan.music
            if (m == null) {
                LaneHint("Add music", Icons.Default.MusicNote, EditorColors.Music, maxOf(xOf(0), half), onAddMusic)
            } else {
                val x = xOf(0)
                val w = total * pxPerMs
                val selected = state.selection is Selection.Music
                Box(
                    Modifier.offset { IntOffset(x.roundToInt(), 0) }.wrapContentWidth(Alignment.Start, unbounded = true).width(with(density) { w.toDp() }).fillMaxHeight().padding(vertical = 2.dp)
                        .clip(RoundedCornerShape(4.dp)).background(EditorColors.Music.copy(alpha = 0.35f))
                        .then(if (selected) Modifier.border(1.5.dp, Color.White, RoundedCornerShape(4.dp)) else Modifier)
                        .clickable { onSelect(if (selected) Selection.None else Selection.Music) },
                ) {
                    val wave = state.waveform
                    Canvas(Modifier.fillMaxSize()) {
                        val songMs = m.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
                        if (wave != null && wave.isNotEmpty()) {
                            // 10 samples a second of the song; the video plays the song from startMs (repeating).
                            val stepPx = 3f
                            var px = 0f
                            while (px < size.width) {
                                val outMs = (px / pxPerMs).toLong()
                                var songT = m.startMs + outMs
                                if (songMs != Long.MAX_VALUE && songT >= songMs) songT = (outMs - (songMs - m.startMs)) % songMs
                                val v = wave.getOrNull((songT / 100).toInt()) ?: 0f
                                val h = size.height * 0.8f * v
                                drawRect(EditorColors.Music, Offset(px, (size.height - h) / 2), Size(2f, h.coerceAtLeast(1f)))
                                px += stepPx
                            }
                        }
                        beatMarks.forEach { b ->
                            val bx = b * pxPerMs
                            if (bx in 0f..size.width) drawCircle(Color.White, 2.5f.dp.toPx(), Offset(bx, size.height - 4.dp.toPx()))
                        }
                    }
                    Text("♪ " + m.title, Modifier.align(Alignment.CenterStart).padding(start = 4.dp), fontSize = 10.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Fixed centre line over the timeline; drawn by the parent so it sits above every lane. */
@Composable
fun Playhead(modifier: Modifier = Modifier) {
    Box(modifier.width(2.dp).background(Color.White))
}

@Composable
private fun Ruler(posMs: Long, pxPerMs: Float, half: Float, total: Long) {
    val density = LocalDensity.current
    // Tick spacing that keeps labels ~70 dp apart.
    val minGapMs = with(density) { 70.dp.toPx() } / pxPerMs
    val step = listOf(250L, 500L, 1000L, 2000L, 5000L, 10_000L, 30_000L, 60_000L).firstOrNull { it >= minGapMs } ?: 120_000L
    Box(Modifier.fillMaxWidth().height(18.dp)) {
        val first = ((posMs - half / pxPerMs) / step).toLong().coerceAtLeast(0) * step
        var t = first
        var guard = 0
        while (guard++ < 60) {
            val x = half + (t - posMs) * pxPerMs
            if (x > half * 2 + 50 || t > total + step) break
            Text(
                rulerLabel(t), Modifier.offset { IntOffset((x - with(density) { 10.dp.toPx() }).roundToInt(), 0) },
                fontSize = 9.sp, color = EditorColors.Muted,
            )
            t += step
        }
    }
}

@Composable
private fun Badge(content: @Composable () -> Unit) {
    Box(Modifier.background(Color(0xAA000000), RoundedCornerShape(3.dp)).padding(horizontal = 3.dp, vertical = 1.dp)) { content() }
}

@Composable
private fun LaneHint(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, x: Float, onClick: () -> Unit) {
    Row(
        Modifier.offset { IntOffset(x.roundToInt(), 0) }.fillMaxHeight().padding(vertical = 2.dp).clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.18f)).clickable(onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(14.dp), tint = color)
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 11.sp, color = color)
    }
}

/** A white drag handle on a selected item's edge; reports drag distance in px as it moves (applied on release). */
@Composable
private fun TrimHandle(x: Float, left: Boolean, height: androidx.compose.ui.unit.Dp = 58.dp, y: Int = 0, onDrag: (Float) -> Unit) {
    val density = LocalDensity.current
    var acc by remember { mutableFloatStateOf(0f) }
    val w = with(density) { 14.dp.toPx() }
    Box(
        Modifier
            .offset { IntOffset((if (left) x - w + acc else x + acc).roundToInt(), y) }
            .width(14.dp).height(height).padding(vertical = 4.dp)
            .clip(RoundedCornerShape(topStart = if (left) 6.dp else 0.dp, bottomStart = if (left) 6.dp else 0.dp, topEnd = if (left) 0.dp else 6.dp, bottomEnd = if (left) 0.dp else 6.dp))
            .background(Color.White)
            .pointerInput(x, left) {
                detectHorizontalDragGestures(
                    onDragEnd = { val d = acc; acc = 0f; if (d != 0f) onDrag(d) },
                    onDragCancel = { acc = 0f },
                ) { ch, dx -> ch.consume(); acc += dx }
            },
        contentAlignment = Alignment.Center,
    ) { Box(Modifier.width(2.dp).height(14.dp).background(Color.Black)) }
}

/** How many rows the text lane needs. */
fun timelineTextRows(texts: List<com.gpic.android.editor.TextItem>): Int = (textRows(texts).values.maxOrNull() ?: 0) + 1

/** Row for each text so overlapping ones don't cover each other (first row that's free at its start). */
private fun textRows(texts: List<com.gpic.android.editor.TextItem>): Map<Long, Int> {
    val ends = mutableListOf<Long>()
    val out = HashMap<Long, Int>()
    for (t in texts.sortedBy { it.startMs }) {
        val r = ends.indexOfFirst { it <= t.startMs }.takeIf { it >= 0 } ?: ends.size.also { ends += 0L }
        ends[r] = t.endMs
        out[t.id] = r.coerceAtMost(3)
    }
    return out
}

fun clock(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d.%d".format(s / 60, s % 60, (ms % 1000) / 100)
}

private fun rulerLabel(ms: Long): String {
    val s = ms / 1000
    return if (ms % 1000 == 0L) "%d:%02d".format(s / 60, s % 60) else "%d:%02d.%d".format(s / 60, s % 60, (ms % 1000) / 100)
}

fun trimNum(f: Float): String = if (f == f.toInt().toFloat()) f.toInt().toString() else "%.2f".format(f).trimEnd('0').trimEnd('.')

private const val MIN_PX_PER_MS = 0.004f // dp per ms, times density
private const val MAX_PX_PER_MS = 0.6f
