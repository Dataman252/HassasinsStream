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
        if (server == null) {
            ShareState.loadEvents(applicationContext)
            // No intent means Android stopped the service and has now brought it back by itself.
            ShareState.log(applicationContext, if (intent == null) "Restarted by itself after the phone stopped it" else "Sharing turned on")
            startSharing()
        }
        return START_STICKY
    }

    /** Logs Wi-Fi and screen changes, so a drop while the phone is locked can be traced afterwards. */
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private var screenReceiver: android.content.BroadcastReceiver? = null

    private fun watchPhone() {
        val connectivity = getSystemService(android.net.ConnectivityManager::class.java)
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) = ShareState.log(applicationContext, "Wi-Fi connected")
            override fun onLost(network: android.net.Network) = ShareState.log(applicationContext, "Wi-Fi lost")
        }
        val wifiOnly = android.net.NetworkRequest.Builder().addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build()
        runCatching { connectivity.registerNetworkCallback(wifiOnly, callback) }.onSuccess { networkCallback = callback }
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> ShareState.log(applicationContext, "Screen locked")
                    Intent.ACTION_SCREEN_ON -> ShareState.log(applicationContext, "Screen on")
                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                        val idle = getSystemService(PowerManager::class.java).isDeviceIdleMode
                        ShareState.log(applicationContext, if (idle) "Phone went into deep sleep (Doze)" else "Phone woke from deep sleep")
                    }
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        }
        runCatching { registerReceiver(receiver, filter) }.onSuccess { screenReceiver = receiver }
    }

    /** Battery temperature in °C, or 0 when the phone does not say. */
    private fun batteryTemperature(): Float {
        val battery = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return 0f
        return battery.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
    }

    /** A number that changes whenever a media file is added, removed or replaced in a shared folder. */
    private fun libraryStamp(storage: Storage): Long {
        var stamp = 17L
        for (root in storage.roots()) {
            root.dir.walkTopDown().maxDepth(12)
                .onEnter { !it.name.startsWith(".") }
                .filter { it.isFile }
                .forEach { file ->
                    val kind = com.wifistream.protocol.kindForFileName(file.name)
                    if (kind == com.wifistream.protocol.EntryKind.VIDEO || kind == com.wifistream.protocol.EntryKind.AUDIO ||
                        kind == com.wifistream.protocol.EntryKind.SUBTITLE
                    ) {
                        stamp = stamp * 31 + file.path.hashCode() + file.length() * 7 + file.lastModified()
                    }
                }
        }
        return if (stamp == 0L) 1L else stamp
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
        watchPhone()
        // Heat and new files: checked quietly in the background.
        scope.launch {
            val power = getSystemService(PowerManager::class.java)
            val storage = Storage(applicationContext)
            var round = 0
            while (isActive) {
                val temperature = batteryTemperature()
                val thermal = power.currentThermalStatus
                val wasPaused = ShareState.status.value.previewsPaused
                // Pause at 40 °C or when Android reports any heat; carry on only once it is clearly cooler.
                val paused = if (wasPaused) {
                    temperature >= 38f || thermal >= PowerManager.THERMAL_STATUS_MODERATE
                } else {
                    temperature >= 40f || thermal >= PowerManager.THERMAL_STATUS_LIGHT
                }
                if (paused != wasPaused) {
                    ShareState.log(
                        applicationContext,
                        if (paused) "Warm (${"%.0f".format(temperature)} °C): seek-bar pictures paused" else "Cooled down: seek-bar pictures resumed",
                    )
                }
                ShareState.status.update { it.copy(temperatureC = temperature, previewsPaused = paused) }
                if (round % 4 == 0) ShareState.libraryStamp = runCatching { libraryStamp(storage) }.getOrDefault(ShareState.libraryStamp)
                round++
                delay(5_000)
            }
        }
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
        if (server != null) ShareState.log(applicationContext, "Sharing stopped")
        networkCallback?.let { cb -> runCatching { getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(cb) } }
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        networkCallback = null
        screenReceiver = null
        discovery?.unregister()
        // Leave the TV's direct network too, if the phone joined one by scanning its code.
        com.wifistream.receiver.DirectLink.release(applicationContext)
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
