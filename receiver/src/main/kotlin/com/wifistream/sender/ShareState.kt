package com.wifistream.sender

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** What the screen shows about the sharing service. Written by [ShareService]. */
data class ShareStatus(
    val running: Boolean = false,
    val deviceName: String = "",
    /** `http://ip:port`, or null when the phone has no Wi-Fi / hotspot address. */
    val address: String? = null,
    /** True once the phone is announced on the network for automatic discovery. */
    val discoverable: Boolean = false,
    val scannedFiles: Int = 0,
    /** Speed measured by the last watching device that ran a speed test against this phone, in Mb/s. */
    val lastSpeedMbps: Double? = null,
    /** Times a shared USB drive vanished without being ejected while sharing, and when it last did. */
    val driveDrops: Int = 0,
    val lastDropAt: Long = 0,
    /** True while a shared drive is missing right now. */
    val driveMissing: Boolean = false,
    /** Battery temperature in °C (0 = not read yet). */
    val temperatureC: Float = 0f,
    /** True while the phone is warm and seek-bar pictures are paused to cool it down. */
    val previewsPaused: Boolean = false,
    val error: String? = null,
)

object ShareState {
    val status = MutableStateFlow(ShareStatus())

    /** Changes whenever a media file appears, disappears or is replaced in a shared folder. */
    @Volatile var libraryStamp = 0L

    /** The newest events first: Wi-Fi lost and back, screen off and on, restarts. Kept across restarts. */
    val events = MutableStateFlow<List<ShareEvent>>(emptyList())

    fun log(context: Context, text: String) {
        val event = ShareEvent(System.currentTimeMillis(), text)
        events.update { (listOf(event) + it).take(MAX_EVENTS) }
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, events.value.joinToString("\n") { "${it.at}\t${it.text.replace('\n', ' ')}" }).apply()
        }
    }

    fun loadEvents(context: Context) {
        if (events.value.isNotEmpty()) return
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        events.value = saved.lines().mapNotNull { line ->
            val at = line.substringBefore('\t').toLongOrNull() ?: return@mapNotNull null
            ShareEvent(at, line.substringAfter('\t'))
        }
    }

    fun clearEvents(context: Context) {
        events.value = emptyList()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    private const val PREFS = "share_events"
    private const val KEY = "events"
    private const val MAX_EVENTS = 40
}

data class ShareEvent(val at: Long, val text: String)
