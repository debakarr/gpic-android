package com.gpic.android.ui.screens.editor

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.gpic.android.editor.Aspect
import com.gpic.android.editor.ClaudeEditPlanner
import com.gpic.android.editor.EditPlan
import com.gpic.android.editor.LutStore
import com.gpic.android.editor.Segment
import com.gpic.android.editor.VideoInfo
import kotlin.math.roundToLong

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(vm: EditorViewModel, onBack: () -> Unit, onBackUp: (Uri) -> Unit) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.messageShown() } }

    // Where picked clips go: a fresh edit, or added to the current one.
    var adding by remember { mutableStateOf(false) }
    val onPicked: (List<Uri>) -> Unit = { uris -> if (adding) vm.addVideos(uris) else vm.openVideos(uris) }
    val fromPhotos = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        onPicked(pickedUris(r.data))
    }
    val fromPhone = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { onPicked(it) }
    val pickFromPhotos = { add: Boolean -> adding = add; fromPhotos.launch(photosPickIntent(context)) }
    val pickFromPhone = { add: Boolean -> adding = add; fromPhone.launch(arrayOf("video/*")) }
    val pickMusic = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::setMusic) }
    var lutAsDji by remember { mutableStateOf(false) }
    val pickLut = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importLut(it, lutAsDji) } }
    var keyDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Video editor") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (state.video != null) TextButton(onClick = { pickFromPhotos(false) }) { Text("New edit") }
                },
            )
        },
        bottomBar = { state.video?.let { ExportBar(state, vm, onBackUp) } },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val video = state.video
        if (video == null || (state.loading != null && state.clips.isEmpty())) {
            EmptyState(state.loading, Modifier.padding(padding), onPhotos = { pickFromPhotos(false) }, onPhone = { pickFromPhone(false) })
            return@Scaffold
        }
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Preview(state)
            val total = state.durations.sum()
            val out = state.plan.outputMs(state.durations)
            Text(
                (if (state.clips.size == 1) "${video.name} • ${video.displayWidth}×${video.displayHeight} • " else "${state.clips.size} clips • ") +
                    clock(total) + if (out != total) " → ${clock(out)}" else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ClipStrip(state, onRemove = vm::removeClip, onAddPhotos = { pickFromPhotos(true) }, onAddPhone = { pickFromPhone(true) })

            QuickEditCard(state, vm)

            ClaudeCard(state, vm, onAddKey = { keyDialog = true })

            Section("Trim") {
                val plan = state.plan
                if (state.clips.size > 1 || plan.segments.size > 1) {
                    val parts = plan.parts(state.durations)
                    Text(
                        "Playing ${parts.size} part${if (parts.size == 1) "" else "s"} in this order. Tap one to drop it.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        parts.forEach { s ->
                            InputChip(
                                selected = false, onClick = { vm.update { p -> p.copy(segments = parts - s) } },
                                label = { Text((if (state.clips.size > 1) "Clip ${s.clip + 1} · " else "") + "${clock(s.startMs)}–${clock(s.endMs)}") },
                                trailingIcon = { Icon(Icons.Default.Close, "Drop this part", Modifier.size(16.dp)) },
                            )
                        }
                    }
                    if (plan.segments.isNotEmpty()) {
                        TextButton(onClick = { vm.update { it.copy(segments = emptyList()) } }) {
                            Text(if (state.clips.size > 1) "Use every clip in full" else "Use the whole video")
                        }
                    }
                } else {
                    val seg = plan.segments.firstOrNull() ?: Segment(0, video.durationMs)
                    var range by remember(seg) { mutableStateOf(seg.startMs.toFloat()..seg.endMs.toFloat()) }
                    RangeSlider(
                        value = range, onValueChange = { range = it },
                        valueRange = 0f..video.durationMs.toFloat(),
                        onValueChangeFinished = {
                            vm.update { it.copy(segments = listOf(Segment(range.start.roundToLong(), range.endInclusive.roundToLong()))) }
                        },
                    )
                    Text("Keep ${clock(range.start.toLong())} – ${clock(range.endInclusive.toLong())}", style = MaterialTheme.typography.bodySmall)
                }
            }

            Section("Speed") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (listOf(0.5f, 1f, 1.5f, 2f, 4f) + state.plan.speed).distinct().sorted().forEach { sp ->
                        FilterChip(selected = state.plan.speed == sp, onClick = { vm.update { it.copy(speed = sp) } }, label = { Text("${trim(sp)}×") })
                    }
                }
            }

            Section("Colour") {
                LutPicker(state, vm, onImport = { asDji -> lutAsDji = asDji; pickLut.launch(arrayOf("*/*")) }, onDjiPage = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LutStore.DJI_DOWNLOADS)))
                })
                LabeledSlider("Brightness", state.plan.brightness, -0.5f..0.5f) { v -> vm.update { it.copy(brightness = v) } }
                LabeledSlider("Contrast", state.plan.contrast, -0.5f..0.5f) { v -> vm.update { it.copy(contrast = v) } }
                LabeledSlider("Saturation", state.plan.saturation / 100f, -0.5f..0.5f) { v -> vm.update { it.copy(saturation = v * 100f) } }
            }

            Section("Audio") {
                if (state.clips.any { it.hasAudio }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Camera sound", Modifier.weight(1f))
                        Switch(checked = state.plan.keepOriginalAudio, onCheckedChange = { on -> vm.update { it.copy(keepOriginalAudio = on) } })
                    }
                    if (state.plan.keepOriginalAudio) {
                        LabeledSlider("Camera volume", state.plan.originalVolume, 0f..2f, center = 1f) { v -> vm.update { it.copy(originalVolume = v) } }
                    }
                } else Text(if (state.clips.size > 1) "These clips have no sound." else "This video has no sound.", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MusicNote, null)
                    Spacer(Modifier.width(8.dp))
                    Text(state.musicName ?: "No music", Modifier.weight(1f), maxLines = 1)
                    if (state.musicUri != null) TextButton(onClick = { vm.setMusic(null) }) { Text("Remove") }
                    TextButton(onClick = { pickMusic.launch(arrayOf("audio/*")) }) { Text(if (state.musicUri == null) "Add music" else "Change") }
                }
                if (state.musicUri != null) {
                    LabeledSlider("Music volume", state.plan.musicVolume, 0f..2f, center = 1f) { v -> vm.update { it.copy(musicVolume = v) } }
                    LabeledSlider("Start the song at", state.plan.musicStartMs / 1000f, 0f..180f, format = { "${it.toInt()} s" }) { v ->
                        vm.update { it.copy(musicStartMs = (v * 1000).toLong()) }
                    }
                    Text("Music loops under the whole video. The preview plays without it.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Section("Frame") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Fade between parts", Modifier.weight(1f))
                    Switch(checked = state.plan.fades, onCheckedChange = { on -> vm.update { it.copy(fades = on) } })
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Aspect.entries.forEach { a ->
                        FilterChip(selected = state.plan.aspect == a, onClick = { vm.update { it.copy(aspect = a) } }, label = { Text(a.label) })
                    }
                }
                val shortSide = minOf(video.width, video.height)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (listOf(0) + listOf(1080, 720).filter { it < shortSide }).forEach { h ->
                        FilterChip(selected = state.plan.maxHeight == h, onClick = { vm.update { it.copy(maxHeight = h) } },
                            label = { Text(if (h == 0) "Full size" else "${h}p") })
                    }
                    listOf(0, 90, 180, 270).forEach { r ->
                        FilterChip(selected = state.plan.rotation == r, onClick = { vm.update { it.copy(rotation = r) } },
                            label = { Text(if (r == 0) "No rotation" else "$r°") })
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (keyDialog) ClaudeKeyDialog(onDismiss = { keyDialog = false }, onSave = { vm.setClaudeKey(it); keyDialog = false }, onOpenConsole = {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.anthropic.com/settings/keys")))
    })
}

@Composable
private fun EmptyState(loading: String?, modifier: Modifier, onPhotos: () -> Unit, onPhone: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        if (loading != null) {
            CircularProgressIndicator()
            Text(loading, style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        Icon(Icons.Default.MovieCreation, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Edit videos", style = MaterialTheme.typography.titleLarge)
        Text(
            "Pick one or more clips, then let Quick edit cut the best moments to a style and length, describe the edit " +
                "to Claude, or trim and grade by hand (DJI D-Log M → Rec.709 for Osmo Action 4). Edited on this phone, " +
                "saved to Movies/GPic.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onPhotos) { Icon(Icons.Default.PhotoLibrary, null); Spacer(Modifier.width(8.dp)); Text("From Google Photos") }
        OutlinedButton(onClick = onPhone) { Icon(Icons.Default.PhoneAndroid, null); Spacer(Modifier.width(8.dp)); Text("From this phone") }
        Text(
            "Clips that are only in the cloud are downloaded by Google Photos, then copied into GPic for editing.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The installed Google Photos (stock or a patched build) answers "pick videos", cloud items included. */
private val PHOTOS_PACKAGES = listOf("com.google.android.apps.photos", "app.morphe.android.apps.photos", "app.revanced.android.apps.photos")

private fun photosPickIntent(context: android.content.Context): Intent {
    val base = Intent(Intent.ACTION_GET_CONTENT)
        .setType("video/*")
        .addCategory(Intent.CATEGORY_OPENABLE)
        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
    val pm = context.packageManager
    val pkg = PHOTOS_PACKAGES.firstOrNull { p -> Intent(base).setPackage(p).resolveActivity(pm) != null }
    return if (pkg != null) base.setPackage(pkg) else Intent.createChooser(base, "Pick videos")
}

private fun pickedUris(data: Intent?): List<Uri> {
    data ?: return emptyList()
    val clip = data.clipData
    if (clip != null) return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    return listOfNotNull(data.data)
}

@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClipStrip(state: EditorUiState, onRemove: (Int) -> Unit, onAddPhotos: () -> Unit, onAddPhone: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        state.clips.forEachIndexed { i, c ->
            InputChip(
                selected = false, onClick = {},
                label = { Text("${i + 1} · ${c.name.take(18)} · ${clock(c.durationMs)}", maxLines = 1) },
                trailingIcon = if (state.clips.size > 1) ({ IconButton(onClick = { onRemove(i) }, Modifier.size(20.dp)) { Icon(Icons.Default.Close, "Remove clip", Modifier.size(16.dp)) } }) else null,
            )
        }
        var menu by remember { mutableStateOf(false) }
        Box {
            AssistChip(onClick = { menu = true }, label = { Text("Add clips") }, leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(18.dp)) },
                enabled = state.loading == null)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("From Google Photos") }, onClick = { menu = false; onAddPhotos() })
                DropdownMenuItem(text = { Text("From this phone") }, onClick = { menu = false; onAddPhone() })
            }
        }
    }
    state.loading?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickEditCard(state: EditorUiState, vm: EditorViewModel) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoFixHigh, null)
                Spacer(Modifier.width(8.dp))
                Text("Quick edit", style = MaterialTheme.typography.titleSmall)
            }
            Text(
                "Picks the liveliest moments of your clips (worked out on this phone) and cuts them to a style.",
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.gpic.android.editor.EditStyle.entries.forEach { st ->
                    FilterChip(selected = state.style == st, onClick = { vm.setStyle(st) }, label = { Text(st.label) }, enabled = state.autoProgress == null)
                }
            }
            Text(state.style.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 60).forEach { sec ->
                    FilterChip(selected = state.targetSec == sec, onClick = { vm.setTarget(sec) }, label = { Text("$sec s") }, enabled = state.autoProgress == null)
                }
            }
            Button(onClick = vm::quickEdit, enabled = state.autoProgress == null && state.exportProgress == null) {
                if (state.autoProgress != null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.AutoFixHigh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(state.autoProgress ?: "Make my edit")
            }
            state.autoNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun Preview(state: EditorUiState) {
    val context = LocalContext.current
    val video = state.clips.first()
    val player = remember { ExoPlayer.Builder(context).build().apply { repeatMode = ExoPlayer.REPEAT_MODE_ALL } }
    DisposableEffect(player) { onDispose { player.release() } }
    val plan = state.plan
    // Cuts: one clipped item per kept part, from whichever clip it's in, so the preview plays what will be exported.
    LaunchedEffect(player, plan.segments, state.clips) {
        val parts = plan.parts(state.durations)
        player.setMediaItems(parts.map { s ->
            MediaItem.Builder().setUri(state.clips[s.clip].uri).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(s.startMs).setEndPositionMs(s.endMs).build()
            ).build()
        })
        player.prepare()
    }
    LaunchedEffect(player, plan.speed) { player.setPlaybackSpeed(plan.speed) }
    LaunchedEffect(player, plan.keepOriginalAudio, plan.originalVolume) {
        player.volume = if (plan.keepOriginalAudio) plan.originalVolume.coerceAtMost(1f) else 0f
    }
    LaunchedEffect(player, state.previewEffects) {
        runCatching { player.setVideoEffects(state.previewEffects) }.onFailure { android.util.Log.w("GPicEditor", "preview effects", it) }
    }
    val ratio = plan.aspect.ratio ?: (video.displayWidth.toFloat() / video.displayHeight.coerceAtLeast(1)).takeIf { it > 0 } ?: (16f / 9f)
    // Tall (phone) videos get a capped height so the controls stay in view.
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { PlayerView(it).apply { this.player = player; useController = true } },
            update = { it.player = player },
            modifier = Modifier.heightIn(max = 340.dp).aspectRatio(ratio.coerceIn(0.4f, 2.4f), matchHeightConstraintsFirst = ratio < 1f),
        )
    }
}

@Composable
private fun ClaudeCard(state: EditorUiState, vm: EditorViewModel, onAddKey: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, null)
                Spacer(Modifier.width(8.dp))
                Text("Ask Claude", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (state.hasClaudeKey) TextButton(onClick = onAddKey) { Text("API key") }
            }
            if (!state.hasClaudeKey) {
                Text(
                    "Describe the edit (\"keep the jump, slow it down, add the song, fix the flat colours\") and Claude sets the " +
                        "controls below. Uses your own Anthropic API key, billed to you.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = onAddKey) { Text("Add API key") }
                return@Column
            }
            OutlinedTextField(
                value = state.request, onValueChange = vm::setRequest, enabled = !state.asking,
                placeholder = { Text("e.g. Cut the first 5 seconds, 2× speed, apply the DJI LUT, mute the wind") },
                modifier = Modifier.fillMaxWidth(), minLines = 2,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = state.sendFrames, onCheckedChange = vm::setSendFrames, enabled = !state.asking)
                Text(
                    "Let Claude see ${ClaudeEditPlanner.FRAME_COUNT} small frames (sent to Anthropic). Off: only length, size and your words are sent.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                )
            }
            Button(onClick = vm::askClaude, enabled = !state.asking && state.request.isNotBlank()) {
                if (state.asking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (state.asking) "Claude is planning…" else "Ask Claude")
            }
            state.claudeNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun LutPicker(state: EditorUiState, vm: EditorViewModel, onImport: (asDji: Boolean) -> Unit, onDjiPage: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = state.luts.firstOrNull { it.id == state.plan.lutId }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = current?.title ?: "No LUT", onValueChange = {}, readOnly = true, label = { Text("Colour LUT") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("No LUT") }, onClick = { vm.update { it.copy(lutId = null) }; open = false })
            state.luts.forEach { l ->
                DropdownMenuItem(
                    text = { Text(l.title + if (l.id == state.defaultLutId) "  (default)" else "") },
                    onClick = { vm.update { it.copy(lutId = l.id) }; open = false },
                    trailingIcon = if (!l.builtIn) ({ IconButton(onClick = { vm.deleteLut(l.id); open = false }) { Icon(Icons.Default.Delete, "Remove LUT") } }) else null,
                )
            }
        }
    }
    if (!state.hasDjiLut) {
        Text(
            "For Osmo Action 4 D-Log M clips, add DJI's free \"D-Log M to Rec.709\" LUT (.cube) from its download page. " +
                "Until then GPic uses its own approximate look.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!state.hasDjiLut) OutlinedButton(onClick = onDjiPage) { Text("DJI download page") }
        OutlinedButton(onClick = { onImport(true) }) { Text(if (state.hasDjiLut) "Replace DJI LUT" else "Add DJI LUT") }
        TextButton(onClick = { onImport(false) }) { Text("Add other .cube") }
    }
}

@Composable
private fun ExportBar(state: EditorUiState, vm: EditorViewModel, onBackUp: (Uri) -> Unit) {
    val context = LocalContext.current
    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
            val p = state.exportProgress
            if (p != null) {
                LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Exporting… $p%", Modifier.weight(1f))
                    TextButton(onClick = vm::cancelExport) { Text("Cancel") }
                }
                return@Column
            }
            val done = state.exported
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (done != null) {
                    TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(done, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }) { Text("Play") }
                    TextButton(onClick = {
                        context.startActivity(Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, done).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
                    }) { Text("Share") }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { onBackUp(done) }) { Icon(Icons.Default.CloudUpload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Back up") }
                } else {
                    Spacer(Modifier.weight(1f))
                    Button(onClick = vm::export, enabled = !state.asking) {
                        Icon(Icons.Default.Movie, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Export")
                    }
                }
            }
        }
    }
}

@Composable
private fun ClaudeKeyDialog(onDismiss: () -> Unit, onSave: (String?) -> Unit, onOpenConsole: () -> Unit) {
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Claude API key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Paste a key from the Anthropic Console. It's stored encrypted on this phone and only sent to Anthropic. " +
                        "Requests use ${ClaudeEditPlanner.MODEL} and are billed to your account.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = key, onValueChange = { key = it.trim() }, singleLine = true, label = { Text("sk-ant-…") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                TextButton(onClick = onOpenConsole) { Text("Get a key at console.anthropic.com") }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(key) }, enabled = key.length > 20) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave(null) }) { Text("Remove key") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    center: Float = 0f,
    format: (Float) -> String = { v -> if (center == 1f) "${(v * 100).toInt()}%" else "%+d".format((v * 100).toInt()) },
    onDone: (Float) -> Unit,
) {
    var v by remember(value) { mutableFloatStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(110.dp), style = MaterialTheme.typography.bodySmall)
        Slider(value = v, onValueChange = { v = it }, valueRange = range, onValueChangeFinished = { onDone(v) }, modifier = Modifier.weight(1f))
        Text(format(v), Modifier.width(52.dp), style = MaterialTheme.typography.labelMedium)
    }
}

private fun clock(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d.%d".format(s / 60, s % 60, (ms % 1000) / 100)
}

private fun trim(f: Float) = if (f == f.toInt().toFloat()) f.toInt().toString() else f.toString()
