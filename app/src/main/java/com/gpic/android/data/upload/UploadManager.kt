package com.gpic.android.data.upload

import android.content.Context
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
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException

/**
 * Port of gphotos/upload.py UploadManager
 * Uses coroutines + semaphore for concurrency.
 * Streams directly from SAF Uri (no temp copy) to avoid "stuck at Preparing".
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

    private val _progressFlow = MutableStateFlow<Map<String, FileProgress>>(emptyMap())
    val progressFlow: StateFlow<Map<String, FileProgress>> = _progressFlow

    @Volatile private var cancelled = false

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

    suspend fun start(files: List<DjiFile>) = withContext(Dispatchers.IO) {
        cancelled = false
        _results.clear()
        progress.reset()
        // Smallest-first: quick wins, avoids head-of-line blocking on GB videos.
        val ordered = files.sortedBy { it.sizeBytes }
        ordered.forEach { f -> progress.addFile(f.uri?.toString() ?: f.file?.absolutePath ?: f.displayName, f.sizeBytes) }
        emitProgress()
        _events.value = UploadEvent.BatchStart(progress.totalFiles, progress.totalBytes)

        api = GooglePhotosApi(
            authString = credential.authString,
            language = credential.language,
            saverMode = saver,
            useQuota = useQuota,
        )

        val effectiveThreads = if (threads <= 0) autoThreads(ordered) else threads
        Log.i("UploadManager","Starting ${ordered.size} files smallest-first with $effectiveThreads threads force=$force")

        val semaphore = Semaphore(effectiveThreads)
        coroutineScope {
            val deferreds = ordered.map { djiFile ->
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

        emitProgress()
        _events.value = UploadEvent.UploadDone
    }

    private fun authService(): String {
        return try {
            credential.authString.split("&").firstOrNull { it.startsWith("service=") }?.substringAfter("=")?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: "(none)"
        } catch (_: Exception) { "(unknown)" }
    }

    private fun hint403(base: String?, phase: String): String {
        val b = base ?: "Failed"
        if (!b.contains("403")) return b
        val svc = authService()
        val extra = if (svc.contains("userinfo")) " You pasted the WRONG logcat line (Google login, not Photos). Filter logcat by photos.native." else if (!svc.contains("photos")) " Service does not look like Photos internal API." else ""
        return b + " | 403 Forbidden at " + phase + " (service=" + svc + ")." + extra + " Bearer OK but scope denied: adb logcat | grep photos.native, reopen Photos, copy FULL androidId=...&service=... line, retry."
    }

    private fun openStreamFor(djiFile: DjiFile): () -> java.io.InputStream {
        val uri = djiFile.uri ?: throw RuntimeException("No Uri for ${djiFile.displayName}")
        return {
            context.contentResolver.openInputStream(uri)
                ?: throw RuntimeException("Cannot open DJI file (null stream). Re-pick folder/files.")
        }
    }

    private fun checkOpenable(djiFile: DjiFile, fp: FileProgress, key: String): String? {
        // Returns error message or null if openable.
        val uri = djiFile.uri
        if (djiFile.file != null) {
            if (!djiFile.file.exists()) return "Local file missing: ${djiFile.displayName}"
            return null
        }
        if (uri == null) return "Cannot open file"
        if (djiFile.sizeBytes <= 0) return "Empty or unreadable file (0 bytes)"
        return try {
            context.contentResolver.openInputStream(uri)?.close()
            null
        } catch (e: SecurityException) {
            "Permission lost for ${djiFile.displayName}. Re-pick folder/files, then retry. (${e.message})"
        } catch (e: java.io.FileNotFoundException) {
            "DJI file not found (disconnected?): ${djiFile.displayName}"
        } catch (e: Exception) {
            "Cannot open DJI file: ${e.message}. Re-pick folder/files."
        }
    }

    private suspend fun uploadForce(djiFile: DjiFile): UploadResult {
        val key = djiFile.uri?.toString() ?: djiFile.file?.absolutePath ?: djiFile.displayName
        val fp = progress.get(key)
        val api = api!!

        fp.status = UploadStatus.PREPARING
        fp.message = "Opening file…"
        fp.totalBytes = djiFile.sizeBytes
        emitProgress()

        djiFile.file?.let { f ->
            return uploadForceFile(djiFile, key, fp, api, f)
        }

        val openErr = checkOpenable(djiFile, fp, key)
        if (openErr != null) {
            fp.status = UploadStatus.ERROR
            fp.message = openErr
            fp.error = openErr
            progress.incFailed()
            emitProgress()
            return UploadResult(key, djiFile.displayName, false, error = openErr)
        }

        val fileSize = djiFile.sizeBytes
        val openStream = openStreamFor(djiFile)
        val timestampSec = if (djiFile.lastModified > 0) djiFile.lastModified / 1000 else System.currentTimeMillis() / 1000

        try {
            fp.status = UploadStatus.UPLOADING
            fp.message = "Requesting upload token…"
            emitProgress()
            val uploadId = try {
                api.getUploadTokenSkipHash(fileSize)
            } catch (e: Exception) {
                throw RuntimeException("Token request failed: ${e.message}")
            }
            fp.message = "Uploading…"
            emitProgress()
            val sha1Out = mutableListOf<ByteArray>()
            var lastEmit = 0L
            val commitToken = api.uploadStream(
                openStream = openStream,
                fileSize = fileSize,
                uploadId = uploadId,
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
            val sha1 = try {
                sha1Out.firstOrNull() ?: api.calculateSha1WithProgress(openStream, fileSize, null)
            } catch (e: Exception) {
                throw RuntimeException("Hash failed: ${e.message}")
            }
            fp.bytesUploaded = fileSize
            emitProgress()
            fp.status = UploadStatus.COMMITTING
            fp.message = "Finalizing…"
            emitProgress()
            val mediaKey = try {
                api.commitUpload(commitToken, djiFile.displayName, sha1, timestampSec)
            } catch (e: Exception) {
                throw RuntimeException("Finalize failed: ${e.message}")
            }
            fp.status = UploadStatus.COMPLETED
            fp.message = "Uploaded"
            fp.bytesUploaded = fileSize
            progress.incCompleted()
            emitProgress()
            if (deleteAfter) tryDeleteOriginal(djiFile)
            return UploadResult(key, djiFile.displayName, true, mediaKey)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            fp.status = UploadStatus.ERROR
            fp.message = hint403(e.message, fp.status.name)
            fp.error = e.message
            progress.incFailed()
            emitProgress()
            Log.e("UploadManager", "Force upload failed ${djiFile.displayName}: ${e.message}", e)
            return UploadResult(key, djiFile.displayName, false, error = e.message ?: "Failed")
        }
    }

    private suspend fun uploadForceFile(
        djiFile: DjiFile,
        key: String,
        fp: FileProgress,
        api: GooglePhotosApi,
        f: java.io.File,
    ): UploadResult {
        val fileSize = f.length()
        fp.totalBytes = fileSize
        try {
            fp.status = UploadStatus.UPLOADING
            fp.message = "Requesting upload token…"
            emitProgress()
            val uploadId = api.getUploadTokenSkipHash(fileSize)
            fp.message = "Uploading…"
            emitProgress()
            val sha1Out = mutableListOf<ByteArray>()
            var lastEmit = 0L
            val commitToken = api.uploadFile(
                file = f,
                uploadId = uploadId,
                fileSize = fileSize,
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
            val sha1 = sha1Out.firstOrNull() ?: api.calculateSha1(f)
            fp.bytesUploaded = fileSize
            emitProgress()
            fp.status = UploadStatus.COMMITTING
            fp.message = "Finalizing…"
            emitProgress()
            val mediaKey = api.commitUpload(commitToken, djiFile.displayName, sha1, f.lastModified() / 1000)
            fp.status = UploadStatus.COMPLETED
            fp.message = "Uploaded"
            fp.bytesUploaded = fileSize
            progress.incCompleted()
            emitProgress()
            if (deleteAfter) tryDeleteOriginal(djiFile)
            return UploadResult(key, djiFile.displayName, true, mediaKey)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            fp.status = UploadStatus.ERROR
            fp.message = hint403(e.message, fp.status.name)
            fp.error = e.message
            progress.incFailed()
            emitProgress()
            return UploadResult(key, djiFile.displayName, false, error = e.message ?: "Failed")
        }
    }

    private suspend fun uploadWithHash(djiFile: DjiFile): UploadResult {
        val key = djiFile.uri?.toString() ?: djiFile.file?.absolutePath ?: djiFile.displayName
        val fp = progress.get(key)
        val api = api!!

        fp.status = UploadStatus.PREPARING
        fp.message = "Opening file…"
        fp.totalBytes = djiFile.sizeBytes
        emitProgress()

        djiFile.file?.let { f ->
            return uploadWithHashFile(djiFile, key, fp, api, f)
        }

        val openErr = checkOpenable(djiFile, fp, key)
        if (openErr != null) {
            fp.status = UploadStatus.ERROR
            fp.message = openErr
            fp.error = openErr
            progress.incFailed()
            emitProgress()
            return UploadResult(key, djiFile.displayName, false, error = openErr)
        }

        val fileSize = djiFile.sizeBytes
        val openStream = openStreamFor(djiFile)
        val timestampSec = if (djiFile.lastModified > 0) djiFile.lastModified / 1000 else System.currentTimeMillis() / 1000

        try {
            fp.status = UploadStatus.HASHING
            fp.message = "Calculating hash…"
            fp.bytesUploaded = 0
            emitProgress()
            var lastHashEmit = 0L
            val sha1 = try {
                api.calculateSha1WithProgress(openStream, fileSize) { read, total ->
                    fp.updateBytes((read * 0.1).toLong().coerceAtMost(total), total)
                    val now = System.currentTimeMillis()
                    if (now - lastHashEmit > 300) {
                        lastHashEmit = now
                        fp.message = "Hashing ${((read.toFloat()/total*100).toInt())}%…"
                        emitProgress()
                    }
                }
            } catch (e: Exception) {
                throw RuntimeException("Hash failed: ${e.message}")
            }
            // Reset progress for upload phase (hash was 0-10% preview)
            fp.bytesUploaded = 0
            val sha1B64 = Base64.getEncoder().encodeToString(sha1)
            if (cancelled) return UploadResult(key, djiFile.displayName, false, error="Cancelled")

            if (!force) {
                fp.status = UploadStatus.CHECKING
                fp.message = "Checking if already backed up…"
                emitProgress()
                val existingKey = try {
                    api.findMediaByHash(sha1)
                } catch (e: Exception) {
                    throw RuntimeException("Check failed: ${e.message}")
                }
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

            var uploadId: String? = null
            var resumeOffset: Long? = null
            var commitToken: gphotos.CommitTokenOuterClass.CommitToken? = null

            val cached = cache.get(key)
            if (cached != null) {
                val cachedId = cached.optString("upload_id","")
                if (cachedId.isNotEmpty()) {
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
            }

            if (uploadId == null && commitToken == null) {
                fp.status = UploadStatus.UPLOADING
                fp.message = "Requesting upload token…"
                emitProgress()
                uploadId = try {
                    api.getUploadToken(sha1B64, fileSize)
                } catch (e: Exception) {
                    throw RuntimeException("Token request failed: ${e.message}")
                }
                cache.set(key, uploadId, fileSize)
            }

            if (commitToken == null) {
                if (fp.status != UploadStatus.RESUMING) fp.status = UploadStatus.UPLOADING
                fp.message = "Uploading…"
                emitProgress()
                var lastEmit = 0L
                commitToken = api.uploadStream(
                    openStream = openStream,
                    fileSize = fileSize,
                    uploadId = uploadId!!,
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
            val mediaKey = try {
                api.commitUpload(commitToken!!, djiFile.displayName, sha1, timestampSec)
            } catch (e: Exception) {
                throw RuntimeException("Finalize failed: ${e.message}")
            }

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
            fp.message = hint403(e.message, fp.status.name)
            progress.incFailed()
            emitProgress()
            Log.e("UploadManager","Upload failed ${djiFile.displayName}: ${e.message}", e)
            return UploadResult(key, djiFile.displayName, false, error = e.message ?: "Failed")
        }
    }

    private suspend fun uploadWithHashFile(
        djiFile: DjiFile,
        key: String,
        fp: FileProgress,
        api: GooglePhotosApi,
        f: java.io.File,
    ): UploadResult {
        val fileSize = f.length()
        fp.totalBytes = fileSize
        try {
            fp.status = UploadStatus.HASHING
            fp.message = "Calculating hash…"
            emitProgress()
            var lastHashEmit = 0L
            val sha1 = api.calculateSha1(f) { read, total ->
                fp.updateBytes((read * 0.1).toLong().coerceAtMost(total), total)
                val now = System.currentTimeMillis()
                if (now - lastHashEmit > 300) {
                    lastHashEmit = now
                    emitProgress()
                }
            }
            fp.bytesUploaded = 0
            val sha1B64 = Base64.getEncoder().encodeToString(sha1)
            if (cancelled) return UploadResult(key, djiFile.displayName, false, error="Cancelled")
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
            var uploadId: String? = null
            var resumeOffset: Long? = null
            var commitToken: gphotos.CommitTokenOuterClass.CommitToken? = null
            val cached = cache.get(key)
            if (cached != null) {
                val cachedId = cached.optString("upload_id","")
                if (cachedId.isNotEmpty()) {
                    val (offset, token) = api.tryResumeSession(cachedId, fileSize)
                    if (token != null) commitToken = token
                    else if (offset != null) {
                        uploadId = cachedId
                        resumeOffset = offset
                        fp.status = UploadStatus.RESUMING
                        fp.resumeOffset = offset
                        fp.updateBytes(offset, fileSize)
                        emitProgress()
                    }
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
                    file = f,
                    uploadId = uploadId!!,
                    fileSize = fileSize,
                    onProgress = { read, total ->
                        fp.updateBytes(read, total)
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
            val mediaKey = api.commitUpload(commitToken!!, djiFile.displayName, sha1, f.lastModified()/1000)
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
            fp.message = hint403(e.message, fp.status.name)
            progress.incFailed()
            emitProgress()
            return UploadResult(key, djiFile.displayName, false, error = e.message ?: "Failed")
        }
    }

    private fun tryDeleteOriginal(djiFile: DjiFile) {
        try {
            djiFile.file?.delete()
            djiFile.uri?.let { uri ->
                try { DocumentsContract.deleteDocument(context.contentResolver, uri) } catch (e: Exception) {}
            }
        } catch (e: Exception) {}
    }
}
