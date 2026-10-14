package com.gpic.android.data.dji

import android.net.Uri

/**
 * A photo/video to back up: a SAF Uri (USB camera or phone storage), a local File (staged
 * download), or a path on a WiFi-connected DJI camera.
 */
data class DjiFile(
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long, // epoch millis
    val uri: Uri? = null, // for SAF DocumentFile
    val file: java.io.File? = null, // for direct file path (if DJI mounts as storage)
    val cameraPath: String? = null, // over WiFi: DCIM/... served by the camera's /v2?storage=&path=
    val cameraStorage: Int = 0,
    val extension: String = displayName.substringAfterLast('.', "").lowercase(),
    val isVideo: Boolean = extension in videoExtensions,
    val isPhoto: Boolean = extension in photoExtensions,
) {
    companion object {
        val photoExtensions = setOf("jpg","jpeg","png","heic","heif","dng","webp","tif","tiff","bmp","arw","nef","cr2","cr3","raf","orf","rw2","pef","sr2","avif","ico")
        val videoExtensions = setOf("mp4","mov","avi","mkv","mts","m2ts","m2t","m4v","mod","tod","wmv","asf","divx","3gp","3g2","mpg","mpeg","mmv","ts")
        val supported = photoExtensions + videoExtensions
    }

    /** Stable identity: progress map, resume cache and list keys all use this. */
    // Camera path first: a staged copy (file set) must keep its camera identity in the list.
    val key: String get() = cameraPath?.let { "camera:$cameraStorage:$it" } ?: uri?.toString() ?: file?.absolutePath ?: displayName

    val typeLabel: String get() = when {
        isVideo -> "Video"
        isPhoto -> "Photo"
        else -> "File"
    }
}
