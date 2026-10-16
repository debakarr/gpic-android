package com.gpic.android.ui.screens.home

import android.Manifest
import android.app.Activity
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.text.input.KeyboardType
import com.gpic.android.data.cleanup.AgeRule
import com.gpic.android.data.cleanup.AgeUnit
import com.gpic.android.data.dji.MediaKind
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.gpic.android.camera.CameraState
import com.gpic.android.camera.FoundCamera
import com.gpic.android.data.auth.AppConfig
import com.gpic.android.data.progress.UploadStatus
import com.gpic.android.ui.components.StatusCard
import com.gpic.android.ui.components.StatusCardState
import com.gpic.android.ui.components.SystemStatsCard
import com.gpic.android.util.formatSize

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onNavigateAuth: () -> Unit,
    onNavigateSettings: () -> Unit,
    onNavigateAlbums: () -> Unit = {},
    onNavigateEditor: () -> Unit = {},
    albumSuggestions: Int = 0,
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val busy = state.uploadRunning

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) vm.setDjiUri(uri)
    }
    val filesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) vm.setDjiFileUris(uris)
    }
    val cleanupFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) vm.setCleanupFolder(uri)
    }

    // Without media access Android strips GPS from photos it hands GPic, so uploads lose their location.
    val mediaPerms = remember { mediaPermissions() }
    fun mediaGranted() = mediaPerms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    var mediaAccess by remember { mutableStateOf(mediaGranted()) }
    // Back from app settings (or a grant elsewhere): re-check so the banner and delete rules update.
    LifecycleResumeEffect(Unit) {
        mediaAccess = mediaGranted()
        onPauseOrDispose {}
    }

    // Which picker to open once the permission prompt closes (saveable: the dialog can outlive a rotation).
    var pendingPick by rememberSaveable { mutableStateOf<String?>(null) }
    val mediaPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        mediaAccess = mediaGranted()
        when (pendingPick) {
            "folder" -> folderPicker.launch(null)
            "files" -> filesPicker.launch(arrayOf("image/*", "video/*"))
            "cleanup" -> cleanupFolderPicker.launch(null)
        }
        pendingPick = null
    }
    val prefs = remember { context.getSharedPreferences("gpic_prefs", Context.MODE_PRIVATE) }
    // After repeated "Don't allow" Android stops showing the prompt and the request returns denied at once.
    fun promptBlocked(): Boolean {
        val activity = context as? Activity ?: return false
        return prefs.getBoolean("media_perm_asked", false) &&
            mediaPerms.none { activity.shouldShowRequestPermissionRationale(it) }
    }
    fun requestMedia() {
        prefs.edit().putBoolean("media_perm_asked", true).apply()
        mediaPermLauncher.launch(mediaPerms)
    }
    /** Ask for media access once, then run the picker either way (uploads still work without it). */
    fun pick(what: String, launch: () -> Unit) {
        // A blocked prompt can't be answered; don't make every pick go through a no-op request.
        if (mediaAccess || promptBlocked()) launch() else { pendingPick = what; requestMedia() }
    }
    var confirmCleanup by remember { mutableStateOf(false) }
    // Upload proceeds either way; the permission only controls whether progress shows in the shade.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.startUpload() }
    val startUpload = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else vm.startUpload()
    }

    // Bluetooth (find + pair) and nearby-WiFi (join the camera's network) for camera-over-WiFi.
    val cameraPerms = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT) }
            else add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }.toTypedArray()
    }
    var permDenied by remember { mutableStateOf(false) }
    val cameraPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        permDenied = res.values.any { !it }
        if (!permDenied) vm.cameraLink?.startScan()
    }
    val startCameraScan: () -> Unit = {
        if (cameraPerms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) vm.cameraLink?.startScan()
        else cameraPermLauncher.launch(cameraPerms)
    }

    // Back would finish the Activity and clear the ViewModel mid-upload; background the task instead.
    BackHandler(enabled = busy) { (context as? Activity)?.moveTaskToBack(true) }

    LaunchedEffect(Unit) { vm.refreshAuth(); vm.refreshUsb() }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val scrolledDown by remember { derivedStateOf { listState.firstVisibleItemIndex > 2 } }
    val toTop: () -> Unit = { scope.launch { listState.animateScrollToItem(0) } }
    var statsExpanded by rememberSaveable { mutableStateOf(false) }
    // After a run: narrow a long list to what needs attention.
    var listFilter by rememberSaveable { mutableStateOf(ListFilter.ALL) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // While uploading the subtitle is a live status line; tapping it jumps back to the progress card.
                    Column(if (busy) Modifier.clickable(onClick = toTop) else Modifier) {
                        Text("GPic")
                        val done = state.completed + state.failed + state.skipped + state.rejected
                        Text(
                            if (busy) "${if (state.cancelling) "Cancelling" else "Uploading"} $done of ${state.totalFiles}" +
                                (if (state.failed > 0) " • ${state.failed} failed" else "")
                            else "Camera → Google Photos",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (busy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateEditor) { Icon(Icons.Default.MovieCreation, "Video editor") }
                    IconButton(onClick = onNavigateAlbums) {
                        BadgedBox(badge = { if (albumSuggestions > 0) Badge { Text("$albumSuggestions") } }) {
                            Icon(Icons.Default.PhotoAlbum, "Album suggestions")
                        }
                    }
                    IconButton(onClick = onNavigateSettings) { Icon(Icons.Default.Settings, "Settings") }
                }
            )
        },
        floatingActionButton = {
          Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AnimatedVisibility(visible = scrolledDown, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                SmallFloatingActionButton(onClick = toTop) { Icon(Icons.Default.KeyboardArrowUp, "Go to top") }
            }
            val ready = state.files.isNotEmpty() && !busy && state.hasAuth && state.authProblem == null &&
                state.cleanup.progress.let { it == null || state.source != Source.CLEANUP }
            // After a run, offer only what's left to do: a fresh "Upload all" would just re-check every file.
            val ran = state.progressMap.isNotEmpty()
            val retryable = if (ran) state.files.count { ListFilter.FAILED.matches(state.progressMap[it.key]?.status) } else 0
            if (ready && ran && retryable > 0) {
                ExtendedFloatingActionButton(
                    onClick = { vm.retryFailed() },
                    icon = { Icon(Icons.Default.Refresh, null) },
                    text = { Text("Retry ${plural(retryable, "file")}") },
                )
            }
            if (ready && !ran) {
                val cleanup = state.source == Source.CLEANUP
                ExtendedFloatingActionButton(
                    onClick = { if (cleanup) confirmCleanup = true else startUpload() },
                    icon = { Icon(if (cleanup) Icons.Default.CleaningServices else Icons.Default.CloudUpload, null) },
                    text = { Text("${if (cleanup) "Back up & delete" else "Upload"} ${state.files.size} · ${formatSize(state.filesBytes)}") }
                )
            }
          }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 1. Photos auth status – most important to user
            item(key = "auth") {
                if (state.hasAuth && state.authProblem != null) {
                    StatusCard(
                        title = "Re-link Google Photos",
                        subtitle = "${state.authEmail}: ${state.authProblem}",
                        icon = Icons.Default.Error, status = StatusCardState.ERROR,
                        actionLabel = "Fix", onAction = onNavigateAuth,
                    )
                } else if (state.hasAuth) {
                    StatusCard(
                        title = state.authEmail ?: "Google Photos",
                        subtitle = "Google Photos linked • token refreshed automatically",
                        icon = Icons.Default.VerifiedUser, status = StatusCardState.OK,
                        actionLabel = "Change", onAction = onNavigateAuth,
                    )
                } else {
                    StatusCard(
                        title = "Google Photos not linked",
                        subtitle = "Paste the photos.native auth line to start backing up",
                        icon = Icons.Default.AccountCircle, status = StatusCardState.WARN,
                        actionLabel = "Link", onAction = onNavigateAuth,
                    )
                }
            }

            // 1b. Batch progress, near the top so it stays one tap (or the go-to-top button) away
            if (busy || state.completed > 0 || state.failed > 0 || state.skipped > 0 || state.rejected > 0) {
                item(key = "progress") {
                    ProgressSection(
                        state = state,
                        onCancel = { vm.cancelUpload() },
                        onCopyErrors = {
                            val sb = StringBuilder()
                            sb.append("GPic upload errors: ${state.completed} done, ${state.skipped} skipped, ${state.failed} failed of ${state.totalFiles}\n")
                            state.progressMap.values.filter { it.status == UploadStatus.ERROR }.forEach { fp ->
                                sb.append("FAIL ${fp.fileName} [${fp.status}] ${fp.message} (err=${fp.error})\n")
                            }
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("gpic-errors", sb.toString()))
                            Toast.makeText(context, "Copied ${state.failed} errors — paste it here", Toast.LENGTH_LONG).show()
                        },
                    )
                }
            }

            // 2. Source: phone/USB, free up space, or camera over WiFi
            item(key = "source-switch") {
                val sources = Source.entries.filter { it != Source.CAMERA || state.cameraSupported }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    sources.forEachIndexed { i, src ->
                        SegmentedButton(
                            selected = state.source == src,
                            onClick = { vm.selectSource(src) },
                            enabled = !busy,
                            shape = SegmentedButtonDefaults.itemShape(i, sources.size),
                            // Three labels don't fit beside icons on a 360 dp screen.
                            icon = {},
                        ) { Text(src.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
            if (state.source == Source.CLEANUP) item(key = "cleanup") {
                CleanupSection(
                    state = state,
                    mediaAccess = mediaAccess,
                    enabled = !busy,
                    onPickFolder = { pick("cleanup") { cleanupFolderPicker.launch(null) } },
                    onRule = vm::setCleanupRule,
                    onRecursive = vm::setCleanupRecursive,
                    onRescan = vm::scanCleanup,
                    onAllowMedia = {
                        if (promptBlocked()) runCatching {
                            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                            Toast.makeText(context, "Permissions → Photos and videos → Allow", Toast.LENGTH_LONG).show()
                        } else requestMedia()
                    },
                )
            } else if (state.source == Source.CAMERA) item(key = "camera") {
                CameraSection(
                    state = state,
                    permDenied = permDenied,
                    onScan = startCameraScan,
                    onConnect = { vm.cameraLink?.connect(it) },
                    onDisconnect = { vm.cameraLink?.disconnect() },
                )
            } else item(key = "source") {
                SourceSection(
                    state = state,
                    enabled = !busy,
                    onPickFolder = {
                        if (state.djiSource == "folder" && state.djiFiles.isNotEmpty()) vm.rescan()
                        else pick("folder") { folderPicker.launch(null) }
                    },
                    onPickFiles = { pick("files") { filesPicker.launch(arrayOf("image/*", "video/*")) } },
                    onClear = { vm.clearSelection() },
                )
            }

            // 2b. File types to include (any source)
            if (state.djiFiles.isNotEmpty()) {
                item(key = "type-filter") {
                    TypeFilterRow(state, enabled = !busy, onToggle = vm::toggleType)
                }
            }

            // 3. Settings that will apply to the next upload
            if (state.files.isNotEmpty()) {
                item(key = "settings-summary") {
                    SettingsSummary(state.config, deletes = state.config.deleteAfterUpload || state.source == Source.CLEANUP, enabled = !busy, onEdit = onNavigateSettings)
                }
            }

            // 4b. Morphe-style live CPU / RAM / network during upload
            if (busy || state.systemStats != null) {
                item(key = "stats") {
                    SystemStatsCard(
                        stats = state.systemStats,
                        doneCount = state.completed + state.failed + state.skipped + state.rejected,
                        totalFiles = state.totalFiles,
                        isUploading = busy,
                        expanded = statsExpanded,
                        onToggle = { statsExpanded = !statsExpanded },
                    )
                }
            }

            // 5. Tips card
            if (state.djiFiles.isEmpty() && !state.isScanning && state.source == Source.DEVICE) {
                item(key = "tips") {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("How it works", style = MaterialTheme.typography.titleSmall)
                            Text("1. Plug the camera in over USB-C. Android can't auto-mount it, so pick its DCIM folder once (or pick individual files).", style = MaterialTheme.typography.bodySmall)
                            Text("2. GPic finds JPG / DNG / MP4 / MOV etc. and hashes each file to skip ones already in your library.", style = MaterialTheme.typography.bodySmall)
                            Text("3. Each file shows live status: hashing → checking → uploading → done / already backed up / failed.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // 6. Per-file list (first 30 until an upload starts, then all with live status)
            if (state.files.isNotEmpty()) {
                val ran = state.progressMap.isNotEmpty()
                val counts = ListFilter.entries.associateWith { f -> state.files.count { f.matches(state.progressMap[it.key]?.status) } }
                val filter = if (ran) listFilter else ListFilter.ALL
                item(key = "files-header") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val of = if (state.files.size != state.djiFiles.size) " of ${state.djiFiles.size}" else ""
                        Text("Files (${state.files.size}$of)", style = MaterialTheme.typography.titleSmall)
                        if (ran) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ListFilter.entries.forEach { f ->
                                FilterChip(
                                    selected = filter == f,
                                    onClick = { listFilter = f },
                                    label = { Text("${f.label} ${counts[f]}") },
                                )
                            }
                        }
                    }
                }
                val toShow = if (!ran) state.files.take(30) else state.files.filter { filter.matches(state.progressMap[it.key]?.status) }
                items(toShow, key = { it.key }) { f ->
                    FileRow(f.displayName, f.sizeBytes, state.progressMap[f.key], takenMs = f.takenMs)
                }
                if (state.files.size > 30 && state.progressMap.isEmpty()) {
                    item(key = "files-more") {
                        Text("…and ${state.files.size - 30} more. Upload will show each file's live status.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            item(key = "fab-spacer") { Spacer(Modifier.height(80.dp)) }
        }
    }

    if (confirmCleanup) {
        AlertDialog(
            onDismissRequest = { confirmCleanup = false },
            icon = { Icon(Icons.Default.DeleteSweep, null) },
            title = { Text("Back up, then delete from phone?") },
            text = {
                Text(
                    "GPic uploads ${plural(state.files.size, "file")} (${formatSize(state.filesBytes)}) taken before " +
                        "${formatDate(state.cleanup.cutoffMs)} to ${state.authEmail ?: "Google Photos"}, then deletes each one " +
                        "from this phone once Google Photos has it. Anything that fails to upload is kept. " +
                        "Copies in Google Photos are never touched."
                )
            },
            confirmButton = {
                Button(onClick = { confirmCleanup = false; startUpload() }) { Text("Back up & delete") }
            },
            dismissButton = { TextButton(onClick = { confirmCleanup = false }) { Text("Cancel") } },
        )
    }
}

private fun mediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.ACCESS_MEDIA_LOCATION,
    )
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION,
    )
    else -> emptyArray() // nothing is redacted before Android 10
}

private fun formatDate(ms: Long): String =
    if (ms <= 0) "—" else java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(ms))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CleanupSection(
    state: HomeUiState,
    mediaAccess: Boolean,
    enabled: Boolean,
    onPickFolder: () -> Unit,
    onRule: (AgeRule) -> Unit,
    onRecursive: (Boolean) -> Unit,
    onRescan: () -> Unit,
    onAllowMedia: () -> Unit,
) {
    val c = state.cleanup
    val title = when {
        c.folderUri == null -> "Free up space"
        c.progress != null -> "Scanning…"
        c.error != null -> "Can't read the folder"
        state.files.isEmpty() && state.djiFiles.isNotEmpty() -> "Nothing older than ${c.rule.description} of the chosen types"
        state.files.isEmpty() -> "Nothing older than ${c.rule.description}"
        else -> "${plural(state.files.size, "file")} • ${formatSize(state.filesBytes)} to free"
    }
    val subtitle = when {
        c.folderUri == null -> "Back up photos and videos older than a set age from one folder, then delete them from this phone. Google Photos keeps its copies."
        c.progress != null -> c.progress
        c.error != null -> c.error
        c.inFolder == 0 -> "No photos or videos in ${c.folderName} — it's empty, or was moved or deleted."
        else -> "Taken before ${formatDate(c.cutoffMs)}, out of ${plural(c.inFolder, "file")} in ${c.folderName}. " +
            "Photo dates come from EXIF when the file has it, otherwise the file date."
    }
    val cardState = when {
        c.error != null -> StatusCardState.ERROR
        state.files.isNotEmpty() && c.progress == null -> StatusCardState.OK
        else -> StatusCardState.NEUTRAL
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusCard(title = title, subtitle = subtitle, icon = Icons.Default.CleaningServices, status = cardState)
        if (c.progress != null) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onPickFolder, enabled = enabled, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(c.folderName ?: "Choose folder", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (c.folderUri != null) {
                IconButton(onClick = onRescan, enabled = enabled && c.progress == null) { Icon(Icons.Default.Refresh, "Rescan folder") }
            }
        }
        // Local text so the field can be briefly empty while typing; the rule only takes valid numbers.
        var amountText by rememberSaveable(c.rule.amount) { mutableStateOf(c.rule.amount.toString()) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Older than", style = MaterialTheme.typography.bodyLarge)
            OutlinedTextField(
                value = amountText,
                onValueChange = { v ->
                    val digits = v.filter(Char::isDigit).take(AgeRule.MAX_AMOUNT.toString().length)
                    amountText = digits
                    digits.toIntOrNull()?.let { onRule(c.rule.copy(amount = it)) }
                },
                enabled = enabled,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(96.dp),
            )
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            AgeUnit.entries.forEachIndexed { i, u ->
                SegmentedButton(
                    selected = c.rule.unit == u,
                    onClick = { onRule(c.rule.copy(unit = u)) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(i, AgeUnit.entries.size),
                    icon = {},
                ) { Text(u.label, maxLines = 1) }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Include subfolders", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = c.recursive, onCheckedChange = onRecursive, enabled = enabled)
        }
        if (!mediaAccess) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Allow photo and video access so GPic can read locations. Without it Android hides them, so anything that may have a location is uploaded but kept on the phone (JPEGs without one are still deleted).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onAllowMedia) { Text("Allow") }
                }
            }
        }
    }
}

@Composable
private fun SourceSection(
    state: HomeUiState,
    enabled: Boolean,
    onPickFolder: () -> Unit,
    onPickFiles: () -> Unit,
    onClear: () -> Unit,
) {
    val title = when {
        state.isScanning -> "Scanning…"
        state.files.isNotEmpty() -> "${plural(state.files.size, "file")} • ${formatSize(state.filesBytes)}"
        state.djiFiles.isNotEmpty() -> "All ${plural(state.djiFiles.size, "file")} hidden by the type filter"
        state.djiUri != null || state.djiSource == "files" -> "Selection ready"
        else -> "Choose what to back up"
    }
    val subtitle = when {
        state.isScanning -> if (state.djiSource == "files") "Reading selected files…" else "Reading DCIM folder over USB-C…"
        state.files.isNotEmpty() -> {
            val photos = state.files.count { it.isPhoto }
            val videos = state.files.count { it.isVideo }
            val src = when {
                state.djiSource == "files" -> "selected files"
                state.djiUri?.authority == com.gpic.android.data.upload.UriSource.MTP_AUTHORITY -> "the camera"
                else -> "the chosen folder"
            }
            "${plural(photos, "photo")} • ${plural(videos, "video")} • from $src"
        }
        state.djiFiles.isNotEmpty() -> {
            val kinds = state.typeFilter.joinToString(" / ") { it.label.lowercase() }
            "None of them are $kinds. Turn on more types below."
        }
        state.usbConnected -> "${state.usbStatus}\nNow pick the DCIM folder or individual files"
        else -> "Plug the camera in over USB-C, or pick files already on this phone"
    }
    val cardState = when {
        state.files.isNotEmpty() -> StatusCardState.OK
        state.usbConnected -> StatusCardState.WARN
        else -> StatusCardState.NEUTRAL
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusCard(title = title, subtitle = subtitle, icon = Icons.Default.Videocam, status = cardState)
        if (state.isScanning) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val rescan = state.djiSource == "folder" && state.djiFiles.isNotEmpty()
            OutlinedButton(onClick = onPickFolder, enabled = enabled && !state.isScanning, modifier = Modifier.weight(1f)) {
                Icon(if (rescan) Icons.Default.Refresh else Icons.Default.FolderOpen, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (rescan) "Rescan folder" else "Choose folder")
            }
            OutlinedButton(onClick = onPickFiles, enabled = enabled && !state.isScanning, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.FileOpen, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Choose files")
            }
        }
        if (state.djiFiles.isNotEmpty()) {
            TextButton(onClick = onClear, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Clear selection") }
        }
        state.scanNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (state.scanError != null) {
            Text("Scan error: ${state.scanError}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** File-list view after an upload starts. */
private enum class ListFilter(val label: String) {
    ALL("All"), ACTIVE("Not done"), FAILED("Failed");

    fun matches(status: UploadStatus?): Boolean = when (this) {
        ALL -> true
        ACTIVE -> status !in setOf(UploadStatus.COMPLETED, UploadStatus.SKIPPED, UploadStatus.REJECTED, UploadStatus.ERROR, UploadStatus.CANCELLED)
        FAILED -> status == UploadStatus.ERROR || status == UploadStatus.CANCELLED
    }
}

@Composable
private fun TypeFilterRow(state: HomeUiState, enabled: Boolean, onToggle: (MediaKind) -> Unit) {
    val counts = state.djiFiles.groupingBy { it.kind }.eachCount()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        MediaKind.entries.forEach { kind ->
            val on = kind in state.typeFilter
            FilterChip(
                selected = on,
                onClick = { onToggle(kind) },
                // The VM ignores switching off the last type (it would select nothing); keep it looking on.
                enabled = enabled,
                label = { Text("${kind.label} ${counts[kind] ?: 0}") },
                leadingIcon = {
                    Icon(
                        if (on) Icons.Default.Check
                        else when (kind) { MediaKind.PHOTO -> Icons.Default.Image; MediaKind.RAW -> Icons.Default.RawOn; MediaKind.VIDEO -> Icons.Default.Videocam },
                        null, Modifier.size(18.dp),
                    )
                },
            )
        }
    }
}

@Composable
private fun SettingsSummary(config: AppConfig, deletes: Boolean, enabled: Boolean, onEdit: () -> Unit) {
    val quality = when {
        config.useQuota -> "Original • counts against storage"
        config.saverMode -> "Storage saver"
        else -> "Original quality"
    }
    val threads = if (config.uploadThreads <= 0) "auto parallel" else "${config.uploadThreads} at a time"
    val dedup = if (config.forceUpload) "re-upload duplicates" else "skip duplicates"
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Tune, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(quality, style = MaterialTheme.typography.bodyMedium)
                Text("$threads • $dedup", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (deletes) {
                    Text("Deletes originals after upload", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            TextButton(onClick = onEdit, enabled = enabled) { Text("Edit") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProgressSection(state: HomeUiState, onCancel: () -> Unit, onCopyErrors: () -> Unit) {
    val done = state.completed + state.failed + state.skipped + state.rejected
    // Byte-weighted: with smallest-first ordering a file count races ahead of the real transfer.
    val sentBytes = state.progressMap.values.sumOf { it.settledBytes }
    val totalBytes = state.progressMap.values.sumOf { it.totalBytes }.takeIf { it > 0 } ?: state.filesBytes
    val fraction = if (totalBytes > 0) (sentBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    val heading = when {
        state.cancelling -> "Cancelling…"
        state.uploadRunning -> "Uploading"
        state.wasCancelled -> "Cancelled"
        state.failed > 0 -> "Finished with errors"
        else -> "Upload complete"
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(heading, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("$done / ${state.totalFiles}", style = MaterialTheme.typography.titleMedium)
            }
            LinearProgressIndicator(progress = { if (state.uploadRunning) fraction else 1f }, modifier = Modifier.fillMaxWidth())
            if (state.uploadRunning) {
                Text("${formatSize(sentBytes)} of ${formatSize(totalBytes)}", style = MaterialTheme.typography.bodySmall)
            }
            state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (state.completed > 0 || done == 0) CountChip(Icons.Default.CheckCircle, "${state.completed} uploaded", MaterialTheme.colorScheme.primary)
                if (state.skipped > 0) CountChip(Icons.Default.Verified, "${state.skipped} already there", MaterialTheme.colorScheme.secondary)
                if (state.relocated > 0) CountChip(Icons.Default.LocationOn, "${state.relocated} re-uploaded with location", MaterialTheme.colorScheme.tertiary)
                if (state.deleted > 0) CountChip(Icons.Default.DeleteSweep, "${state.deleted} deleted from phone", MaterialTheme.colorScheme.secondary)
                if (state.rejected > 0) CountChip(Icons.Default.Block, "${state.rejected} not accepted by Google Photos", MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.failed > 0) CountChip(Icons.Default.Error, "${state.failed} failed", MaterialTheme.colorScheme.error)
            }
            if (state.uploadRunning) {
                OutlinedButton(onClick = onCancel, enabled = !state.cancelling, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Stop, null); Spacer(Modifier.width(8.dp))
                    Text(if (state.cancelling) "Stopping current transfers…" else "Cancel")
                }
            }
            if (state.failed > 0) {
                OutlinedButton(onClick = onCopyErrors, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(8.dp)); Text("Copy ${state.failed} errors")
                }
            }
        }
    }
}

@Composable
private fun CountChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun FileRow(name: String, size: Long, prog: com.gpic.android.data.progress.FileProgress?, takenMs: Long = 0) {
    val ctx = LocalContext.current
    val active = prog != null && prog.status in setOf(
        UploadStatus.PREPARING, UploadStatus.HASHING, UploadStatus.CHECKING,
        UploadStatus.UPLOADING, UploadStatus.RESUMING, UploadStatus.COMMITTING,
    )
    Card(modifier = Modifier.fillMaxWidth()) {
        // Compact rows: a long batch should fit ~12 per screen, not 8.
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when {
                        prog == null -> Icons.Default.Image
                        prog.status == UploadStatus.COMPLETED -> Icons.Default.CheckCircle
                        prog.status == UploadStatus.SKIPPED -> Icons.Default.Verified
                        prog.status == UploadStatus.ERROR -> Icons.Default.Error
                        prog.status == UploadStatus.CANCELLED || prog.status == UploadStatus.REJECTED -> Icons.Default.Block
                        prog.status == UploadStatus.UPLOADING || prog.status == UploadStatus.RESUMING -> Icons.Default.CloudUpload
                        else -> Icons.Default.HourglassTop
                    },
                    contentDescription = null,
                    tint = when (prog?.status) {
                        UploadStatus.COMPLETED -> MaterialTheme.colorScheme.primary
                        UploadStatus.SKIPPED -> MaterialTheme.colorScheme.secondary
                        UploadStatus.ERROR -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    SelectionContainer {
                        Text(
                            listOfNotNull(
                                formatSize(size),
                                takenMs.takeIf { it > 0 }?.let { "taken ${formatDate(it)}" },
                                prog?.detailLine ?: "Queued",
                            ).joinToString(" • "),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (prog?.status == UploadStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (active) {
                    Text("${prog!!.percentage.toInt()}%", style = MaterialTheme.typography.labelMedium)
                }
                if (prog != null && prog.status == UploadStatus.ERROR) {
                    IconButton(onClick = {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("gpic-error", "FAIL $name [${prog.status}] ${prog.message} (err=${prog.error})"))
                        Toast.makeText(ctx, "Error copied — paste it here", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy error")
                    }
                }
            }
            if (active) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { prog!!.percentage / 100f }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

@Composable
private fun CameraSection(
    state: HomeUiState,
    permDenied: Boolean,
    onScan: () -> Unit,
    onConnect: (FoundCamera) -> Unit,
    onDisconnect: () -> Unit,
) {
    val busy = state.uploadRunning
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val cs = state.camera) {
            CameraState.Idle -> {
                StatusCard(
                    title = "Connect to a DJI camera",
                    subtitle = "Turn the camera on (screen awake). GPic pairs over Bluetooth, joins the camera's WiFi and lists its files.",
                    icon = Icons.Default.Wifi, status = StatusCardState.NEUTRAL,
                )
                Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.BluetoothSearching, null); Spacer(Modifier.width(8.dp)); Text("Find camera")
                }
            }
            is CameraState.Scanning -> {
                StatusCard(
                    title = if (cs.active) "Looking for cameras…" else if (cs.found.isEmpty()) "No camera found" else "Pick your camera",
                    subtitle = if (cs.found.isEmpty()) "Make sure the camera is on and its screen is awake, and keep it next to the phone." else "Tap Connect, then approve the pairing on the camera's screen.",
                    icon = Icons.Default.BluetoothSearching, status = StatusCardState.NEUTRAL,
                )
                if (cs.active) LinearProgressIndicator(Modifier.fillMaxWidth())
                cs.found.forEach { cam ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Videocam, null)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(cam.name, style = MaterialTheme.typography.bodyLarge)
                                Text("${cam.model.name} • signal ${cam.rssi} dBm", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            FilledTonalButton(onClick = { onConnect(cam) }) { Text("Connect") }
                        }
                    }
                }
                if (!cs.active) OutlinedButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text("Scan again") }
            }
            is CameraState.Connecting -> {
                StatusCard(title = cs.camera.name, subtitle = cs.step, icon = Icons.Default.Sync, status = StatusCardState.WARN)
                LinearProgressIndicator(progress = { cs.progress }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
            is CameraState.AwaitingApproval -> {
                StatusCard(
                    title = "Approve on the camera",
                    subtitle = "${cs.camera.name} is asking to pair. Tap the check mark on the camera's screen.",
                    icon = Icons.Default.TouchApp, status = StatusCardState.WARN,
                )
                TextButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
            is CameraState.Ready -> {
                val mode = if (state.config.cameraStream) "Streams straight to Google Photos" else "Downloads to the phone, then uploads"
                StatusCard(
                    title = "${cs.camera.name} • ${plural(state.files.size, "file")}",
                    subtitle = "${formatSize(state.filesBytes)} • $mode",
                    icon = Icons.Default.Videocam, status = StatusCardState.OK,
                    actionLabel = if (busy) null else "Disconnect", onAction = onDisconnect,
                )
                if (state.config.cameraStream && !cs.hasInternet) {
                    Text("This phone has no internet while on the camera's WiFi (no mobile data), so files will be downloaded first.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
                if (state.config.deleteAfterUpload) {
                    Text("Delete after upload doesn't apply to camera WiFi yet; files stay on the camera.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            is CameraState.Failed -> {
                StatusCard(title = "Camera connection failed", subtitle = cs.message, icon = Icons.Default.Error, status = StatusCardState.ERROR)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    cs.camera?.let { cam -> Button(onClick = { onConnect(cam) }, modifier = Modifier.weight(1f)) { Text("Retry") } }
                    OutlinedButton(onClick = onScan, modifier = Modifier.weight(1f)) { Text("Scan again") }
                }
            }
        }
        // Staged files left from an interrupted run, with the camera already disconnected.
        if (state.camera !is CameraState.Ready && state.files.isNotEmpty()) {
            Text("${plural(state.files.size, "file")} downloaded from the camera and waiting to upload.", style = MaterialTheme.typography.bodySmall)
        }
        if (permDenied) {
            Text("GPic needs Nearby devices permission to find and pair with the camera.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (!busy) state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
