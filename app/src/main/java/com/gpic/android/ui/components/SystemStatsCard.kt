package com.gpic.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.gpic.android.data.stats.LiveStats
import com.gpic.android.util.formatSize
import com.gpic.android.util.formatSpeed

/**
 * Morphe-style live stats panel for uploads.
 * Morphe Expert patching shows: overall progress + patch counter + live memory vs heap + log.
 * Here: overall upload progress + file counter + live CPU / RAM / network + upload throughput.
 */
@Composable
fun SystemStatsCard(
    stats: LiveStats?,
    doneCount: Int,
    totalFiles: Int,
    isUploading: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // pulsing dot equivalent: filled circle when live
                Icon(
                    if (isUploading) Icons.Default.FiberManualRecord else Icons.Default.PauseCircle,
                    contentDescription = null,
                    tint = if (isUploading) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isUploading) "Live • uploading $doneCount/$totalFiles" else "Device stats • idle",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.weight(1f))
                if (stats != null && stats.uploadSpeedBps > 0) {
                    Text(formatSpeed(stats.uploadSpeedBps), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }

            if (stats == null) {
                Text("Starting live stats…", style = MaterialTheme.typography.bodySmall)
                return@Column
            }

            // CPU row
            StatRow(
                icon = Icons.Default.Memory,
                label = "CPU",
                value = "${"%.0f".format(stats.cpuPercent)}%",
                barFraction = (stats.cpuPercent / 100f).coerceIn(0f, 1f),
                barColor = MaterialTheme.colorScheme.primary,
                sparkFloat = stats.cpuHistory,
                sparkMax = 100f,
            )
            // RAM row (device + app PSS like Morphe "memory vs heap")
            StatRow(
                icon = Icons.Default.Storage,
                label = "RAM",
                value = "${formatSize(stats.ramUsedBytes)} / ${formatSize(stats.ramTotalBytes)} • app ${"%.0f".format(stats.appPssMb)} MB" + if (stats.lowMemory) " • LOW" else "",
                barFraction = (stats.ramPercent / 100f).coerceIn(0f, 1f),
                barColor = if (stats.lowMemory) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                sparkFloat = stats.memHistory,
                sparkMax = 100f,
            )
            // Network row: up/down + app UID + totals
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SwapVert, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Network  ↑ ${formatSpeed(stats.txSpeedBps)}  ↓ ${formatSpeed(stats.rxSpeedBps)}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "App ↑ ${formatSpeed(stats.appTxSpeedBps)}  ↓ ${formatSpeed(stats.appRxSpeedBps)} • total ↑ ${formatSize(stats.totalTxBytes)} ↓ ${formatSize(stats.totalRxBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // tx sparkline (upload matters most)
                SparklineLong(values = stats.txHistory.map { it.toFloat() }, color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth().height(28.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("↑ tx history", style = MaterialTheme.typography.labelSmall)
                    Text("storage free ${formatSize(stats.storageAvailBytes)}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun StatRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    barFraction: Float,
    barColor: Color,
    sparkFloat: List<Float>,
    sparkMax: Float,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(44.dp))
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        }
        LinearProgressIndicator(progress = { barFraction }, modifier = Modifier.fillMaxWidth(), color = barColor)
        Sparkline(values = sparkFloat, max = sparkMax, color = barColor, modifier = Modifier.fillMaxWidth().height(28.dp))
    }
}

@Composable
private fun Sparkline(values: List<Float>, max: Float, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val w = size.width; val h = size.height
        val path = Path()
        val n = values.size
        values.forEachIndexed { i, v ->
            val x = w * i / (n - 1).coerceAtLeast(1)
            val y = h - (h * (v / max.coerceAtLeast(1f)).coerceIn(0f, 1f))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 3f))
    }
}

@Composable
private fun SparklineLong(values: List<Float>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val max = (values.maxOrNull() ?: 1f).coerceAtLeast(1f)
        val w = size.width; val h = size.height
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = w * i / (values.size - 1).coerceAtLeast(1)
            val y = h - (h * (v / max).coerceIn(0f, 1f))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 3f))
    }
}
