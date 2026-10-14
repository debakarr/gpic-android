package com.gpic.android.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.gpic.android.data.auth.AppConfig
import com.gpic.android.data.auth.CredentialStore
import kotlin.math.roundToInt

private const val MAX_THREADS = 12

private enum class Quality(val title: String, val detail: String) {
    ORIGINAL("Original quality", "Full resolution, doesn't count against storage (Pixel XL profile)"),
    SAVER("Storage saver", "Compressed by Google, doesn't count against storage (Pixel 2 profile)"),
    QUOTA("Original, uses quota", "Full resolution, counts against your Google storage (Pixel 8 profile)"),
}

private val AppConfig.quality: Quality
    get() = when {
        useQuota -> Quality.QUOTA
        saverMode -> Quality.SAVER
        else -> Quality.ORIGINAL
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(store: CredentialStore, onBack: () -> Unit) {
    val config by store.configFlow.collectAsState()
    // Changes apply immediately; there's no Save button to forget before pressing back.
    fun save(transform: AppConfig.() -> AppConfig) = store.saveConfig(store.loadConfig().transform())

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
        ) {
            SectionHeader("Upload quality")
            Column(Modifier.selectableGroup()) {
                Quality.entries.forEach { q ->
                    ListItem(
                        headlineContent = { Text(q.title) },
                        supportingContent = { Text(q.detail) },
                        leadingContent = { RadioButton(selected = config.quality == q, onClick = null) },
                        modifier = Modifier.selectable(
                            selected = config.quality == q,
                            role = Role.RadioButton,
                            onClick = { save { copy(saverMode = q == Quality.SAVER, useQuota = q == Quality.QUOTA) } },
                        ),
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader("Transfers")
            var threads by remember(config.uploadThreads) { mutableFloatStateOf(config.uploadThreads.coerceIn(0, MAX_THREADS).toFloat()) }
            ListItem(
                headlineContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Parallel uploads", Modifier.weight(1f))
                        Text(if (threads.roundToInt() == 0) "Auto" else threads.roundToInt().toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                },
                supportingContent = {
                    Column {
                        Slider(
                            value = threads,
                            onValueChange = { threads = it },
                            onValueChangeFinished = { save { copy(uploadThreads = threads.roundToInt()) } },
                            valueRange = 0f..MAX_THREADS.toFloat(),
                            steps = MAX_THREADS - 1,
                        )
                        Text("Auto picks a count from file sizes. Fewer is gentler on a USB-connected camera.")
                    }
                },
            )
            SwitchItem(
                title = "Skip files already in Google Photos",
                detail = "Hashes each file first and asks Google whether it's already backed up",
                checked = !config.forceUpload,
                onChange = { skip -> save { copy(forceUpload = !skip) } },
            )
            SwitchItem(
                title = "Delete originals after upload",
                detail = "Removes each file from the camera once it is uploaded or found already backed up. Can't be undone.",
                detailColor = if (config.deleteAfterUpload) MaterialTheme.colorScheme.error else Color.Unspecified,
                checked = config.deleteAfterUpload,
                onChange = { save { copy(deleteAfterUpload = it) } },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader("Camera over WiFi")
            Column(Modifier.selectableGroup()) {
                listOf(
                    Triple(false, "Download to phone first", "Works on any phone. Needs free space; the phone leaves the camera's WiFi to upload."),
                    Triple(true, "Stream straight to Google Photos", "No phone storage used, but needs mobile data while connected to the camera. Falls back to downloading when there's none."),
                ).forEach { (stream, title, detail) ->
                    ListItem(
                        headlineContent = { Text(title) },
                        supportingContent = { Text(detail) },
                        leadingContent = { RadioButton(selected = config.cameraStream == stream, onClick = null) },
                        modifier = Modifier.selectable(
                            selected = config.cameraStream == stream,
                            role = Role.RadioButton,
                            onClick = { save { copy(cameraStream = stream) } },
                        ),
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader("About")
            ListItem(
                headlineContent = { Text("GPic Android") },
                supportingContent = {
                    Text("Companion to github.com/debakarr/gpic. Uses the Google Photos mobile upload API with resumable transfers and hash-based dedup.")
                },
            )
            ListItem(
                headlineContent = { Text("Status meanings") },
                supportingContent = {
                    Text("Hashing = computing SHA-1 • Checking = asking Google if it's already there • Uploading = sending bytes • Resuming = continuing an interrupted upload • Finalizing = committing")
                },
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SwitchItem(
    title: String,
    detail: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    detailColor: Color = Color.Unspecified,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(detail, color = detailColor) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}
