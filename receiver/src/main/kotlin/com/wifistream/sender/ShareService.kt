package com.wifistream.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import java.io.File
import com.wifistream.protocol.WifiStream
import com.wifistream.receiver.MainActivity
import com.wifistream.receiver.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Keeps the file server alive while the screen is off or the app is closed. */
class ShareService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var server: FileServer? = null
    private var scanner: MediaScanner? = null
    private var discovery: Discovery? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(
            NOTIFICATION_ID,
            notification("Starting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        if (server == null) startSharing()
        return START_STICKY
    }

    private fun startSharing() {
        val name = deviceName()
        val mediaScanner = MediaScanner(applicationContext, scope).also { scanner = it }
        val keys = com.wifistream.receiver.ApiKeys.get(applicationContext)
        // Pictures now live with the videos (see FileServer); the old copies in the cache are removed.
        Thread { File(cacheDir, "frames").deleteRecursively() }.start()
        val fileServer = FileServer(name, Storage(applicationContext), mediaScanner, keys, File(filesDir, "previews"))
        try {
            fileServer.start(WifiStream.DEFAULT_PORT)
        } catch (e: Exception) {
            ShareState.status.value = ShareStatus(error = "Could not start: ${e.message ?: e.javaClass.simpleName}")
            stopSelf()
            return
        }
        server = fileServer
        fileServer.startPreviewTidy()

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hassasin:share").apply { acquire() }
        @Suppress("DEPRECATION")
        wifiLock = applicationContext.getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "hassasin:share").apply { acquire() }

        ShareState.status.value = ShareStatus(running = true, deviceName = name)
        discovery = Discovery(applicationContext) { registered ->
            ShareState.status.update { it.copy(discoverable = registered && it.running) }
        }.also { it.register(name, WifiStream.DEFAULT_PORT) }

        // Find out straight away whether the keys work, so watching devices can be told.
        scope.launch { runCatching { keys.check(force = true) } }
        scope.launch { mediaScanner.scannedCount.collect { n -> ShareState.status.update { it.copy(scannedFiles = n) } } }
        // The address changes when the phone hops between Wi-Fi networks or turns on its hotspot.
        scope.launch {
            var shown: String? = "unset"
            // Shared drives present when sharing started; one that disappears was knocked loose or lost power.
            val storage = Storage(applicationContext)
            var chosen = storage.sharedPaths()
            var known = storage.roots().map { it.info.id }.toSet()
            while (isActive) {
                // Removing a shared folder on purpose is not a drop: start counting again.
                val nowChosen = storage.sharedPaths()
                if (nowChosen != chosen) {
                    chosen = nowChosen
                    known = storage.roots().map { it.info.id }.toSet()
                }
                val present = storage.roots().map { it.info.id }.toSet()
                val missing = known - present
                val wasMissing = ShareState.status.value.driveMissing
                if (missing.isNotEmpty() && !wasMissing) {
                    ShareState.status.update { it.copy(driveDrops = it.driveDrops + 1, lastDropAt = System.currentTimeMillis(), driveMissing = true) }
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, notification("USB drive disconnected. Check its cable and power."))
                    shown = "dropped"
                } else if (missing.isEmpty() && wasMissing) {
                    ShareState.status.update { it.copy(driveMissing = false) }
                    shown = "back"
                }
                known = known + present
                val address = localWifiAddress()?.let { "http://$it:${WifiStream.DEFAULT_PORT}" }
                if (address != shown) {
                    shown = address
                    ShareState.status.update { it.copy(address = address) }
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, notification(address ?: "Waiting for Wi-Fi"))
                }
                delay(5_000)
            }
        }
    }

    override fun onDestroy() {
        discovery?.unregister()
        scope.cancel()
        scanner?.save()
        val running = server
        server = null
        // Stopping the server blocks briefly, so keep it off the main thread.
        if (running != null) Thread { running.stop() }.start()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        ShareState.status.update { if (it.error != null) it else ShareStatus() }
        super.onDestroy()
    }

    private fun deviceName(): String =
        Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"

    private fun notification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Sharing", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ShareService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_share)
            .setContentTitle("Sharing storage over Wi-Fi")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val ACTION_STOP = "com.wifistream.sender.STOP"
        private const val CHANNEL_ID = "sharing"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            ShareState.status.update { it.copy(error = null) }
            context.startForegroundService(Intent(context, ShareService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ShareService::class.java))
        }
    }
}
