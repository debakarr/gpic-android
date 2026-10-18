package com.gpic.android.camera

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.gpic.android.camera.osmosis.camera.PathAddressing
import com.gpic.android.data.dji.DjiFile
import com.gpic.android.data.upload.MediaSource
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * A camera file read over HTTP that survives the camera's habits: it cuts long transfers every so
 * often and answers 404/500 while busy (both transient on DJI firmware — see osmosis
 * MediaDownloader). On any failure we wait, then Range-request the remainder, so callers see one
 * continuous stream. Gives up only after [MAX_BARREN] reconnects in a row that moved no bytes.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class CameraStream(
    private val link: CameraLink,
    private val urlPath: String,
    private val size: Long,
    start: Long,
    private val isCancelled: () -> Boolean = { false },
) : InputStream() {
    private var pos = start
    private var resp: Response? = null
    private var body: InputStream? = null
    private var barren = 0

    private fun connect() {
        closeCurrent()
        val client = link.httpClient() ?: throw IOException("Camera WiFi disconnected")
        val req = Request.Builder().url(link.url(urlPath)).apply { if (pos > 0) header("Range", "bytes=$pos-") }.build()
        val r = client.newCall(req).execute()
        when {
            r.code == 206 || (r.code == 200 && pos == 0L) -> { resp = r; body = r.body!!.byteStream() }
            r.code == 200 -> {
                // Camera ignored the Range: read past what we already have.
                resp = r; body = r.body!!.byteStream()
                com.gpic.android.data.upload.skipFully(body!!, pos, size)
            }
            else -> { r.close(); throw IOException("Camera answered HTTP ${r.code}") }
        }
    }

    private fun <T> withResume(op: () -> T): T {
        while (true) {
            if (isCancelled()) throw IOException("Upload cancelled")
            try {
                if (body == null) connect()
                return op()
            } catch (e: IOException) {
                if (isCancelled()) throw e
                closeCurrent()
                barren++
                if (barren > MAX_BARREN) throw IOException("Camera stopped sending ${urlPath.substringAfterLast('/')} at $pos/$size: ${e.message}", e)
                Log.i(CameraLink.TAG, "camera read interrupted at $pos (${e.message}); resuming in ${BACKOFF_MS * barren} ms")
                // The camera needs a moment to let go of the previous transfer before it serves again.
                Thread.sleep(BACKOFF_MS * barren)
            }
        }
    }

    override fun read(): Int {
        val b = ByteArray(1)
        return if (read(b, 0, 1) == -1) -1 else b[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (pos >= size) return -1
        val n = withResume {
            val r = body!!.read(b, off, minOf(len.toLong(), size - pos).toInt())
            if (r == -1) throw IOException("Camera closed the transfer early")
            r
        }
        pos += n
        barren = 0
        return n
    }

    override fun close() = closeCurrent()

    private fun closeCurrent() {
        runCatching { body?.close() }
        runCatching { resp?.close() }
        body = null; resp = null
    }

    companion object {
        private const val MAX_BARREN = 6
        private const val BACKOFF_MS = 1500L
    }
}

/** Stream mode: upload straight from the camera, hashing in-flight (one read over WiFi per file). */
@RequiresApi(Build.VERSION_CODES.Q)
class CameraHttpSource(
    private val link: CameraLink,
    private val f: DjiFile,
    private val isCancelled: () -> Boolean,
) : MediaSource {
    private val urlPath = PathAddressing.byPath(f.cameraStorage, f.cameraPath!!)
    override val key get() = f.key
    override val displayName get() = f.displayName
    override val sizeBytes get() = f.sizeBytes
    override val lastModifiedMs get() = f.lastModified
    override val singlePass get() = true
    // One WiFi link to one camera: parallel reads just split the same bandwidth and provoke cuts.
    override val maxParallel get() = 1

    override fun checkReadable(): String? = when {
        link.httpClient() == null -> "Camera WiFi disconnected"
        f.sizeBytes <= 0 -> "Camera didn't report a size for ${f.displayName}"
        else -> null
    }

    override fun open(offset: Long): InputStream = CameraStream(link, urlPath, f.sizeBytes, offset, isCancelled)

    override fun delete(): String = DELETE_UNSUPPORTED
}

/** Download-first mode: copy camera files into app storage, resuming partial files. */
@RequiresApi(Build.VERSION_CODES.Q)
class CameraStager(private val link: CameraLink, private val dir: File) {

    fun stagedFile(f: DjiFile): File = File(dir, f.displayName)

    /** Bytes still to download for [files] (partial files count only their remainder). */
    fun bytesNeeded(files: List<DjiFile>): Long =
        files.sumOf { (it.sizeBytes - stagedFile(it).length()).coerceAtLeast(0) }

    /**
     * Download [f] (or finish a partial copy). [onBytes] gets the file's running total.
     * @return the complete local file.
     */
    fun download(f: DjiFile, isCancelled: () -> Boolean, onBytes: (Long) -> Unit): File {
        // Nothing to compare a download against: an empty "copy" would only fail later as a missing file.
        if (f.sizeBytes <= 0) throw IOException("The camera didn't report the size of ${f.displayName}")
        dir.mkdirs()
        val out = stagedFile(f)
        val have = out.length().let { if (it > f.sizeBytes) { out.delete(); 0L } else it }
        if (have == f.sizeBytes) { onBytes(have); return out }
        val path = PathAddressing.byPath(f.cameraStorage, f.cameraPath!!)
        CameraStream(link, path, f.sizeBytes, have, isCancelled).use { input ->
            FileOutputStream(out, true).use { os ->
                val buf = ByteArray(256 * 1024)
                var total = have
                while (true) {
                    val r = input.read(buf)
                    if (r == -1) break
                    os.write(buf, 0, r)
                    total += r
                    onBytes(total)
                }
            }
        }
        if (out.length() != f.sizeBytes) throw IOException("Download incomplete: ${out.length()}/${f.sizeBytes}")
        if (f.lastModified > 0) out.setLastModified(f.lastModified)
        return out
    }

    /** Remove staged copies (after upload, or when the user clears the camera selection). */
    fun clean(files: List<DjiFile>) = files.forEach { stagedFile(it).delete() }
}

const val DELETE_UNSUPPORTED = "deleting from the camera over WiFi isn't supported yet"
