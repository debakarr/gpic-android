package com.gpic.android.data.upload

import android.content.Context
import android.util.Log
import com.gpic.android.data.albums.IndexedMedia
import com.gpic.android.data.albums.MediaIndex
import com.gpic.android.data.api.GooglePhotosApi
import com.gpic.android.data.auth.Credential
import com.gpic.android.data.cache.UploadCache
import com.gpic.android.data.cache.CheckedCache
import com.gpic.android.data.cache.UploadHistory
import com.gpic.android.data.dji.DjiFile
import com.gpic.android.data.progress.FileProgress
import com.gpic.android.data.progress.ProgressTracker
import com.gpic.android.data.progress.UploadStatus
import com.gpic.android.util.Perf
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
    private val checked by lazy { CheckedCache(context) }

    val progress = ProgressTracker()
    private val _results = mutableListOf<UploadResult>()
    val results: List<UploadResult> get() = _results

    private val _events = MutableStateFlow<UploadEvent?>(null)
    val events: StateFlow<UploadEvent?> = _events

    private val _progressFlow = MutableStateFlow<Map<String, FileProgress>>(emptyMap())
    val progressFlow: StateFlow<Map<String, FileProgress>> = _progressFlow

    @Volatile private var cancelled = false
    private val perf = Perf()

    @Volatile private var progressDirty = false

    /**
     * Marks progress changed. The copy for the UI is made by a ticker in [start] a few times a second: a
     * snapshot copies every row, and per-step emits made big batches (thousands of WhatsApp photos) quadratic.
     */
    private fun emitProgress() {
        progressDirty = true
    }

    private fun publishProgress() = perf.time("emit") {
        progressDirty = false
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
        publishProgress()
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

        val batchStart = System.nanoTime()
        // Two pools: uploads use the thread setting; the "already backed up?" step (a read + one round trip of
        // ~0.5 s) gets at least CHECK_PARALLEL, so big batches of small files aren't paced by the upload count.
        val uploadSlots = Semaphore(effectiveThreads)
        val checkSlots = Semaphore(maxOf(effectiveThreads, cap?.let { minOf(it, CHECK_PARALLEL) } ?: CHECK_PARALLEL))
        try { coroutineScope {
            val ticker = launch {
                while (isActive) {
                    delay(PROGRESS_INTERVAL_MS)
                    if (progressDirty) publishProgress()
                }
            }
            sources.map { source ->
                async {
                    try {
                        val result = uploadOne(source, checkSlots, uploadSlots) ?: return@async
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
            }.forEach { d ->
                try { d.await() } catch (e: CancellationException) {} catch (e: Exception) { Log.e("UploadManager","job failed ${e.message}") }
            }
            ticker.cancel()
        } } finally {
            // Also on cancellation: both stores batch their writes.
            perf.time("indexFlush") { MediaIndex.get(context).flush() }
            perf.time("historyWrite") { history.flush(); checked.flush() }
        }
        publishProgress()
        Log.i(Perf.TAG, "batch ${sources.size} files, $effectiveThreads threads: ${(System.nanoTime() - batchStart) / 1_000_000}ms total; ${perf.summary()}")
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

    /** Remember what's in the library for album suggestions. Before any delete: it reads the file. */
    private fun index(source: MediaSource, mediaKey: String) {
        if (mediaKey.isEmpty()) return
        perf.time("index") { runCatching {
            val c = source.captureInfo()
            MediaIndex.get(context).record(IndexedMedia(mediaKey, source.displayName, c.takenMs, c.lat, c.lon, source.thumbUri))
        }.onFailure { Log.w("UploadManager", "index failed for ${source.displayName}: ${it.message}") } }
    }

    private fun skipped(source: MediaSource, fp: FileProgress, mediaKey: String, why: String): UploadResult {
        // A remembered check was indexed when it was made; re-reading the file is what it saves.
        if (why != CHECKED_BEFORE) index(source, mediaKey)
        if (why == ALREADY_IN_LIBRARY) remember(source, mediaKey)
        fp.status = UploadStatus.SKIPPED
        fp.message = why
        progress.incSkipped()
        // A hash match is an exact byte-for-byte copy already in the library, so the original is as
        // safe to remove as one we just uploaded. A local-history match is name+size only — keep it.
        if (deleteAfter && why == ALREADY_IN_LIBRARY) deleteOriginal(source, fp)
        emitProgress()
        return UploadResult(source.key, source.displayName, true, mediaKey, skipped = true)
    }

    /**
     * Remember a confirmed file so a re-run skips it. Not one read without its location: once the permission
     * is granted, the next run must read the original and upload it with GPS.
     */
    private fun remember(source: MediaSource, mediaKey: String) {
        if (!source.mayHaveLostLocation) checked.record(source.key, source.sizeBytes, source.lastModifiedMs, mediaKey)
    }

    private fun deleteOriginal(source: MediaSource, fp: FileProgress) {
        val done = fp.message.ifEmpty { "Uploaded" }
        // What Google has may be missing the GPS Android hid from us; the original is the only copy with it.
        if (source.mayHaveLostLocation) {
            fp.message = "$done • original kept: " + if (source.originalHidden) "it's the only copy with the location"
            else if (OriginalMedia.hasLocationPermission(context))
                "Android didn't hand GPic the original, so its location may not have uploaded"
            else "allow GPic photo and video access so it can read the location"
            return
        }
        val err = source.delete()
        if (err == null) {
            fp.deleted = true
            progress.incDeleted()
            fp.message = "$done • deleted from phone"
            return
        }
        Log.w("UploadManager", "delete-after failed: $err")
        fp.message = "$done • original kept: $err"
    }

    /**
     * One file, start to finish: hash → dedup → (resume or new token) → stream → commit.
     * [phase] tracks the step for error messages; it's captured before status flips to ERROR.
     * Everything up to the dedup answer holds a [checkSlots] permit, the transfer an [uploadSlots] one.
     * Null when the batch was cancelled before this file started.
     */
    private suspend fun uploadOne(source: MediaSource, checkSlots: Semaphore, uploadSlots: Semaphore): UploadResult? {
        checkSlots.acquire()
        var checking = true
        fun endCheck() { if (checking) { checking = false; checkSlots.release() } }
        try {
            // Queued workers wake here after cancel; don't open (and fail) their files.
            if (cancelled) {
                progress.get(source.key).status = UploadStatus.CANCELLED
                return null
            }
            return uploadOneChecked(source, ::endCheck, uploadSlots)
        } finally {
            endCheck()
        }
    }

    private suspend fun uploadOneChecked(source: MediaSource, endCheck: () -> Unit, uploadSlots: Semaphore): UploadResult {
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

        fp.totalBytes = size
        // Confirmed in the library on an earlier run and unchanged since: skip without reading it. Not when
        // originals get deleted (Free up space): that needs today's exact-hash answer.
        val remembered = if (force || source.singlePass) null else checked.find(source.key, size, source.lastModifiedMs)
        if (remembered == CheckedCache.REJECTED) {
            return rejected(source, fp, "Google Photos didn't accept this file on an earlier run, so it isn't sent again")
        }
        if (remembered != null && !deleteAfter) return skipped(source, fp, remembered, CHECKED_BEFORE)
        enter(UploadStatus.PREPARING, "Opening file…")
        // With the permission granted, a missing original is Android not handing it over (seen once right after
        // 40 files were copied in). Uploading the stripped read would leave a GPS-less duplicate; fail so Retry can
        // fetch the original. Without the permission the user chose stripped uploads (they're just never deleted).
        // Where Android never hands it over (.nomedia folders), upload what we can read; it's never deleted.
        val withheld = if (source.knownStrippedGps && !source.originalHidden && OriginalMedia.hasLocationPermission(context))
            "Android didn't hand over the original file, so its location would be lost. Retry in a moment." else null
        (withheld ?: perf.time("prepare") { source.checkReadable() })?.let { err ->
            fp.status = UploadStatus.ERROR
            fp.message = err
            fp.error = err
            progress.incFailed()
            emitProgress()
            return UploadResult(source.key, source.displayName, false, error = err)
        }
        // A file named like media but holding something else would become a broken item in Google Photos.
        if (!source.singlePass) {
            val notMedia = runCatching { source.open(0).use { MediaSniff.mismatch(source.displayName, readHead(it, MediaSniff.HEAD_BYTES)) } }.getOrNull()
            if (notMedia != null) {
                checked.recordRejected(source.key, size, source.lastModifiedMs)
                return rejected(source, fp, notMedia)
            }
        }

        try {
            // Hash first when a second read is cheap: it lets Google tell us the file is already
            // backed up before we send a byte. Single-pass sources (camera WiFi) hash while uploading.
            var sha1: ByteArray? = null
            var staleKey: String? = null
            if (!force && !source.singlePass) {
                enter(UploadStatus.HASHING, "Calculating hash…")
                var lastHashEmit = 0L
                sha1 = perf.time("hash") { api.calculateSha1WithProgress(source::open, size) { read, total ->
                    fp.updateBytes((read * 0.1).toLong().coerceAtMost(total), total)
                    val now = System.currentTimeMillis()
                    if (now - lastHashEmit > 300) {
                        lastHashEmit = now
                        fp.message = "Hashing ${(read.toFloat() / total * 100).toInt()}%…"
                        emitProgress()
                    }
                } }
                fp.bytesUploaded = 0
                if (cancelled) return cancelledResult(source, fp)

                Log.i("GPicMedia", "${source.displayName} sha1=${sha1.joinToString("") { "%02x".format(it) }} lostLocation=${source.mayHaveLostLocation}")
                enter(UploadStatus.CHECKING, "Checking if already backed up…")
                val existingKey = perf.time("hashCheck") { api.findMediaByHash(sha1) }
                if (existingKey.isNotEmpty()) return skipped(source, fp, existingKey, ALREADY_IN_LIBRARY)
                // Not there as-is. An older GPic read this photo with its GPS stripped; if that copy is
                // what's in the library, this upload is the one that brings the location.
                source.openRedacted?.let { openPlain -> perf.time("plainRecheck") {
                    enter(UploadStatus.CHECKING, "Checking for a copy without location…")
                    val plain = api.calculateSha1WithProgress(openPlain, size)
                    if (!plain.contentEquals(sha1)) staleKey = api.findMediaByHash(plain).ifEmpty { null }
                    Log.i("GPicMedia", "${source.displayName} plain sha1=${plain.joinToString("") { "%02x".format(it) }} staleCopy=${staleKey != null}")
                    if (cancelled) return cancelledResult(source, fp)
                } }
            } else if (!force) {
                history.find(source.displayName, size)?.let { return skipped(source, fp, it, "Uploaded before from this phone") }
            }
            if (cancelled) return cancelledResult(source, fp)
            endCheck()
            enter(UploadStatus.QUEUED, "Not backed up yet • waiting for an upload slot")
            return uploadSlots.withPermit {
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
                    uploadId = perf.time("token") { if (sha1 != null) api.getUploadToken(Base64.getEncoder().encodeToString(sha1), size)
                    else api.getUploadTokenSkipHash(size) }
                    perf.time("cacheWrite") { cache.set(source.key, uploadId!!, size) }
                }

                if (commitToken == null) {
                    if (fp.status != UploadStatus.RESUMING) enter(UploadStatus.UPLOADING, "Uploading…") else phase = UploadStatus.RESUMING
                    val hashOut = if (sha1 == null) mutableListOf<ByteArray>() else null
                    commitToken = perf.time("upload") { api.uploadStream(
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
                    ) }
                    if (sha1 == null) sha1 = hashOut?.firstOrNull()
                    fp.bytesUploaded = size
                }
                // No whole-file hash yet (the upload resumed mid-file): one extra read to get it right.
                if (sha1 == null) {
                    enter(UploadStatus.HASHING, "Hashing for commit…")
                    sha1 = api.calculateSha1WithProgress(source::open, size)
                }

                enter(UploadStatus.COMMITTING, "Finalizing…")
                val mediaKey = perf.time("commit") { api.commitUpload(commitToken!!, source.displayName, sha1!!, source.lastModifiedMs.takeIf { it > 0 }?.div(1000) ?: (System.currentTimeMillis() / 1000)) }
                perf.time("cacheWrite") { cache.remove(source.key) }
                perf.time("historyWrite") {
                history.record(source.displayName, size, mediaKey)
                remember(source, mediaKey)
            }

                fp.status = UploadStatus.COMPLETED
                fp.message = if (source.knownStrippedGps) "Uploaded without its location: Android hides it from apps in folders with a .nomedia file" else "Uploaded"
                fp.bytesUploaded = size
                progress.incCompleted()
                index(source, mediaKey)
                if (staleKey != null) {
                    fp.staleKey = staleKey
                    fp.message = "Uploaded with location • the older copy without it is still in Google Photos (delete it there)"
                    progress.incRelocated()
                }
                if (deleteAfter) deleteOriginal(source, fp)
                emitProgress()
                UploadResult(source.key, source.displayName, true, mediaKey)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: com.gpic.android.data.api.RejectedByPhotosException) {
            cache.remove(source.key)
            checked.recordRejected(source.key, size, source.lastModifiedMs)
            return rejected(source, fp, "Google Photos didn't accept this file (it doesn't take WhatsApp stickers, for example). It won't be sent again")
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

    /** Final, not an error: Retry would only be refused again. The original is never deleted. */
    private fun rejected(source: MediaSource, fp: FileProgress, why: String): UploadResult {
        fp.status = UploadStatus.REJECTED
        fp.message = why
        progress.incRejected()
        emitProgress()
        Log.i("UploadManager", "${source.displayName}: not accepted by Google Photos")
        return UploadResult(source.key, source.displayName, false, error = why)
    }

    private fun cancelledResult(source: MediaSource, fp: FileProgress): UploadResult {
        fp.status = UploadStatus.CANCELLED
        fp.message = "Cancelled"
        emitProgress()
        return UploadResult(source.key, source.displayName, false, error = "Cancelled")
    }
}

private const val ALREADY_IN_LIBRARY = "Already in library"
private const val CHECKED_BEFORE = "Already in library (checked on an earlier run)"
private const val PROGRESS_INTERVAL_MS = 250L
private const val CHECK_PARALLEL = 8
