package com.gpic.android.upload

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.gpic.android.MainActivity
import com.gpic.android.util.Constants

/**
 * Foreground service to keep uploads alive when app is backgrounded.
 * Actual upload work runs in HomeViewModel coroutines; service just holds the notification,
 * which HomeViewModel refreshes via [update] as files finish.
 */
class UploadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must reach startForeground even when the batch already ended, or the system crashes us.
        startForeground(Constants.NOTIF_ID, buildNotification(this, "Preparing upload…", 0, 0))
        running = true
        holdLocks()
        if (stopRequested) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        releaseLocks()
        super.onDestroy()
    }

    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    /**
     * A long run (thousands of WhatsApp photos) with the screen off: without these the CPU naps between
     * network replies and WiFi drops to power-save, which stretches every round trip. Held only while running.
     */
    private fun holdLocks() {
        if (wakeLock?.isHeld != true) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "GPic:upload")
                .apply { setReferenceCounted(false); acquire(MAX_LOCK_MS) }
        }
        if (wifiLock?.isHeld != true) {
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager)
                .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "GPic:upload")
                .apply { setReferenceCounted(false); runCatching { acquire() } }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    // Android 15+ caps dataSync services (~6 h/day); drop background protection instead of crashing.
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    companion object {
        // Main-thread only (ViewModel + service callbacks), so no locking needed.
        private var running = false
        /** Safety net if a run never ends cleanly. */
        private const val MAX_LOCK_MS = 6 * 60 * 60 * 1000L
        private var stopRequested = false

        fun start(context: Context) {
            stopRequested = false
            ContextCompat.startForegroundService(context, Intent(context, UploadService::class.java))
        }

        /** Safe before onStartCommand has run: the service then stops itself right after startForeground. */
        fun stop(context: Context) {
            stopRequested = true
            if (running) context.stopService(Intent(context, UploadService::class.java))
        }

        /** Replace the ongoing notification with determinate "done of total" progress. */
        fun update(context: Context, text: String, done: Int, total: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            NotificationManagerCompat.from(context).notify(Constants.NOTIF_ID, buildNotification(context, text, done, total))
        }

        private fun buildNotification(context: Context, text: String, done: Int, total: Int): Notification {
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(context, Constants.NOTIF_CHANNEL_ID)
                .setContentTitle(if (total > 0) "Uploading $done of $total to Google Photos" else "GPic")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(total, done, total == 0)
                .build()
        }
    }
}
