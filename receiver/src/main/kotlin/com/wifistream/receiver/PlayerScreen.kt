package com.wifistream.receiver

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import coil.compose.AsyncImage
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.common.MimeTypes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class PlayerPanel { TRACKS, AUDIO, EQUALIZER, TONE, CHANNELS, DYNAMICS, CONVOLUTION, SUBTITLES, SUBTITLE_SEARCH, PICTURE, SPEED, SLEEP, EPISODES, CHAPTERS, MORE }

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private val SLEEP_MINUTES = listOf(0, 15, 30, 45, 60, 90)
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

private fun signed(value: Int, unit: String) = (if (value > 0) "+" else "") + value + " " + unit

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    screen: Screen.Player,
    store: LibraryStore,
    online: OpenSubtitles,
    senderName: String,
    onExit: () -> Unit,
    onCompat: (Screen) -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val window = (context as? Activity)?.window
    val isTv = LocalIsTv.current
    val scope = rememberCoroutineScope()
    val exit by rememberUpdatedState(onExit)
    val audioOnly = remember { screen.items.all { it.audio } }
    val stats = remember { PlaybackStats() }

    var itemIndex by remember { mutableIntStateOf(screen.startIndex) }
    val item = screen.items[itemIndex.coerceIn(0, screen.items.lastIndex)]
    var title by remember { mutableStateOf(screen.items[screen.startIndex].title) }
    var error by remember { mutableStateOf<String?>(null) }

    // Connection health: lost link, stalls, and the card that suggests the phone's hotspot.
    var lostConnection by remember { mutableStateOf(false) }
    val stalls = remember { androidx.compose.runtime.mutableStateListOf<Long>() }
    var lastJumpAt by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    var hadReady by remember { mutableStateOf(false) }
    var slowShownFor by remember { mutableStateOf<Int?>(null) }
    var slowCard by remember { mutableStateOf(false) }

    // New for this version: resume question, format badge, chapters, lock, loading and pause screens.
    val firstItem = screen.items[screen.startIndex]
    val showChoices = remember { store.showChoices(firstItem.showKey) }
    var resumeAsk by remember { mutableStateOf(!audioOnly && firstItem.startMs > 0 && screen.askResume) }
    var report by remember { mutableStateOf<FormatReport?>(null) }
    var announce by remember { mutableStateOf(false) }
    var announcedKey by remember { mutableStateOf<String?>(null) }
    var chapters by remember { mutableStateOf<List<Chapter>>(emptyList()) }
    var timeMode by remember { mutableIntStateOf(0) }
    var locked by remember { mutableStateOf(false) }
    var lockNotice by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    var pausedScreen by remember { mutableStateOf(false) }
    var centerHeld by remember { mutableStateOf(false) }
    var formatBadge by remember { mutableStateOf(store.formatBadge) }
    val resumeFocus = remember { FocusRequester() }
    var resumeFocused by remember { mutableStateOf(false) }
    // Picture subtitles (PGS, VobSub) can be made larger and lifted.
    var picSubScale by remember { mutableFloatStateOf(1f) }
    var picSubLift by remember { mutableIntStateOf(0) }
    val inPip = PlayerSession.inPip

    // What is on screen over the video.
    var controls by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf<PlayerPanel?>(null) }
    var showInfo by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    // Bumped on every key press or touch, which restarts the countdown that hides the controls.
    var activity by remember { mutableIntStateOf(0) }

    // Picture and pace choices for this playback.
    var picture by remember { mutableStateOf(PictureMode.FIT) }
    var speed by remember { mutableFloatStateOf(1f) }
    var sleepMinutes by remember { mutableIntStateOf(0) }
    // Repeat a stretch of the video: once both ends are set it loops.
    var repeatFrom by remember { mutableStateOf<Long?>(null) }
    var repeatTo by remember { mutableStateOf<Long?>(null) }
    var shuffle by remember { mutableStateOf(false) }
    var repeatAll by remember { mutableStateOf(false) }
    // True while a finger is held down for temporary double speed.
    var holding by remember { mutableStateOf(false) }
    var matchRate by remember { mutableStateOf(store.matchFrameRate) }

    // Sound. The engine only works on sound decoded here, so shaping the sound switches passthrough off.
    var sound by remember { mutableStateOf(store.sound) }
    // Which band the equalizer's controls are editing.
    var eqBand by remember { mutableIntStateOf(0) }
    var passthrough by remember { mutableStateOf(store.passthrough) }
    val engine = remember { SoundProcessor() }

    // Pinch zoom: how much larger than the screen the picture is drawn, and how far it is dragged off-centre.
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }

    // The position being chosen on the seek bar, before playback jumps there.
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    // The last seek-bar picture that finished loading, shown while the next one loads.
    var shownFrame by remember { mutableStateOf<String?>(null) }
    var scrubByKeys by remember { mutableStateOf(false) }
    var soundDelayMs by remember { mutableIntStateOf(0) }
    val soundOffsetUs = remember { AtomicLong(0) }

    // Subtitles. Built-in tracks are drawn from the player's cues; files and downloads from [externalCues].
    var externalCues by remember { mutableStateOf<List<SubCue>?>(null) }
    var externalName by remember { mutableStateOf<String?>(null) }
    var externalLine by remember { mutableStateOf<String?>(null) }
    var builtInLine by remember { mutableStateOf("") }
    var builtInIsPicture by remember { mutableStateOf(false) }
    var subDelayMs by remember { mutableIntStateOf(showChoices?.subtitleDelayMs ?: 0) }
    var subLanguage by remember { mutableStateOf(store.subLanguage) }
    var subSize by remember { mutableIntStateOf(store.subSize) }
    var subColour by remember { mutableIntStateOf(store.subColour) }
    var subBackground by remember { mutableIntStateOf(store.subBackground) }
    var searchHits by remember { mutableStateOf<List<SubtitleHit>>(emptyList()) }
    var searchStatus by remember { mutableStateOf<String?>(null) }

    // Mirrors of player state, refreshed twice a second while the screen is up.
    var playing by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0) }
    var durationMs by remember { mutableLongStateOf(0) }
    var bufferedMs by remember { mutableLongStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    var tracksVersion by remember { mutableIntStateOf(0) }

    // Dolby Vision can be switched to its HDR10 layer, from Settings or automatically after a decoder failure.
    var dvAsHdr10 by remember { mutableStateOf(store.dvAsHdr10) }
    // Where to pick up when the player is rebuilt (decoder or passthrough change): item index and position.
    var restart by remember { mutableStateOf<Pair<Int, Long>?>(null) }

    // One sound session for the whole screen, so the equalizer stays attached when the player is rebuilt.
    val sessionId = remember { (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).generateAudioSessionId() }

    val player = remember(dvAsHdr10, passthrough) {
        ExoPlayer.Builder(context, AppRenderersFactory(context, passthrough, dvAsHdr10, soundOffsetUs, engine))
            .setLoadControl(roomyLoadControl())
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    // Wrapped so MKV files with a split seek table can still be seeked.
                    SeekFixDataSource.Factory(
                        DefaultHttpDataSource.Factory().setConnectTimeoutMs(8_000).setReadTimeoutMs(20_000),
                    ),
                    // Seeking in files without an index (MP3, MPEG-TS) and TS recordings that start mid-stream.
                    DefaultExtractorsFactory()
                        .setConstantBitrateSeekingEnabled(true)
                        .setTsExtractorFlags(
                            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
                        ),
                ),
            )
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
            .apply {
                val index = restart?.first ?: screen.startIndex
                val position = restart?.second ?: screen.items[index].startMs
                setMediaItems(screen.items.map { it.toMediaItem() }, index, if (position > 0) position else C.TIME_UNSET)
                setPlaybackSpeed(speed)
                audioSessionId = sessionId
                // Start on the languages chosen last time, when the file has them.
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setPreferredAudioLanguage((showChoices?.audioLanguage ?: store.audioLanguage).ifEmpty { null })
                    .setPreferredTextLanguage((showChoices?.subtitleLanguage ?: store.subtitleLanguage).ifEmpty { null })
                    .build()
                prepare()
                // Waits for the resume question on the first start; rebuilds carry straight on.
                playWhenReady = restart != null || !resumeAsk
            }
    }

    // Episodes that arrived on the sharing phone while this plays join the end of the queue.
    LaunchedEffect(player, screen.items.size) {
        val have = player.mediaItemCount
        if (screen.items.size > have) player.addMediaItems(screen.items.drop(have).map { it.toMediaItem() })
    }

    fun saveProgress() {
        val key = screen.items.getOrNull(player.currentMediaItemIndex)?.key ?: return
        val duration = player.duration
        if (duration != C.TIME_UNSET && duration > 0) store.setProgress(key, player.currentPosition, duration)
    }

    /** Rebuilds the player where it stands, for changes that need different decoders. */
    fun rebuild(change: () -> Unit) {
        saveProgress()
        restart = player.currentMediaItemIndex to player.currentPosition
        change()
    }

    fun skip(forward: Boolean) {
        if (forward) player.seekForward() else player.seekBack()
        positionMs = player.currentPosition
        hint = if (forward) "+30 s" else "−10 s"
    }

    fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun commitScrub() {
        scrubMs?.let {
            player.seekTo(it)
            positionMs = it
        }
        scrubMs = null
    }

    fun setSound(new: SoundSettings) {
        sound = new
        store.setSound(new)
        // A stream that is being passed through undecoded cannot be shaped, so decode it here instead.
        if (new.shapesSound && passthrough && stats.passthrough == true) {
            rebuild { passthrough = false }
            hint = "Passthrough paused so the sound can be adjusted"
        }
    }

    var userPresets by remember { mutableStateOf(store.userPresets) }
    val irDir = remember { java.io.File(context.filesDir, "irs").apply { mkdirs() } }
    var irVersion by remember { mutableIntStateOf(0) }
    val impulses = remember(irVersion) {
        irDir.listFiles().orEmpty().filter { it.isFile && it.extension.equals("wav", true) }.sortedBy { it.name.lowercase() }
            .map { it.name to WavReader.describe(it) }
    }
    val importIr = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val name = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val raw = (uri.lastPathSegment?.substringAfterLast('/') ?: "impulse").substringBeforeLast('.')
                val safe = raw.replace(Regex("[^A-Za-z0-9 ._()-]"), "").take(40).ifBlank { "impulse" } + ".wav"
                val out = java.io.File(irDir, safe)
                runCatching { context.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } } }
                if (WavReader.read(out) == null) {
                    out.delete()
                    null
                } else {
                    safe
                }
            }
            if (name == null) {
                hint = "That file is not a WAV impulse response"
            } else {
                irVersion++
                setSound(sound.copy(convFile = name, convOn = true))
                hint = "Impulse response loaded"
            }
        }
    }
    // The chosen impulse response is read once, away from the screen, and handed to the sound engine.
    LaunchedEffect(sound.convOn, sound.convFile) {
        val file = sound.convFile.takeIf { sound.convOn && it.isNotBlank() }?.let { java.io.File(irDir, it) }
        val ir = file?.let { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { WavReader.read(it) } }
        engine.setImpulse(ir)
    }
    val importEq = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
        val snapshot = text?.let(::parseEqFile)
        if (snapshot == null) {
            hint = "No equalizer filters found in that file"
        } else {
            val name = (uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Imported").take(28)
            store.saveUserPreset(name, snapshot)
            userPresets = store.userPresets
            eqBand = 0
            setSound(sound.apply(snapshot, name))
            hint = "Imported ${snapshot.bands.size} filters"
        }
    }
    val exportEq = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(sound.toEqFile().toByteArray()) } }.isSuccess
        hint = if (ok) "Equalizer saved" else "Could not save there"
    }

    fun useExternalSubtitles(text: String, name: String) {
        val cues = parseSubtitles(text)
        if (cues.isEmpty()) {
            hint = "That subtitle file could not be read"
            return
        }
        selectTrack(player, C.TRACK_TYPE_TEXT, null)
        externalCues = cues
        externalName = name
        hint = "Subtitles on"
    }

    fun saveSubtitleLook() = store.setSubtitleLook(subLanguage, subSize, subColour, subBackground)

    var switched by remember { mutableStateOf(false) }

    /** Hands this video to the compatibility engine at the same spot. */
    fun useCompat(reason: String) {
        if (switched) return
        switched = true
        saveProgress()
        onCompat(Screen.CompatPlayer(screen.items, player.currentMediaItemIndex.coerceAtLeast(0), player.currentPosition, reason))
    }

    fun rememberAudio(language: String?) {
        store.setAudioLanguage(language.orEmpty())
        item.showKey?.let { show -> store.setShowChoices(show) { copy(audioLanguage = language.orEmpty()) } }
    }

    fun rememberSubtitles(language: String?) {
        store.setSubtitleLanguage(language.orEmpty())
        item.showKey?.let { show -> store.setShowChoices(show) { copy(subtitleLanguage = language.orEmpty()) } }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                title = mediaItem?.mediaMetadata?.title?.toString().orEmpty()
                itemIndex = player.currentMediaItemIndex
                // A new video starts without the previous one's downloaded subtitles.
                externalCues = null
                externalName = null
                hadReady = false
                announcedKey = null
                stalls.clear()
                stats.stalls = 0
                slowCard = false
                // Rolling into the next episode means the previous one was watched to the end.
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    screen.items.getOrNull(player.currentMediaItemIndex - 1)?.key?.let { store.setWatched(listOf(it), true) }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onTracksChanged(tracks: Tracks) {
                tracksVersion++
                // A picture or sound this device has no decoder for: let the compatibility engine try.
                val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                val sound = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                if (video.isNotEmpty() && video.none { it.isSupported }) {
                    useCompat("This device has no decoder for this video's format, so the compatibility engine is playing it.")
                } else if (!audioOnly && sound.isNotEmpty() && sound.none { it.isSupported }) {
                    useCompat("This device cannot decode this file's sound, so the compatibility engine is playing it.")
                }
            }

            override fun onCues(cueGroup: CueGroup) {
                builtInIsPicture = cueGroup.cues.any { it.bitmap != null }
                val line = cueGroup.cues.mapNotNull { it.text?.toString() }.joinToString("\n")
                // Built-in subtitles arrive at their own time, so they can be held back but not brought forward.
                if (subDelayMs > 0) {
                    scope.launch {
                        delay(subDelayMs.toLong())
                        builtInLine = line
                    }
                } else {
                    builtInLine = line
                }
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) lastJumpAt = System.currentTimeMillis()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    hadReady = true
                    lostConnection = false
                }
                // Waiting for data in the middle of playing, not just after a jump, means the connection could not keep up.
                if (playbackState == Player.STATE_BUFFERING && hadReady && player.playWhenReady &&
                    System.currentTimeMillis() - lastJumpAt > 3_000
                ) {
                    stalls += System.currentTimeMillis()
                    stats.stalls = stalls.size
                }
                if (playbackState == Player.STATE_ENDED) {
                    screen.items.getOrNull(player.currentMediaItemIndex)?.key?.let { store.setWatched(listOf(it), true) }
                    exit()
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                val decoderProblem = e.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                    e.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                    e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                    e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
                if (decoderProblem && !dvAsHdr10) {
                    // One automatic retry without the Dolby Vision decoder before giving up.
                    restart = player.currentMediaItemIndex to player.currentPosition
                    dvAsHdr10 = true
                    return
                }
                if (e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                    e.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                ) {
                    // Keeps trying, so switching to the phone's hotspot mid-film just carries on.
                    lostConnection = true
                    error = "Lost the connection to $senderName. Trying again…\n" +
                        "If you are switching to its hotspot, playback carries on by itself once this device has joined it."
                    return
                }
                if (decoderProblem ||
                    e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                    e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                    e.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
                ) {
                    useCompat("The main player could not play this file, so the compatibility engine took over.")
                    return
                }
                error = when {
                    e.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "$senderName could not send this file. Is it still shared?"
                    decoderProblem -> "This device cannot play this file's format."
                    else -> "Could not play this file (${e.errorCodeName})."
                }
            }
        }
        val analytics = object : AnalyticsListener {
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, a: Long, b: Long) {
                stats.videoDecoder = decoderName
            }

            override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, a: Long, b: Long) {
                stats.audioDecoder = decoderName
            }

            override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
                // Anything that is not plain PCM is going out untouched for the TV or soundbar to decode.
                stats.passthrough = !Util.isEncodingLinearPcm(audioTrackConfig.encoding)
            }

            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                stats.droppedFrames += droppedFrames
            }

            override fun onBandwidthEstimate(eventTime: AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long) {
                stats.networkBitsPerSecond = bitrateEstimate
            }
        }
        player.addListener(listener)
        player.addAnalyticsListener(analytics)
        onDispose {
            saveProgress()
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(sound) { engine.set(sound) }
    // ReplayGain: each song at the same loudness, from the music file's own tags.
    LaunchedEffect(itemIndex, sound.replayGain) {
        val tags = item.media
        val db = if (!item.audio) null else when (sound.replayGain) {
            1 -> tags?.replayGainTrackDb ?: tags?.replayGainAlbumDb
            2 -> tags?.replayGainAlbumDb ?: tags?.replayGainTrackDb
            else -> null
        }
        engine.setTrackGain(db ?: 0f)
    }

    // With a remote, each press of left or right moves the chosen position; playback jumps once the presses stop.
    LaunchedEffect(scrubMs, scrubByKeys) {
        if (scrubMs != null && scrubByKeys) {
            delay(800)
            commitScrub()
        }
    }

    // After a lost connection, try again every few seconds from the same spot.
    LaunchedEffect(lostConnection) {
        while (lostConnection) {
            delay(5_000)
            if (lostConnection) {
                player.prepare()
                player.playWhenReady = true
            }
        }
    }
    LaunchedEffect(lostConnection) { if (!lostConnection && error?.startsWith("Lost the connection") == true) error = null }

    LaunchedEffect(itemIndex) { stats.needBitsPerSecond = item.bitsPerSecond }

    // Seek-bar pictures are cached on this device; the video on screen goes to the front of the queue.
    LaunchedEffect(itemIndex, durationMs > 0) {
        if (audioOnly || durationMs <= 0) return@LaunchedEffect
        Thumbs.setPlaying(item.key?.let { ThumbTarget(it, title, item.url, durationMs) })
    }
    DisposableEffect(Unit) { onDispose { Thumbs.setPlaying(null); Thumbs.holdOff = false } }
    LaunchedEffect(buffering) { Thumbs.holdOff = buffering }
    val cachedThumbs = remember(Thumbs.version, itemIndex, durationMs) { Thumbs.coverage(item.key, durationMs) }

    // While a position is being chosen, fetch the pictures either side of it so the next ones are ready.
    LaunchedEffect(scrubMs?.let { it / 30_000 }) {
        val at = scrubMs ?: return@LaunchedEffect
        if (audioOnly || durationMs <= 0) return@LaunchedEffect
        val loader = coil.Coil.imageLoader(context)
        for (step in listOf(10_000L, -10_000L, 20_000L, -20_000L, 30_000L, -30_000L, 60_000L, -60_000L)) {
            val t = at + step
            if (t in 0..durationMs) loader.enqueue(coil.request.ImageRequest.Builder(context).data(frameUrl(item.url, t)).build())
        }
    }

    // Chapters come from the file itself (MKV), read straight from the sharing phone.
    LaunchedEffect(itemIndex) {
        chapters = emptyList()
        if (!audioOnly) chapters = MkvChapters.load(item.url)
    }

    // The format badge: worked out from what is really playing, announced once per video.
    LaunchedEffect(tick / 2, itemIndex) {
        val now = formatReport(player, stats, item.media, view.display, Uri.parse(item.url).getQueryParameter("path").orEmpty()) ?: return@LaunchedEffect
        report = now
        if (formatBadge && !resumeAsk && player.isPlaying && now.key != announcedKey) {
            announcedKey = now.key
            announce = true
        }
    }
    LaunchedEffect(announce, controls, panel) {
        if (announce && !controls && panel == null) {
            delay(4_000)
            announce = false
        }
    }

    // Phones: leaving the app during a video shrinks it into a floating window.
    LaunchedEffect(playing, tracksVersion) {
        PlayerSession.pipAllowed = !isTv && !audioOnly && playing
        player.videoFormat?.let { v ->
            if (v.width > 0 && v.height > 0) {
                val ratio = (v.width * v.pixelWidthHeightRatio / v.height).coerceIn(0.42f, 2.38f)
                PlayerSession.pipAspect = android.util.Rational((ratio * 1000).roundToInt(), 1000)
            }
        }
    }
    DisposableEffect(Unit) { onDispose { PlayerSession.pipAllowed = false } }
    LaunchedEffect(inPip) { if (inPip) controls = false }

    // Music keeps playing in the background, with controls on the lock screen.
    if (audioOnly) {
        DisposableEffect(player) {
            PlaybackService.attach(context, player)
            onDispose { PlaybackService.detach(context) }
        }
    }

    // Each show remembers its subtitle timing.
    LaunchedEffect(subDelayMs) { item.showKey?.let { show -> store.setShowChoices(show) { copy(subtitleDelayMs = subDelayMs) } } }

    // A few seconds into a pause, with nothing else on screen, show what is playing (like Apple TV).
    LaunchedEffect(playing, controls, panel, resumeAsk, error) {
        pausedScreen = false
        if (!audioOnly && !controls && panel == null && !resumeAsk && error == null && !player.playWhenReady) {
            delay(1_200)
            pausedScreen = !player.playWhenReady
        }
    }

    // "Crop black bars": measure the bars once, then zoom just past them.
    LaunchedEffect(picture, itemIndex, durationMs > 0) {
        if (picture != PictureMode.BARS || durationMs <= 0) return@LaunchedEffect
        val bars = blackBarFraction(item.url, durationMs)
        if (bars == null) {
            hint = "No black bars found in this video"
            return@LaunchedEffect
        }
        val v = player.videoFormat
        val frameAspect = if (v != null && v.width > 0 && v.height > 0) v.width * v.pixelWidthHeightRatio / v.height else 16f / 9f
        val screenAspect = if (view.height > 0) view.width.toFloat() / view.height else 16f / 9f
        zoom = cropZoom(bars, frameAspect, screenAspect)
        panX = 0f
        panY = 0f
        hint = if (zoom > 1.01f) "Black bars cropped" else "The bars already fit this screen"
    }

    // Suggest the hotspot once per video: after two stalls in three minutes, or at the start when the
    // last speed test already showed the connection is too slow for this file.
    LaunchedEffect(tick / 4, itemIndex) {
        if (audioOnly || slowShownFor == itemIndex) return@LaunchedEffect
        val now = System.currentTimeMillis()
        val recentStalls = stalls.count { now - it < 180_000 }
        val tested = Link.lastSpeedMbps
        val testedTooSlow = item.bitsPerSecond > 0 && tested != null && tested * 1e6 < item.bitsPerSecond * 1.2
        if (recentStalls >= 2 || testedTooSlow) {
            slowCard = true
            slowShownFor = itemIndex
        }
    }
    LaunchedEffect(slowCard) {
        if (slowCard) {
            delay(25_000)
            slowCard = false
        }
    }

    // Keep the on-screen numbers current, and remember the position so a power cut loses only seconds.
    LaunchedEffect(player) {
        var beats = 0
        while (true) {
            positionMs = player.currentPosition
            durationMs = player.duration.coerceAtLeast(0)
            bufferedMs = player.bufferedPosition
            val from = repeatFrom
            val to = repeatTo
            if (from != null && to != null && positionMs >= to) player.seekTo(from)
            tick++
            if (++beats % 10 == 0 && player.isPlaying) saveProgress()
            delay(500)
        }
    }

    // Downloaded and side-by-side subtitles are timed here, which is what lets them move earlier or later.
    LaunchedEffect(player, externalCues, subDelayMs) {
        val cues = externalCues
        if (cues == null) {
            externalLine = null
            return@LaunchedEffect
        }
        while (true) {
            externalLine = cues.at(player.currentPosition - subDelayMs)
            delay(100)
        }
    }

    // Leaving the app (Home button, phone call) pauses a video instead of playing on unseen. Music carries on.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (!audioOnly) player.pause()
        saveProgress()
        store.flush()
    }

    // Full screen on phones; restore bars, brightness and the TV's refresh rate on the way out.
    DisposableEffect(Unit) {
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        // Films are wide: video turns a phone's screen on its side (either way up). Music leaves it alone.
        val host = context as? Activity
        if (!audioOnly) host?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            host?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            bars?.show(WindowInsetsCompat.Type.systemBars())
            window?.let {
                // Waits for the player to be released first, so the TV leaves HDR before the mode changes.
                resetRefreshRate(it, afterMs = 800)
                it.attributes = it.attributes.apply { screenBrightness = -1f }
            }
        }
    }

    // Controls fade away after five quiet seconds of playback. Music keeps them, having no picture to uncover.
    LaunchedEffect(controls, activity, playing, panel, resumeAsk) {
        if (controls && panel == null && !audioOnly && !resumeAsk) {
            // While paused they stay a little longer, then give way to the pause screen.
            delay(if (playing) 5_000 else 8_000)
            controls = false
        }
    }
    LaunchedEffect(lockNotice) {
        if (lockNotice) {
            delay(4_000)
            lockNotice = false
        }
    }
    LaunchedEffect(hint) {
        if (hint != null) {
            delay(1_600)
            hint = null
        }
    }
    LaunchedEffect(sleepMinutes) {
        if (sleepMinutes > 0) {
            delay(sleepMinutes * 60_000L)
            player.pause()
            saveProgress()
            exit()
        }
    }
    LaunchedEffect(tracksVersion, matchRate) {
        val fps = player.videoFormat?.frameRate ?: 0f
        if (window == null) return@LaunchedEffect
        if (matchRate) {
            matchRefreshRate(window, view.display, fps)?.let { hint = "Screen set to ${"%.0f".format(it)} Hz" }
        } else {
            resetRefreshRate(window)
        }
    }
    // Looking for subtitles online whenever the search panel opens, or its language changes.
    LaunchedEffect(panel == PlayerPanel.SUBTITLE_SEARCH, subLanguage, itemIndex) {
        if (panel != PlayerPanel.SUBTITLE_SEARCH) return@LaunchedEffect
        searchHits = emptyList()
        if (!online.enabled) {
            searchStatus = "$senderName has no working OpenSubtitles key. Add one there: Share this device, API keys."
            return@LaunchedEffect
        }
        searchStatus = "Searching…"
        runCatching { online.search(item, subLanguage) }
            .onSuccess {
                searchHits = it.take(25)
                searchStatus = if (it.isEmpty()) "Nothing found in ${languageName(subLanguage)}. Try another language." else null
            }
            .onFailure { searchStatus = it.message ?: "Could not reach OpenSubtitles." }
    }

    // "Skip intro" and "Skip credits", from chapter names.
    val current = chapters.at(positionMs, durationMs)
    val skipKind = current?.first?.kind?.takeIf { it != ChapterKind.NORMAL && positionMs < current.second - 2_000 }
    fun doSkip() {
        val target = current ?: return
        if (target.first.kind == ChapterKind.CREDITS && itemIndex < screen.items.lastIndex) {
            player.seekToNextMediaItem()
        } else {
            player.seekTo(target.second)
        }
        hint = when (target.first.kind) {
            ChapterKind.INTRO -> "Intro skipped"
            ChapterKind.RECAP -> "Recap skipped"
            else -> "Credits skipped"
        }
    }
    fun chapterStep(forward: Boolean) {
        if (chapters.isEmpty()) return
        val index = chapters.indexOfLast { it.startMs <= player.currentPosition - if (forward) 0 else 3_000 }
        val target = chapters.getOrNull(if (forward) index + 1 else index.coerceAtLeast(0)) ?: return
        player.seekTo(target.startMs)
        hint = target.title
    }

    // The remote's keys land on the button row when controls are up, and on the screen itself when they are hidden.
    val screenFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(controls, panel == null, resumeAsk) {
        withFrameNanos { }
        // The resume question keeps the remote for itself.
        if (resumeAsk) return@LaunchedEffect
        if (panel == null) runCatching { if (controls) playFocus.requestFocus() else screenFocus.requestFocus() }
    }

    // Back peels away one layer at a time: an open panel, then the controls, then the player itself.
    fun goBack() {
        when {
            panel == PlayerPanel.SUBTITLE_SEARCH -> panel = PlayerPanel.SUBTITLES
            panel == PlayerPanel.EQUALIZER || panel == PlayerPanel.TONE ||
                panel == PlayerPanel.CHANNELS || panel == PlayerPanel.DYNAMICS || panel == PlayerPanel.CONVOLUTION -> panel = PlayerPanel.AUDIO
            panel == PlayerPanel.SUBTITLES -> panel = PlayerPanel.TRACKS
            panel == PlayerPanel.SLEEP -> panel = PlayerPanel.MORE
            panel != null -> panel = null
            resumeAsk -> exit()
            controls && playing && !audioOnly -> controls = false
            else -> exit()
        }
    }
    // Covers the Back gesture on phones; the remote's Back key is handled with the other keys below.
    BackHandler { goBack() }

    fun onKey(keyCode: Int): Boolean {
        // Dedicated media keys work whatever is showing.
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> togglePlay()
            KeyEvent.KEYCODE_MEDIA_PLAY -> player.play()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> player.pause()
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> skip(true)
            KeyEvent.KEYCODE_MEDIA_REWIND -> skip(false)
            KeyEvent.KEYCODE_MEDIA_NEXT -> player.seekToNextMediaItem()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> player.seekToPreviousMediaItem()
            KeyEvent.KEYCODE_MENU -> showInfo = !showInfo
            else -> {
                if (resumeAsk && !resumeFocused) {
                    // On a touch screen the prompt's buttons only take focus after a first key press.
                    runCatching { resumeFocus.requestFocus() }
                    return true
                }
                if (controls || panel != null || resumeAsk) {
                    activity++
                    return false // let focus move between the buttons
                }
                // Controls hidden: left and right skip, up shows the details, OK skips an intro when offered,
                // anything else brings the controls up.
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> skip(false)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> skip(true)
                    KeyEvent.KEYCODE_DPAD_UP -> showInfo = !showInfo
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                        if (skipKind != null) doSkip() else controls = true
                    KeyEvent.KEYCODE_DPAD_DOWN -> controls = true
                    KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> chapterStep(true)
                    KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> chapterStep(false)
                    else -> return false
                }
            }
        }
        activity++
        return true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                val code = event.nativeKeyEvent.keyCode
                val center = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                if (code == KeyEvent.KEYCODE_BACK) {
                    // Taken here, before the focus system, which would otherwise spend Back on
                    // moving focus off the buttons instead of closing what is open.
                    if (event.type == KeyEventType.KeyUp) goBack()
                    true
                } else if (center && centerHeld) {
                    // The rest of a long press that already opened Audio & Subtitles.
                    if (event.type == KeyEventType.KeyUp) centerHeld = false
                    true
                } else if (center && event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 1 && panel == null && !resumeAsk) {
                    // Holding OK opens Audio & Subtitles.
                    centerHeld = true
                    controls = true
                    panel = PlayerPanel.TRACKS
                    true
                } else {
                    event.type == KeyEventType.KeyDown && onKey(code)
                }
            }
            .focusRequester(screenFocus)
            .focusable()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        activity++
                        if (panel != null) panel = null else if (!resumeAsk) controls = !controls
                    },
                    // Hold a finger down for double speed; let go to return to the chosen speed.
                    onLongPress = {
                        if (!audioOnly) {
                            holding = true
                            player.setPlaybackSpeed(2f)
                            hint = "2× while held"
                        }
                    },
                    onPress = {
                        tryAwaitRelease()
                        if (holding) {
                            holding = false
                            player.setPlaybackSpeed(speed)
                        }
                    },
                    // Double-tap the left or right third to skip, the middle to pause.
                    onDoubleTap = { offset ->
                        when {
                            offset.x < size.width / 3 -> skip(false)
                            offset.x > size.width * 2 / 3 -> skip(true)
                            else -> togglePlay()
                        }
                    },
                )
            }
            .pointerInput(isTv) {
                if (isTv) return@pointerInput
                // Phones. Sideways: seek, with a picture of where you will land. Up and down: volume on the
                // right half, brightness on the left. The first clear movement decides which it is.
                var sideways: Boolean? = null
                var travelled = 0f
                var swiped = 0f
                var rightSide = false
                var startedAt = 0L
                detectDragGestures(
                    onDragStart = { start ->
                        sideways = null
                        travelled = 0f
                        swiped = 0f
                        rightSide = start.x > size.width / 2
                        startedAt = player.currentPosition
                    },
                    onDragEnd = { if (sideways == true) commitScrub() },
                    onDragCancel = { if (sideways == true) scrubMs = null },
                ) { _, drag ->
                    if (sideways == null) sideways = abs(drag.x) > abs(drag.y)
                    if (sideways == true) {
                        if (audioOnly && durationMs <= 0) return@detectDragGestures
                        swiped += drag.x
                        // A full-width swipe covers ten minutes, or the whole video if it is shorter.
                        val span = minOf(durationMs, 600_000L).coerceAtLeast(30_000L)
                        scrubByKeys = false
                        scrubMs = (startedAt + (swiped / size.width * span).toLong()).coerceIn(0, durationMs.coerceAtLeast(0))
                        activity++
                        return@detectDragGestures
                    }
                    travelled += drag.y
                    if (abs(travelled) < 40f) return@detectDragGestures
                    val up = travelled < 0
                    travelled = 0f
                    if (rightSide) {
                        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        audio.adjustStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                            0,
                        )
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                        hint = "Volume ${audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max}%"
                    } else if (window != null) {
                        val current = window.attributes.screenBrightness.takeIf { it >= 0 } ?: 0.5f
                        val next = (current + if (up) 0.06f else -0.06f).coerceIn(0.02f, 1f)
                        window.attributes = window.attributes.apply { screenBrightness = next }
                        hint = "Brightness ${(next * 100).toInt()}%"
                    }
                }
            }
            .pointerInput(Unit) {
                // Two fingers: pinch to zoom, drag to move the zoomed picture.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } >= 2) {
                            zoom = (zoom * event.calculateZoom()).coerceIn(1f, 4f)
                            val moved = event.calculatePan()
                            val maxX = size.width * (zoom - 1f) / 2f
                            val maxY = size.height * (zoom - 1f) / 2f
                            panX = (panX + moved.x).coerceIn(-maxX, maxX)
                            panY = (panY + moved.y).coerceIn(-maxY, maxY)
                            hint = "Zoom ${(zoom * 100).roundToInt()}%"
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Picture subtitles (PGS, VobSub) are images, so the stock view draws those; text is drawn below.
        val stockSubtitles = builtInIsPicture && externalCues == null
        AndroidView(
            modifier = when (picture) {
                PictureMode.WIDE -> Modifier.aspectRatio(16f / 9f, matchHeightConstraintsFirst = true)
                PictureMode.CLASSIC -> Modifier.aspectRatio(4f / 3f, matchHeightConstraintsFirst = true)
                else -> Modifier.fillMaxSize()
            }.layout { measurable, constraints ->
                // Zoom by really making the video view larger than the screen, which a video surface handles
                // reliably, then shifting it by the pan.
                val w = (constraints.maxWidth * zoom).roundToInt()
                val h = (constraints.maxHeight * zoom).roundToInt()
                val placeable = measurable.measure(Constraints.fixed(w, h))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(
                        ((constraints.maxWidth - w) / 2f + panX).roundToInt(),
                        ((constraints.maxHeight - h) / 2f + panY).roundToInt(),
                    )
                }
            },
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    // The controls are drawn by this screen, not by the stock player view.
                    useController = false
                    keepScreenOn = true
                    isFocusable = false
                }
            },
            update = {
                it.player = player
                it.resizeMode = when (picture) {
                    PictureMode.FIT, PictureMode.BARS -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    PictureMode.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                }
                it.subtitleView?.apply {
                    visibility = if (stockSubtitles) View.VISIBLE else View.GONE
                    pivotX = width / 2f
                    pivotY = height.toFloat()
                    scaleX = picSubScale
                    scaleY = picSubScale
                    translationY = -picSubLift * height / 100f
                }
            },
        )

        val subtitleLine = if (externalCues != null) externalLine else builtInLine.takeIf { !builtInIsPicture }
        if (!subtitleLine.isNullOrBlank()) {
            SubtitleText(
                subtitleLine,
                subSize,
                subColour,
                subBackground,
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 40.dp)
                    // Lifted clear of the control bar while it is showing.
                    .padding(bottom = if (controls && panel == null) 132.dp else 44.dp)
                    .pointerInput(externalCues, subDelayMs) {
                        // Swipe the subtitle itself: left for the next line, right for the one before,
                        // and playback jumps to where that line is spoken.
                        var moved = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { moved = 0f },
                            onDragEnd = {
                                if (abs(moved) > 50f) {
                                    val forward = moved < 0
                                    val cues = externalCues
                                    if (cues != null) {
                                        cues.neighbour(player.currentPosition - subDelayMs, forward)?.let {
                                            player.seekTo((it.startMs + subDelayMs).coerceAtLeast(0))
                                            hint = if (forward) "Next line" else "Previous line"
                                        }
                                    } else {
                                        // Built-in subtitles do not expose their line list, so step a few seconds instead.
                                        player.seekTo((player.currentPosition + if (forward) 5_000 else -5_000).coerceAtLeast(0))
                                        hint = if (forward) "+5 s" else "−5 s"
                                    }
                                }
                            },
                        ) { change, dx ->
                            moved += dx
                            change.consume()
                        }
                    },
            )
        }

        // While a position is being chosen: the picture at that point, above the bar, so it is clear where to stop.
        scrubMs?.let { target ->
            val along = if (durationMs > 0) (target.toFloat() / durationMs).coerceIn(0.001f, 0.999f) else 0.5f
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = screenMargin())
                    .padding(bottom = 126.dp),
            ) {
                Spacer(Modifier.weight(along))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!audioOnly) {
                        val shape = RoundedCornerShape(14.dp)
                        Box(
                            Modifier
                                .size(208.dp, 117.dp)
                                .clip(shape)
                                .background(Color(0xFF05060F))
                                .border(1.dp, Color(0x80FFFFFF), shape),
                        ) {
                            // The last picture stays up until the next one arrives, so the preview is never blank.
                            val url = frameUrl(item.url, target)
                            val saved = Thumbs.local(item.key, target)
                            AsyncImage(
                                model = coil.request.ImageRequest.Builder(context)
                                    .data(saved ?: url)
                                    .placeholderMemoryCacheKey(shownFrame)
                                    .crossfade(false)
                                    .build(),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                onSuccess = { shownFrame = (saved ?: url).toString() },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    chapters.at(target, durationMs)?.first?.title?.let { name ->
                        Text(name, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                    }
                    Text(
                        formatClock(target),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .background(GlassDark, RoundedCornerShape(50))
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                Spacer(Modifier.weight(1f - along))
            }
        }

        // In the last seconds of an episode, say what follows.
        val remainingMs = durationMs - positionMs
        val upNext = screen.items.getOrNull(itemIndex + 1)
        if (!audioOnly && upNext != null && durationMs > 0 && remainingMs in 1..20_000) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = screenMargin(), bottom = if (controls && panel == null) 132.dp else 40.dp)
                    .background(GlassDark, RoundedCornerShape(16.dp))
                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(16.dp))
                    .clickable { player.seekToNextMediaItem() }
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            ) {
                Text("Up next in ${remainingMs / 1000 + 1} s", color = Muted, style = MaterialTheme.typography.bodySmall)
                Text(upNext.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        // Loading, instead of a frozen picture.
        if (buffering && !controls && !resumeAsk && !inPip && error == null) {
            LoadingBadge(bufferedMs - positionMs, stats.networkBitsPerSecond, Modifier.align(Alignment.Center))
        }

        if (pausedScreen && !inPip) {
            if (AppTheme.layout == AppLayout.BENTO && !audioOnly) {
                BentoPausedOverlay(item, title, positionMs, durationMs, screen.items.getOrNull(itemIndex + 1))
            } else {
                PausedOverlay(item, title, positionMs, durationMs)
            }
        }

        // "Skip intro" / "Skip credits", where the file's chapters say so.
        if (skipKind != null && panel == null && !resumeAsk && !inPip && error == null) {
            Row(
                Modifier.align(Alignment.BottomEnd).padding(end = screenMargin(), bottom = if (controls) 150.dp else 48.dp),
            ) {
                PillButton(
                    when (skipKind) {
                        ChapterKind.INTRO -> "Skip intro"
                        ChapterKind.RECAP -> "Skip recap"
                        else -> if (itemIndex < screen.items.lastIndex) "Next episode" else "Skip credits"
                    } + if (isTv && !controls) "  (OK)" else "",
                    onClick = ::doSkip,
                    primary = true,
                )
            }
        }

        if (!inPip) {
            FormatAnnounce(
                report,
                announce && !controls && panel == null,
                Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = screenMargin()),
            )
        }

        if (showInfo) {
            val fileName = remember(title) {
                screen.items.getOrNull(player.currentMediaItemIndex)?.url
                    ?.let { Uri.parse(it).getQueryParameter("path") }?.substringAfterLast('/') ?: title
            }
            InfoOverlay(
                remember(tick) { infoLines(player, stats, view.display, fileName) },
                Modifier.align(Alignment.TopStart).padding(start = screenMargin(), top = 72.dp, end = screenMargin()),
            )
        }

        if (!inPip) hint?.let {
            Text(
                it,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .background(Color(0xB3000000), RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }

        if (slowCard && error == null) {
            SlowWifiCard(
                senderName = senderName,
                needBitsPerSecond = item.bitsPerSecond,
                gettingMbps = stats.networkBitsPerSecond.takeIf { it > 0 }?.let { it / 1e6 } ?: Link.lastSpeedMbps,
                band = remember { Link.wifiBand(context) },
                isTv = isTv,
                onDismiss = { slowCard = false },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp, start = screenMargin(), end = screenMargin()),
            )
        }

        error?.let {
            Text(
                "$it\nPress Back to return.",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.background(Color(0xCC000000), RoundedCornerShape(12.dp)).padding(24.dp),
            )
        }

        if (controls && panel == null && error == null && !resumeAsk && !inPip) {
            // Title and clock.
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                    .padding(horizontal = screenMargin(), vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!isTv) {
                    Icon(
                        painterResource(R.drawable.ic_back),
                        contentDescription = "Back",
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { exit() }
                            .padding(8.dp)
                            .size(26.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = listOfNotNull(item.episodeName, current?.first?.title?.takeIf { chapters.size > 1 })
                    if (sub.isNotEmpty()) {
                        Text(sub.joinToString("  ·  "), color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                // What is playing, at a glance, like a TV's info bar: badges, then the exact resolution.
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        report?.badges?.take(if (isTv) 5 else 4)?.forEach { FormatChip(it, large = false) }
                        val ahead = bufferedMs - positionMs
                        if (playing && ahead in 0..4_000 && stalls.isNotEmpty()) {
                            Text("Slow network", color = Color(0xFFFBBF24), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    report?.resolution?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
                }
                Spacer(Modifier.width(14.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(remember(tick / 20) { LocalTime.now().format(CLOCK) })
                    if (durationMs > 0 && !audioOnly) {
                        val ends = LocalTime.now().plusSeconds(((durationMs - positionMs) / speed / 1000).toLong())
                        Text("Ends at ${ends.format(CLOCK)}", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = controls && panel == null && error == null && !resumeAsk && !inPip,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(26.dp),
            ) {
                if (screen.items.size > 1) {
                    RoundButton(R.drawable.ic_prev, "Previous", onClick = { player.seekToPreviousMediaItem() })
                }
                ChipButton("−10 s", onClick = { skip(false) })
                RoundButton(
                    if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                    if (playing) "Pause" else "Play",
                    onClick = ::togglePlay,
                    modifier = Modifier.focusRequester(playFocus),
                    large = true,
                )
                ChipButton("+30 s", onClick = { skip(true) })
                if (screen.items.size > 1) {
                    RoundButton(R.drawable.ic_next, "Next", onClick = { player.seekToNextMediaItem() })
                }
            }
        }

        val controlBar: @Composable (Modifier) -> Unit = { placed ->
            Column(
                // A floating pane of dark glass, clear of the screen edges.
                modifier = placed
                    .padding(horizontal = screenMargin(), vertical = 16.dp)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0x6615152B), Color(0xA60B0B1A)).map { it.glassy() }), RoundedCornerShape(28.dp))
                    .background(Brush.verticalGradient(listOf(Color(0x24FFFFFF), Color.Transparent)), RoundedCornerShape(28.dp))
                    .border(1.dp, Brush.linearGradient(listOf(Color(0xB3FFFFFF), Color(0x14FFFFFF), Color(0x4DFFFFFF))), RoundedCornerShape(28.dp))
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(formatClock(scrubMs ?: positionMs))
                    SeekBar(
                        positionMs = positionMs,
                        durationMs = durationMs,
                        bufferedMs = bufferedMs,
                        scrubMs = scrubMs,
                        onScrub = {
                            scrubByKeys = false
                            scrubMs = it
                            activity++
                        },
                        onScrubEnd = { commitScrub() },
                        onStep = { forward ->
                            scrubByKeys = true
                            scrubMs = ((scrubMs ?: positionMs) + if (forward) 30_000 else -10_000).coerceIn(0, durationMs.coerceAtLeast(0))
                            activity++
                        },
                        modifier = Modifier.weight(1f),
                        marks = if (durationMs > 0) chapters.map { it.startMs.toFloat() / durationMs } else emptyList(),
                        cached = cachedThumbs,
                    )
                    // Tap to switch between length, time left and end time.
                    Text(
                        when (timeMode) {
                            1 -> "−" + formatClock(durationMs - (scrubMs ?: positionMs))
                            2 -> "Ends " + LocalTime.now().plusSeconds(((durationMs - positionMs) / speed / 1000).toLong()).format(CLOCK)
                            else -> formatClock(durationMs)
                        },
                        color = Muted,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { timeMode = (timeMode + 1) % 3 }.padding(4.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                ) {
                    ChipButton(if (audioOnly) "Audio" else "Audio & Subtitles", onClick = { panel = PlayerPanel.TRACKS }, active = externalCues != null)
                    if (!audioOnly) {
                        ChipButton("Picture", onClick = { panel = PlayerPanel.PICTURE }, active = picture != PictureMode.FIT || zoom > 1f)
                    }
                    ChipButton("Sound", onClick = { panel = PlayerPanel.AUDIO }, active = sound.shapesSound || soundDelayMs != 0)
                    ChipButton(
                        if (speed == 1f) "Speed" else "Speed ${speed}×",
                        onClick = { panel = PlayerPanel.SPEED },
                        active = speed != 1f || repeatFrom != null || shuffle || repeatAll,
                    )
                    if (screen.items.size > 1) {
                        ChipButton(if (audioOnly) "Tracks" else "Episodes", onClick = { panel = PlayerPanel.EPISODES })
                    }
                    if (chapters.size > 1) ChipButton("Chapters", onClick = { panel = PlayerPanel.CHAPTERS })
                    ChipButton("More", onClick = { panel = PlayerPanel.MORE }, active = sleepMinutes != 0 || showInfo)
                }
            }
        }
        AnimatedVisibility(
            visible = controls && panel == null && error == null && !resumeAsk && !inPip,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) { controlBar(Modifier) }

        // Rows shared by the combined Audio & Subtitles sheet and the detailed panels.
        fun audioRows(): List<PanelItem> = buildList {
            val tracks = trackChoices(player, C.TRACK_TYPE_AUDIO)
            if (tracks.isEmpty()) add(NoteRow("This file has no audio track."))
            tracks.forEach { choice ->
                add(ChoiceRow(choice.label, choice.detail, choice.selected, choice.supported) {
                    selectTrack(player, C.TRACK_TYPE_AUDIO, choice)
                    rememberAudio(choice.language)
                })
            }
        }
        fun subtitleRows(): List<PanelItem> = buildList {
            val builtIn = trackChoices(player, C.TRACK_TYPE_TEXT)
            add(ChoiceRow("Off", selected = externalCues == null && builtIn.none { it.selected }) {
                externalCues = null
                externalName = null
                selectTrack(player, C.TRACK_TYPE_TEXT, null)
                rememberSubtitles(null)
            })
            builtIn.forEach { choice ->
                add(ChoiceRow(
                    choice.label,
                    "Built into the file  ·  ${choice.detail}",
                    selected = choice.selected && externalCues == null,
                    enabled = choice.supported,
                ) {
                    externalCues = null
                    externalName = null
                    selectTrack(player, C.TRACK_TYPE_TEXT, choice)
                    rememberSubtitles(choice.language)
                })
            }
            item.subtitles.filter { it.fileName.substringAfterLast('.').lowercase() in setOf("srt", "vtt") }.forEach { file ->
                add(ChoiceRow(file.fileName, "File next to the video", selected = externalName == file.fileName) {
                    scope.launch {
                        runCatching { online.fetchText(file.url) }
                            .onSuccess { useExternalSubtitles(it, file.fileName) }
                            .onFailure { hint = "Could not load that subtitle file" }
                    }
                })
            }
            item.key?.let { key ->
                online.saved(key).forEach { saved ->
                    add(ChoiceRow(saved.label, "Downloaded earlier", selected = externalName == saved.file.name) {
                        useExternalSubtitles(saved.file.readText(), saved.file.name)
                    })
                }
            }
            add(ChoiceRow(
                "Find subtitles online",
                if (builtIn.isEmpty()) "This file has none built in. Search OpenSubtitles in ${languageName(subLanguage)}."
                else "Only if the built-in ones are missing or wrong. Searches OpenSubtitles.",
            ) { panel = PlayerPanel.SUBTITLE_SEARCH })
            // Anime-style subtitles lose their fonts and positions here; the compatibility engine draws them properly.
            val styled = player.currentTracks.groups.any { g ->
                g.type == C.TRACK_TYPE_TEXT && (0 until g.length).any { g.getTrackFormat(it).realMime() == MimeTypes.TEXT_SSA }
            } || item.subtitles.any { it.fileName.endsWith(".ass", true) || it.fileName.endsWith(".ssa", true) }
            if (styled) {
                add(ChoiceRow(
                    "Show styled subtitles",
                    "This file's subtitles have their own fonts and positions. Opens it in the compatibility engine to show them as made.",
                ) { useCompat("Styled subtitles are shown by the compatibility engine.") })
            }
        }

        panel?.takeIf { it == PlayerPanel.TRACKS }?.let {
            tracksVersion
            // Audio and subtitles side by side, the way the big streaming apps do it.
            Row(Modifier.align(Alignment.CenterEnd)) {
                SidePanel(
                    "Audio",
                    // Only the choice of track: sound settings have their own button, Sound, in the controls.
                    audioRows(),
                    width = if (isTv) 380.dp else 300.dp,
                )
                if (!audioOnly) {
                    SidePanel(
                        "Subtitles",
                        subtitleRows() + ChoiceRow("Subtitle look and timing", "Size, colour, background, sync") { panel = PlayerPanel.SUBTITLES },
                        width = if (isTv) 420.dp else 340.dp,
                        autoFocus = false,
                    )
                }
            }
        }

        val soundUi = SoundUi(
            sound = sound,
            set = ::setSound,
            band = eqBand,
            selectBand = { eqBand = it },
            userPresets = userPresets,
            savePreset = {
                val name = "My preset ${userPresets.size + 1}"
                store.saveUserPreset(name, sound.snapshot())
                userPresets = store.userPresets
                setSound(sound.copy(preset = name))
                hint = "Saved as $name"
            },
            deletePreset = { name ->
                store.deleteUserPreset(name)
                userPresets = store.userPresets
                setSound(sound.copy(preset = "Custom"))
            },
            importFile = if (isTv) null else ({ runCatching { importEq.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) } }),
            exportFile = if (isTv) null else ({ runCatching { exportEq.launch("hassasin-eq.txt") } }),
            isMusic = audioOnly,
            passedThrough = stats.passthrough == true,
            isTv = isTv,
            impulses = impulses,
            importImpulse = if (isTv) null else ({ runCatching { importIr.launch(arrayOf("audio/wav", "audio/x-wav", "audio/*", "*/*")) } }),
            deleteImpulse = { name ->
                java.io.File(irDir, name).delete()
                irVersion++
            },
        )

        panel?.takeIf { it != PlayerPanel.TRACKS }?.let { open ->
            // Read here so the lists refresh when the player reports new tracks.
            tracksVersion
            // Kept as a separate function so this screen stays small enough for Android to compile fully.
            val build: (PlayerPanel) -> List<PanelItem> = { which ->
                when (which) {
                PlayerPanel.TRACKS -> emptyList()

                PlayerPanel.AUDIO -> buildList {
                    add(NoteRow("Quick sound"))
                    addAll(quickItems(soundUi))
                    add(NoteRow("More detail. Every layer adds to the ones above."))
                    val softwareDecoder = stats.audioDecoder?.contains("ffmpeg", ignoreCase = true) == true
                    fun delayTo(ms: Int) {
                        soundDelayMs = ms.coerceIn(-2000, 2000)
                        soundOffsetUs.set(soundDelayMs * 1000L)
                    }
                    add(StepperRow(
                        "Sound delay",
                        signed(soundDelayMs, "ms"),
                        fraction = (soundDelayMs + 2000) / 4000f,
                        detail = if (softwareDecoder) "Not available while the app's software decoder plays this track."
                        else "Fixes lip-sync. Plus plays the sound later, minus earlier.",
                        enabled = !softwareDecoder,
                        onSet = { f -> delayTo(((f * 4000 - 2000) / 50).roundToInt() * 50) },
                        onReset = { delayTo(0) },
                        changed = soundDelayMs != 0,
                    ) { delayTo(soundDelayMs + it * 50) })
                    add(ChoiceRow("Equalizer", if (sound.eqOn) "${sound.mode.label}  ·  ${sound.preset}" else "Off") { panel = PlayerPanel.EQUALIZER })
                    add(ChoiceRow("Tone tuning (MSEB style)", if (sound.toneOn && sound.tone != Tone()) "On" else "Temperature, bass texture, vocals, air and more") { panel = PlayerPanel.TONE })
                    add(ChoiceRow(
                        "Channels and headphones",
                        listOfNotNull(
                            "balance".takeIf { sound.balance != 0f },
                            "width ${(sound.width * 100).roundToInt()}%".takeIf { sound.width != 1f },
                            "crossfeed".takeIf { sound.crossfeed > 0 },
                            "dialogue boost".takeIf { sound.dialogue > 0 },
                            "mono".takeIf { sound.mono },
                            "swapped".takeIf { sound.swap },
                        ).joinToString(", ").ifEmpty { "Balance, width, crossfeed, dialogue boost" },
                    ) { panel = PlayerPanel.CHANNELS })
                    add(ChoiceRow(
                        "Loudness and dynamics",
                        listOfNotNull(
                            "+${sound.boostDb} dB".takeIf { sound.boostDb > 0 },
                            "night mode".takeIf { sound.night },
                            "ReplayGain".takeIf { sound.replayGain > 0 && audioOnly },
                        ).joinToString(", ").ifEmpty { "Limiter, ReplayGain" },
                    ) { panel = PlayerPanel.DYNAMICS })
                    add(ChoiceRow(
                        "Convolution",
                        if (sound.convOn && sound.convFile.isNotBlank()) "On: ${sound.convFile.substringBeforeLast('.')}" else "Impulse responses: rooms, speakers, headphone correction",
                    ) { panel = PlayerPanel.CONVOLUTION })
                    add(ToggleRow(
                        "Passthrough",
                        if (passthrough) "On: Dolby and DTS go untouched to the TV or soundbar."
                        else "Off: all sound is decoded on this device.",
                        on = passthrough,
                    ) { turnOn ->
                        store.setPassthrough(turnOn)
                        rebuild { passthrough = turnOn }
                    })
                }

                PlayerPanel.EQUALIZER -> equalizerItems(soundUi)
                PlayerPanel.TONE -> toneItems(soundUi)
                PlayerPanel.CHANNELS -> channelItems(soundUi)
                PlayerPanel.DYNAMICS -> dynamicsItems(soundUi)
                PlayerPanel.CONVOLUTION -> convolutionItems(soundUi)

                PlayerPanel.SUBTITLES -> buildList {
                    if (builtInIsPicture && externalCues == null) {
                        add(NoteRow("These subtitles are pictures (Blu-ray or DVD style), so only their size and height can change."))
                        val scales = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f)
                        add(ChipsRow("Size", scales.map { "${(it * 100).roundToInt()}%" }, scales.indexOf(picSubScale)) { picSubScale = scales[it] })
                        val lifts = listOf(0, 5, 10, 15, 20)
                        add(ChipsRow("Raise", lifts.map { if (it == 0) "Off" else "$it%" }, lifts.indexOf(picSubLift)) { picSubLift = lifts[it] })
                    }
                    fun timingTo(ms: Int) {
                        subDelayMs = ms.coerceIn(-30_000, 30_000)
                    }
                    add(StepperRow(
                        "Timing",
                        "%+.2f s".format(subDelayMs / 1000.0),
                        fraction = (subDelayMs + 10_000) / 20_000f,
                        detail = if (externalCues == null) "Plus shows subtitles later. Built-in subtitles cannot be moved earlier."
                        else "Plus shows subtitles later, minus earlier.",
                        onSet = { f -> timingTo(((f * 20_000 - 10_000) / 250).roundToInt() * 250) },
                        onReset = { timingTo(0) },
                        changed = subDelayMs != 0,
                    ) { timingTo(subDelayMs + it * 250) })
                    add(ChipsRow("Size", SUBTITLE_SIZES.map { it.first }, subSize) {
                        subSize = it
                        saveSubtitleLook()
                    })
                    add(ChipsRow("Colour", SUBTITLE_COLOURS.map { it.first }, subColour) {
                        subColour = it
                        saveSubtitleLook()
                    })
                    add(ChipsRow("Background", SUBTITLE_BACKGROUNDS, subBackground) {
                        subBackground = it
                        saveSubtitleLook()
                    })
                }

                PlayerPanel.SUBTITLE_SEARCH -> buildList {
                    add(ChipsRow("Language", SUBTITLE_LANGUAGES.map(::languageName), SUBTITLE_LANGUAGES.indexOf(subLanguage)) {
                        subLanguage = SUBTITLE_LANGUAGES[it]
                        saveSubtitleLook()
                    })
                    searchStatus?.let { add(NoteRow(it)) }
                    searchHits.forEach { hit ->
                        add(ChoiceRow(
                            hit.release.ifBlank { "Subtitle ${hit.fileId}" },
                            listOfNotNull(
                                languageName(hit.language),
                                "${hit.downloads} downloads",
                                "for the hard of hearing".takeIf { hit.hearingImpaired },
                            ).joinToString("  ·  "),
                        ) {
                            val videoKey = item.key ?: item.url
                            searchStatus = "Downloading…"
                            scope.launch {
                                runCatching { online.download(hit, videoKey) }
                                    .onSuccess {
                                        useExternalSubtitles(it, "${hit.language}_${hit.fileId}")
                                        searchStatus = null
                                        panel = null
                                    }
                                    .onFailure { searchStatus = it.message ?: "The download failed." }
                            }
                        })
                    }
                }

                PlayerPanel.PICTURE -> buildList {
                    val modes = PictureMode.entries
                    add(ChipsRow("Shape", modes.map { it.label }, modes.indexOf(picture)) { picture = modes[it] })
                    add(NoteRow(picture.detail))
                    fun zoomTo(value: Float) {
                        zoom = value.coerceIn(1f, 4f)
                        val maxX = view.width * (zoom - 1f) / 2f
                        val maxY = view.height * (zoom - 1f) / 2f
                        panX = panX.coerceIn(-maxX, maxX)
                        panY = panY.coerceIn(-maxY, maxY)
                    }
                    add(StepperRow(
                        "Zoom",
                        "${(zoom * 100).roundToInt()}%",
                        fraction = (zoom - 1f) / 3f,
                        detail = "Or pinch the picture with two fingers, and drag with two fingers to move it.",
                        onSet = { f -> zoomTo(1f + (f * 30).roundToInt() / 10f) },
                        onReset = {
                            zoomTo(1f)
                            panX = 0f
                            panY = 0f
                        },
                        changed = zoom != 1f || panX != 0f || panY != 0f,
                    ) { zoomTo(((zoom + it * 0.1f) * 10).roundToInt() / 10f) })
                    add(ToggleRow(
                        "Match the TV's refresh rate",
                        "Smoother film motion. The screen goes black for a moment while the TV switches.",
                        on = matchRate,
                    ) {
                        matchRate = it
                        store.setMatchFrameRate(it)
                    })
                }

                PlayerPanel.SPEED -> buildList {
                    add(ChipsRow("Speed", SPEEDS.map { if (it == 1f) "Normal" else "${it}×" }, SPEEDS.indexOf(speed)) {
                        speed = SPEEDS[it]
                        player.setPlaybackSpeed(speed)
                    })
                    if (audioOnly) {
                        add(ToggleRow("Shuffle", on = shuffle) {
                            shuffle = it
                            player.shuffleModeEnabled = it
                        })
                        add(ToggleRow("Repeat the list", on = repeatAll) {
                            repeatAll = it
                            player.repeatMode = if (it) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
                        })
                    } else {
                        add(NoteRow("Hold a finger on the picture for 2× speed while held."))
                        val from = repeatFrom
                        val to = repeatTo
                        add(ChoiceRow(
                            when {
                                from == null -> "Repeat a section"
                                to == null -> "Repeat a section: starts at ${formatClock(from)}"
                                else -> "Repeating ${formatClock(from)} to ${formatClock(to)}"
                            },
                            when {
                                from == null -> "Tap at the start of the part to repeat."
                                to == null -> "Tap again at the end of the part."
                                else -> "Tap to stop repeating."
                            },
                            selected = from != null && to != null,
                        ) {
                            when {
                                from == null -> repeatFrom = player.currentPosition
                                to == null -> if (player.currentPosition > from + 1_000) repeatTo = player.currentPosition
                                else -> {
                                    repeatFrom = null
                                    repeatTo = null
                                }
                            }
                        })
                    }
                }

                PlayerPanel.EPISODES -> screen.items.mapIndexed { i, entry ->
                    val p = entry.key?.let(store::progress)
                    ChoiceRow(
                        listOfNotNull(entry.title, entry.episodeName).joinToString("  ·  "),
                        when {
                            i == itemIndex -> "Playing now"
                            p?.finished == true -> "Watched"
                            p != null && p.durationMs > 0 -> "${formatClock(p.durationMs - p.positionMs)} left"
                            else -> null
                        },
                        selected = i == itemIndex,
                    ) {
                        if (i != itemIndex) player.seekTo(i, entry.key?.let(store::resumePosition) ?: 0)
                        panel = null
                    }
                }

                PlayerPanel.CHAPTERS -> buildList {
                    add(ChoiceRow("Next chapter") { chapterStep(true) })
                    add(ChoiceRow("Previous chapter") { chapterStep(false) })
                    chapters.forEach { chapter ->
                        add(ChoiceRow(chapter.title, formatClock(chapter.startMs), selected = current?.first == chapter) {
                            player.seekTo(chapter.startMs)
                            panel = null
                        })
                    }
                }

                PlayerPanel.MORE -> buildList {
                    add(ChoiceRow(
                        "Sleep timer",
                        if (sleepMinutes == 0) "Off" else "Stops in $sleepMinutes min",
                    ) { panel = PlayerPanel.SLEEP })
                    add(ToggleRow("Stats for nerds", "Codec, bit rate, buffer, network, dropped frames.", on = showInfo) { showInfo = it })
                    add(ToggleRow("Format badge", "Announce Dolby Vision, HDR and Atmos when a video starts.", on = formatBadge) {
                        formatBadge = it
                        store.setFormatBadge(it)
                    })
                    if (!isTv && !audioOnly) {
                        add(ChoiceRow("Lock the screen", "Ignores touches, so a pocket or a child changes nothing.") {
                            locked = true
                            panel = null
                            controls = false
                        })
                        add(ChoiceRow("Picture-in-picture", "Keep watching in a small window over other apps.") {
                            panel = null
                            (context as? MainActivity)?.enterPip()
                        })
                    }
                    if (!audioOnly) {
                        add(ChoiceRow(
                            "Play with the compatibility engine",
                            "For files that stutter, show wrong colours or will not play. Fewer options, plays nearly anything.",
                        ) { useCompat("Switched to the compatibility engine.") })
                    }
                }

                PlayerPanel.SLEEP -> listOf(
                    ChipsRow("Stop playing after", SLEEP_MINUTES.map { if (it == 0) "Off" else "$it min" }, SLEEP_MINUTES.indexOf(sleepMinutes)) {
                        sleepMinutes = SLEEP_MINUTES[it]
                    },
                )
                }
            }
            val items = build(open)
            fun resetSubtitles() {
                subDelayMs = 0
                subSize = 1
                subColour = 0
                subBackground = 0
                picSubScale = 1f
                picSubLift = 0
                saveSubtitleLook()
            }
            // Each panel's "Reset", and whether there is anything to reset. The Sound panel's covers all sound.
            val reset: Pair<() -> Unit, Boolean>? = when (open) {
                PlayerPanel.AUDIO -> Pair({
                    setSound(SoundSettings())
                    soundDelayMs = 0
                    soundOffsetUs.set(0)
                    hint = "All sound reset. Saved presets are kept."
                }, sound != SoundSettings() || soundDelayMs != 0)
                PlayerPanel.EQUALIZER -> Pair({ setSound(sound.resetEq()) }, sound.eqChanged())
                PlayerPanel.TONE -> Pair({ setSound(sound.resetTone()) }, sound.toneChanged())
                PlayerPanel.CHANNELS -> Pair({ setSound(sound.resetChannels()) }, sound.channelsChanged())
                PlayerPanel.DYNAMICS -> Pair({ setSound(sound.resetDynamics()) }, sound.dynamicsChanged())
                PlayerPanel.CONVOLUTION -> Pair({ setSound(sound.resetConvolution()) }, sound.convolutionChanged())
                PlayerPanel.SUBTITLES -> Pair(::resetSubtitles, subDelayMs != 0 || subSize != 1 || subColour != 0 || subBackground != 0 || picSubScale != 1f || picSubLift != 0)
                PlayerPanel.PICTURE -> Pair({
                    picture = PictureMode.FIT
                    zoom = 1f
                    panX = 0f
                    panY = 0f
                }, picture != PictureMode.FIT || zoom != 1f || panX != 0f || panY != 0f)
                PlayerPanel.SPEED -> Pair({
                    speed = 1f
                    player.setPlaybackSpeed(1f)
                }, speed != 1f)
                else -> null
            }
            SidePanel(
                title = when (open) {
                    PlayerPanel.TRACKS -> "Audio & Subtitles"
                    PlayerPanel.AUDIO -> "Sound"
                    PlayerPanel.EQUALIZER -> "Equalizer"
                    PlayerPanel.TONE -> "Tone tuning"
                    PlayerPanel.CHANNELS -> "Channels and headphones"
                    PlayerPanel.DYNAMICS -> "Loudness and dynamics"
                    PlayerPanel.CONVOLUTION -> "Convolution"
                    PlayerPanel.SUBTITLES -> "Subtitle look and timing"
                    PlayerPanel.EPISODES -> if (audioOnly) "Tracks" else "Episodes"
                    PlayerPanel.CHAPTERS -> "Chapters"
                    PlayerPanel.MORE -> "More"
                    PlayerPanel.SUBTITLE_SEARCH -> "Find subtitles"
                    PlayerPanel.PICTURE -> "Picture"
                    PlayerPanel.SPEED -> "Playback"
                    PlayerPanel.SLEEP -> "Sleep timer"
                },
                items = items,
                modifier = Modifier.align(Alignment.CenterEnd),
                width = if (open == PlayerPanel.EQUALIZER) (if (isTv) 620.dp else 520.dp) else 400.dp,
                onReset = reset?.first,
                canReset = reset?.second ?: false,
            )
        }

        if (resumeAsk && error == null && !inPip) {
            ResumePrompt(
                title = listOfNotNull(firstItem.title, firstItem.episodeName).joinToString("  ·  "),
                positionMs = firstItem.startMs,
                onResume = {
                    resumeAsk = false
                    player.play()
                    controls = false
                },
                onStartOver = {
                    resumeAsk = false
                    player.seekTo(0)
                    player.play()
                    controls = false
                },
                first = resumeFocus,
                onFocus = { resumeFocused = it },
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (locked) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false).consume()
                            lockNotice = true
                            do {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                        }
                    },
            ) {
                if (lockNotice) {
                    LockedNotice(
                        onUnlock = {
                            locked = false
                            lockNotice = false
                            controls = true
                        },
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
    }
}

// Subtitle files next to the video are loaded by this screen (so their timing can be shifted),
// which is why they are not handed to the player here.
private fun PlayItem.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setUri(url)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
        .build()
