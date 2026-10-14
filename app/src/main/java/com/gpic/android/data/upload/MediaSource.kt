package com.gpic.android.data.upload

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.gpic.android.data.dji.DjiFile
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Where an upload's bytes come from. UploadManager runs one pipeline over any source, so phone
 * storage, a USB camera (MTP via SAF), a staged download and a WiFi camera stream share the same
 * hash / dedup / resume / commit code.
 */
interface MediaSource {
    val key: String
    val displayName: String
    val sizeBytes: Long
    val lastModifiedMs: Long

    /**
     * True when a second full read is expensive (camera over WiFi). The pipeline then skips the
     * hash-first dedup pass and hashes while uploading instead.
     */
    val singlePass: Boolean get() = false

    /** Cap on concurrent transfers from this kind of source, or null for no cap. */
    val maxParallel: Int? get() = null

    /** Error message when the source can't be read right now, else null. Cheap. */
    fun checkReadable(): String?

    /** A fresh stream positioned at [offset]. Caller closes it. */
    fun open(offset: Long = 0): InputStream

    /** Delete the original. Returns an error message, or null on success. */
    fun delete(): String?
}

/** File on the phone's filesystem (e.g. a staged camera download). */
class LocalFileSource(
    private val f: DjiFile,
    private val file: File,
    private val deleteFile: Boolean = true,
) : MediaSource {
    override val key get() = f.key
    override val displayName get() = f.displayName
    override val sizeBytes get() = file.length()
    override val lastModifiedMs get() = if (f.lastModified > 0) f.lastModified else file.lastModified()

    override fun checkReadable(): String? =
        if (!file.exists()) "Local file missing: ${f.displayName}" else null

    override fun open(offset: Long): InputStream =
        FileInputStream(file).apply { if (offset > 0) channel.position(offset) }

    override fun delete(): String? =
        if (!deleteFile || file.delete()) null else "Couldn't delete ${file.name}"
}

/** SAF document: phone storage or a USB-connected camera exposed over MTP. */
class UriSource(private val context: Context, private val f: DjiFile, private val uri: Uri) : MediaSource {
    override val key get() = f.key
    override val displayName get() = f.displayName
    override val sizeBytes get() = f.sizeBytes
    override val lastModifiedMs get() = f.lastModified

    // MTP serialises object reads; more parallel streams just queue on the camera.
    override val maxParallel: Int? get() = if (uri.authority == MTP_AUTHORITY) 2 else null

    override fun checkReadable(): String? {
        if (f.sizeBytes <= 0) return "Empty or unreadable file (0 bytes)"
        return try {
            context.contentResolver.openInputStream(uri)?.close()
            null
        } catch (e: SecurityException) {
            "Permission lost for ${f.displayName}. Re-pick folder/files, then retry. (${e.message})"
        } catch (e: java.io.FileNotFoundException) {
            "File not found (camera disconnected?): ${f.displayName}"
        } catch (e: Exception) {
            "Cannot open file: ${e.message}. Re-pick folder/files."
        }
    }

    override fun open(offset: Long): InputStream {
        val raw = context.contentResolver.openInputStream(uri)
            ?: throw RuntimeException("Cannot open ${f.displayName} (null stream). Re-pick folder/files.")
        skipFully(raw, offset, f.sizeBytes)
        return raw
    }

    override fun delete(): String? = try {
        if (DocumentsContract.deleteDocument(context.contentResolver, uri)) null
        else "Camera/storage refused to delete ${f.displayName}"
    } catch (e: SecurityException) {
        "No write access to delete ${f.displayName} — re-pick the folder to grant it"
    } catch (e: Exception) {
        "Couldn't delete ${f.displayName}: ${e.message}"
    }

    companion object {
        const val MTP_AUTHORITY = "com.android.mtp.documents"
    }
}

/** Advance [s] by exactly [n] bytes. ContentResolver streams may skip short or not at all. */
fun skipFully(s: InputStream, n: Long, total: Long) {
    var toSkip = n
    val discard by lazy { ByteArray(256 * 1024) }
    while (toSkip > 0) {
        val skipped = s.skip(toSkip)
        if (skipped > 0) { toSkip -= skipped; continue }
        val r = s.read(discard, 0, minOf(discard.size.toLong(), toSkip).toInt())
        if (r == -1) throw RuntimeException("Stream ended before resume offset $n/$total")
        toSkip -= r
    }
}

/** Default source for files found by DjiScanner. Camera files need a CameraLink-provided source. */
fun DjiFile.defaultSource(context: Context): MediaSource = when {
    file != null -> LocalFileSource(this, file)
    uri != null -> UriSource(context, this, uri)
    else -> throw IllegalStateException("No local source for ${displayName}")
}
