package com.gpic.android.data.auth

/**
 * Port of gphotos/config.py Credential
 * authString format: "androidId=...&Email=...&Token=..." extracted via adb logcat from Google Photos
 * Example extraction:
 *   adb logcat -c; adb logcat | grep -i "photos.native"   (make auth-log)
 *   then open Google Photos app on phone, copy the line containing androidId & Token
 */
data class Credential(
    val email: String = "",
    val authString: String = "",
    val language: String = "en",
)

data class AppConfig(
    val selectedEmail: String = "",
    val credentials: List<Credential> = emptyList(),
    val uploadThreads: Int = 3,
    val forceUpload: Boolean = false,
    val deleteAfterUpload: Boolean = false,
    val saverMode: Boolean = false,
    val useQuota: Boolean = false,
    /** Camera over WiFi: true = upload straight from the camera, false = download to the phone first. */
    val cameraStream: Boolean = false,
)
