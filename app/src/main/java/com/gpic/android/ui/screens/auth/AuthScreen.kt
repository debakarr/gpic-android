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
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext

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
    val ctx = LocalContext.current

    Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text("Google Photos account") }, navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Default.Close, null) } }) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // RESEARCH RESULT 2026-09-02: seamless link via installed Photos is possible via
            // system Google account (CredentialManager + AuthorizationClient), NOT via silent log theft.
            // See docs/AUTH_LINKING_RESEARCH.md for full analysis.
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Option A — One-tap link (recommended, uses your Photos account)", style = MaterialTheme.typography.titleSmall)
                    Text("If Google Photos is installed, the *same* Google account is already on this device. GPic can show the system account picker (the same emails Photos uses) and get an OAuth token with one tap – no PC needed.", style = MaterialTheme.typography.bodySmall)
                    Text("• Uses Credential Manager + AuthorizationClient (`photoslibrary.appendonly` + `readonly.appcreateddata` or Picker). After March 2025 this only sees app-created media (Google restriction). For unlimited Pixel-style uploads, use Option B.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = {
                        Toast.makeText(ctx, "One-tap link needs Google Cloud WEB_CLIENT_ID. See docs/AUTH_LINKING_RESEARCH.md and wire GoogleAccountLinker.kt – then this becomes a real account picker.", Toast.LENGTH_LONG).show()
                        message = "One-tap needs Cloud Console setup. For now use Option B (paste). See docs/AUTH_LINKING_RESEARCH.md."
                        isError = false
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.AccountCircle, null); Spacer(Modifier.width(8.dp)); Text("Link Google account on this device")
                    }
                    Text("Status: skeleton wired (GoogleAccountLinker.kt). Set WEB_CLIENT_ID and the button will launch PendingIntent consent → official Photos Library API.", style = MaterialTheme.typography.labelSmall)
                }
            }

            Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Option B — Paste adb logcat auth string (unlimited, same as desktop gpic)", style = MaterialTheme.typography.titleSmall)
                Text("1. On your PC with the phone connected via USB:", style = MaterialTheme.typography.bodySmall)
                Text("  adb logcat -c; adb logcat | grep -i auth", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Text("2. Open Google Photos app on this phone", style = MaterialTheme.typography.bodySmall)
                Text("3. Copy the FULL line containing androidId=…&Email=…&Token=…&service=… (must include service, usually photos.native)", style = MaterialTheme.typography.bodySmall)
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
                placeholder = { Text("androidId=…&Email=you@gmail.com&Token=…&service=…") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                minLines = 3,
            )

            Button(onClick = {
                val raw = authInput.trim()
                val cred = store.addCredential(raw)
                if (cred != null) {
                    val svc = store.describeService(raw)
                    message = "Saved for ${cred.email}. service=$svc Ready to upload."
                    isError = false
                    authInput = ""
                } else {
                    message = "Could not parse. Need androidId + Email + Token. Ensure you copied the FULL line including service=... (usually photos.native)."
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
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Why two options?", style = MaterialTheme.typography.titleSmall)
                    Text("Option A = official OAuth via the same system account Photos uses (seamless, but Google now restricts to app-created media after Mar 2025; needs Cloud verification). Option B = internal mobile API with Pixel spoofing (unlimited) – needs that adb logcat master Token because Google Photos’ sandbox blocks silent reads. See docs/AUTH_LINKING_RESEARCH.md.", style = MaterialTheme.typography.bodySmall)
                    Text("Research: READ_LOGS is signature|privileged since Android 4.1, third-party apps can’t read Photos logs; in-app logcat won’t see them. ReVanced GmsCore log needs PC adb. CredentialManager+AuthorizationClient is the correct seamless path.", style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    Text("Failed: UNREGISTERED_ON_API_CONSOLE? You do NOT need your own SHA-1/OAuth client for Option B — GPic reuses the Photos app registration (com.google.android.apps.photos). Fix: (1) re-copy FULL line with service=..., (2) reopen Photos once then re-copy (Token may be revoked), (3) ensure no extra spaces/line breaks. Only Option A (official API) needs your package com.gpic.android + debug SHA1 51:8D:06:60:...:2D:A4 registered in Google Cloud Console.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
