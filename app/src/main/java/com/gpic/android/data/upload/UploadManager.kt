package com.gpic.android.data.upload

import android.content.Context
import android.util.Log
import com.gpic.android.data.api.GooglePhotosApi
import com.gpic.android.data.auth.Credential
import com.gpic.android.data.cache.UploadCache
import com.gpic.android.data.cache.UploadHistory
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
 * Uses coroutines + semaphore for concurrency, over any [MediaSource] (no temp copies).
 */
class UploadManager(
    private val context: Context,
    private val credential: Credential,
    private val threads: Int = 3,
    private val force: Boolean = false,
    private val saver: Boolean = false,
    private val useQuota: Boolean = false,
    private val deleteAfter: Boolean = false,
    /** Maps a list item to its byte source; camera files need the live camera link. */
    private val sourceFor: (DjiFile) -> MediaSource = { it.defaultSource(context) },
) {
    private var api: GooglePhotosApi? = null
    private val cache by lazy { UploadCache(context) }
    private val history by lazy { UploadHistory(context) }

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
        fun autoThreads(sizes: List<Long>): Int {
            if (sizes.isEmpty()) return 3
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
        val sources = files.sortedBy { it.sizeBytes }.map(sourceFor)
        sources.forEach { progress.addFile(it.key, it.sizeBytes, it.displayName) }
        emitProgress()
        _events.value = UploadEvent.BatchStart(progress.totalFiles, progress.totalBytes)

        api = GooglePhotosApi(
            authString = credential.authString,
            language = credential.language,
            saverMode = saver,
            useQuota = useQuota,
        )

        val requested = if (threads <= 0) autoThreads(sources.map { it.sizeBytes }) else threads
        // A source's cap wins over both "auto" and an explicit count: extra streams on an MTP camera
        // or the camera's WiFi only queue behind each other.
        val cap = sources.mapNotNull { it.maxParallel }.minOrNull()
        val effectiveThreads = if (cap != null) minOf(requested, cap) else requested
        Log.i("UploadManager","Starting ${sources.size} files smallest-first with $effectiveThreads threads force=$force")

        val semaphore = Semaphore(effectiveThreads)
        coroutineScope {
            sources.map { source ->
                async {
                    semaphore.withPermit {
                        // Queued workers wake here after cancel; don't open (and fail) their files.
                        if (cancelled) {
                            progress.get(source.key).status = UploadStatus.CANCELLED
                            return@withPermit
                        }
                        try {
                            val result = uploadOne(source)
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
            }.forEach { d ->
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

    private fun skipped(source: MediaSource, fp: FileProgress, mediaKey: String, why: String): UploadResult {
        fp.status = UploadStatus.SKIPPED
        fp.message = why
        progress.incSkipped()
        // A hash match is an exact byte-for-byte copy already in the library, so the original is as
        // safe to remove as one we just uploaded. A local-history match is name+size only — keep it.
        if (deleteAfter && why == ALREADY_IN_LIBRARY) deleteOriginal(source, fp)
        emitProgress()
        return UploadResult(source.key, source.displayName, true, mediaKey, skipped = true)
    }

    private fun deleteOriginal(source: MediaSource, fp: FileProgress) {
        val err = source.delete() ?: return
        Log.w("UploadManager", "delete-after failed: $err")
        fp.message = "${fp.message.ifEmpty { "Uploaded" }} • original kept: $err"
    }

    /**
     * One file, start to finish: hash → dedup → (resume or new token) → stream → commit.
     * [phase] tracks the step for error messages; it's captured before status flips to ERROR.
     */
    private fun uploadOne(source: MediaSource): UploadResult {
        val fp = progress.get(source.key)
        val api = api!!
        val size = source.sizeBytes
        var phase = UploadStatus.PREPARING
        fun enter(status: UploadStatus, msg: String) {
            phase = status
            fp.status = status
            fp.message = msg
            emitProgress()
        }

        enter(UploadStatus.PREPARING, "Opening file…")
        fp.totalBytes = size
        source.checkReadable()?.let { err ->
            fp.status = UploadStatus.ERROR
            fp.message = err
            fp.error = err
            progress.incFailed()
            emitProgress()
            return UploadResult(source.key, source.displayName, false, error = err)
        }

        try {
            // Hash first when a second read is cheap: it lets Google tell us the file is already
            // backed up before we send a byte. Single-pass sources (camera WiFi) hash while uploading.
            var sha1: ByteArray? = null
            if (!force && !source.singlePass) {
                enter(UploadStatus.HASHING, "Calculating hash…")
                var lastHashEmit = 0L
                sha1 = api.calculateSha1WithProgress(source::open, size) { read, total ->
                    fp.updateBytes((read * 0.1).toLong().coerceAtMost(total), total)
                    val now = System.currentTimeMillis()
                    if (now - lastHashEmit > 300) {
                        lastHashEmit = now
                        fp.message = "Hashing ${(read.toFloat() / total * 100).toInt()}%…"
                        emitProgress()
                    }
                }
                fp.bytesUploaded = 0
                if (cancelled) return cancelledResult(source, fp)

                enter(UploadStatus.CHECKING, "Checking if already backed up…")
                val existingKey = api.findMediaByHash(sha1)
                if (existingKey.isNotEmpty()) return skipped(source, fp, existingKey, ALREADY_IN_LIBRARY)
            } else if (!force) {
                history.find(source.displayName, size)?.let { return skipped(source, fp, it, "Uploaded before from this phone") }
            }
            if (cancelled) return cancelledResult(source, fp)

            // Resume an interrupted session for this file if the server still has it.
            var uploadId: String? = null
            var resumeOffset = 0L
            var commitToken: gphotos.CommitTokenOuterClass.CommitToken? = null
            cache.get(source.key)?.optString("upload_id", "")?.takeIf { it.isNotEmpty() }?.let { cachedId ->
                val (nextByte, token) = api.tryResumeSession(cachedId, size)
                if (token != null) commitToken = token
                else if (nextByte != null) {
                    uploadId = cachedId
                    resumeOffset = nextByte
                    fp.resumeOffset = nextByte
                    fp.updateBytes(nextByte, size)
                    enter(UploadStatus.RESUMING, "Resuming from ${"%.1f".format(if (size > 0) nextByte * 100f / size else 0f)}%")
                }
            }

            if (uploadId == null && commitToken == null) {
                enter(UploadStatus.UPLOADING, "Requesting upload token…")
                uploadId = if (sha1 != null) api.getUploadToken(Base64.getEncoder().encodeToString(sha1), size)
                else api.getUploadTokenSkipHash(size)
                cache.set(source.key, uploadId!!, size)
            }

            if (commitToken == null) {
                if (fp.status != UploadStatus.RESUMING) enter(UploadStatus.UPLOADING, "Uploading…") else phase = UploadStatus.RESUMING
                val hashOut = if (sha1 == null) mutableListOf<ByteArray>() else null
                commitToken = api.uploadStream(
                    openStream = source::open,
                    fileSize = size,
                    uploadId = uploadId!!,
                    onProgress = { read, total ->
                        if (fp.status == UploadStatus.RESUMING && read >= fp.resumeOffset + 1024 * 1024) {
                            fp.status = UploadStatus.UPLOADING
                        }
                        if (fp.trackUpload(read, total)) emitProgress()
                    },
                    resumeOffset = resumeOffset,
                    hashOut = hashOut,
                )
                if (sha1 == null) sha1 = hashOut?.firstOrNull()
                fp.bytesUploaded = size
            }
            // No whole-file hash yet (the upload resumed mid-file): one extra read to get it right.
            if (sha1 == null) {
                enter(UploadStatus.HASHING, "Hashing for commit…")
                sha1 = api.calculateSha1WithProgress(source::open, size)
            }

            enter(UploadStatus.COMMITTING, "Finalizing…")
            val mediaKey = api.commitUpload(commitToken!!, source.displayName, sha1!!, source.lastModifiedMs.takeIf { it > 0 }?.div(1000) ?: (System.currentTimeMillis() / 1000))
            cache.remove(source.key)
            history.record(source.displayName, size, mediaKey)

            fp.status = UploadStatus.COMPLETED
            fp.message = "Uploaded"
            fp.bytesUploaded = size
            progress.incCompleted()
            if (deleteAfter) deleteOriginal(source, fp)
            emitProgress()
            return UploadResult(source.key, source.displayName, true, mediaKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (cancelled) return cancelledResult(source, fp)
            fp.status = UploadStatus.ERROR
            fp.message = hint403(e.message, phase.name)
            fp.error = e.message
            progress.incFailed()
            emitProgress()
            Log.e("UploadManager", "Upload failed ${source.displayName} at $phase: ${e.message}", e)
            return UploadResult(source.key, source.displayName, false, error = e.message ?: "Failed")
        }
    }

    private fun cancelledResult(source: MediaSource, fp: FileProgress): UploadResult {
        fp.status = UploadStatus.CANCELLED
        fp.message = "Cancelled"
        emitProgress()
        return UploadResult(source.key, source.displayName, false, error = "Cancelled")
    }
}

private const val ALREADY_IN_LIBRARY = "Already in library"
