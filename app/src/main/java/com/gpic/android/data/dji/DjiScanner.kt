package com.gpic.android.data.dji

import android.content.Context
import android.hardware.usb.UsbManager
import android.net.Uri
import android.provider.DocumentsContract
import com.gpic.android.data.cleanup.parseExifDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * DJI Action 4 scanner
 * 
 * DJI Action 4 when connected via USB-C to Android exposes storage via MTP.
 * Android phones via OTG generally do NOT auto-mount MTP as filesystem.
 * Instead we rely on SAF (Storage Access Framework) folder picker:
 *   - User taps "Connect DJI" -> open SAF picker -> selects DJI DCIM folder
 *   - App gets persisted URI permission and scans recursively
 * 
 * Fallback: also tries to detect UsbManager devices for status display,
 * and tries common mount points like /storage/xxxx/DCIM if device is rooted/OTG mounted.
 */
class DjiScanner(private val context: Context) {
    companion object {
        const val DJI_VENDOR_ID = 0x2ca3
    }

    fun checkUsbConnected(): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        // Any USB device attached is potential DJI; we just inform user
        return usbManager.deviceList.isNotEmpty()
    }

    fun usbDeviceSummary(): String {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = usbManager.deviceList.values
        if (devices.isEmpty()) return "No USB device detected. Plug DJI Action 4 via USB-C."
        // Report the camera, not whatever hub/keyboard enumerated first.
        val d = devices.firstOrNull { it.vendorId == DJI_VENDOR_ID } ?: devices.first()
        return "USB device: ${d.manufacturerName ?: "Unknown"} ${d.productName ?: ""} (VID ${d.vendorId} PID ${d.productId})"
    }

    /** Every supported file in the folder and its subfolders, smallest first (quick wins, no GB-video head-of-line blocking). */
    suspend fun scanViaSaf(treeUri: Uri, skipped: MutableList<String>? = null): List<DjiFile> =
        listTree(treeUri, recursive = true, skipped).sortedBy { it.sizeBytes }

    /**
     * Build DjiFile list from user-picked individual files (OpenMultipleDocuments).
     * One projected query per Uri (DocumentFile costs several); unsupported extensions are dropped.
     * Returns smallest-first sorted list.
     */
    suspend fun filesFromUris(uris: List<Uri>): List<DjiFile> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val out = mutableListOf<DjiFile>()
        for (uri in uris) {
            var name: String? = null
            var size = 0L
            var modified = 0L
            fun read(c: android.database.Cursor) {
                if (!c.moveToFirst()) return
                c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !c.isNull(it) }?.let { name = c.getString(it) }
                c.getColumnIndex(android.provider.OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED).takeIf { it >= 0 && !c.isNull(it) }?.let { modified = c.getLong(it) }
            }
            // Some providers reject a column they don't have; ask for everything then.
            val ok = runCatching { context.contentResolver.query(uri, projection, null, null, null)?.use(::read) != null }.getOrDefault(false)
            if (!ok) runCatching { context.contentResolver.query(uri, null, null, null, null)?.use(::read) }
            val finalName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
            if (finalName.substringAfterLast('.', "").lowercase() !in DjiFile.supported) continue
            out.add(DjiFile(displayName = finalName, sizeBytes = size.coerceAtLeast(0), lastModified = modified, uri = uri))
        }
        out.sortedBy { it.sizeBytes }
    }

    /**
     * Every supported file under [treeUri], one DocumentsContract query per folder (DocumentFile costs
     * several queries per file). Hidden entries (".thumbnails", ".trashed-…") are skipped, and so are sticker
     * subfolders ("WhatsApp Stickers"): Google Photos refuses those .webp files. Their names go to [skipped].
     * A sticker folder picked as the root itself is scanned: that's an explicit choice.
     */
    suspend fun listTree(treeUri: Uri, recursive: Boolean, skipped: MutableList<String>? = null): List<DjiFile> = withContext(Dispatchers.IO) {
        val out = mutableListOf<DjiFile>()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val dirs = ArrayDeque(listOf(DocumentsContract.getTreeDocumentId(treeUri)))
        while (dirs.isNotEmpty()) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirs.removeLast())
            context.contentResolver.query(children, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (name.startsWith(".")) continue
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (isStickerFolder(name)) skipped?.add(name)
                        else if (recursive) dirs.addLast(id)
                        continue
                    }
                    if (name.substringAfterLast('.', "").lowercase() !in DjiFile.supported) continue
                    out += DjiFile(
                        displayName = name,
                        sizeBytes = if (c.isNull(3)) 0 else c.getLong(3),
                        lastModified = if (c.isNull(4)) 0 else c.getLong(4),
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                    )
                }
            }
        }
        out
    }

    /**
     * Every supported file in several picked folders. A file reached through two overlapping folders (a folder
     * and its subfolder, which a USB camera's opaque ids can't reveal up front) is listed once. A folder GPic
     * lost access to is skipped and reported in [unreadable] instead of failing the rest.
     */
    suspend fun listTrees(
        trees: List<Uri>,
        recursive: Boolean,
        skipped: MutableList<String>? = null,
        unreadable: MutableList<Uri>? = null,
    ): List<DjiFile> {
        val all = mutableListOf<DjiFile>()
        for (tree in trees) {
            val label = folderLabel(tree)
            try {
                listTree(tree, recursive, skipped).mapTo(all) { it.copy(group = label) }
            } catch (e: SecurityException) {
                android.util.Log.w("DjiScanner", "no access to $label: ${e.message}")
                unreadable?.add(tree)
            } catch (e: IllegalArgumentException) {
                // A folder that was deleted or renamed: its tree id no longer resolves.
                android.util.Log.w("DjiScanner", "can't list $label: ${e.message}")
                unreadable?.add(tree)
            }
        }
        return FolderSet.dedupe(all) { f -> f.uri?.let { "${it.authority}|${runCatching { DocumentsContract.getDocumentId(it) }.getOrDefault(it.toString())}" } ?: f.key }
    }

    fun folderId(tree: Uri): FolderSet.Folder =
        FolderSet.Folder(tree.authority ?: "", runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrDefault(tree.toString()))

    /**
     * "DCIM/Camera", "Internal storage", or the camera folder's own name (MTP ids aren't paths, so that one asks
     * the provider: call it off the main thread, or pass [query] = false for a placeholder).
     */
    fun folderLabel(tree: Uri, query: Boolean = true): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return tree.lastPathSegment ?: "folder"
        if (':' in id) return id.substringAfter(':').ifEmpty { "Internal storage" }
        if (!query) return "Camera folder"
        return runCatching {
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, id)
            context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: id
    }

    /** Gives up a folder's saved permission (when no tab uses it any more). */
    fun releasePermission(uri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure {
            runCatching { context.contentResolver.releasePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    private fun isStickerFolder(name: String) = name.contains("sticker", ignoreCase = true)

    /** A photo's EXIF capture time (header only), or null for videos / no date / unreadable. */
    fun exifTakenMs(f: DjiFile): Long? {
        val uri = f.uri ?: return null
        if (!f.isPhoto) return null
        return try {
            context.contentResolver.openInputStream(uri)?.use { s ->
                val exif = android.media.ExifInterface(s)
                parseExifDate(exif.getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL), exif.getAttribute(android.media.ExifInterface.TAG_OFFSET_TIME_ORIGINAL))
                    ?: parseExifDate(exif.getAttribute(android.media.ExifInterface.TAG_DATETIME_DIGITIZED), exif.getAttribute(android.media.ExifInterface.TAG_OFFSET_TIME_DIGITIZED))
            }
        } catch (_: Exception) {
            null
        }
    }

    fun takePersistableFilePermission(uri: Uri) {
        val read = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        val write = android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        // Write too, when the picker granted it, so "Delete after upload" still works after a restart.
        try {
            context.contentResolver.takePersistableUriPermission(uri, read or write)
        } catch (_: Exception) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, read)
            } catch (_: Exception) {
                // Non-persistable Uris (e.g. from Downloads) still work for this session.
            }
        }
    }

    suspend fun scanDirectPath(path: String): List<DjiFile> = withContext(Dispatchers.IO) {
        val root = File(path)
        if (!root.exists()) return@withContext emptyList()
        val result = mutableListOf<DjiFile>()
        root.walkTopDown().forEach { f ->
            if (f.isFile) {
                val ext = f.extension.lowercase()
                if (ext in DjiFile.supported) {
                    result.add(
                        DjiFile(
                            displayName = f.name,
                            sizeBytes = f.length(),
                            lastModified = f.lastModified(),
                            file = f,
                        )
                    )
                }
            }
        }
        result
    }

    /**
     * Attempt to discover DJI by scanning common OTG mount points.
     * Not all devices expose this, but we try best-effort.
     */
    suspend fun autoDiscover(): List<DjiFile> = withContext(Dispatchers.IO) {
        val candidates = listOf(
            "/storage",
            "/mnt/media_rw",
            "/Removable",
        )
        val found = mutableListOf<DjiFile>()
        for (base in candidates) {
            val b = File(base)
            if (!b.exists()) continue
            b.listFiles()?.forEach { vol ->
                // DJI typically has DCIM folder
                val dcim = File(vol, "DCIM")
                if (dcim.exists()) {
                    dcim.walkTopDown().forEach { f ->
                        if (f.isFile && f.extension.lowercase() in DjiFile.supported) {
                            found.add(DjiFile(f.name, f.length(), f.lastModified(), file = f))
                        }
                    }
                }
            }
        }
        found
    }

    fun takePersistablePermission(uri: Uri) {
        try {
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: Exception) {
            // ignore
        }
    }
}
