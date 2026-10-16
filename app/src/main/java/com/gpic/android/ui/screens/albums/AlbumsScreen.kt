package com.gpic.android.ui.screens.albums

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.gpic.android.data.albums.AlbumSuggester
import com.gpic.android.data.albums.Suggestion

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsScreen(vm: AlbumsViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.messageShown() }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Album suggestions") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (state.suggestions.size > 1) {
                        val all = state.selected.size == state.suggestions.size
                        TextButton(onClick = { if (all) vm.clearSelection() else vm.selectAll() }, enabled = !state.busy) {
                            Text(if (all) "Select none" else "Select all")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (state.selected.isNotEmpty() || state.batchProgress != null) {
                Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val count = state.selected.size
                        val items = state.suggestions.filter { it.id in state.selected }.sumOf { it.items.size }
                        Text(
                            state.batchProgress?.let { "Creating $it…" } ?: "$count selected • $items items",
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = vm::clearSelection, enabled = !state.busy) { Text("Clear") }
                        Button(onClick = vm::createSelected, enabled = !state.busy && count > 0) {
                            if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.PhotoAlbum, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (state.busy) "Creating…" else if (count == 1) "Create album" else "Create $count albums")
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "about") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Lock, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Worked out on this phone", style = MaterialTheme.typography.titleSmall)
                        }
                        Text(
                            "GPic groups photos by when they were taken. Nothing is sent anywhere until you tap " +
                                "Create album, and then only the album name and which photos go in it.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Based on ${state.indexed} items GPic has uploaded or found in your library. To include older " +
                                "backups, run Upload on their folder — files already in Google Photos aren't sent again.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            item(key = "min") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Suggest groups of at least", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(3, 5, 10, 20).forEach { n ->
                            FilterChip(selected = state.minSize == n, onClick = { vm.setMinSize(n) }, label = { Text("$n") })
                        }
                    }
                }
            }
            if (state.suggestions.isEmpty()) {
                item(key = "empty") {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Default.PhotoAlbum, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("No suggestions yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "They appear once GPic has backed up a few photos taken close together, like a day out or a trip.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(state.suggestions, key = { it.id }) { s ->
                SuggestionCard(
                    s = s,
                    name = state.nameOf(s),
                    selected = s.id in state.selected,
                    creating = state.creatingId == s.id,
                    busy = state.busy,
                    onRename = { vm.rename(s, it) },
                    onToggle = { vm.toggleSelected(s) },
                    onCreate = { vm.create(s) },
                    onDismiss = { vm.dismiss(s) },
                )
            }
        }
    }
}

@Composable
private fun SuggestionCard(
    s: Suggestion,
    name: String,
    selected: Boolean,
    creating: Boolean,
    busy: Boolean,
    onRename: (String) -> Unit,
    onToggle: () -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = selected, onCheckedChange = { onToggle() }, enabled = !busy)
                OutlinedTextField(
                    value = name, onValueChange = onRename,
                    label = { Text("Album name") }, singleLine = true, enabled = !busy,
                    modifier = Modifier.weight(1f),
                )
            }
            val located = if (s.withLocation > 0) " • ${s.withLocation} with location" else ""
            Text("${s.items.size} items$located", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val thumbs = s.items.filter { it.localUri != null }.take(8)
            if (thumbs.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(thumbs, key = { it.mediaKey }) { m ->
                        SubcomposeAsyncImage(
                            model = Uri.parse(m.localUri),
                            contentDescription = m.name,
                            contentScale = ContentScale.Crop,
                            // Deleted after upload (or a camera that's unplugged): show a plain tile.
                            error = { Box(Modifier.background(MaterialTheme.colorScheme.surfaceVariant)) },
                            loading = { Box(Modifier.background(MaterialTheme.colorScheme.surfaceVariant)) },
                            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onCreate, enabled = !busy) {
                    if (creating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.PhotoAlbum, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (creating) "Creating…" else "Create album")
                }
                TextButton(onClick = onDismiss, enabled = !busy) { Text("Dismiss") }
            }
        }
    }
}
