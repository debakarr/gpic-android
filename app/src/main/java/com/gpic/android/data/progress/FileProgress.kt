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
