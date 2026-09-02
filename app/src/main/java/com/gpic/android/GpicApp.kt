package com.gpic.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.gpic.android.data.auth.CredentialStore
import com.gpic.android.data.cache.UploadCache
import com.gpic.android.data.dji.DjiScanner
import com.gpic.android.util.Constants

class GpicApp : Application() {
    lateinit var credentialStore: CredentialStore
    lateinit var djiScanner: DjiScanner
    lateinit var uploadCache: UploadCache

    // Simple container; no Hilt for v1
    override fun onCreate() {
        super.onCreate()
        credentialStore = CredentialStore(this)
        djiScanner = DjiScanner(this)
        uploadCache = UploadCache(this)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.NOTIF_CHANNEL_ID,
                "GPic Uploads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows upload progress for DJI files"
                setShowBadge(false)
            }
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(channel)
        }
    }
}
