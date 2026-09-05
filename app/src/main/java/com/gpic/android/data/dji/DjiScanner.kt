package com.gpic.android.data.dji

import android.content.Context
import android.hardware.usb.UsbManager
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
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

    fun checkUsbConnected(): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        // Any USB device attached is potential DJI; we just inform user
        return usbManager.deviceList.isNotEmpty()
    }

    fun usbDeviceSummary(): String {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = usbManager.deviceList.values
        if (devices.isEmpty()) return "No USB device detected. Plug DJI Action 4 via USB-C."
        val d = devices.first()
        return "USB device: ${d.manufacturerName ?: "Unknown"} ${d.productName ?: ""} (VID ${d.vendorId} PID ${d.productId})"
    }

    suspend fun scanViaSaf(treeUri: Uri): List<DjiFile> = withContext(Dispatchers.IO) {
        val doc = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
        val result = mutableListOf<DjiFile>()
        scanDocumentRecursive(doc, result)
        // Smallest-first: quick wins first, avoids GB video head-of-line blocking.
        result.sortedBy { it.sizeBytes }
    }

    /**
     * Build DjiFile list from user-picked individual files (OpenMultipleDocuments).
     * Queries display name + size via ContentResolver; filters by supported extensions.
     * Returns smallest-first sorted list.
     */
    suspend fun filesFromUris(uris: List<Uri>): List<DjiFile> = withContext(Dispatchers.IO) {
        val out = mutableListOf<DjiFile>()
        for (uri in uris) {
            try {
                var name: String? = null
                var size = 0L
                var modified = 0L
                // Try DocumentFile first (fast path for SAF Uris)
                try {
                    val doc = DocumentFile.fromSingleUri(context, uri)
                    if (doc != null && doc.exists()) {
                        name = doc.name
                        size = doc.length()
                        modified = doc.lastModified()
                    }
                } catch (_: Exception) {}
                // Fallback to ContentResolver query (OpenableColumns + DocumentsContract)
                if (name == null || size <= 0) {
                    try {
                        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                            val nameIdx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            val sizeIdx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                            val modIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                            if (c.moveToFirst()) {
                                if (name == null && nameIdx >= 0) name = c.getString(nameIdx)
                                if (size <= 0 && sizeIdx >= 0) size = c.getLong(sizeIdx)
                                if (modified <= 0 && modIdx >= 0) {
                                    try { modified = c.getLong(modIdx) } catch (_: Exception) {}
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
                val finalName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
                if (finalName.substringAfterLast('.', "").lowercase() !in DjiFile.supported) continue
                if (size < 0) size = 0
                out.add(DjiFile(displayName = finalName, sizeBytes = size, lastModified = modified, uri = uri))
            } catch (_: Exception) {}
        }
        out.sortedBy { it.sizeBytes }
    }

    fun takePersistableFilePermission(uri: Uri) {
        try {
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: Exception) {
            // Non-persistable Uris (e.g. from Downloads) still work for this session.
        }
    }

    private fun scanDocumentRecursive(doc: DocumentFile, out: MutableList<DjiFile>) {
        if (doc.isFile) {
            val name = doc.name ?: return
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext in DjiFile.supported) {
                out.add(
                    DjiFile(
                        displayName = name,
                        sizeBytes = doc.length(),
                        lastModified = doc.lastModified(),
                        uri = doc.uri,
                    )
                )
            }
        } else if (doc.isDirectory) {
            val children = doc.listFiles()
            for (child in children) {
                scanDocumentRecursive(child, out)
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
