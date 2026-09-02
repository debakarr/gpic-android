package com.gpic.android.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
fun StatusCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    status: StatusCardState,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val containerColor = when (status) {
        StatusCardState.OK -> MaterialTheme.colorScheme.primaryContainer
        StatusCardState.WARN -> MaterialTheme.colorScheme.tertiaryContainer
        StatusCardState.ERROR -> MaterialTheme.colorScheme.errorContainer
        StatusCardState.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when (status) {
        StatusCardState.OK -> MaterialTheme.colorScheme.onPrimaryContainer
        StatusCardState.WARN -> MaterialTheme.colorScheme.onTertiaryContainer
        StatusCardState.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        StatusCardState.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            if (actionLabel != null && onAction != null) {
                FilledTonalButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

enum class StatusCardState { OK, WARN, ERROR, NEUTRAL }
