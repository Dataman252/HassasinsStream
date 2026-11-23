package com.wifistream.receiver

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.SurfaceView
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import com.wifistream.protocol.EntryKind
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer as VlcPlayer

/**
 * Format lab: plays every video in a shared folder through the same player the app uses and
 * reports, per file, whether it opens, decodes picture and sound, and seeks. Files the main player
 * cannot handle are tried in the compatibility engine. Started with:
 * `am start -n com.wifistream.receiver/.FormatLabActivity --es addr http://ip:8765 --es root ID --es folder "Format Lab"`.
 * Results go to the log (tag FormatLab) and to files/format-lab.txt.
 */
@OptIn(UnstableApi::class)
class FormatLabActivity : Activity() {
    private val scope: CoroutineScope = MainScope()
    private lateinit var surface: SurfaceView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surface = SurfaceView(this)
        status = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xCC000000.toInt())
            textSize = 12f
            setPadding(24, 24, 24, 24)
        }
        setContentView(FrameLayout(this).apply {
            addView(surface)
            addView(status)
        })
        val addr = intent.getStringExtra("addr") ?: return finish()
        val root = intent.getStringExtra("root") ?: return finish()
        val folder = intent.getStringExtra("folder").orEmpty()
        scope.launch { run(addr, root, folder) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun show(line: String) {
        Log.i("FormatLab", line)
        status.text = (status.text.toString() + "\n" + line).takeLast(4000)
    }

    private suspend fun run(addr: String, root: String, folder: String) {
        val client = SenderClient(addr)
        val files = withContext(Dispatchers.IO) { client.library(root) }.entries
            .filter { it.kind == EntryKind.VIDEO && (folder.isEmpty() || it.path.startsWith("$folder/")) }
            .sortedBy { it.path }
        show("Format lab: ${files.size} videos")
        val report = StringBuilder()
        for (entry in files) {
            val url = client.fileUrl(root, entry.path)
            val name = entry.path.substringAfterLast('/')
            val ext = name.substringAfterLast('.', "").lowercase()
            val main = if (ext in AppViewModel.COMPAT_ONLY) null else testMain(url)
            val line = if (main == null || !main.ok) {
                val vlc = testVlc(url)
                "$name | main: ${main?.summary ?: "skipped (sent to compatibility engine)"} | vlc: $vlc"
            } else {
                "$name | main: ${main.summary}"
            }
            show(line)
            report.appendLine(line)
        }
        File(filesDir, "format-lab.txt").writeText(report.toString())
        show("DONE")
    }

    private class MainResult(val ok: Boolean, val summary: String)

    private suspend fun testMain(url: String): MainResult {
        var videoDecoder = "-"
        var audioDecoder = "-"
        var error: PlaybackException? = null
        val player = ExoPlayer.Builder(this, AppRenderersFactory(this, false, false, AtomicLong(0), SoundProcessor()))
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    SeekFixDataSource.Factory(DefaultHttpDataSource.Factory().setConnectTimeoutMs(8_000).setReadTimeoutMs(20_000)),
                    DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true),
                ),
            )
            .build()
        player.setVideoSurfaceView(surface)
        player.volume = 0f
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                videoDecoder = decoderName
            }
            override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                audioDecoder = decoderName
            }
        })
        player.addListener(object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                error = e
            }
        })
        try {
            player.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
            player.prepare()
            player.play()
            if (!waitFor(15_000) { player.playbackState == Player.STATE_READY || error != null }) return MainResult(false, "never became ready")
            error?.let { return MainResult(false, "error ${it.errorCodeName}") }
            // Same rules as the player: tracks this device cannot decode go to the compatibility engine.
            val groups = player.currentTracks.groups
            val vg = groups.filter { it.type == C.TRACK_TYPE_VIDEO }
            val ag = groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            if (vg.isNotEmpty() && vg.none { it.isSupported }) return MainResult(false, "no decoder for the picture")
            if (ag.isNotEmpty() && ag.none { it.isSupported }) return MainResult(false, "no decoder for the sound")
            if (ag.isEmpty() && needsSound(url)) return MainResult(false, "sound track not found")
            val v = player.videoFormat
            val a = player.audioFormat
            val formats = "${v?.sampleMimeType?.substringAfter('/') ?: "no video"} ${v?.width ?: 0}x${v?.height ?: 0}" +
                " + ${a?.sampleMimeType?.substringAfter('/') ?: "no audio"} ${a?.channelCount ?: 0}ch"
            val start = player.currentPosition
            delay(2_500)
            error?.let { return MainResult(false, "$formats, error while playing ${it.errorCodeName}") }
            val advanced = player.currentPosition - start
            if (advanced < 800) return MainResult(false, "$formats, stuck (advanced ${advanced} ms)")
            val duration = player.duration
            val seekable = player.isCurrentMediaItemSeekable
            var seek = "not seekable"
            if (seekable && duration != C.TIME_UNSET && duration > 10_000) {
                val target = duration * 7 / 10
                player.seekTo(target)
                waitFor(10_000) { player.playbackState == Player.STATE_READY || error != null }
                delay(600)
                val off = player.currentPosition - target
                seek = if (error == null && off in -3_000..5_000) "seek ok" else "seek landed ${off} ms off"
            }
            val ok = error == null && (seekable || duration == C.TIME_UNSET)
            return MainResult(ok, "$formats | video ${videoDecoder} | audio ${audioDecoder} | $seek")
        } finally {
            player.release()
        }
    }

    /** Plays the file in the compatibility engine (sound and timing only) and tries a seek. */
    private suspend fun testVlc(url: String): String = withContext(Dispatchers.Main) {
        val vlc = LibVLC(this@FormatLabActivity, arrayListOf("--http-reconnect", "--vout=dummy", "--aout=dummy"))
        val mp = VlcPlayer(vlc)
        try {
            val media = Media(vlc, Uri.parse(url))
            mp.media = media
            media.release()
            mp.play()
            val started = waitFor(12_000) { mp.time > 500 }
            if (!started) return@withContext "did not start"
            waitFor(5_000) { mp.length > 0 }
            val length = mp.length
            if (length > 10_000) {
                val target = length * 7 / 10
                mp.time = target
                delay(2_500)
                val off = mp.time - target
                "plays, ${length / 1000}s, seek ${if (off in -4_000..6_000) "ok" else "off by $off ms"}"
            } else {
                // Length unknown: jump by position, as the player does.
                val before = mp.time
                mp.position = 0.7f
                delay(2_500)
                "plays, length unknown, position jump ${if (mp.time > before + 10_000) "ok (now ${mp.time / 1000}s)" else "failed (at ${mp.time / 1000}s)"}"
            }
        } finally {
            mp.stop()
            mp.release()
            vlc.release()
        }
    }

    /** AVI files carry their sound in formats (AC3, DTS) the main player can miss entirely. */
    private fun needsSound(url: String) = (Uri.parse(url).getQueryParameter("path") ?: url).lowercase().endsWith(".avi")

    private suspend fun waitFor(timeoutMs: Long, done: () -> Boolean): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (!done()) delay(100)
            true
        } ?: false
}
