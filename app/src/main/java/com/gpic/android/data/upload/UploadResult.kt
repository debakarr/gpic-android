package com.gpic.android.data.upload

data class UploadResult(
    val filePath: String,
    val fileName: String,
    val success: Boolean,
    val mediaKey: String = "",
    val error: String = "",
    val skipped: Boolean = false,
)
