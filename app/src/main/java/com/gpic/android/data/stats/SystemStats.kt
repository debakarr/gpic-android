package com.gpic.android.data.stats

/**
 * Live device stats snapshot, Morphe-style.
 * Mirrors MorpheApp/morphe-manager DeviceStats (RAM/storage) + adds CPU + network.
 * See docs: Morphe Expert patching screen shows overall progress, patch counter,
 * live memory usage vs heap limit, and log. Here we show the same for uploads.
 */
data class LiveStats(
    val cpuPercent: Float = 0f,
    val ramAvailBytes: Long = 0L,
    val ramTotalBytes: Long = 0L,
    val appPssMb: Float = 0f,
    val storageAvailBytes: Long = 0L,
    val storageTotalBytes: Long = 0L,
    val appCacheBytes: Long = 0L,
    val appFilesBytes: Long = 0L,
    val lowMemory: Boolean = false,
    // device-wide network speeds
    val rxSpeedBps: Long = 0L,
    val txSpeedBps: Long = 0L,
    // this app UID speeds (closest proxy for GPic upload traffic)
    val appRxSpeedBps: Long = 0L,
    val appTxSpeedBps: Long = 0L,
    // totals since boot
    val totalRxBytes: Long = 0L,
    val totalTxBytes: Long = 0L,
    // upload throughput derived from UploadManager progress deltas
    val uploadSpeedBps: Long = 0L,
    val uploadedBytes: Long = 0L,
    // sparkline histories (last N samples, oldest->newest)
    val cpuHistory: List<Float> = emptyList(),
    val memHistory: List<Float> = emptyList(),
    val txHistory: List<Long> = emptyList(),
    val rxHistory: List<Long> = emptyList(),
    val diskHistory: List<Float> = emptyList(),
) {
    val ramUsedBytes: Long get() = (ramTotalBytes - ramAvailBytes).coerceAtLeast(0)
    val ramPercent: Float get() = if (ramTotalBytes <= 0) 0f else ramUsedBytes.toFloat() / ramTotalBytes * 100f
    val storageUsedBytes: Long get() = (storageTotalBytes - storageAvailBytes).coerceAtLeast(0)
    val storagePercent: Float get() = if (storageTotalBytes <= 0) 0f else storageUsedBytes.toFloat() / storageTotalBytes * 100f
}
