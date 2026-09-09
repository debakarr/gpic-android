package com.gpic.android.data.progress

/**
 * Port of gphotos/progress.py FileProgress
 */
enum class UploadStatus {
    PENDING, PREPARING, HASHING, CHECKING, UPLOADING, RESUMING, COMMITTING, COMPLETED, ERROR, SKIPPED, QUEUED
}

data class FileProgress(
    val filePath: String,
    val fileName: String,
    var totalBytes: Long = 0,
    var bytesUploaded: Long = 0,
    var resumeOffset: Long = 0,
    var attempt: Int = 1,
    var status: UploadStatus = UploadStatus.PENDING,
    var message: String = "",
    var speedBps: Long = 0,
    var etaSeconds: Long = 0,
    var error: String? = null,
) {
    val percentage: Float
        get() = if (totalBytes == 0L) 0f else (bytesUploaded.toFloat() / totalBytes.toFloat()) * 100f

    val resumedPercentage: Float
        get() = if (totalBytes == 0L) 0f else (resumeOffset.toFloat() / totalBytes.toFloat()) * 100f

    fun updateBytes(read: Long, total: Long) {
        bytesUploaded = read
        totalBytes = total
    }

    // Last upload sample for speed/ETA; copied by snapshot() but only mutated on worker thread.
    private var lastSampleMs: Long = 0L
    private var lastSampleBytes: Long = 0L

    /**
     * Record an upload sample, updating speedBps/etaSeconds with light smoothing.
     * @return true when enough time passed (or upload finished) that the caller should emit.
     */
    fun trackUpload(read: Long, total: Long, nowMs: Long = System.currentTimeMillis(), minIntervalMs: Long = 200): Boolean {
        bytesUploaded = read
        totalBytes = total
        val dt = nowMs - lastSampleMs
        if (read >= total) {
            if (dt > 0 && read > lastSampleBytes) {
                speedBps = ((read - lastSampleBytes) * 1000 / dt).coerceAtLeast(0)
            }
            etaSeconds = 0
            lastSampleMs = nowMs
            lastSampleBytes = read
            return true
        }
        if (dt < minIntervalMs) return false
        if (dt > 0 && read > lastSampleBytes) {
            val instant = ((read - lastSampleBytes) * 1000 / dt).coerceAtLeast(0)
            speedBps = if (speedBps == 0L) instant else (speedBps * 7 / 10 + instant * 3 / 10)
            etaSeconds = if (speedBps > 0) (total - read) / speedBps else 0
        }
        lastSampleMs = nowMs
        lastSampleBytes = read
        return true
    }

    /** One-line transfer summary: speed + remaining time (no duplicated status text). */
    val transferSummary: String
        get() {
            if (speedBps <= 0) return "starting…"
            val eta = if (etaSeconds > 0) " • ${formatEtaCompat(etaSeconds)} left" else ""
            return "${formatSpeedCompat(speedBps)}$eta"
        }

    private fun formatSpeedCompat(bps: Long): String {
        if (bps < 1024) return "$bps B/s"
        val kb = bps / 1024.0
        if (kb < 1024) return String.format("%.1f KB/s", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB/s", mb)
        return String.format("%.2f GB/s", mb / 1024.0)
    }

    private fun formatEtaCompat(seconds: Long): String {
        if (seconds <= 0) return "--"
        val m = seconds / 60
        val s = seconds % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }

    val detailLine: String
        get() = when (status) {
            UploadStatus.UPLOADING, UploadStatus.RESUMING -> "$statusLabel • $transferSummary"
            else -> if (message.isNotEmpty() && status != UploadStatus.COMPLETED) "$statusLabel • $message" else statusLabel
        }

    val statusLabel: String
        get() = when (status) {
            UploadStatus.PENDING -> "Queued"
            UploadStatus.QUEUED -> "Queued"
            UploadStatus.PREPARING -> "Preparing"
            UploadStatus.HASHING -> "Hashing"
            UploadStatus.CHECKING -> "Checking"
            UploadStatus.UPLOADING -> "Uploading"
            UploadStatus.RESUMING -> "Resuming"
            UploadStatus.COMMITTING -> "Finalizing"
            UploadStatus.COMPLETED -> "Done"
            UploadStatus.SKIPPED -> "Already backed up"
            UploadStatus.ERROR -> "Failed"
        }
}

class ProgressTracker {
    private val _files = java.util.concurrent.ConcurrentHashMap<String, FileProgress>()
    val files: Map<String, FileProgress> get() = _files

    @Volatile var totalFiles: Int = 0
        private set
    @Volatile var totalBytes: Long = 0L
        private set
    @Volatile var completed: Int = 0
        private set
    @Volatile var failed: Int = 0
        private set
    @Volatile var skipped: Int = 0
        private set

    val doneCount: Int get() = completed + failed + skipped

    val overallPercentage: Float
        get() = if (totalFiles == 0) 0f else (doneCount.toFloat() / totalFiles.toFloat()) * 100f

    @Synchronized
    fun addFile(filePath: String, fileSize: Long) {
        val name = filePath.substringAfterLast("/").substringAfterLast("\\")
        _files[filePath] = FileProgress(filePath = filePath, fileName = name, totalBytes = fileSize)
        totalFiles++
        totalBytes += fileSize
    }

    fun get(filePath: String): FileProgress = _files[filePath]!!

    /**
     * Deep-copy snapshot for StateFlow emission.
     * Required because FileProgress is mutable: emitting a shallow toMap()
     * shares the same object refs, so StateFlow's structural-equality check
     * sees old == new and drops the update (UI stuck at "Queued").
     */
    fun snapshot(): Map<String, FileProgress> = _files.mapValues { (_, v) -> v.copy() }

    @Synchronized fun incCompleted() { completed++ }
    @Synchronized fun incFailed() { failed++ }
    @Synchronized fun incSkipped() { skipped++ }

    @Synchronized
    fun reset() {
        _files.clear()
        totalFiles = 0; totalBytes = 0; completed = 0; failed = 0; skipped = 0
    }
}
