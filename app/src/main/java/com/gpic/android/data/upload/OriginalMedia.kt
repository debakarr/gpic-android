package com.gpic.android.data.upload

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log

/**
 * Android 10+ strips GPS from photos it hands to apps that lack ACCESS_MEDIA_LOCATION, so an upload
 * of the plain Uri lands in Google Photos without a location (and its hash then matches that stripped
 * copy forever). The fix is to read the MediaStore item with [MediaStore.setRequireOriginal].
 */
object OriginalMedia {
    private const val TAG = "GPicMedia"
    private const val EXTERNAL_STORAGE_DOCS = "com.android.externalstorage.documents"
    private const val MEDIA_DOCS = "com.android.providers.media.documents"

    /** Providers backed by shared storage, where Android may redact location metadata. */
    fun mayRedactLocation(uri: Uri): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            uri.authority in setOf(MediaStore.AUTHORITY, EXTERNAL_STORAGE_DOCS, MEDIA_DOCS)

    fun hasLocationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * An unredacted Uri for [uri] that this app can actually open, or null when there isn't one
     * (no permission, not shared storage, or the item isn't indexed by MediaStore).
     */
    fun originalUri(context: Context, uri: Uri, mimeHint: String?): Uri? {
        if (!mayRedactLocation(uri) || !hasLocationPermission(context)) return null
        val media = mediaUriFor(context, uri, mimeHint) ?: run {
            Log.w(TAG, "no MediaStore entry for $uri")
            return null
        }
        val original = MediaStore.setRequireOriginal(media)
        return try {
            context.contentResolver.openInputStream(original)?.close() ?: return null
            Log.i(TAG, "reading original via $media")
            original
        } catch (e: Exception) {
            Log.w(TAG, "original unavailable for $uri: ${e.javaClass.simpleName} ${e.message}")
            null
        }
    }

    private fun mediaUriFor(context: Context, uri: Uri, mimeHint: String?): Uri? {
        if (uri.authority == MediaStore.AUTHORITY) return uri
        runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull()?.let { return it }
        // getMediaUri needs the provider's cooperation; for phone storage the document id is a path
        // ("primary:DCIM/Camera/x.jpg"), which MediaStore can look up directly.
        if (uri.authority != EXTERNAL_STORAGE_DOCS) return null
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        val volumeId = docId.substringBefore(':', "")
        val path = docId.substringAfter(':', "")
        if (volumeId.isEmpty() || path.isEmpty()) return null
        val volume = if (volumeId == "primary") MediaStore.VOLUME_EXTERNAL_PRIMARY else volumeId.lowercase()
        val collection = when {
            mimeHint?.startsWith("video/") == true -> MediaStore.Video.Media.getContentUri(volume)
            mimeHint?.startsWith("image/") == true -> MediaStore.Images.Media.getContentUri(volume)
            else -> MediaStore.Files.getContentUri(volume)
        }
        val dir = path.substringBeforeLast('/', "")
        val relativePath = if (dir.isEmpty()) "" else "$dir/"
        fun lookup() = runCatching {
            context.contentResolver.query(
                collection, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(relativePath, path.substringAfterLast('/')), null,
            )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null }
        }.getOrNull()
        lookup()?.let { return it }
        // Not indexed yet (e.g. copied in by a file manager or adb): ask the media scanner, then look again.
        // Seen on a Redmi 3S: freshly copied files read stripped even with the permission until scanned.
        val root = if (volumeId == "primary") "/storage/emulated/0" else "/storage/$volumeId"
        scanAndWait(context, "$root/$path")
        return lookup()
    }

    /** Index one file with the media scanner; returns when it's done (or after a few seconds). Off the main thread. */
    private fun scanAndWait(context: Context, path: String) {
        val done = java.util.concurrent.CountDownLatch(1)
        MediaScannerConnection.scanFile(context, arrayOf(path), null) { _, _ -> done.countDown() }
        if (!done.await(5, java.util.concurrent.TimeUnit.SECONDS)) Log.w(TAG, "media scan timed out for $path")
    }
}
