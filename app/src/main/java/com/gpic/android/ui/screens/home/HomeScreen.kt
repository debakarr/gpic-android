package com.gpic.android.ui.screens.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) vm.setDjiUri(uri)
    }

    LaunchedEffect(Unit) { vm.refreshAuth(); vm.refreshUsb() }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("GPic • DJI → Google Photos") },
                actions = {
                    IconButton(onClick = onNavigateSettings) { Icon(Icons.Default.Settings, "Settings") }
                }
            )
        },
        floatingActionButton = {
            if (state.djiFiles.isNotEmpty() && !state.uploadRunning && state.hasAuth) {
                ExtendedFloatingActionButton(
                    onClick = { vm.startUpload() },
                    icon = { Icon(Icons.Default.CloudUpload, null) },
                    text = { Text("Upload ${state.djiFiles.size} files") }
                )
            }
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // 1. Photos auth status – most important to user
            item {
                val (title, subtitle, icon, st) = if (state.hasAuth) {
                    Quad("Google Photos: ${state.authEmail}", "Ready to back up • Token managed automatically", Icons.Default.VerifiedUser, StatusCardState.OK)
                } else {
                    Quad("Google Photos not linked", "Tap to add your Photos account (paste auth string from desktop)", Icons.Default.AccountCircle, StatusCardState.WARN)
                }
                StatusCard(title = title, subtitle = subtitle, icon = icon, status = st, actionLabel = if (!state.hasAuth) "Link" else "Change", onAction = onNavigateAuth)
            }

            // 2. DJI connection status
            item {
                val djiTitle = when {
                    state.isScanning -> "Scanning DJI storage…"
                    state.djiFiles.isNotEmpty() -> "DJI Action 4 • ${state.djiFiles.size} files • ${formatSize(state.totalBytes)}"
                    state.djiUri != null -> "DJI folder selected • scanning…"
                    else -> "Connect DJI Action 4"
                }
                val djiSubtitle = when {
                    state.isScanning -> "Reading DCIM folder via USB-C…"
                    state.djiFiles.isNotEmpty() -> {
                        val photos = state.djiFiles.count { it.isPhoto }
                        val videos = state.djiFiles.count { it.isVideo }
                        "$photos photos • $videos videos • ${state.usbStatus}"
                    }
                    state.usbConnected -> "${state.usbStatus} • Now pick DJI folder"
                    else -> "1. Plug camera via USB-C  2. Tap 'Choose DJI folder'  3. Select DCIM"
                }
                val djiState = when {
                    state.djiFiles.isNotEmpty() -> StatusCardState.OK
                    state.usbConnected -> StatusCardState.WARN
                    else -> StatusCardState.NEUTRAL
                }
                StatusCard(
                    title = djiTitle,
                    subtitle = djiSubtitle,
                    icon = Icons.Default.Videocam,
                    status = djiState,
                    actionLabel = when {
                        state.djiFiles.isNotEmpty() -> "Rescan"
                        state.djiUri != null -> "Rescan"
                        else -> "Choose DJI folder"
                    },
                    onAction = {
                        if (state.djiUri != null && state.djiFiles.isNotEmpty()) vm.rescan()
                        else picker.launch(null)
                    }
                )
                if (state.scanError != null) {
                    Text("Scan error: ${state.scanError}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }

            // 3. Quick stats while uploading
            if (state.uploadRunning || state.completed > 0 || state.failed > 0) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Upload progress", style = MaterialTheme.typography.titleSmall)
                            LinearProgressIndicator(
                                progress = { if (state.totalFiles > 0) (state.completed + state.failed + state.skipped).toFloat() / state.totalFiles else 0f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${state.completed} done", color = MaterialTheme.colorScheme.primary)
                                Text("${state.skipped} already backed up", color = MaterialTheme.colorScheme.secondary)
                                Text("${state.failed} failed", color = MaterialTheme.colorScheme.error)
                            }
                            if (state.uploadRunning) {
                                OutlinedButton(onClick = { vm.cancelUpload() }, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Default.Stop, null); Spacer(Modifier.width(8.dp)); Text("Cancel")
                                }
                            }
                        }
                    }
                }
            }

            // 3b. Morphe-style live CPU / RAM / network during upload
            if (state.uploadRunning || state.systemStats != null) {
                item {
                    SystemStatsCard(
                        stats = state.systemStats,
                        doneCount = state.completed + state.failed + state.skipped,
                        totalFiles = state.totalFiles,
                        isUploading = state.uploadRunning,
                    )
                }
            }

            // 4. Tips card
            if (state.djiFiles.isEmpty() && !state.isScanning) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("How it works", style = MaterialTheme.typography.titleSmall)
                            Text("• DJI Action 4 connects as MTP over USB-C. Android can't auto-mount it, so you pick the folder once.", style = MaterialTheme.typography.bodySmall)
                            Text("• App finds all JPG / DNG / MP4 / MOV etc., checks hash to skip duplicates already in your library.", style = MaterialTheme.typography.bodySmall)
                            Text("• Shows live status per file: hashing → checking → uploading → done / already backed up / failed.", style = MaterialTheme.typography.bodySmall)
                            Text("• Needs Google Photos installed – paste the auth string you extracted on desktop with 'gpic creds'.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // 5. Per-file list (show first 20 + summary)
            if (state.djiFiles.isNotEmpty()) {
                item { Text("Files on DJI", style = MaterialTheme.typography.titleSmall) }
                val toShow = if (state.progressMap.isEmpty()) state.djiFiles.take(30) else state.djiFiles // if uploading, show all with status
                items(toShow) { f ->
                    val key = f.uri?.toString() ?: f.file?.absolutePath ?: f.displayName
                    val prog = state.progressMap[key]
                    FileRow(f.displayName, f.sizeBytes, prog)
                }
                if (state.djiFiles.size > 30 && state.progressMap.isEmpty()) {
                    item { Text("…and ${state.djiFiles.size - 30} more. Upload will show each file's live status.", style = MaterialTheme.typography.bodySmall) }
                }
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

private data class Quad(val a: String, val b: String, val c: androidx.compose.ui.graphics.vector.ImageVector, val d: StatusCardState)

@Composable
private fun FileRow(name: String, size: Long, prog: com.gpic.android.data.progress.FileProgress?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when {
                        prog == null -> Icons.Default.Image
                        prog.status == UploadStatus.COMPLETED -> Icons.Default.CheckCircle
                        prog.status == UploadStatus.SKIPPED -> Icons.Default.Verified
                        prog.status == UploadStatus.ERROR -> Icons.Default.Error
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
                    Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Text("${formatSize(size)} • ${prog?.statusLabel ?: "Queued"} ${if (prog?.message?.isNotEmpty() == true && prog.status != UploadStatus.COMPLETED) "• ${prog.message}" else ""}", style = MaterialTheme.typography.bodySmall)
                }
                if (prog != null && prog.status == UploadStatus.ERROR) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                }
            }
            if (prog != null && (prog.status == UploadStatus.UPLOADING || prog.status == UploadStatus.RESUMING || prog.status == UploadStatus.HASHING || prog.status == UploadStatus.CHECKING || prog.status == UploadStatus.PREPARING || prog.status == UploadStatus.COMMITTING)) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { prog.percentage / 100f }, modifier = Modifier.fillMaxWidth())
                Text("${"%.1f".format(prog.percentage)}%", style = MaterialTheme.typography.labelSmall)
            }
            if (prog != null && prog.status == UploadStatus.COMPLETED) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
