package com.gpic.android.ui.screens.auth

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
fun AuthScreen(
    store: CredentialStore,
    onDone: () -> Unit,
) {
    var authInput by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    val config by store.configFlow.collectAsState()

    Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text("Google Photos account") }, navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Default.Close, null) } }) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("How to get your auth string (same as desktop gpic)", style = MaterialTheme.typography.titleSmall)
                Text("1. On your PC with the phone connected via USB:", style = MaterialTheme.typography.bodySmall)
                Text("  adb logcat -c; adb logcat | grep -i auth", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Text("2. Open Google Photos app on this phone", style = MaterialTheme.typography.bodySmall)
                Text("3. Copy the line containing androidId=…&Email=…&Token=…", style = MaterialTheme.typography.bodySmall)
                Text("4. Paste below and tap Save. The app stores it securely (EncryptedSharedPreferences).", style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Text("Tip: you can also run 'gpic creds list' on desktop and share the config.json via QR or file.", style = MaterialTheme.typography.bodySmall)
            } }

            if (config.credentials.isNotEmpty()) {
                Card { Column(Modifier.padding(16.dp)) {
                    Text("Saved accounts", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    config.credentials.forEach { c ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text(c.email, style = MaterialTheme.typography.bodyMedium)
                                Text(if (c.email == config.selectedEmail) "Active" else "Tap to activate", style = MaterialTheme.typography.bodySmall)
                            }
                            Row {
                                if (c.email != config.selectedEmail) {
                                    TextButton(onClick = { store.setActive(c.email); message = "Active: ${c.email}"; isError = false }) { Text("Use") }
                                }
                                TextButton(onClick = { store.removeCredential(c.email); message = "Removed ${c.email}"; isError = false }) { Text("Remove") }
                            }
                        }
                        HorizontalDivider()
                    }
                } }
            }

            OutlinedTextField(
                value = authInput,
                onValueChange = { authInput = it },
                label = { Text("Paste auth string") },
                placeholder = { Text("androidId=…&Email=you@gmail.com&Token=…") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                minLines = 3,
            )

            Button(onClick = {
                val cred = store.addCredential(authInput.trim())
                if (cred != null) {
                    message = "Saved for ${cred.email}. Ready to upload."
                    isError = false
                    authInput = ""
                } else {
                    message = "Could not parse. Need Email= field. Ensure you copied the full line."
                    isError = true
                }
            }, modifier = Modifier.fillMaxWidth(), enabled = authInput.isNotBlank()) {
                Icon(Icons.Default.Save, null); Spacer(Modifier.width(8.dp)); Text("Save credential")
            }

            if (message != null) {
                Card(colors = CardDefaults.cardColors(containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer)) {
                    Text(message!!, modifier = Modifier.padding(12.dp), color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Why not OAuth?", style = MaterialTheme.typography.titleSmall)
                    Text("GPic uses the internal Google Photos mobile API (like the desktop 'gotohp'/'gpic' tools) for Pixel-style unlimited high-quality backup. That's why it needs this token, not a normal OAuth consent screen. Your token is stored only on this device, never sent elsewhere except to Google.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
