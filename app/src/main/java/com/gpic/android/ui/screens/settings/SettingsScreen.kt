package com.gpic.android.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gpic.android.data.auth.CredentialStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(store: CredentialStore, onBack: () -> Unit) {
    val config by store.configFlow.collectAsState()
    var threads by remember { mutableStateOf(config.uploadThreads.toString()) }
    var force by remember { mutableStateOf(config.forceUpload) }
    var saver by remember { mutableStateOf(config.saverMode) }
    var useQuota by remember { mutableStateOf(config.useQuota) }
    var deleteAfter by remember { mutableStateOf(config.deleteAfterUpload) }

    Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } }) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Upload", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(value = threads, onValueChange = { threads = it }, label = { Text("Threads (0=auto)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Force re-upload (ignore dedup)")
                        Switch(checked = force, onCheckedChange = { force = it })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Storage saver (Pixel 2 quality)")
                        Switch(checked = saver, onCheckedChange = { saver = it; if (it) useQuota = false })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Use quota (Pixel 8)")
                        Switch(checked = useQuota, onCheckedChange = { useQuota = it; if (it) saver = false })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Delete from DJI after upload")
                        Switch(checked = deleteAfter, onCheckedChange = { deleteAfter = it })
                    }
                    Button(onClick = {
                        val t = threads.toIntOrNull() ?: 3
                        store.saveConfig(config.copy(uploadThreads = t, forceUpload = force, saverMode = saver, useQuota = useQuota, deleteAfterUpload = deleteAfter))
                    }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("About", style = MaterialTheme.typography.titleSmall)
                    Text("GPic Android • companion to github.com/debakarr/gpic", style = MaterialTheme.typography.bodySmall)
                    Text("Ports: auth (adb logcat token) → bearer exchange, protobuf upload, resumable via Content-Range, hash dedup, concurrent uploads.", style = MaterialTheme.typography.bodySmall)
                    Text("DJI Action 4 connects via USB-C as MTP; app uses SAF folder picker to access DCIM.", style = MaterialTheme.typography.bodySmall)
                    Text("Status meanings: Hashing = computing SHA-1, Checking = asking Google if already there, Uploading = sending bytes, Resuming = continuing interrupted, Finalizing = committing, Done / Already backed up / Failed.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
