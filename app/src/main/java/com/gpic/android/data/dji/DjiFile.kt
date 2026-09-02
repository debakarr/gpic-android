package com.gpic.android.data.dji

import android.net.Uri

/**
 * Represents a file discovered on DJI Action 4 storage.
 * Could be local File or DocumentFile via SAF (Uri + stream).
 */
data class DjiFile(
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long, // epoch millis
    val uri: Uri? = null, // for SAF DocumentFile
    val file: java.io.File? = null, // for direct file path (if DJI mounts as storage)
    val extension: String = displayName.substringAfterLast('.', "").lowercase(),
    val isVideo: Boolean = extension in videoExtensions,
    val isPhoto: Boolean = extension in photoExtensions,
) {
    companion object {
        val photoExtensions = setOf("jpg","jpeg","png","heic","heif","dng","webp","tif","tiff","bmp","arw","nef","cr2","cr3","raf","orf","rw2","pef","sr2","avif","ico")
        val videoExtensions = setOf("mp4","mov","avi","mkv","mts","m2ts","m2t","m4v","mod","tod","wmv","asf","divx","3gp","3g2","mpg","mpeg","mmv","ts")
        val supported = photoExtensions + videoExtensions
    }

    val typeLabel: String get() = when {
        isVideo -> "Video"
        isPhoto -> "Photo"
        else -> "File"
    }
}
