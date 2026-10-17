package com.gpic.android.ui.screens.editor

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.CompositionPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.gpic.android.editor.ClaudeEditPlanner
import com.gpic.android.editor.TextAnimMath
import com.gpic.android.editor.TextItem
import com.gpic.android.editor.TextRender
import com.gpic.android.editor.Timeline
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun EditorScreen(vm: EditorViewModel, onBack: () -> Unit, onBackUp: (Uri) -> Unit) {
    val state by vm.state.collectAsState()
    MaterialTheme(colorScheme = darkColorScheme(primary = EditorColors.Accent, onPrimary = Color.Black, surface = EditorColors.Panel, background = EditorColors.Background)) {
        if (state.projectId == null || state.clips.isEmpty()) ProjectsHome(state, vm, onBack)
        else Workspace(state, vm, onBackUp)
    }
}

/** The installed Google Photos (stock or a patched build) answers "pick videos/photos", cloud items included. */
private val PHOTOS_PACKAGES = listOf("com.google.android.apps.photos", "app.morphe.android.apps.photos", "app.revanced.android.apps.photos")

private fun photosPickIntent(context: android.content.Context): Intent {
    val base = Intent(Intent.ACTION_GET_CONTENT)
        .setType("*/*")
        .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "image/*"))
        .addCategory(Intent.CATEGORY_OPENABLE)
        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
    val pm = context.packageManager
    val pkg = PHOTOS_PACKAGES.firstOrNull { p -> Intent(base).setPackage(p).resolveActivity(pm) != null }
    return if (pkg != null) base.setPackage(pkg) else Intent.createChooser(base, "Pick videos or photos")
}

private fun pickedUris(data: Intent?): List<Uri> {
    data ?: return emptyList()
    val clip = data.clipData
    if (clip != null) return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    return listOfNotNull(data.data)
}

/** Launchers for "add clips" from Google Photos or the phone; [add] picks between a new project and adding. */
private class ClipPickers(val fromPhotos: (Boolean) -> Unit, val fromPhone: (Boolean) -> Unit)

@Composable
private fun rememberClipPickers(vm: EditorViewModel): ClipPickers {
    val context = LocalContext.current
    var adding by remember { mutableStateOf(false) }
    val onPicked: (List<Uri>) -> Unit = { uris -> if (adding) vm.addVideos(uris) else vm.openVideos(uris) }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r -> onPicked(pickedUris(r.data)) }
    val phone = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { onPicked(it) }
    return remember {
        ClipPickers(
            fromPhotos = { add -> adding = add; photos.launch(photosPickIntent(context)) },
            fromPhone = { add -> adding = add; phone.launch(arrayOf("video/*", "image/*")) },
        )
    }
}

// ---- project list ----------------------------------------------------------------------------------------------

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectsHome(state: EditorUiState, vm: EditorViewModel, onBack: () -> Unit) {
    val pickers = rememberClipPickers(vm)
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.messageShown() } }
    LaunchedEffect(Unit) { vm.refreshProjects() }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    Scaffold(
        containerColor = EditorColors.Background,
        topBar = {
            TopAppBar(
                title = { Text("Video editor") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = EditorColors.Background, titleContentColor = Color.White, navigationIconContentColor = Color.White),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.loading != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp))
                    Text(state.loading, color = Color.White)
                }
            }
            // New project
            Card(colors = CardDefaults.cardColors(containerColor = EditorColors.Lane)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AddCircle, null, tint = EditorColors.Accent, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("New project", color = Color.White, style = MaterialTheme.typography.titleMedium)
                            Text("Videos and photos · trim, speed ramps, transitions, filters, text, music on the beat",
                                color = EditorColors.Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { pickers.fromPhotos(false) }, enabled = state.loading == null, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.PhotoLibrary, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Google Photos", maxLines = 1)
                        }
                        OutlinedButton(onClick = { pickers.fromPhone(false) }, enabled = state.loading == null, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.PhoneAndroid, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("This phone", maxLines = 1)
                        }
                    }
                }
            }
            Text("Projects", color = Color.White, style = MaterialTheme.typography.titleSmall)
            if (state.projects.isEmpty()) {
                Text("Your edits are saved here automatically as you work.", color = EditorColors.Muted, style = MaterialTheme.typography.bodyMedium)
            }
            LazyVerticalGrid(GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                items(state.projects, key = { it.id }) { p ->
                    Column(Modifier.clip(RoundedCornerShape(10.dp)).background(EditorColors.Lane).clickable { vm.openProject(p.id) }) {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).background(Color.Black)) {
                            val cover = remember(p.cover, p.updatedMs) { p.cover?.let { runCatching { BitmapFactory.decodeFile(it.path) }.getOrNull() } }
                            cover?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                            Text(clock(p.durationMs).substringBefore('.'), Modifier.align(Alignment.BottomEnd).padding(6.dp)
                                .background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp), color = Color.White, fontSize = 11.sp)
                            Box(Modifier.align(Alignment.TopEnd)) {
                                IconButton(onClick = { menuFor = p.id }) { Icon(Icons.Default.MoreVert, "Project options", tint = Color.White) }
                                DropdownMenu(expanded = menuFor == p.id, onDismissRequest = { menuFor = null }) {
                                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menuFor = null; confirmDelete = p.id })
                                }
                            }
                        }
                        Column(Modifier.padding(8.dp)) {
                            Text(p.name.ifEmpty { "Untitled" }, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            Text("${p.clipCount} clip${if (p.clipCount == 1) "" else "s"} · ${ago(p.updatedMs)}", color = EditorColors.Muted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
    confirmDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this project?") },
            text = { Text("The edit and GPic's copies of its clips are removed. Your original videos and anything exported stay.") },
            confirmButton = { TextButton(onClick = { vm.deleteProject(id); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

private fun ago(ms: Long): String {
    val d = (System.currentTimeMillis() - ms).coerceAtLeast(0) / 60_000
    return when {
        d < 1 -> "just now"
        d < 60 -> "$d min ago"
        d < 24 * 60 -> "${d / 60} h ago"
        else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(ms))
    }
}

// ---- the editor ------------------------------------------------------------------------------------------------

@OptIn(UnstableApi::class)
@Composable
private fun Workspace(state: EditorUiState, vm: EditorViewModel, onBackUp: (Uri) -> Unit) {
    val context = LocalContext.current
    val pickers = rememberClipPickers(vm)
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it, duration = SnackbarDuration.Short); vm.messageShown() } }
    val pickMusic = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> vm.useLocalAudio(u) } }
    var lutAsDji by remember { mutableStateOf(false) }
    val pickLut = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importLut(it, lutAsDji) } }
    var keyDialog by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var stickers by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val thumbs by vm.thumbs.collectAsState()
    val built by vm.preview.collectAsState()

    // Player + playhead
    val player = remember { CompositionPlayer.Builder(context).build() }
    var posMs by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var seekTo by remember { mutableStateOf<Long?>(null) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(s: Int) { if (s == Player.STATE_ENDED) { playing = false } }
            // Decoder/GL failures (e.g. 10-bit HEVC on an old phone) arrive here, not as exceptions.
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.w("GPicEditor", "preview playback failed", error)
                vm.toast("The preview can't play this (${error.errorCodeName}). Export may still work; try a lower resolution if it doesn't.")
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }
    LaunchedEffect(built) {
        // No composition (edit copies being made): release the decoder for them.
        val b = built ?: return@LaunchedEffect run { runCatching { player.stop() } }
        posMs = posMs.coerceIn(0, (b.durationMs - 1).coerceAtLeast(0))
        runCatching {
            player.setComposition(b.composition, posMs)
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
        }.onFailure { e -> android.util.Log.w("GPicEditor", "preview failed", e); vm.toast("Preview failed: ${e.message ?: e.javaClass.simpleName}") }
    }
    LaunchedEffect(playing) {
        while (playing) {
            withFrameMillis { }
            posMs = player.currentPosition.coerceAtLeast(0)
        }
    }
    // Scrubbing: seek to the latest position at most every ~60 ms.
    LaunchedEffect(Unit) {
        while (true) {
            val t = seekTo
            if (t != null && built != null) { seekTo = null; runCatching { player.seekTo(t) } }
            delay(60)
        }
    }
    fun scrub(ms: Long) {
        if (playing) player.pause()
        posMs = ms.coerceIn(0, state.outputMs)
        seekTo = posMs
    }
    fun togglePlay() {
        if (playing) player.pause()
        else {
            if (built == null) return
            if (player.playbackState == Player.STATE_ENDED || posMs >= state.outputMs - 50) { posMs = 0; runCatching { player.seekTo(0) } }
            player.play()
        }
    }

    BackHandler {
        when {
            stickers -> stickers = false
            state.panel != null -> vm.openPanel(null)
            state.selection != Selection.None -> vm.select(Selection.None)
            else -> { player.pause(); vm.closeProject() }
        }
    }

    Scaffold(containerColor = EditorColors.Background, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Top bar
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { player.pause(); vm.closeProject() }) { Icon(Icons.Default.Close, "Close project", tint = Color.White) }
                Text(state.projectName, Modifier.weight(1f).clickable { renameDialog = true }, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall)
                val res = when (state.plan.maxHeight) { 0 -> "Best"; 1440 -> "2K"; 2160 -> "4K"; else -> "${state.plan.maxHeight}p" }
                TextButton(onClick = { vm.select(Selection.None); vm.openPanel(Panel.EXPORT) }) { Text(res, color = Color.White); Icon(Icons.Default.ArrowDropDown, null, tint = Color.White) }
                Button(onClick = { vm.select(Selection.None); vm.openPanel(Panel.EXPORT) }, enabled = state.exportProgress == null, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("Export") }
            }
            // Preview
            Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black), contentAlignment = Alignment.Center) {
                val ratio = (built?.let { it.width.toFloat() / it.height } ?: (16f / 9f)).coerceIn(0.3f, 3f)
                // The largest box of the video's shape that fits, so the text layer lines up with the picture.
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val boxW = minOf(maxWidth, maxHeight * ratio)
                // The player keeps its last frame at the old size when the preview resizes (a panel opened or
                // closed): draw it again at the new size.
                LaunchedEffect(boxW) {
                    delay(250)
                    // Re-seeking renders a fresh frame at the new size (redrawing the last one isn't always enough).
                    if (built != null && !playing) runCatching { player.seekTo(posMs) } else runCatching { player.experimentalRedrawLastFrame() }
                }
                Box(Modifier.size(boxW, boxW / ratio)) {
                    AndroidView(
                        factory = {
                            PlayerView(it).apply {
                                useController = false
                                // The box already has the output's shape; the composition player can report a misleading video size.
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                                setShutterBackgroundColor(android.graphics.Color.BLACK)
                                this.player = player
                            }
                        },
                        modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { togglePlay() } },
                    )
                    TextLayer(state, posMs, vm)
                }
                }
                state.preparing?.let { msg ->
                    Column(
                        Modifier.align(Alignment.Center).padding(24.dp).background(Color(0xE617191E), RoundedCornerShape(12.dp)).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(color = EditorColors.Accent)
                        Text(msg, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                        Text("A small copy makes the preview smooth on big clips. The export still uses the full-quality original.",
                            color = EditorColors.Muted, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = vm::skipProxies) { Text("Skip (edit the originals)") }
                    }
                }
                if (state.loading != null) Text(state.loading, Modifier.align(Alignment.TopCenter).padding(8.dp).background(Color(0xAA000000), RoundedCornerShape(6.dp)).padding(6.dp), color = Color.White)
            }
            // Transport
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${clock(posMs)} / ${clock(state.outputMs)}", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = ::togglePlay) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(32.dp))
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = vm::undo, enabled = state.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo", tint = if (state.canUndo) Color.White else Color.DarkGray) }
                    IconButton(onClick = vm::redo, enabled = state.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo", tint = if (state.canRedo) Color.White else Color.DarkGray) }
                }
            }
            // Timeline with the centre playhead; taller when texts stack in rows.
            val textRows = remember(state.plan.texts) { timelineTextRows(state.plan.texts) }
            Box(Modifier.fillMaxWidth().height(116.dp + 26.dp * textRows)) {
                val marks = remember(state.plan.music, state.beatPace, state.outputMs) { vm.beatMarkers(state) }
                EditorTimeline(
                    state = state, thumbs = thumbs, thumbStep = { i -> state.clips.getOrNull(i)?.let(vm::thumbStepMs) ?: 1000L }, beatMarks = marks,
                    posMs = posMs, onScrub = ::scrub,
                    onSelect = { vm.select(it) },
                    onTrim = { id, s, e -> vm.trimPart(id, s, e) },
                    onTransition = { id -> vm.select(Selection.Part(id)); vm.openPanel(Panel.TRANSITION) },
                    onMoveText = { id, s, e -> vm.updateText(id) { it.copy(startMs = s, endMs = e) } },
                    onAddClip = { pickers.fromPhotos(true) },
                    onAddText = { vm.addText(posMs) },
                    onAddMusic = { vm.select(Selection.None); vm.openPanel(Panel.MUSIC_LIBRARY) },
                    onToggleSound = { vm.commit { it.copy(keepOriginalAudio = !it.keepOriginalAudio) } },
                    modifier = Modifier.fillMaxSize(),
                )
                Playhead(Modifier.align(Alignment.TopCenter).fillMaxHeight())
            }
            // Panel or toolbar
            if (stickers) {
                PanelSheet("Stickers", onClose = { stickers = false }) {
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(GridCells.Adaptive(44.dp), Modifier.heightIn(max = 180.dp)) {
                        items(STICKERS) { e ->
                            Text(e, fontSize = 28.sp, modifier = Modifier.clickable { vm.addText(posMs, sticker = e); stickers = false }.padding(6.dp))
                        }
                    }
                }
            } else when (state.panel) {
                Panel.SPEED -> SpeedPanel(state, vm)
                Panel.VOLUME -> VolumePanel(state, vm)
                Panel.FILTERS -> FiltersPanel(state, vm) { asDji -> lutAsDji = asDji; pickLut.launch(arrayOf("*/*")) }
                Panel.ADJUST -> AdjustPanel(state, vm)
                Panel.TRANSITION -> TransitionPanel(state, vm)
                Panel.MOTION -> MotionPanel(state, vm)
                Panel.TEXT -> TextPanel(state, vm)
                Panel.MUSIC_LIBRARY -> MusicLibraryPanel(state, vm, onPickPhoneAudio = { pickMusic.launch(arrayOf("audio/*")) },
                    onClipSound = { i -> state.clips.getOrNull(i)?.let { vm.useLocalAudio(it.uri, fromVideo = true) } })
                Panel.MUSIC -> MusicPanel(state, vm, vm.beatMarkers(state).size)
                Panel.CANVAS -> CanvasPanel(state, vm)
                Panel.QUICK_EDIT -> QuickEditPanel(state, vm)
                Panel.CLAUDE -> ClaudePanel(state, vm, onAddKey = { keyDialog = true },
                    onShareToApp = { withBoard -> scope.launch { shareToClaude(context, vm, withBoard) } },
                    onPasteReply = { vm.applyClaudeReply(clipboard.getText()?.text) })
                Panel.CARDS -> CardsPanel(state, vm)
                Panel.TEMPLATES -> TemplatesPanel(state, vm)
                Panel.EXPORT -> ExportPanel(state, vm)
                null -> Toolbar(state, vm, posMs, onStickers = { stickers = true })
            }
        }
    }

    // Export progress / result
    state.exportProgress?.let { p ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Exporting…") },
            text = { Column { LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth()); Spacer(Modifier.height(8.dp)); Text("$p% · keep GPic open") } },
            confirmButton = {},
            dismissButton = { TextButton(onClick = vm::cancelExport) { Text("Cancel") } },
        )
    }
    state.exported?.let { done ->
        val clipboard = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = vm::dismissExported,
            icon = { Icon(Icons.Default.CheckCircle, null) },
            title = { Text("Saved to Movies/GPic") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.exportedCredits?.let { c ->
                        Text("The music's license asks you to credit it where you share the video:", style = MaterialTheme.typography.bodySmall)
                        Text(c, style = MaterialTheme.typography.bodySmall, color = EditorColors.Accent)
                        TextButton(onClick = { clipboard.setText(AnnotatedString(c)) }) { Text("Copy credit") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(done, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        }) { Text("Play") }
                        OutlinedButton(onClick = {
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, done)
                                .apply { state.exportedCredits?.let { putExtra(Intent.EXTRA_TEXT, it) } }
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
                        }) { Text("Share") }
                    }
                }
            },
            confirmButton = { Button(onClick = { vm.dismissExported(); onBackUp(done) }) { Icon(Icons.Default.CloudUpload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Back up") } },
            dismissButton = { TextButton(onClick = vm::dismissExported) { Text("Done") } },
        )
    }
    if (keyDialog) ClaudeKeyDialog(onDismiss = { keyDialog = false }, onSave = { vm.setClaudeKey(it); keyDialog = false }, onOpenConsole = {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.anthropic.com/settings/keys")))
    })
    if (renameDialog) {
        var name by remember { mutableStateOf(state.projectName) }
        AlertDialog(
            onDismissRequest = { renameDialog = false },
            title = { Text("Project name") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.renameProject(name.trim().ifEmpty { state.projectName }); renameDialog = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renameDialog = false }) { Text("Cancel") } },
        )
    }
}

private class Tool(val label: String, val icon: ImageVector, val enabled: Boolean = true, val onClick: () -> Unit)

/** The bottom tool row: the whole video's tools, or the selected item's. */
@Composable
private fun Toolbar(state: EditorUiState, vm: EditorViewModel, posMs: Long, onStickers: () -> Unit) {
    val part = state.selectedPart
    val text = state.selectedText
    val tools: List<Tool> = when {
        part != null -> {
            val i = state.plan.segments.indexOfFirst { it.id == part.id }
            listOf(
                Tool("Back", Icons.AutoMirrored.Filled.ArrowBack) { vm.select(Selection.None) },
                Tool("Split", Icons.Default.ContentCut) { vm.splitAt(posMs) },
                Tool("Speed", Icons.Default.Speed) { vm.openPanel(Panel.SPEED) },
                Tool("Volume", Icons.AutoMirrored.Filled.VolumeUp) { vm.openPanel(Panel.VOLUME) },
                Tool("Filters", Icons.Default.AutoAwesomeMosaic) { vm.openPanel(Panel.FILTERS) },
                Tool("Adjust", Icons.Default.Tune) { vm.openPanel(Panel.ADJUST) },
                Tool("Transition", Icons.Default.SwapHoriz, enabled = i < state.plan.segments.lastIndex) { vm.openPanel(Panel.TRANSITION) },
                Tool("Zoom", Icons.Default.ZoomIn) { vm.openPanel(Panel.MOTION) },
                Tool("Rotate", Icons.Default.RotateRight) { vm.updatePart(part.id) { it.copy(rotation = (it.rotation + 90) % 360) } },
                Tool("Mirror", Icons.Default.Flip) { vm.updatePart(part.id) { it.copy(mirror = !it.mirror) } },
                Tool("Move left", Icons.Default.ChevronLeft, enabled = i > 0) { vm.movePart(part.id, i - 1) },
                Tool("Move right", Icons.Default.ChevronRight, enabled = i < state.plan.segments.lastIndex) { vm.movePart(part.id, i + 1) },
                Tool("Copy", Icons.Default.ContentCopy) { vm.duplicatePart(part.id) },
                Tool("Delete", Icons.Default.Delete, enabled = state.plan.segments.size > 1) { vm.deletePart(part.id) },
            )
        }
        text != null -> listOf(
            Tool("Back", Icons.AutoMirrored.Filled.ArrowBack) { vm.select(Selection.None) },
            Tool(if (text.sticker) "Style" else "Edit", Icons.Default.Edit) { vm.openPanel(Panel.TEXT) },
            Tool("Copy", Icons.Default.ContentCopy) { vm.duplicateText(text.id) },
            Tool("Delete", Icons.Default.Delete) { vm.deleteText(text.id) },
        )
        state.selection is Selection.Music && state.plan.music != null -> listOf(
            Tool("Back", Icons.AutoMirrored.Filled.ArrowBack) { vm.select(Selection.None) },
            Tool("Volume & fades", Icons.Default.Tune) { vm.openPanel(Panel.MUSIC) },
            Tool("Beats", Icons.Default.GraphicEq) { vm.detectBeats(); vm.openPanel(Panel.MUSIC) },
            Tool("Cut on beats", Icons.Default.ContentCut, enabled = state.plan.music.beatsMs.isNotEmpty()) { vm.snapToBeats() },
            Tool("Replace", Icons.Default.LibraryMusic) { vm.openPanel(Panel.MUSIC_LIBRARY) },
            Tool("Delete", Icons.Default.Delete) { vm.removeMusic() },
        )
        else -> listOf(
            Tool("Templates", Icons.Default.Dashboard) { vm.openPanel(Panel.TEMPLATES) },
            Tool("Edit", Icons.Default.ContentCut) {
                Timeline.locate(state.parts, posMs)?.let { (i, _) -> state.parts.getOrNull(i)?.let { p -> if (p.id > 0) vm.select(Selection.Part(p.id)) } }
            },
            Tool("Audio", Icons.Default.MusicNote) {
                if (state.plan.music != null) { vm.select(Selection.Music); vm.openPanel(Panel.MUSIC) } else vm.openPanel(Panel.MUSIC_LIBRARY)
            },
            Tool("Text", Icons.Default.TextFields) { vm.addText(posMs) },
            Tool("Stickers", Icons.Default.EmojiEmotions, onClick = onStickers),
            Tool("Filters", Icons.Default.AutoAwesomeMosaic) { vm.openPanel(Panel.FILTERS) },
            Tool("Adjust", Icons.Default.Tune) { vm.openPanel(Panel.ADJUST) },
            Tool("Format", Icons.Default.AspectRatio) { vm.openPanel(Panel.CANVAS) },
            Tool("Smart edit", Icons.Default.AutoFixHigh) { vm.openPanel(Panel.QUICK_EDIT) },
            Tool("Cards", Icons.Default.Title) { vm.openPanel(Panel.CARDS) },
            Tool("Claude", Icons.Default.AutoAwesome) { vm.openPanel(Panel.CLAUDE) },
        )
    }
    Row(
        Modifier.fillMaxWidth().background(EditorColors.Panel).horizontalScroll(rememberScrollState()).padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        tools.forEach { t ->
            Column(
                Modifier.width(64.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = t.enabled, onClick = t.onClick).padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val c = if (t.enabled) Color.White else Color.DarkGray
                Icon(t.icon, null, tint = c, modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(3.dp))
                Text(t.label, color = c, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Text and stickers over the preview, drawn with the same renderer as the export. Tap one to select it; drag the
 * selected one to move it (committed when the finger lifts).
 */
@Composable
private fun TextLayer(state: EditorUiState, posMs: Long, vm: EditorViewModel) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val cache = remember { HashMap<Triple<TextItem, IntSize, Int>, TextRender.Drawn>() }
    var drag by remember { mutableStateOf(Offset.Zero) }
    val current by rememberUpdatedState(state)
    val pos by rememberUpdatedState(posMs)
    fun drawn(t: TextItem, chars: Int): TextRender.Drawn? {
        if (size.width <= 0) return null
        val key = Triple(t, size, chars)
        return cache.getOrPut(key) {
            if (cache.size > 80) cache.clear()
            TextRender.draw(t, size.width, size.height, chars)
        }
    }
    fun hit(o: Offset): TextItem? = current.plan.texts.lastOrNull { t ->
        val st = TextAnimMath.at(t, pos)
        if (!st.visible) return@lastOrNull false
        val d = drawn(t, t.text.length) ?: return@lastOrNull false
        kotlin.math.abs(o.x - d.cx) < d.bitmap.width / 2f + 20 && kotlin.math.abs(o.y - d.cy) < d.bitmap.height / 2f + 20
    }
    Canvas(
        Modifier.fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { o ->
                    val t = hit(o)
                    if (t != null) vm.select(Selection.Text(t.id)) else if (current.selection is Selection.Text) vm.select(Selection.None)
                })
            }
            .pointerInput(Unit) {
                var target: TextItem? = null
                detectDragGestures(
                    onDragStart = { o -> target = hit(o)?.takeIf { (current.selection as? Selection.Text)?.id == it.id }; drag = Offset.Zero },
                    onDragEnd = {
                        val t = target
                        if (t != null && size.width > 0) vm.updateText(t.id) { it.copy(x = (t.x + drag.x / size.width).coerceIn(0f, 1f), y = (t.y + drag.y / size.height).coerceIn(0f, 1f)) }
                        target = null; drag = Offset.Zero
                    },
                    onDragCancel = { target = null; drag = Offset.Zero },
                ) { ch, d -> if (target != null) { ch.consume(); drag += d } }
            },
    ) {
        state.plan.texts.forEach { t ->
            val st = TextAnimMath.at(t, posMs)
            val selected = (state.selection as? Selection.Text)?.id == t.id
            if (!st.visible && !selected) return@forEach
            // The one being edited shows in full, even at a moment its animation hides it.
            val d = drawn(t, if (st.visible && !selected) st.chars else t.text.length) ?: return@forEach
            val off = if (selected) drag else Offset.Zero
            val w = d.bitmap.width * st.scale
            val h = d.bitmap.height * st.scale
            val x = d.cx - w / 2 + off.x
            val y = d.cy - h / 2 + off.y + st.dy * size.height
            drawImage(d.bitmap.asImageBitmap(), dstOffset = IntOffset(x.roundToInt(), y.roundToInt()), dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)),
                alpha = when { !st.visible -> 0.35f; selected -> maxOf(st.alpha, 0.7f); else -> st.alpha })
            if (selected) drawRect(EditorColors.Accent, topLeft = Offset(x - 4, y - 4), size = androidx.compose.ui.geometry.Size(w + 8, h + 8),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
        }
    }
}

/** The Claude app's package (Play Store build). */
private const val CLAUDE_APP = "com.anthropic.claude"

/**
 * Opens the Claude app (or the share menu) with the storyboard image(s). The Claude app keeps shared images but
 * drops shared text when images come along, so the prompt goes on the clipboard to paste into the message box.
 */
private suspend fun shareToClaude(context: android.content.Context, vm: EditorViewModel, withStoryboard: Boolean) {
    val (prompt, images) = vm.prepareClaudeShare(withStoryboard)
    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
    cm?.setPrimaryClip(android.content.ClipData.newPlainText("GPic prompt", prompt))
    val send = when {
        images.size > 1 -> Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/jpeg"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(images))
        }
        images.size == 1 -> Intent(Intent.ACTION_SEND).apply { type = "image/jpeg"; putExtra(Intent.EXTRA_STREAM, images[0]) }
        else -> Intent(Intent.ACTION_SEND).apply { type = "text/plain" }
    }.apply {
        putExtra(Intent.EXTRA_TEXT, prompt)
        if (images.isNotEmpty()) {
            clipData = android.content.ClipData.newRawUri("storyboard", images[0]).apply { images.drop(1).forEach { addItem(android.content.ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    val direct = Intent(send).setPackage(CLAUDE_APP)
    val intent = if (direct.resolveActivity(context.packageManager) != null) direct else Intent.createChooser(send, "Send to Claude")
    runCatching { context.startActivity(intent) }.onFailure { vm.toast("Couldn't open Claude; the prompt is copied, paste it into Claude yourself.") }
    vm.toast(if (images.isNotEmpty()) "Prompt copied: in Claude, long-press the message box and Paste, then send." else "Prompt copied too, in case Claude opens without it.")
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
