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
import androidx.compose.foundation.layout.*
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onNavigateAuth: () -> Unit,
    onNavigateSettings: () -> Unit,
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("GPic")
                        Text("Camera → Google Photos", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateSettings) { Icon(Icons.Default.Settings, "Settings") }
                }
            )
        },
        floatingActionButton = {
            if (state.djiFiles.isNotEmpty() && !busy && state.hasAuth && state.authProblem == null) {
                ExtendedFloatingActionButton(
                    onClick = startUpload,
                    icon = { Icon(Icons.Default.CloudUpload, null) },
                    text = { Text("Upload ${state.djiFiles.size} · ${formatSize(state.totalBytes)}") }
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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

            // 2. Source: phone/USB or camera over WiFi
            if (state.cameraSupported) {
                item(key = "source-switch") {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        Source.entries.forEachIndexed { i, src ->
                            SegmentedButton(
                                selected = state.source == src,
                                onClick = { vm.selectSource(src) },
                                enabled = !busy,
                                shape = SegmentedButtonDefaults.itemShape(i, Source.entries.size),
                                icon = { Icon(if (src == Source.DEVICE) Icons.Default.Usb else Icons.Default.Wifi, null, Modifier.size(18.dp)) },
                            ) { Text(if (src == Source.DEVICE) "Phone / USB" else "Camera WiFi") }
                        }
                    }
                }
            }
            if (state.source == Source.CAMERA) item(key = "camera") {
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
                        else folderPicker.launch(null)
                    },
                    onPickFiles = { filesPicker.launch(arrayOf("image/*", "video/*")) },
                    onClear = { vm.clearSelection() },
                )
            }

            // 3. Settings that will apply to the next upload
            if (state.djiFiles.isNotEmpty()) {
                item(key = "settings-summary") {
                    SettingsSummary(state.config, enabled = !busy, onEdit = onNavigateSettings)
                }
            }

            // 4. Batch progress
            if (busy || state.completed > 0 || state.failed > 0 || state.skipped > 0) {
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

            // 4b. Morphe-style live CPU / RAM / network during upload
            if (busy || state.systemStats != null) {
                item(key = "stats") {
                    SystemStatsCard(
                        stats = state.systemStats,
                        doneCount = state.completed + state.failed + state.skipped,
                        totalFiles = state.totalFiles,
                        isUploading = busy,
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
            if (state.djiFiles.isNotEmpty()) {
                item(key = "files-header") {
                    Text("Files (${state.djiFiles.size})", style = MaterialTheme.typography.titleSmall)
                }
                val toShow = if (state.progressMap.isEmpty()) state.djiFiles.take(30) else state.djiFiles
                items(toShow, key = { it.key }) { f ->
                    FileRow(f.displayName, f.sizeBytes, state.progressMap[f.key])
                }
                if (state.djiFiles.size > 30 && state.progressMap.isEmpty()) {
                    item(key = "files-more") {
                        Text("…and ${state.djiFiles.size - 30} more. Upload will show each file's live status.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            item(key = "fab-spacer") { Spacer(Modifier.height(80.dp)) }
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
        state.djiFiles.isNotEmpty() -> "${plural(state.djiFiles.size, "file")} • ${formatSize(state.totalBytes)}"
        state.djiUri != null || state.djiSource == "files" -> "Selection ready"
        else -> "Choose what to back up"
    }
    val subtitle = when {
        state.isScanning -> if (state.djiSource == "files") "Reading selected files…" else "Reading DCIM folder over USB-C…"
        state.djiFiles.isNotEmpty() -> {
            val photos = state.djiFiles.count { it.isPhoto }
            val videos = state.djiFiles.count { it.isVideo }
            val src = if (state.djiSource == "files") "selected files" else "camera folder"
            "$photos photos • $videos videos • from $src"
        }
        state.usbConnected -> "${state.usbStatus}\nNow pick the DCIM folder or individual files"
        else -> "Plug the camera in over USB-C, or pick files already on this phone"
    }
    val cardState = when {
        state.djiFiles.isNotEmpty() -> StatusCardState.OK
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
        if (state.scanError != null) {
            Text("Scan error: ${state.scanError}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SettingsSummary(config: AppConfig, enabled: Boolean, onEdit: () -> Unit) {
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
                if (config.deleteAfterUpload) {
                    Text("Deletes originals after upload", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            TextButton(onClick = onEdit, enabled = enabled) { Text("Edit") }
        }
    }
}

@Composable
private fun ProgressSection(state: HomeUiState, onCancel: () -> Unit, onCopyErrors: () -> Unit) {
    val done = state.completed + state.failed + state.skipped
    // Byte-weighted: with smallest-first ordering a file count races ahead of the real transfer.
    val sentBytes = state.progressMap.values.sumOf { it.settledBytes }
    val totalBytes = state.progressMap.values.sumOf { it.totalBytes }.takeIf { it > 0 } ?: state.totalBytes
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
            state.cameraNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CountChip(Icons.Default.CheckCircle, "${state.completed} uploaded", MaterialTheme.colorScheme.primary)
                if (state.skipped > 0) CountChip(Icons.Default.Verified, "${state.skipped} already there", MaterialTheme.colorScheme.secondary)
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
private fun FileRow(name: String, size: Long, prog: com.gpic.android.data.progress.FileProgress?) {
    val ctx = LocalContext.current
    val active = prog != null && prog.status in setOf(
        UploadStatus.PREPARING, UploadStatus.HASHING, UploadStatus.CHECKING,
        UploadStatus.UPLOADING, UploadStatus.RESUMING, UploadStatus.COMMITTING,
    )
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when {
                        prog == null -> Icons.Default.Image
                        prog.status == UploadStatus.COMPLETED -> Icons.Default.CheckCircle
                        prog.status == UploadStatus.SKIPPED -> Icons.Default.Verified
                        prog.status == UploadStatus.ERROR -> Icons.Default.Error
                        prog.status == UploadStatus.CANCELLED -> Icons.Default.Block
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
                            "${formatSize(size)} • ${prog?.detailLine ?: "Queued"}",
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
                    title = "${cs.camera.name} • ${plural(state.djiFiles.size, "file")}",
                    subtitle = "${formatSize(state.totalBytes)} • $mode",
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
        if (state.camera !is CameraState.Ready && state.djiFiles.isNotEmpty()) {
            Text("${plural(state.djiFiles.size, "file")} downloaded from the camera and waiting to upload.", style = MaterialTheme.typography.bodySmall)
        }
        if (permDenied) {
            Text("GPic needs Nearby devices permission to find and pair with the camera.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (!busy) state.cameraNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
