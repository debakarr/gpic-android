package com.gpic.android.ui.screens.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.gpic.android.data.auth.CredentialStore
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

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
        // imePadding: keep Save reachable above the keyboard.
        Column(Modifier.padding(padding).imePadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Paste your Google Photos auth line", style = MaterialTheme.typography.titleSmall)
                Text("1. On your PC with the phone connected via USB:", style = MaterialTheme.typography.bodySmall)
                SelectionContainer {
                    Text("  adb logcat -c; adb logcat | grep -i photos.native", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                }
                Text("2. Open Google Photos app on this phone", style = MaterialTheme.typography.bodySmall)
                Text("3. Copy the FULL line containing androidId=…&Email=…&Token=…&service=…photos.native. A line with userinfo.profile is the wrong one and will 403.", style = MaterialTheme.typography.bodySmall)
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

            val focus = LocalFocusManager.current
            fun save() {
                focus.clearFocus()
                val raw = authInput.trim()
                // Refuse lines that can't upload: saving them only moves the failure to a 403 later.
                val problem = store.serviceProblem(raw)
                if (problem != null) {
                    message = problem
                    isError = true
                    return
                }
                val cred = store.addCredential(raw)
                if (cred != null) {
                    message = "Saved for ${cred.email}. Ready to upload."
                    isError = false
                    authInput = ""
                } else {
                    message = "Could not parse. Need androidId + Email + Token. Ensure you copied the FULL line including service=... (usually photos.native)."
                    isError = true
                }
            }

            OutlinedTextField(
                value = authInput,
                onValueChange = { authInput = it },
                label = { Text("Paste auth string") },
                placeholder = { Text("androidId=…&Email=you@gmail.com&Token=…&service=…") },
                modifier = Modifier.fillMaxWidth(),
                // The line is ~750 chars; unbounded, the field outgrows the screen and buries Save.
                minLines = 3,
                maxLines = 4,
                // The master token is a full-account credential: never show it on screen.
                visualTransformation = MaskTokenTransformation,
                // Password type: keyboards don't suggest or learn from it (display masking is ours, above).
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { if (authInput.isNotBlank()) save() }),
                trailingIcon = {
                    IconButton(onClick = {
                        val clip = (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                            .primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
                        if (!clip.isNullOrBlank()) authInput = clip.trim()
                    }) { Icon(Icons.Default.ContentPaste, "Paste from clipboard") }
                },
            )

            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth(), enabled = authInput.isNotBlank()) {
                Icon(Icons.Default.Save, null); Spacer(Modifier.width(8.dp)); Text("Save credential")
            }

            if (message != null) {
                Card(colors = CardDefaults.cardColors(containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer)) {
                    Text(message!!, modifier = Modifier.padding(12.dp), color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }

            // RESEARCH RESULT 2026-09-02: seamless link via installed Photos is possible via
            // system Google account (CredentialManager + AuthorizationClient), NOT via silent log theft.
            // See docs/AUTH_LINKING_RESEARCH.md for full analysis. Not wired yet (GoogleAccountLinker.kt stub).
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("One-tap sign-in (not available yet)", style = MaterialTheme.typography.titleSmall)
                    Text("Linking through the account already on this phone needs the official Photos Library API, which since March 2025 only sees media the app itself uploaded and has no unlimited Pixel-style storage. The paste flow above stays the way to back up.", style = MaterialTheme.typography.bodySmall)
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Troubleshooting", style = MaterialTheme.typography.titleSmall)
                    Text("Why a PC? Apps can't read other apps' logs unless READ_LOGS is granted over adb, so the Photos line has to be captured with adb logcat. With MicroG/ReVanced GmsCore the line only appears when Photos fetches a fresh token (roughly hourly) — reopen Photos later if nothing shows.", style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    Text("Failed: UNREGISTERED_ON_API_CONSOLE? You do NOT need your own SHA-1/OAuth client for the paste flow — GPic reuses the Photos app registration (com.google.android.apps.photos). Fix: (1) re-copy FULL line with service=..., (2) reopen Photos once then re-copy (Token may be revoked), (3) ensure no extra spaces/line breaks. Only one-tap sign-in (official API) would need your package com.gpic.android + debug SHA1 51:8D:06:60:...:2D:A4 registered in Google Cloud Console.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** Shows the auth line with the `Token=` value replaced by dots (same length, so cursor maths holds). */
private object MaskTokenTransformation : VisualTransformation {
    private val token = Regex("""(?<=(^|&)Token=)[^&]*""")
    override fun filter(text: AnnotatedString): TransformedText {
        val masked = token.replace(text.text) { "•".repeat(it.value.length) }
        return TransformedText(AnnotatedString(masked), OffsetMapping.Identity)
    }
}
