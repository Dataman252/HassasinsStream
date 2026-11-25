package com.wifistream.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * Runs while the app plays straight to a USB DAC. As a media foreground service it keeps the app
 * in Android's "playing media" group when the screen turns off, and it holds a CPU wake lock, so
 * the streaming thread is not frozen long enough to empty the DAC's queue (heard as a crackle).
 */
class UsbKeepAliveService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val CHANNEL = "usb_dac"
        private const val ID = 4711

        // Android kills an app whose service is stopped before it has gone foreground, so a stop that
        // comes too early is held until the service is up, and it then shuts itself down.
        @Volatile private var foreground = false
        @Volatile private var stopWanted = false

        fun start(context: Context) {
            stopWanted = false
            runCatching { context.startForegroundService(Intent(context, UsbKeepAliveService::class.java)) }
                .onFailure { Log.w("UsbKeepAlive", "could not start", it) }
        }

        fun stop(context: Context) {
            if (foreground) context.stopService(Intent(context, UsbKeepAliveService::class.java))
            else stopWanted = true
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "USB DAC", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Playing to your USB DAC")
            .setContentText("Direct output, untouched by Android")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(ID, notification)
        foreground = true
        if (stopWanted) {
            stopWanted = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HassasinStream:usb-dac")
                .apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        foreground = false
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }
}
