package com.wifistream.receiver

import android.app.Activity
import android.net.Uri
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

private val VLC_SCALES = listOf(
    MediaPlayer.ScaleType.SURFACE_BEST_FIT to "Fit",
    MediaPlayer.ScaleType.SURFACE_FILL to "Fill",
    MediaPlayer.ScaleType.SURFACE_16_9 to "16:9",
    MediaPlayer.ScaleType.SURFACE_4_3 to "4:3",
)

/**
 * The compatibility engine: VLC, which decodes nearly anything in software (WMV/VC-1, RMVB,
 * interlaced MPEG-2, old DivX) and draws styled ASS subtitles with their fonts and positions.
 * Fewer options than the main player, but it plays what the main player cannot.
 */
@Composable
fun CompatPlayerScreen(screen: Screen.CompatPlayer, store: LibraryStore, onExit: () -> Unit, onMainPlayer: (Screen) -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val isTv = LocalIsTv.current
    val exit by rememberUpdatedState(onExit)

    var index by remember { mutableIntStateOf(screen.startIndex.coerceIn(0, screen.items.lastIndex)) }
    val item = screen.items[index]
    var playing by remember { mutableStateOf(false) }
    var timeMs by remember { mutableLongStateOf(0L) }
    var lengthMs by remember { mutableLongStateOf(0L) }
    var controls by remember { mutableStateOf(true) }
    var activity by remember { mutableIntStateOf(0) }
    var hint by remember { mutableStateOf(screen.reason) }
    var error by remember { mutableStateOf<String?>(null) }
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    var audioName by remember { mutableStateOf("Default") }
    var subtitleName by remember { mutableStateOf("Off") }
    var scale by remember { mutableIntStateOf(0) }
    var ended by remember { mutableIntStateOf(0) }

    val libVlc = remember {
        LibVLC(context, arrayListOf("--audio-time-stretch", "--http-reconnect", "--avcodec-skiploopfilter=0", "--subsdec-encoding=UTF-8"))
    }
    val player = remember { MediaPlayer(libVlc) }

    fun saveProgress() {
        val key = item.key ?: return
        if (lengthMs > 0) store.setProgress(key, player.time.coerceAtLeast(0), lengthMs)
    }

    // A new item (start, or the next episode) loads here.
    LaunchedEffect(index) {
        val start = if (index == screen.startIndex) screen.startMs ?: item.startMs else 0L
        val media = Media(libVlc, Uri.parse(Link.map(item.url))).apply {
            setHWDecoderEnabled(true, false)
            addOption(":network-caching=4000")
            if (start > 0) addOption(":start-time=${start / 1000.0}")
            // Subtitle files next to the video are added so VLC can draw them too.
            item.subtitles.forEach { addSlave(org.videolan.libvlc.interfaces.IMedia.Slave(org.videolan.libvlc.interfaces.IMedia.Slave.Type.Subtitle, 4, Link.map(it.url))) }
        }
        player.media = media
        media.release()
        player.play()
    }

    DisposableEffect(Unit) {
        player.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    playing = true
                    error = null
                }
                MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> playing = false
                MediaPlayer.Event.TimeChanged -> timeMs = event.timeChanged
                MediaPlayer.Event.LengthChanged -> lengthMs = event.lengthChanged
                MediaPlayer.Event.EndReached -> ended++
                MediaPlayer.Event.EncounteredError -> error = "The compatibility engine could not play this file either."
                // VLC lists "Disable" as track -1 and picks its tracks a moment after they appear,
                // so the names are read again whenever a track is added or chosen.
                MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESSelected, MediaPlayer.Event.Vout -> {
                    val a = player.audioTrack
                    audioName = if (a < 0) "Off" else player.audioTracks?.firstOrNull { it.id == a }?.name ?: audioName
                    val t = player.spuTrack
                    subtitleName = if (t < 0) "Off" else player.spuTracks?.firstOrNull { it.id == t }?.name ?: "Off"
                }
            }
        }
        val bars = (context as? Activity)?.window?.let { WindowCompat.getInsetsController(it, view) }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        val host = context as? Activity
        host?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            saveProgress()
            player.setEventListener(null)
            player.stop()
            player.detachViews()
            player.release()
            libVlc.release()
            host?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            bars?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    LaunchedEffect(ended) {
        if (ended == 0) return@LaunchedEffect
        item.key?.let { store.setWatched(listOf(it), true) }
        if (index < screen.items.lastIndex) index++ else exit()
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            if (player.isPlaying) saveProgress()
        }
    }
    LaunchedEffect(controls, activity, playing) {
        if (controls && playing) {
            delay(5_000)
            controls = false
        }
    }
    LaunchedEffect(hint) {
        if (hint != null) {
            delay(if (hint == screen.reason) 4_000 else 1_600)
            hint = null
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        player.pause()
        saveProgress()
        store.flush()
    }

    fun togglePlay() = if (player.isPlaying) player.pause() else player.play()
    fun skip(forward: Boolean) {
        player.time = (player.time + if (forward) 30_000 else -10_000).coerceIn(0, lengthMs.coerceAtLeast(0))
        hint = if (forward) "+30 s" else "−10 s"
    }
    fun cycleAudio() {
        val tracks = player.audioTracks?.filter { it.id >= 0 }.orEmpty()
        if (tracks.isEmpty()) return
        val next = tracks[(tracks.indexOfFirst { it.id == player.audioTrack } + 1) % tracks.size]
        player.setAudioTrack(next.id)
        audioName = next.name
        hint = "Audio: ${next.name}"
    }
    fun cycleSubtitles() {
        val tracks = player.spuTracks.orEmpty().toList()
        if (tracks.isEmpty()) {
            hint = "This file has no subtitles"
            return
        }
        val next = tracks[(tracks.indexOfFirst { it.id == player.spuTrack } + 1) % tracks.size]
        player.setSpuTrack(next.id)
        subtitleName = if (next.id < 0) "Off" else next.name
        hint = "Subtitles: $subtitleName"
    }
    fun cycleScale() {
        scale = (scale + 1) % VLC_SCALES.size
        player.videoScale = VLC_SCALES[scale].first
        hint = "Picture: ${VLC_SCALES[scale].second}"
    }

    fun goBack() {
        if (controls && playing) controls = false else exit()
    }
    BackHandler { goBack() }

    val playFocus = remember { FocusRequester() }
    val screenFocus = remember { FocusRequester() }
    LaunchedEffect(controls) {
        withFrameNanos { }
        runCatching { if (controls) playFocus.requestFocus() else screenFocus.requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                val code = event.nativeKeyEvent.keyCode
                if (code == KeyEvent.KEYCODE_BACK) {
                    if (event.type == KeyEventType.KeyUp) goBack()
                    return@onPreviewKeyEvent true
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                activity++
                when (code) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> togglePlay().let { true }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> skip(true).let { true }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> skip(false).let { true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (!controls) skip(false).let { true } else false
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (!controls) skip(true).let { true } else false
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN ->
                        if (!controls) {
                            controls = true
                            true
                        } else {
                            false
                        }
                    else -> false
                }
            }
            .focusRequester(screenFocus)
            .focusable()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        activity++
                        controls = !controls
                    },
                    onDoubleTap = { offset ->
                        when {
                            offset.x < size.width / 3 -> skip(false)
                            offset.x > size.width * 2 / 3 -> skip(true)
                            else -> togglePlay()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                VLCVideoLayout(ctx).also {
                    it.keepScreenOn = true
                    player.attachViews(it, null, true, false)
                }
            },
        )

        hint?.let {
            Text(
                it,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(if (it == screen.reason) Alignment.TopCenter else Alignment.Center)
                    .padding(top = if (it == screen.reason) 80.dp else 0.dp)
                    .background(Color(0xB3000000), RoundedCornerShape(12.dp))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
        error?.let {
            Text(
                "$it\nPress Back to return.",
                modifier = Modifier.background(Color(0xCC000000), RoundedCornerShape(12.dp)).padding(24.dp),
            )
        }

        if (controls && error == null) {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                    .padding(horizontal = screenMargin(), vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(item.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                FormatChip("COMPATIBILITY ENGINE", large = false)
            }
            Row(
                Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(26.dp),
            ) {
                ChipButton("−10 s", onClick = { skip(false) })
                RoundButton(
                    if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                    if (playing) "Pause" else "Play",
                    onClick = { togglePlay() },
                    modifier = Modifier.focusRequester(playFocus),
                    large = true,
                )
                ChipButton("+30 s", onClick = { skip(true) })
            }
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = screenMargin(), vertical = 16.dp)
                    .fillMaxWidth()
                    .background(GlassDark, RoundedCornerShape(26.dp))
                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(26.dp))
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(formatClock(scrubMs ?: timeMs))
                    SeekBar(
                        positionMs = timeMs,
                        durationMs = lengthMs,
                        bufferedMs = timeMs,
                        scrubMs = scrubMs,
                        onScrub = {
                            scrubMs = it
                            activity++
                        },
                        onScrubEnd = {
                            scrubMs?.let { player.time = it }
                            scrubMs = null
                        },
                        onStep = { forward -> skip(forward) },
                        modifier = Modifier.weight(1f),
                    )
                    Text(formatClock(lengthMs), color = Muted)
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                ) {
                    ChipButton("Audio: $audioName", onClick = ::cycleAudio)
                    ChipButton("Subtitles: $subtitleName", onClick = ::cycleSubtitles)
                    ChipButton("Picture: ${VLC_SCALES[scale].second}", onClick = ::cycleScale)
                    ChipButton("Main player", onClick = {
                        saveProgress()
                        onMainPlayer(Screen.Player(screen.items.map { if (it === item) it.copyStart(player.time) else it }, index, askResume = false))
                    })
                }
                if (!isTv) Text("Double-tap the sides to skip, the middle to pause.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** The same item, starting at [ms]. */
fun PlayItem.copyStart(ms: Long): PlayItem = PlayItem(
    url = url,
    title = title,
    key = key,
    startMs = ms.coerceAtLeast(0),
    subtitles = subtitles,
    audio = audio,
    tmdbId = tmdbId,
    season = season,
    episode = episode,
    bitsPerSecond = bitsPerSecond,
    episodeName = episodeName,
    overview = overview,
    meta = meta,
    media = media,
    showKey = showKey,
)
