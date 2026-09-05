package com.gpic.android.data.upload

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.gpic.android.data.api.GooglePhotosApi
import com.gpic.android.data.auth.Credential
import com.gpic.android.data.cache.UploadCache
import com.gpic.android.data.dji.DjiFile
import com.gpic.android.data.progress.FileProgress
import com.gpic.android.data.progress.ProgressTracker
import com.gpic.android.data.progress.UploadStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.FileOutputStream
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException

/**
 * Port of gphotos/upload.py UploadManager
 * Simplified for Android: uses coroutines + semaphore for concurrency instead of ThreadPoolExecutor.
 */
class UploadManager(
    private val context: Context,
    private val credential: Credential,
    private val threads: Int = 3,
    private val force: Boolean = false,
    private val saver: Boolean = false,
    private val useQuota: Boolean = false,
    private val deleteAfter: Boolean = false,
) {
    private var api: GooglePhotosApi? = null
    private val cache by lazy { UploadCache(context) }

    val progress = ProgressTracker()
    private val _results = mutableListOf<UploadResult>()
    val results: List<UploadResult> get() = _results

    private val _events = MutableStateFlow<UploadEvent?>(null)
    val events: StateFlow<UploadEvent?> = _events

    // Expose copy of tracker for Compose
    private val _progressFlow = MutableStateFlow<Map<String, FileProgress>>(emptyMap())
    val progressFlow: StateFlow<Map<String, FileProgress>> = _progressFlow

    @Volatile private var cancelled = false

    /**
     * Snapshot tracker into StateFlow with deep copies.
     * Must copy each FileProgress: FileProgress is mutable, so a shallow
     * toMap() shares object refs and StateFlow's equality check treats
     * old == new and drops the emission (UI stuck at "Queued").
     */
    private fun emitProgress() {
        _progressFlow.value = progress.snapshot()
    }

    sealed class UploadEvent {
        data class FileProgressEvent(val fp: FileProgress): UploadEvent()
        data class BatchStart(val total: Int, val totalBytes: Long): UploadEvent()
        object UploadDone: UploadEvent()
        data class FileResult(val result: UploadResult): UploadEvent()
    }

    companion object {
        /**
         * Auto-detect optimal thread count – port of UploadManager.auto_threads()
         */
        fun autoThreads(files: List<DjiFile>): Int {
            if (files.isEmpty()) return 3
            val sizes = files.map { it.sizeBytes }
            val count = sizes.size
            val total = sizes.sum()
            val avg = if (count > 0) total / count else 0L
            val biggest = sizes.maxOrNull() ?: 0L
            var score = 4
            when {
                avg > 500_000_000 -> score -= 2
                avg > 50_000_000 -> {}
                avg > 5_000_000 -> score += 2
                else -> score += 4
            }
            if (biggest > 5_000_000_000) score = maxOf(score -1, 1)
            else if (biggest > 1_000_000_000) score = maxOf(score -1, 2)
            when {
                count >= 100 -> score += 2
                count >= 50 -> score += 1
                count <= 3 && avg > 100_000_000 -> score = minOf(score, 2)
            }
            return score.coerceIn(1, 12)
        }
    }

    fun cancel() {
        cancelled = true
        api?.cancel()
    }

    /**
     * Main entry: start upload for given DJI files.
     * For SAF Uris, we first copy to cache dir to get a File for uploading (needed for streaming & hash).
     */
    suspend fun start(files: List<DjiFile>) = withContext(Dispatchers.IO) {
        cancelled = false
        _results.clear()
        progress.reset()
        files.forEach { f -> progress.addFile(f.uri?.toString() ?: f.file?.absolutePath ?: f.displayName, f.sizeBytes) }
        emitProgress()
        _events.value = UploadEvent.BatchStart(progress.totalFiles, progress.totalBytes)

        api = GooglePhotosApi(
            authString = credential.authString,
            language = credential.language,
            saverMode = saver,
            useQuota = useQuota,
        )

        val effectiveThreads = if (threads <= 0) autoThreads(files) else threads
        Log.i("UploadManager","Starting ${files.size} files with $effectiveThreads threads force=$force")

        // Non-blocking coroutine semaphore: each file runs in async + withPermit.
        // (Old code used blocking java Semaphore.acquire() in the launch loop,
        // which blocks the parent IO thread and serializes startup.)
        val semaphore = Semaphore(effectiveThreads)
        coroutineScope {
            val deferreds = files.map { djiFile ->
                async {
                    if (cancelled) return@async
                    semaphore.withPermit {
                        try {
                            val result = if (force) uploadForce(djiFile) else uploadWithHash(djiFile)
                            synchronized(_results) { _results.add(result) }
                            _events.value = UploadEvent.FileResult(result)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e("UploadManager", "job failed ${e.message}")
                        } finally {
                            emitProgress()
                        }
                    }
                }
            }
            deferreds.forEach { d ->
                try { d.await() } catch (e: CancellationException) {} catch (e: Exception) { Log.e("UploadManager","job failed ${e.message}") }
            }
        }

        // Album handling could be added here if needed

        emitProgress()
        _events.value = UploadEvent.UploadDone
    }

    /**
     * Force mode: skip dedup, get token without hash, compute hash during upload
     */
    private suspend fun uploadForce(djiFile: DjiFile): UploadResult {
        val key = djiFile.uri?.toString() ?: djiFile.file?.absolutePath ?: djiFile.displayName
        val fp = progress.get(key)
        val api = api!!

        // Show preparing BEFORE the (potentially slow, GB-scale) SAF -> cache copy,
        // otherwise the file sits at "Queued" with no feedback during the copy.
        fp.status = UploadStatus.PREPARING
        fp.message = "Preparing…"
        emitProgress()
        val (file, tempFile) = resolveToFile(djiFile) ?: run {
            fp.status = UploadStatus.ERROR
            fp.message = "Cannot open file"
            fp.error = "Cannot open file"
            progress.incFailed()
            emitProgress()
            return UploadResult(key, djiFile.displayName, false, error = "Cannot open file")
        }

        try {
            fp.status = UploadStatus.UPLOADING
            fp.message = "Requesting upload token…"
            emitProgress()

            val uploadId = api.getUploadTokenSkipHash(file.length())

            fp.message = "Uploading…"
            val sha1Out = mutableListOf<ByteArray>()
            var lastEmit = 0L
            val commitToken = api.uploadFile(
                file = file,
                uploadId = uploadId,
                fileSize = file.length(),
                onProgress = { read, total ->
                    fp.updateBytes(read, total)
                    val now = System.currentTimeMillis()
                    if (now - lastEmit > 200) {
                        lastEmit = now
                        emitProgress()
                    }
                },
                resumeOffset = 0,
                computeHash = true,
                hashOut = sha1Out
            )
            val sha1 = sha1Out.firstOrNull() ?: api.calculateSha1(file)
            fp.bytesUploaded = file.length()
            emitProgress()

            fp.status = UploadStatus.COMMITTING
            fp.message = "Finalizing…"
            emitProgress()
            val mediaKey = api.commitUpload(commitToken, djiFile.displayName, sha1, (file.lastModified()/1000).toInt().toLong() + 0)

            fp.status = UploadStatus.COMPLETED
            fp.message = "Uploaded"
            fp.bytesUploaded = file.length()
            progress.incCompleted()
            emitProgress()

            if (deleteAfter) tryDeleteOriginal(djiFile)

            return UploadResult(key, djiFile.displayName, true, mediaKey)
        } catch (e: Exception) {
            fp.status = UploadStatus.ERROR
            fp.message = e.message ?: "Failed"
            fp.error = e.message
            progress.incFailed()
            emitProgress()
            Log.e("UploadManager","Force upload failed ${djiFile.displayName}: ${e.message}", e)
            return UploadResult(key, djiFile.displayName, false, error = e.message ?: "Failed")
        } finally {
            tempFile?.delete()
        }
    }

    /**
     * Normal mode: pre-hash, dedup check, then upload with resume support
     */
    private suspend fun uploadWithHash(djiFile: DjiFile): UploadResult {
        val key = djiFile.uri?.toString() ?: djiFile.file?.absolutePath ?: djiFile.displayName
        val fp = progress.get(key)
        val api = api!!

        fp.status = UploadStatus.PREPARING
        fp.message = "Preparing…"
        emitProgress()
        val (file, tempFile) = resolveToFile(djiFile) ?: run {
            fp.status = UploadStatus.ERROR
            fp.message = "Cannot open file"
            fp.error = "Cannot open file"
            progress.incFailed()
            emitProgress()
            return UploadResult(key, djiFile.displayName, false, error = "Cannot open file")
        }

        try {
            // Phase 1: hash
            fp.status = UploadStatus.HASHING
            fp.message = "Calculating hash…"
            emitProgress()
            val sha1 = api.calculateSha1(file)
            val sha1B64 = Base64.getEncoder().encodeToString(sha1)

            if (cancelled) return UploadResult(key, djiFile.displayName, false, error="Cancelled")

            // Phase 2: dedup check
            if (!force) {
                fp.status = UploadStatus.CHECKING
                fp.message = "Checking if already backed up…"
                emitProgress()
                val existingKey = api.findMediaByHash(sha1)
                if (existingKey.isNotEmpty()) {
                    fp.status = UploadStatus.SKIPPED
                    fp.message = "Already in library"
                    progress.incSkipped()
                    emitProgress()
                    if (deleteAfter) tryDeleteOriginal(djiFile)
                    return UploadResult(key, djiFile.displayName, true, existingKey, skipped = true)
                }
            }

            if (cancelled) return UploadResult(key, djiFile.displayName, false, error="Cancelled")

            // Phase 3: resume cache check or new token
            val fileSize = file.length()
            var uploadId: String? = null
            var resumeOffset: Long? = null
            var commitToken: gphotos.CommitTokenOuterClass.CommitToken? = null

            val cached = cache.get(key)
            if (cached != null) {
                val cachedId = cached.optString("upload_id","")
                val (offset, token) = api.tryResumeSession(cachedId, fileSize)
                if (token != null) commitToken = token
                else if (offset != null) {
                    uploadId = cachedId
                    resumeOffset = offset
                    fp.status = UploadStatus.RESUMING
                    fp.resumeOffset = offset
                    fp.updateBytes(offset, fileSize)
                    val pct = if (fileSize>0) offset.toFloat()/fileSize.toFloat()*100f else 0f
                    fp.message = "Resuming from ${"%.1f".format(pct)}%"
                    emitProgress()
                }
            }

            if (uploadId == null && commitToken == null) {
                fp.status = UploadStatus.UPLOADING
                fp.message = "Requesting upload token…"
                emitProgress()
                uploadId = api.getUploadToken(sha1B64, fileSize)
                cache.set(key, uploadId, fileSize)
            }

            if (commitToken == null) {
                if (fp.status != UploadStatus.RESUMING) fp.status = UploadStatus.UPLOADING
                fp.message = "Uploading…"
                emitProgress()
                var lastEmit = 0L
                commitToken = api.uploadFile(
                    file = file,
                    uploadId = uploadId!!,
                    fileSize = fileSize,
                    onProgress = { read, total ->
                        fp.updateBytes(read, total)
                        if (fp.status == UploadStatus.RESUMING && read >= (fp.resumeOffset + 1024*1024)) {
                            fp.status = UploadStatus.UPLOADING
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastEmit > 200) {
                            lastEmit = now
                            emitProgress()
                        }
                    },
                    resumeOffset = resumeOffset ?: 0
                )
                fp.bytesUploaded = fileSize
                emitProgress()
            }

            cache.remove(key)

            fp.status = UploadStatus.COMMITTING
            fp.message = "Finalizing…"
            emitProgress()
            val mediaKey = api.commitUpload(commitToken!!, djiFile.displayName, sha1, file.lastModified()/1000)

            fp.status = UploadStatus.COMPLETED
            fp.message = "Uploaded"
            progress.incCompleted()
            emitProgress()

            if (deleteAfter) tryDeleteOriginal(djiFile)

            return UploadResult(key, djiFile.displayName, true, mediaKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fp.status = UploadStatus.ERROR
            fp.message = e.message ?: "Failed"
            progress.incFailed()
            emitProgress()
            Log.e("UploadManager","Upload failed ${djiFile.displayName}: ${e.message}", e)
            return UploadResult(key, djiFile.displayName, false, error = e.message ?: "Failed")
        } finally {
            tempFile?.delete()
        }
    }

    /**
     * For SAF Uris, copy to app cache so OkHttp can stream with Content-Length & resume.
     * For direct File, return as-is.
     */
    private fun resolveToFile(djiFile: DjiFile): Pair<File, File?>? {
        djiFile.file?.let { return it to null }
        val uri = djiFile.uri ?: return null
        return try {
            val safeName = djiFile.displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val temp = File(context.cacheDir, "upload_${System.nanoTime()}_${keyHash(uri.toString())}_$safeName")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp).use { output ->
                    input.copyTo(output)
                }
            } ?: return null
            // Preserve last modified for commitUpload timestamp; approximate with doc lastModified
            if (djiFile.lastModified > 0) temp.setLastModified(djiFile.lastModified)
            temp to temp
        } catch (e: Exception) {
            Log.e("UploadManager","resolveToFile failed $uri: ${e.message}")
            null
        }
    }

    private fun keyHash(key: String): String {
        // Short stable hash to disambiguate same displayName in different folders.
        return key.hashCode().toUInt().toString(16)
    }

    private fun tryDeleteOriginal(djiFile: DjiFile) {
        try {
            djiFile.file?.delete()
            djiFile.uri?.let { uri ->
                // For SAF, try DocumentsContract delete
                try { DocumentsContract.deleteDocument(context.contentResolver, uri) } catch (e: Exception) {}
            }
        } catch (e: Exception) {}
    }
}
