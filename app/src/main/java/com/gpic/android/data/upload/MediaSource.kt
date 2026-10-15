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

    /**
     * A photo/video whose location Android may have stripped from what [open] returns (no media-location
     * permission). Its upload can lack GPS, so the original must not be deleted on its strength.
     */
    val mayHaveLostLocation: Boolean get() = false

    /**
     * Set when [open] reads the unredacted original: the plain read Android gives apps without the
     * permission. Hashing it finds a GPS-less copy uploaded by an earlier GPic version.
     */
    val openRedacted: ((offset: Long) -> InputStream)? get() = null

    /** A photo that has a location, but what [open] returns has it blanked. */
    val knownStrippedGps: Boolean get() = false

    /** Capture time + GPS for the album index (photos: EXIF header only). */
    fun captureInfo(): CaptureInfo = CaptureInfo(lastModifiedMs)

    /** Where a thumbnail can be loaded from while the original still exists. */
    val thumbUri: String? get() = null
}

data class CaptureInfo(val takenMs: Long, val lat: Double? = null, val lon: Double? = null)

/** EXIF capture time (else [fallbackMs]) and GPS. A stripped read's 0,0 counts as no location. */
internal fun readCapture(isPhoto: Boolean, fallbackMs: Long, open: () -> InputStream): CaptureInfo {
    if (!isPhoto) return CaptureInfo(fallbackMs)
    return runCatching {
        open().use { s ->
            val exif = android.media.ExifInterface(s)
            val taken = com.gpic.android.data.cleanup.parseExifDate(
                exif.getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL),
                exif.getAttribute(android.media.ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
            )
            val ll = FloatArray(2)
            @Suppress("DEPRECATION")
            val hasGps = exif.getLatLong(ll) && !(ll[0] == 0f && ll[1] == 0f)
            CaptureInfo(taken ?: fallbackMs, ll[0].toDouble().takeIf { hasGps }, ll[1].toDouble().takeIf { hasGps })
        }
    }.getOrElse { CaptureInfo(fallbackMs) }
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

    override fun captureInfo() = readCapture(f.isPhoto, lastModifiedMs) { open(0) }

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

    private val mimeHint = when { f.isVideo -> "video/*"; f.isPhoto -> "image/*"; else -> null }
    // Resolved once, off the main thread (first checkReadable/open); null = read the Uri as given.
    private val original: Uri? by lazy { OriginalMedia.originalUri(context, uri, mimeHint) }
    private val readUri: Uri get() = original ?: uri

    /**
     * GPS tags in the plain read. Stripping zeroes the values but keeps the tags (measured on Android 13:
     * GPSLatitude read back as 0/1,0/1,0/1), so tags present = the photo had a location; none = it never did.
     */
    private enum class Gps { PRESENT, ABSENT, UNKNOWN }

    /**
     * Only JPEG is trusted to answer "no GPS": the framework ExifInterface may read nothing from other
     * formats through a stream (PNG/WebP/HEIC/real RAW), and nothing read must not mean "safe to delete".
     */
    private val plainGps: Gps by lazy {
        if (!f.isPhoto) return@lazy Gps.UNKNOWN
        runCatching {
            val isJpeg = context.contentResolver.openInputStream(uri)?.use { s ->
                val head = ByteArray(3)
                s.read(head) == 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()
            } ?: false
            if (!isJpeg) return@runCatching Gps.UNKNOWN
            context.contentResolver.openInputStream(uri)?.use { s ->
                val exif = android.media.ExifInterface(s)
                if (exif.hasAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE) || exif.hasAttribute(android.media.ExifInterface.TAG_GPS_LONGITUDE)) Gps.PRESENT
                else Gps.ABSENT
            } ?: Gps.UNKNOWN
        }.getOrDefault(Gps.UNKNOWN)
    }

    private val readStripped: Boolean get() = OriginalMedia.mayRedactLocation(uri) && original == null

    override val knownStrippedGps: Boolean get() = readStripped && plainGps == Gps.PRESENT

    override val mayHaveLostLocation: Boolean
        // Videos and non-JPEG photos can't be checked, so assume the worst: upload, never delete.
        get() = readStripped && (f.isVideo || plainGps != Gps.ABSENT)

    override val openRedacted: ((Long) -> InputStream)?
        get() = if (original != null && f.isPhoto) { offset -> openUri(uri, offset) } else null

    override fun checkReadable(): String? {
        if (f.sizeBytes <= 0) return "Empty or unreadable file (0 bytes)"
        return try {
            context.contentResolver.openInputStream(readUri)?.close()
            null
        } catch (e: SecurityException) {
            "Permission lost for ${f.displayName}. Re-pick folder/files, then retry. (${e.message})"
        } catch (e: java.io.FileNotFoundException) {
            "File not found (camera disconnected?): ${f.displayName}"
        } catch (e: Exception) {
            "Cannot open file: ${e.message}. Re-pick folder/files."
        }
    }

    override fun open(offset: Long): InputStream = openUri(readUri, offset)

    override fun captureInfo() = readCapture(f.isPhoto, lastModifiedMs) { open(0) }

    override val thumbUri: String get() = uri.toString()

    private fun openUri(uri: Uri, offset: Long): InputStream {
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
