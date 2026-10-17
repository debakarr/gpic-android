package com.gpic.android.ui.screens.editor

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.gpic.android.editor.Aspect
import com.gpic.android.editor.Beats
import com.gpic.android.editor.Canvas
import com.gpic.android.editor.ClaudeEditPlanner
import com.gpic.android.editor.EditPlan
import com.gpic.android.editor.EditStyle
import com.gpic.android.editor.Encoders
import com.gpic.android.editor.Filter
import com.gpic.android.editor.Look
import com.gpic.android.editor.LookMath
import com.gpic.android.editor.LutStore
import com.gpic.android.editor.Motion
import com.gpic.android.editor.Openverse
import com.gpic.android.editor.SpeedCurve
import com.gpic.android.editor.TextAnim
import com.gpic.android.editor.TextFont
import com.gpic.android.editor.TextStyle
import com.gpic.android.editor.Transition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The sheet above the toolbar: a title row (✕ / title / ✓) and the panel's controls. */
@Composable
fun PanelSheet(title: String, onClose: () -> Unit, applyAll: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(EditorColors.Panel).padding(bottom = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
            Text(title, Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.titleSmall)
            if (applyAll != null) TextButton(onClick = applyAll) {
                Icon(Icons.Default.DoneAll, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Apply to all")
            }
            IconButton(onClick = onClose) { Icon(Icons.Default.Check, "Done", tint = EditorColors.Accent) }
        }
        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
fun ChipRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
fun Pick(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, enabled = enabled, label = { Text(label, maxLines = 1) })
}

/** A slider that reports while dragging (for the label) and commits once on release. */
@Composable
fun CommitSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    steps: Int = 0,
    onCommit: (Float) -> Unit,
) {
    var v by remember(value) { mutableFloatStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(92.dp), color = Color.White, style = MaterialTheme.typography.bodySmall)
        Slider(value = v, onValueChange = { v = it }, valueRange = range, steps = steps, onValueChangeFinished = { onCommit(v) }, modifier = Modifier.weight(1f))
        Text(format(v), Modifier.width(52.dp), color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

private fun pct(v: Float) = "%+d".format((v * 100).toInt())

// ---- part panels ---------------------------------------------------------------------------------------------

@Composable
fun SpeedPanel(state: EditorUiState, vm: EditorViewModel) {
    val part = state.selectedPart ?: return
    var tab by remember { mutableIntStateOf(if (part.curve == SpeedCurve.NONE) 0 else 1) }
    PanelSheet("Speed", onClose = { vm.openPanel(null) }, applyAll = { vm.updateAllParts { it.copy(speed = part.speed, curve = part.curve) } }) {
        ChipRow { Pick("Normal", tab == 0) { tab = 0 }; Pick("Curve", tab == 1) { tab = 1 } }
        if (tab == 0) {
            // Log scale so 0.25× and 4× sit at the ends with 1× in the middle.
            val logV = kotlin.math.log2(part.speed)
            CommitSlider("Speed", logV, -2f..2f, { "${trimNum(Math.round(Math.pow(2.0, it.toDouble()) * 100) / 100f)}×" }) { l ->
                val sp = (Math.round(Math.pow(2.0, l.toDouble()) * 20) / 20f).coerceIn(EditPlan.MIN_SPEED, EditPlan.MAX_SPEED)
                vm.updatePart(part.id) { it.copy(speed = sp, curve = SpeedCurve.NONE) }
            }
            Text("${clock(part.durationMs)} of clip → ${clock(part.outputMs)}", color = EditorColors.Muted, style = MaterialTheme.typography.bodySmall)
        } else {
            ChipRow {
                SpeedCurve.entries.forEach { c -> Pick(c.label, part.curve == c) { vm.updatePart(part.id) { it.copy(curve = c) } } }
            }
            Text("Speed ramps for action shots: Hero and Bullet slow the middle down, Montage alternates fast and slow.",
                color = EditorColors.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun VolumePanel(state: EditorUiState, vm: EditorViewModel) {
    val part = state.selectedPart ?: return
    PanelSheet("Volume", onClose = { vm.openPanel(null) }, applyAll = { vm.updateAllParts { it.copy(volume = part.volume) } }) {
        if (state.clips.getOrNull(part.clip)?.hasAudio != true) {
            Text("This clip has no sound.", color = EditorColors.Muted)
        } else {
            CommitSlider("This clip", part.volume, 0f..2f, { "${(it * 100).toInt()}%" }) { v -> vm.updatePart(part.id) { it.copy(volume = v) } }
            CommitSlider("All camera", state.plan.originalVolume, 0f..2f, { "${(it * 100).toInt()}%" }) { v -> vm.commit { it.copy(originalVolume = v) } }
        }
    }
}

@Composable
fun TransitionPanel(state: EditorUiState, vm: EditorViewModel) {
    val part = state.selectedPart ?: return
    PanelSheet("Transition to next clip", onClose = { vm.openPanel(null) },
        applyAll = { vm.updateAllParts { it.copy(transition = part.transition, transitionMs = part.transitionMs) } }) {
        ChipRow { Transition.entries.forEach { t -> Pick(t.label, part.transition == t) { vm.updatePart(part.id) { it.copy(transition = t) } } } }
        if (part.transition != Transition.NONE) {
            CommitSlider("Length", part.transitionMs / 1000f, 0.2f..2f, { "%.1f s".format(it) }) { v -> vm.updatePart(part.id) { it.copy(transitionMs = (v * 1000).toLong()) } }
        }
    }
}

@Composable
fun MotionPanel(state: EditorUiState, vm: EditorViewModel) {
    val part = state.selectedPart ?: return
    PanelSheet("Zoom & pan", onClose = { vm.openPanel(null) }, applyAll = { vm.updateAllParts { it.copy(motion = part.motion) } }) {
        ChipRow { Motion.entries.forEach { m -> Pick(m.label, part.motion == m) { vm.updatePart(part.id) { it.copy(motion = m) } } } }
        Text("A slow camera move over the whole clip. Nice on photos and still shots.", color = EditorColors.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

// ---- looks ---------------------------------------------------------------------------------------------------

/** Filters for the selected part (when one is selected) or the whole video. */
@Composable
fun FiltersPanel(state: EditorUiState, vm: EditorViewModel, onImportLut: (asDji: Boolean) -> Unit) {
    val part = state.selectedPart
    val look = part?.let { state.plan.lookOf(it) } ?: state.plan.look
    fun set(l: Look) = if (part != null) vm.updatePart(part.id) { it.copy(look = l) } else vm.commit { it.copy(look = l) }
    val context = LocalContext.current
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(part?.id) { frame = vm.sampleFrame() }
    PanelSheet(if (part != null) "Filters · this clip" else "Filters · whole video", onClose = { vm.openPanel(null) },
        applyAll = if (part != null) ({ vm.commit { p -> p.copy(look = look, segments = p.segments.map { it.copy(look = null) }) } }) else null) {
        // Camera LUT (D-Log M etc.)
        ChipRow {
            Text("Camera", color = EditorColors.Muted, style = MaterialTheme.typography.labelMedium)
            Pick("None", look.lutId == null) { set(look.copy(lutId = null)) }
            state.luts.forEach { l -> Pick(l.title, look.lutId == l.id) { set(look.copy(lutId = l.id)) } }
            AssistChip(onClick = { onImportLut(!state.hasDjiLut) }, label = { Text(if (state.hasDjiLut) "+ .cube" else "+ DJI LUT") })
            if (!state.hasDjiLut) TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LutStore.DJI_DOWNLOADS))) }) { Text("Get it") }
        }
        // Presets with live thumbnails of this clip.
        val camera = remember(look.lutId) { look.lutId?.let { vm.loadLut(it) } }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterThumb("None", frame, look.copy(filter = null), camera, look.filter == null) { set(look.copy(filter = null)) }
            Filter.entries.forEach { f ->
                FilterThumb(f.label, frame, look.copy(filter = f, filterAmount = 1f), camera, look.filter == f) {
                    set(look.copy(filter = f, filterAmount = if (look.filter == null) 0.8f else look.filterAmount))
                }
            }
        }
        if (look.filter != null) {
            CommitSlider("Strength", look.filterAmount, 0f..1f, { "${(it * 100).toInt()}" }) { v -> set(look.copy(filterAmount = v)) }
        }
        if (part?.look != null) TextButton(onClick = { vm.updatePart(part.id) { it.copy(look = null) } }) { Text("Use the whole video's look") }
    }
}

@Composable
private fun FilterThumb(label: String, frame: Bitmap?, look: Look, camera: com.gpic.android.editor.CubeLut?, selected: Boolean, onClick: () -> Unit) {
    var img by remember(frame, look, camera) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(frame, look, camera) {
        val f = frame ?: return@LaunchedEffect
        img = withContext(Dispatchers.Default) {
            val px = IntArray(f.width * f.height)
            f.getPixels(px, 0, f.width, 0, 0, f.width, f.height)
            LookMath.applyToPixels(look, camera, px)
            Bitmap.createBitmap(px, f.width, f.height, Bitmap.Config.ARGB_8888)
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp).clickable(onClick = onClick)) {
        Box(
            Modifier.size(58.dp).clip(RoundedCornerShape(8.dp)).background(EditorColors.Lane)
                .then(if (selected) Modifier.border(2.dp, EditorColors.Accent, RoundedCornerShape(8.dp)) else Modifier),
        ) {
            img?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Text(label, color = if (selected) EditorColors.Accent else Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun AdjustPanel(state: EditorUiState, vm: EditorViewModel) {
    val part = state.selectedPart
    val look = part?.let { state.plan.lookOf(it) } ?: state.plan.look
    fun set(l: Look) = if (part != null) vm.updatePart(part.id) { it.copy(look = l) } else vm.commit { it.copy(look = l) }
    var which by remember { mutableStateOf(Look.ADJUSTMENTS.first()) }
    PanelSheet(if (part != null) "Adjust · this clip" else "Adjust · whole video", onClose = { vm.openPanel(null) },
        applyAll = if (part != null) ({ vm.commit { p -> p.copy(look = look, segments = p.segments.map { it.copy(look = null) }) } }) else null) {
        ChipRow {
            Look.ADJUSTMENTS.forEach { a ->
                val v = a.get(look)
                Pick(a.label + if (v != 0f) " •" else "", which.key == a.key) { which = a }
            }
        }
        CommitSlider(which.label, which.get(look), which.min..1f, ::pct) { v -> set(which.set(look, (Math.round(v * 100) / 100f))) }
        if (!look.adjustNeutral || look.vignette > 0f) TextButton(onClick = { set(Look(lutId = look.lutId, filter = look.filter, filterAmount = look.filterAmount)) }) { Text("Reset adjustments") }
    }
}

// ---- text ----------------------------------------------------------------------------------------------------

private val SWATCHES = listOf(0xFFFFFFFF, 0xFF000000, 0xFFFFEB3B, 0xFFFF9800, 0xFFF44336, 0xFFE91E63, 0xFF9C27B0, 0xFF3F51B5, 0xFF03A9F4, 0xFF00E5FF, 0xFF4CAF50, 0xFF8BC34A).map { it.toInt() }

@Composable
fun TextPanel(state: EditorUiState, vm: EditorViewModel) {
    val t = state.selectedText ?: return
    var tab by remember { mutableIntStateOf(0) }
    var text by remember(t.id) { mutableStateOf(t.text) }
    PanelSheet(if (t.sticker) "Sticker" else "Text", onClose = { if (text != t.text) vm.updateText(t.id) { it.copy(text = text) }; vm.openPanel(null) }) {
        if (!t.sticker) OutlinedTextField(
            value = text, onValueChange = { text = it.take(EditPlan.MAX_TEXT) }, modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 3,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
            trailingIcon = { IconButton(onClick = { vm.updateText(t.id) { it.copy(text = text) } }) { Icon(Icons.Default.Check, "Apply text", tint = EditorColors.Accent) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { vm.updateText(t.id) { it.copy(text = text) } }),
        )
        ChipRow { listOf("Style", "Font", "Animation").forEachIndexed { i, l -> Pick(l, tab == i) { tab = i } } }
        when (tab) {
            0 -> {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SWATCHES.forEach { c ->
                        Box(Modifier.size(28.dp).clip(CircleShape).background(Color(c))
                            .border(if (t.color == c) 3.dp else 1.dp, if (t.color == c) EditorColors.Accent else Color.Gray, CircleShape)
                            .clickable { vm.updateText(t.id) { it.copy(color = c) } })
                    }
                }
                ChipRow { TextStyle.entries.forEach { s -> Pick(s.label, t.style == s) { vm.updateText(t.id) { it.copy(style = s) } } } }
                CommitSlider("Size", t.size, 0.02f..0.35f, { "${(it * 100).toInt()}" }) { v -> vm.updateText(t.id) { it.copy(size = v) } }
            }
            1 -> ChipRow { TextFont.entries.forEach { f -> Pick(f.label, t.font == f) { vm.updateText(t.id) { it.copy(font = f) } } } }
            else -> {
                ChipRow { Text("In", color = EditorColors.Muted); TextAnim.entries.forEach { a -> Pick(a.label, t.animIn == a) { vm.updateText(t.id) { it.copy(animIn = a) } } } }
                ChipRow {
                    Text("Out", color = EditorColors.Muted)
                    TextAnim.entries.filter { it != TextAnim.TYPEWRITER }.forEach { a -> Pick(a.label, t.animOut == a) { vm.updateText(t.id) { it.copy(animOut = a) } } }
                }
            }
        }
        Text("Drag the text on the video to move it; drag its bar on the timeline to change when it shows.", color = EditorColors.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

/** Emoji stickers (drawn with the phone's emoji font, so nothing is downloaded). */
val STICKERS = listOf("😂", "😍", "🔥", "💯", "👍", "🎉", "❤️", "✨", "😎", "🤩", "😱", "🥳", "🙌", "👏", "💪", "🚀", "⭐", "🌊", "🏔️", "🌅",
    "🌴", "🏄", "🚴", "🏂", "⛷️", "🧗", "📍", "✈️", "🚗", "📸", "🎬", "🎵", "☀️", "🌙", "⚡", "💥", "👀", "🤙", "😴", "🍕")

// ---- music ---------------------------------------------------------------------------------------------------

@OptIn(UnstableApi::class)
@Composable
fun MusicLibraryPanel(state: EditorUiState, vm: EditorViewModel, onPickPhoneAudio: () -> Unit, onClipSound: (Int) -> Unit) {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }
    var playing by remember { mutableStateOf<String?>(null) }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(Unit) { if (state.musicResults.isEmpty() && state.downloaded.isEmpty() && !state.musicSearching) vm.searchMusic("Upbeat") }
    fun preview(id: String, url: String) {
        if (playing == id) { player.stop(); playing = null; return }
        player.setMediaItem(MediaItem.fromUri(url)); player.prepare(); player.play(); playing = id
    }
    PanelSheet("Music", onClose = { player.stop(); vm.openPanel(null) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.musicQuery, onValueChange = vm::setMusicQuery, singleLine = true, modifier = Modifier.weight(1f),
                placeholder = { Text("Search free music (Openverse)") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.searchMusic() }),
            )
            IconButton(onClick = { vm.searchMusic() }, enabled = !state.musicSearching) { Icon(Icons.Default.Search, "Search", tint = Color.White) }
        }
        ChipRow {
            Openverse.MOODS.forEach { m -> Pick(m, state.musicQuery.equals(m, true), enabled = !state.musicSearching) { vm.searchMusic(m) } }
        }
        ChipRow {
            AssistChip(onClick = onPickPhoneAudio, label = { Text("From phone") }, leadingIcon = { Icon(Icons.Default.PhoneAndroid, null, Modifier.size(16.dp)) })
            state.clips.forEachIndexed { i, c ->
                if (c.hasAudio) AssistChip(onClick = { onClipSound(i) }, label = { Text("Sound of clip ${i + 1}") }, leadingIcon = { Icon(Icons.Default.GraphicEq, null, Modifier.size(16.dp)) })
            }
        }
        if (state.musicSearching) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.musicError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        val list = state.musicResults.ifEmpty { state.downloaded }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 230.dp)) {
            if (state.musicResults.isNotEmpty() && state.downloaded.isNotEmpty()) item {
                Text("Downloaded: " + state.downloaded.take(3).joinToString { it.title }, color = EditorColors.Muted, fontSize = 11.sp,
                    modifier = Modifier.clickable { vm.useTrack(state.downloaded.first()) }.padding(vertical = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            items(list, key = { it.id }) { t ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { preview(t.id, t.url) }) {
                        Icon(if (playing == t.id) Icons.Default.Stop else Icons.Default.PlayArrow, "Listen", tint = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(t.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Text("${t.creator} · ${clock(t.durationMs).substringBefore('.')} · ${t.licenseLabel}", color = EditorColors.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val dl = state.downloading[t.id]
                    if (dl != null) CircularProgressIndicator(progress = { if (dl < 0) 0.5f else dl / 100f }, modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                    else Button(onClick = { player.stop(); playing = null; vm.useTrack(t) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Use") }
                }
            }
        }
        Text("Music from Openverse is Creative Commons. GPic shows the credit to post with your video when the license asks for one.",
            color = EditorColors.Muted, fontSize = 10.sp)
    }
}

@Composable
fun MusicPanel(state: EditorUiState, vm: EditorViewModel, beatMarks: Int) {
    val m = state.plan.music ?: return
    val clipboard = LocalClipboardManager.current
    PanelSheet("Music · ${m.title}", onClose = { vm.openPanel(null) }) {
        Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CommitSlider("Volume", m.volume, 0f..2f, { "${(it * 100).toInt()}%" }) { v -> vm.updateMusic { it.copy(volume = v) } }
            val songS = (m.durationMs / 1000f).takeIf { it > 1 } ?: 300f
            CommitSlider("Start at", m.startMs / 1000f, 0f..(songS - 1).coerceAtLeast(1f), { "${it.toInt() / 60}:%02d".format(it.toInt() % 60) }) { v ->
                vm.updateMusic { it.copy(startMs = (v * 1000).toLong()) }
            }
            CommitSlider("Fade in", m.fadeInMs / 1000f, 0f..5f, { "%.1f s".format(it) }) { v -> vm.updateMusic { it.copy(fadeInMs = (v * 1000).toLong()) } }
            CommitSlider("Fade out", m.fadeOutMs / 1000f, 0f..5f, { "%.1f s".format(it) }) { v -> vm.updateMusic { it.copy(fadeOutMs = (v * 1000).toLong()) } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (m.bpm > 0) "Beats · ${m.bpm.toInt()} BPM" else "Beats", color = Color.White, modifier = Modifier.weight(1f))
                if (state.beatsBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else if (m.beatsMs.isEmpty()) TextButton(onClick = vm::detectBeats) { Text("Find beats") }
            }
            if (m.beatsMs.isNotEmpty()) {
                ChipRow {
                    Beats.Pace.entries.forEach { p -> Pick(p.label, state.beatPace == p) { vm.setBeatPace(p) } }
                    Button(onClick = vm::snapToBeats) { Icon(Icons.Default.ContentCut, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Cut on beats") }
                }
                Text("$beatMarks markers on the timeline. “Cut on beats” moves each cut to the nearest marker.", color = EditorColors.Muted, fontSize = 11.sp)
            }
            m.credit?.let { c ->
                Text("Credit: $c", color = EditorColors.Muted, fontSize = 11.sp)
                TextButton(onClick = { clipboard.setText(AnnotatedString("Music: $c")) }) { Text("Copy credit") }
            }
            Row {
                TextButton(onClick = { vm.openPanel(Panel.MUSIC_LIBRARY) }) { Text("Replace") }
                TextButton(onClick = vm::removeMusic) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

// ---- whole video ---------------------------------------------------------------------------------------------

@Composable
fun CanvasPanel(state: EditorUiState, vm: EditorViewModel) {
    PanelSheet("Format", onClose = { vm.openPanel(null) }) {
        ChipRow { Aspect.entries.forEach { a -> Pick(a.label, state.plan.aspect == a) { vm.commit { it.copy(aspect = a) } } } }
        ChipRow {
            Text("Background", color = EditorColors.Muted, style = MaterialTheme.typography.labelMedium)
            Canvas.entries.forEach { c -> Pick(c.label, state.plan.canvas == c) { vm.commit { it.copy(canvas = c) } } }
        }
        Text("Background applies where a clip's shape differs from the video's (e.g. a vertical clip in 16:9).",
            color = EditorColors.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun QuickEditPanel(state: EditorUiState, vm: EditorViewModel) {
    PanelSheet("Quick edit", onClose = { vm.openPanel(null) }) {
        Text("Picks the liveliest moments of your clips (worked out on this phone), cuts them to a style, and to the beat when there's music.",
            color = EditorColors.Muted, style = MaterialTheme.typography.bodySmall)
        ChipRow { EditStyle.entries.forEach { st -> Pick(st.label, state.style == st, enabled = state.autoProgress == null) { vm.setStyle(st) } } }
        Text(state.style.blurb, color = Color.White, style = MaterialTheme.typography.bodySmall)
        ChipRow { listOf(15, 30, 60).forEach { sec -> Pick("$sec s", state.targetSec == sec, enabled = state.autoProgress == null) { vm.setTarget(sec) } } }
        Button(onClick = vm::quickEdit, enabled = state.autoProgress == null && state.exportProgress == null) {
            if (state.autoProgress != null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.AutoFixHigh, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(state.autoProgress ?: "Make my edit")
        }
        state.autoNote?.let { Text(it, color = Color.White, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
fun ClaudePanel(state: EditorUiState, vm: EditorViewModel, onAddKey: () -> Unit) {
    PanelSheet("Ask Claude", onClose = { vm.openPanel(null) }) {
        if (!state.hasClaudeKey) {
            Text("Describe the edit (\"keep the jumps, slow-mo the landing, add a title, cut to the beat\") and Claude sets it up. " +
                "Uses your own Anthropic API key, billed to you.", color = Color.White, style = MaterialTheme.typography.bodySmall)
            Button(onClick = onAddKey) { Text("Add API key") }
            return@PanelSheet
        }
        OutlinedTextField(
            value = state.request, onValueChange = vm::setRequest, enabled = !state.asking, modifier = Modifier.fillMaxWidth(), minLines = 2,
            placeholder = { Text("e.g. Hero slow-mo on the jump, title “Goa 2026” at the start, cut on the beat, warm colour") },
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = state.sendFrames, onCheckedChange = vm::setSendFrames, enabled = !state.asking)
            Text("Let Claude see up to ${ClaudeEditPlanner.FRAME_COUNT} small frames (sent to Anthropic). Off: only lengths, sizes and your words.",
                color = EditorColors.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = vm::askClaude, enabled = !state.asking && state.request.isNotBlank()) {
                if (state.asking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (state.asking) "Claude is planning…" else "Ask Claude")
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onAddKey) { Text("API key") }
        }
        state.claudeNote?.let { Text(it, color = Color.White, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
fun ExportPanel(state: EditorUiState, vm: EditorViewModel) {
    val sourceShort = state.clips.maxOfOrNull { minOf(it.displayWidth, it.displayHeight) } ?: 1080
    val options = EditPlan.RESOLUTIONS.filter { it == 0 || (it <= state.maxShortSide && it <= sourceShort) }
    PanelSheet("Export", onClose = { vm.openPanel(null) }) {
        ChipRow {
            Text("Resolution", color = EditorColors.Muted, style = MaterialTheme.typography.labelMedium)
            options.forEach { r ->
                val label = when (r) { 0 -> "Best (${minOf(sourceShort, state.maxShortSide)}p)"; 1440 -> "2K"; 2160 -> "4K"; else -> "${r}p" }
                Pick(label, state.plan.maxHeight == r) { vm.commit { it.copy(maxHeight = r) } }
            }
        }
        val sourceFps = state.clips.mapNotNull { it.frameRate }.maxOrNull()?.toInt() ?: 30
        ChipRow {
            Text("Frame rate", color = EditorColors.Muted, style = MaterialTheme.typography.labelMedium)
            Pick("Same as clips", state.plan.fps == 0) { vm.commit { it.copy(fps = 0) } }
            // Frames can be dropped, not invented: only rates up to the clips' own.
            EditPlan.FRAME_RATES.filter { it in 1..maxOf(30, sourceFps) }.forEach { f -> Pick("$f fps", state.plan.fps == f) { vm.commit { it.copy(fps = f) } } }
        }
        val (w, h) = vm.frameFor(state.plan.maxHeight)
        Text("${w}×$h · ${clock(state.outputMs).substringBefore('.')} · about ${formatMb(Encoders.estimateBytes(w, h, state.plan.fps, state.outputMs))}",
            color = Color.White, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = vm::export, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Movie, null); Spacer(Modifier.width(8.dp)); Text("Export to Movies/GPic") }
    }
}

private fun formatMb(b: Long) = if (b >= 1_000_000_000) "%.1f GB".format(b / 1e9) else "%.0f MB".format((b / 1e6).coerceAtLeast(1.0))
