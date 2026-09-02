package com.gpic.android.upload

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.gpic.android.util.Constants

/**
 * Foreground service to keep uploads alive when app is backgrounded.
 * Actual upload work runs in UploadViewModel coroutines; service just holds notification.
 */
class UploadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification(intent?.getStringExtra("text") ?: "Uploading DJI files…")
        startForeground(Constants.NOTIF_ID, notification)
        return START_NOT_STICKY
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, Constants.NOTIF_CHANNEL_ID)
            .setContentTitle("GPic")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
    }
}
