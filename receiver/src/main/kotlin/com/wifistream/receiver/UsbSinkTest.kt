package com.wifistream.receiver

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Plays the FLAC files in files/usbtest through the real player setup with direct USB output on,
 * and on each one pauses, resumes and seeks, checking the clock stays in step with real time.
 * `am start -n com.wifistream.receiver/.UsbSinkTestActivity [--ez keep true] [--ei vol -40]`;
 * results in files/usb-sink-test.txt (ends with DONE).
 */
@OptIn(UnstableApi::class)
class UsbSinkTestActivity : Activity() {
    private lateinit var text: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private val action = "com.wifistream.receiver.USB_SINK_TEST_PERMISSION"
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { if (intent.action == action) begin() }
    }
    private val t0 = SystemClock.elapsedRealtime()
    private val lines = StringBuilder()

    private fun show(s: String) {
        val line = "[${SystemClock.elapsedRealtime() - t0} ms] $s"
        Log.i("UsbSinkTest", line)
        lines.appendLine(line)
        runCatching { File(filesDir, "usb-sink-test.txt").writeText(lines.toString()) }
        text.text = lines
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this).apply { setTextColor(-1); setBackgroundColor(-0x1000000); textSize = 13f; setPadding(32, 48, 32, 32) }
        setContentView(ScrollView(this).apply { addView(text) })
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, IntentFilter(action), RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(receiver, IntentFilter(action))
        val usb = getSystemService(USB_SERVICE) as UsbManager
        val dac = UsbDacProbe.findDac(usb)
        when {
            dac == null -> show("No USB DAC.\nDONE")
            usb.hasPermission(dac) -> begin()
            else -> usb.requestPermission(dac, PendingIntent.getBroadcast(this, 0, Intent(action).setPackage(packageName), PendingIntent.FLAG_MUTABLE))
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    private class ItemState {
        var step = 0
        var pausedAt = 0L
        var wall0 = 0L
        var pos0 = 0L
    }

    private fun begin() {
        val only = intent.getStringExtra("only")
        val files = getExternalFilesDir("usbtest")?.listFiles()?.filter { it.extension == "flac" && (only == null || it.name.startsWith(only)) }?.sortedBy { it.name }.orEmpty()
        UsbDacSession.cpuMode = intent.getIntExtra("cpu", 1)
        UsbDacSession.queueOverride = intent.getIntExtra("queue", 0).takeIf { it > 0 }
        if (files.isEmpty()) return show("No test files.\nDONE")
        val keep = intent.getBooleanExtra("keep", false)
        val vol = intent.getIntExtra("vol", -40).toDouble().coerceAtMost(0.0)
        show("Direct USB, keep sound settings: $keep, volume $vol dB, ${files.size} files")
        val p = ExoPlayer.Builder(this, AppRenderersFactory(this, false, false, AtomicLong(0), SoundProcessor()) {
            UsbOutputSettings(enabled = intent.getBooleanExtra("direct", true), keepSound = keep, volumeDb = vol, maxDb = 0.0)
        }).build()
        // Keeps the CPU awake while playing, so the screen turning off does not starve the DAC.
        if (intent.getBooleanExtra("wake", true)) p.setWakeMode(androidx.media3.common.C.WAKE_MODE_LOCAL)
        player = p
        val states = files.map { ItemState() }
        p.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                show("Now: ${files.getOrNull(p.currentMediaItemIndex)?.name}")
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    p.release(); player = null
                    UsbDacSession.streamStats.forEach { show("  engine: $it") }
                    show("DONE")
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                show("ERROR ${error.errorCodeName}: ${error.message}\nDONE")
            }
        })
        p.setMediaItems(files.map { MediaItem.fromUri(Uri.fromFile(it)) })
        p.prepare()
        p.play()
        val tick = object : Runnable {
            override fun run() {
                val pl = player ?: return
                val st = states.getOrNull(pl.currentMediaItemIndex) ?: return
                val pos = pl.currentPosition
                val now = SystemClock.elapsedRealtime()
                when (st.step) {
                    0 -> if (pos >= 2000) {
                        val f = pl.audioFormat
                        show("  format ${f?.sampleRate} Hz, encoding ${f?.pcmEncoding}; pause at $pos ms")
                        pl.pause(); st.pausedAt = now; st.step = 1
                    }
                    1 -> if (now - st.pausedAt >= 1000) { show("  resume at ${pl.currentPosition} ms"); pl.play(); st.step = 2 }
                    2 -> if (pos >= 4000) { show("  seek from $pos to 1000 ms"); pl.seekTo(1000); st.step = 3 }
                    3 -> if (pl.isPlaying && pos in 1100..2000) { st.wall0 = now; st.pos0 = pos; st.step = 4 }
                    4 -> if (pos >= 6500) {
                        val drift = (pos - st.pos0) - (now - st.wall0)
                        show("  clock check: played ${pos - st.pos0} ms in ${now - st.wall0} ms real time (drift $drift ms)")
                        st.step = 5
                    }
                }
                handler.postDelayed(this, 20)
            }
        }
        handler.post(tick)
    }
}
