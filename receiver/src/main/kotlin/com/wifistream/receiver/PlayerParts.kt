package com.wifistream.receiver

import android.view.Display
import android.view.Window
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import com.wifistream.protocol.WifiStream
import kotlin.math.log10
import kotlin.math.pow
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// ---------- picture modes ----------

enum class PictureMode(val label: String, val detail: String) {
    FIT("Fit", "Whole picture, black bars where needed"),
    CROP("Crop to fill", "Fills the screen, trims the edges"),
    STRETCH("Stretch", "Fills the screen, changes the shape"),
    WIDE("Force 16:9", "For files flagged with the wrong shape"),
    CLASSIC("Force 4:3", "For old TV recordings"),
    BARS("Crop black bars", "Zooms past black bars baked into the picture, keeping the whole width"),
}

// ---------- live technical details ----------

/** Facts the player reports while it runs, shown in the Info overlay. */
class PlaybackStats {
    var videoDecoder by mutableStateOf<String?>(null)
    var audioDecoder by mutableStateOf<String?>(null)

    /** True when the untouched Dolby/DTS stream goes to the TV or soundbar; null until audio starts. */
    var passthrough by mutableStateOf<Boolean?>(null)
    var droppedFrames by mutableIntStateOf(0)
    var networkBitsPerSecond by mutableLongStateOf(0)

    /** Average bit rate of the file; 0 when unknown. */
    var needBitsPerSecond by mutableLongStateOf(0)

    /** Times playback stopped to wait for data (not counting the wait after a jump). */
    var stalls by mutableIntStateOf(0)
}

/**
 * How much video to hold in memory ahead of the picture. The default holds about 13 seconds of a
 * big 4K file, so any Wi-Fi hiccup longer than that stops playback. This holds up to two minutes,
 * capped by the memory the app may use.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun roomyLoadControl(): androidx.media3.exoplayer.DefaultLoadControl {
    val bytes = (Runtime.getRuntime().maxMemory() * 0.4).toLong().coerceIn(64L shl 20, 320L shl 20).toInt()
    return androidx.media3.exoplayer.DefaultLoadControl.Builder()
        .setBufferDurationsMs(30_000, 120_000, 2_000, 5_000)
        .setTargetBufferBytes(bytes)
        .setPrioritizeTimeOverSizeThresholds(false)
        .build()
}

fun codecLabel(mime: String?): String = when (mime) {
    null -> "Unknown"
    MimeTypes.VIDEO_H265 -> "HEVC (H.265)"
    MimeTypes.VIDEO_H264 -> "H.264"
    MimeTypes.VIDEO_AV1 -> "AV1"
    MimeTypes.VIDEO_VP9 -> "VP9"
    MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
    MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
    MimeTypes.AUDIO_E_AC3 -> "Dolby Digital+ (E-AC3)"
    MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos (E-AC3)"
    MimeTypes.AUDIO_AC3 -> "Dolby Digital (AC3)"
    MimeTypes.AUDIO_AC4 -> "Dolby AC-4"
    MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
    MimeTypes.AUDIO_DTS -> "DTS"
    MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_X -> "DTS-HD"
    MimeTypes.AUDIO_AAC -> "AAC"
    MimeTypes.AUDIO_FLAC -> "FLAC"
    MimeTypes.AUDIO_OPUS -> "Opus"
    MimeTypes.AUDIO_VORBIS -> "Vorbis"
    MimeTypes.AUDIO_MPEG -> "MP3"
    MimeTypes.AUDIO_RAW -> "PCM"
    MimeTypes.APPLICATION_SUBRIP -> "SRT"
    MimeTypes.TEXT_SSA -> "ASS"
    MimeTypes.TEXT_VTT -> "WebVTT"
    MimeTypes.APPLICATION_PGS -> "PGS (picture)"
    MimeTypes.APPLICATION_VOBSUB -> "VobSub (picture)"
    else -> mime.substringAfter('/').uppercase()
}

fun channelLabel(channels: Int): String? = when (channels) {
    Format.NO_VALUE, 0 -> null
    1 -> "Mono"
    2 -> "Stereo"
    6 -> "5.1"
    8 -> "7.1"
    else -> "$channels ch"
}

private fun languageLabel(code: String?): String? =
    code?.takeIf { it.isNotBlank() && it != "und" }?.let { Locale.forLanguageTag(it).displayLanguage.ifBlank { it } }

private fun mbps(bitsPerSecond: Long): String = "%.1f Mbps".format(bitsPerSecond / 1_000_000.0)

/** The Info overlay's lines: label to value. */
@OptIn(UnstableApi::class)
fun infoLines(player: ExoPlayer, stats: PlaybackStats, display: Display?, fileName: String): List<Pair<String, String>> {
    val lines = mutableListOf<Pair<String, String>>()
    lines += "File" to fileName
    player.videoFormat?.let { v ->
        val transfer = v.colorInfo?.colorTransfer
        lines += "Video" to listOfNotNull(
            codecLabel(v.sampleMimeType),
            "${v.width}×${v.height}",
            v.frameRate.takeIf { it > 0 }?.let { "%.3f fps".format(it).replace(".000", "") },
            v.bitrate.takeIf { it > 0 }?.let { mbps(it.toLong()) },
        ).joinToString("  ·  ")
        lines += "Colour" to listOfNotNull(
            when {
                v.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision" + (v.codecs?.let { " ($it)" } ?: "")
                transfer == C.COLOR_TRANSFER_ST2084 -> "HDR10 (PQ)"
                transfer == C.COLOR_TRANSFER_HLG -> "HLG"
                else -> "SDR"
            },
            when (v.colorInfo?.colorSpace) {
                C.COLOR_SPACE_BT2020 -> "BT.2020"
                C.COLOR_SPACE_BT709 -> "BT.709"
                C.COLOR_SPACE_BT601 -> "BT.601"
                else -> null
            },
            v.codecs?.takeIf { v.sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION },
        ).joinToString("  ·  ")
        lines += "Video decoder" to (stats.videoDecoder?.let { name ->
            val software = name.startsWith("c2.android") || name.startsWith("OMX.google") || name.contains("ffmpeg", true)
            "$name  ·  ${if (software) "software" else "hardware"}"
        } ?: "Starting…")
        lines += "Dropped frames" to stats.droppedFrames.toString()
    }
    player.audioFormat?.let { a ->
        lines += "Audio" to listOfNotNull(
            codecLabel(a.sampleMimeType),
            channelLabel(a.channelCount),
            a.sampleRate.takeIf { it > 0 }?.let { "${it / 1000.0} kHz".replace(".0 ", " ") },
            a.bitrate.takeIf { it > 0 }?.let { "${it / 1000} kbps" },
            languageLabel(a.language),
        ).joinToString("  ·  ")
        lines += "Audio output" to when (stats.passthrough) {
            true -> "Passthrough: sent untouched to the TV or soundbar"
            false -> "Decoded here" + (stats.audioDecoder?.let { " by $it" } ?: "")
            null -> "Starting…"
        }
    }
    lines += "Buffer" to "${player.totalBufferedDuration / 1000} s ahead"
    if (stats.networkBitsPerSecond > 0) lines += "Network" to mbps(stats.networkBitsPerSecond)
    if (stats.needBitsPerSecond > 0) lines += "File needs" to mbps(stats.needBitsPerSecond) + " on average"
    lines += "Stalls" to stats.stalls.toString()
    display?.mode?.let { mode ->
        lines += "Screen" to "${mode.physicalWidth}×${mode.physicalHeight} at ${"%.2f".format(mode.refreshRate)} Hz"
    }
    return lines
}

@Composable
fun InfoOverlay(lines: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color(0xD90B1220), RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        lines.forEach { (label, value) ->
            Row {
                Text(label, color = Muted, fontSize = 12.sp, modifier = Modifier.width(110.dp))
                Text(value, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

// ---------- tracks ----------

class TrackChoice(
    val group: TrackGroup,
    val index: Int,
    val language: String?,
    val label: String,
    val detail: String?,
    val selected: Boolean,
    val supported: Boolean,
)

/** The audio or subtitle tracks inside the file, as rows for a panel. */
@OptIn(UnstableApi::class)
fun trackChoices(player: Player, type: Int): List<TrackChoice> =
    player.currentTracks.groups.filter { it.type == type }.flatMap { group ->
        (0 until group.length).map { i ->
            val format = group.getTrackFormat(i)
            val language = languageLabel(format.language)
            // A subtitle's own name ("Forced", "SDH", "Commentary") matters more than its format.
            val name = format.label?.takeIf { it.isNotBlank() && !it.equals(language, ignoreCase = true) }
            TrackChoice(
                group = group.mediaTrackGroup,
                index = i,
                language = format.language,
                label = listOfNotNull(language ?: "Unknown language", name).joinToString("  ·  "),
                detail = listOfNotNull(
                    codecLabel(format.realMime()),
                    channelLabel(format.channelCount).takeIf { type == C.TRACK_TYPE_AUDIO },
                    "not playable on this device".takeIf { !group.isTrackSupported(i) },
                ).joinToString("  ·  "),
                selected = group.isTrackSelected(i),
                supported = group.isTrackSupported(i),
            )
        }
    }

/**
 * The track's real format. Subtitles are converted while the file is read, which renames them
 * to an internal type; the original type is kept in [Format.codecs].
 */
fun androidx.media3.common.Format.realMime(): String? =
    if (sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) codecs ?: sampleMimeType else sampleMimeType ?: codecs

fun selectTrack(player: Player, type: Int, choice: TrackChoice?) {
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(type, choice == null)
        .apply {
            clearOverridesOfType(type)
            if (choice != null) setOverrideForType(TrackSelectionOverride(choice.group, choice.index))
        }
        .build()
}

// ---------- refresh rate ----------

/**
 * Asks the TV to switch to a refresh rate that is a clean multiple of the video's frame rate
 * (24 fps film on a 24 or 48 Hz mode), which removes the judder of 24 fps on 60 Hz.
 * Returns the chosen rate, or null when the screen has no better mode.
 */
fun matchRefreshRate(window: Window, display: Display?, videoFps: Float): Float? {
    if (display == null || videoFps <= 0) return null
    val current = display.mode
    val sameSize = display.supportedModes.filter {
        it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
    }
    val best = sameSize.minByOrNull { mode ->
        // Distance from the nearest whole multiple of the frame rate; prefer the lower multiple on a tie.
        val multiple = (mode.refreshRate / videoFps).roundToInt().coerceAtLeast(1)
        abs(mode.refreshRate - videoFps * multiple) + multiple * 0.001f
    } ?: return null
    val multiple = (best.refreshRate / videoFps).roundToInt().coerceAtLeast(1)
    if (abs(best.refreshRate - videoFps * multiple) > 0.5f || best.modeId == current.modeId) return null
    // Remember the TV's mode before the app first changed it, so leaving restores exactly that.
    if (DisplayModeMemory.originalModeId == 0) DisplayModeMemory.originalModeId = current.modeId
    window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
    return best.refreshRate
}

/** The TV's own mode before the player switched it; 0 when the app has not changed anything. */
object DisplayModeMemory {
    @Volatile var originalModeId = 0
}

/**
 * Gives the screen back to the TV, and only if the app changed it. With [afterMs] the change waits
 * until the player has let go of the picture: switching the HDMI mode while an HDR stream is still
 * closing can leave the TV in the wrong colour format and a lower resolution until it is replugged.
 */
fun resetRefreshRate(window: Window, afterMs: Long = 0) {
    if (DisplayModeMemory.originalModeId == 0) return
    val apply = Runnable {
        val original = DisplayModeMemory.originalModeId
        if (original == 0) return@Runnable
        DisplayModeMemory.originalModeId = 0
        // Ask for the original mode by id first, then clear the request so the TV's own setting rules again.
        window.attributes = window.attributes.apply { preferredDisplayModeId = original }
        window.decorView.postDelayed({ window.attributes = window.attributes.apply { preferredDisplayModeId = 0 } }, 1_500)
    }
    if (afterMs > 0) window.decorView.postDelayed(apply, afterMs) else apply.run()
}

// ---------- controls ----------

fun formatClock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Round transport button (play, next…) that turns amber under the remote. */
@Composable
fun RoundButton(icon: Int, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, large: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier = modifier
            .size(if (large) 78.dp else 52.dp)
            .clip(CircleShape)
            .background(if (focused) Amber else if (large) Color.White else Color(0x33FFFFFF))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = description,
            tint = if (focused) OnAccent else if (large) Night else Color.White,
            modifier = Modifier.size(if (large) 42.dp else 26.dp),
        )
    }
}

/** Small text button for the options row and skip steps. */
@Composable
fun ChipButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(50)
    Text(
        text,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        color = if (focused) OnAccent else Color.White,
        maxLines = 1,
        modifier = modifier
            .clip(shape)
            .background(if (focused) Amber else Color(0x33FFFFFF))
            .border(1.dp, if (active && !focused) Amber else Color.Transparent, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** Address of a small picture of the video near [ms], served by the sharing device. */
fun frameUrl(fileUrl: String, ms: Long): String =
    Link.map(fileUrl).replace(WifiStream.Path.FILE + "?", WifiStream.Path.FRAME + "?") + "&t=" + (ms.coerceAtLeast(0) / 10_000 * 10_000)

/** Every picture the sharing phone already has for a video, in one download. */
fun previewsUrl(fileUrl: String): String =
    Link.map(fileUrl).replace(WifiStream.Path.FILE + "?", WifiStream.Path.PREVIEWS + "?")

fun hzLabel(hz: Float): String = if (hz >= 1000) "%.1f kHz".format(hz / 1000).replace(".0 ", " ") else "${hz.toInt()} Hz"

/**
 * Progress bar. Touch: tap or drag, and playback jumps when the finger lifts. Remote: focus it and
 * press left or right. While a position is being chosen, [scrubMs] is where the bar points.
 */
@Composable
fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    scrubMs: Long?,
    onScrub: (Long) -> Unit,
    onScrubEnd: () -> Unit,
    onStep: (forward: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** Chapter starts, as fractions of the length, drawn as small gaps in the bar. */
    marks: List<Float> = emptyList(),
    /** Which equal slices of the length have a saved seek-bar picture: drawn as a lighter band. */
    cached: BooleanArray = BooleanArray(0),
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scrub by rememberUpdatedState(onScrub)
    val scrubEnd by rememberUpdatedState(onScrubEnd)
    val shown = scrubMs ?: positionMs
    val played = if (durationMs > 0) (shown.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val buffered = if (durationMs > 0) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val active = focused || scrubMs != null
    Box(
        modifier = modifier
            .fillMaxWidth()
            // A tall touch area around the thin bar, so a finger lands on it easily.
            .height(48.dp)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> onStep(false).let { true }
                    Key.DirectionRight -> onStep(true).let { true }
                    else -> false
                }
            }
            .focusable(interactionSource = interaction)
            .pointerInput(durationMs) {
                detectTapGestures { offset ->
                    if (durationMs > 0) {
                        scrub((offset.x / size.width * durationMs).toLong().coerceIn(0, durationMs))
                        scrubEnd()
                    }
                }
            }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures(onDragEnd = { scrubEnd() }, onDragCancel = { scrubEnd() }) { change, _ ->
                    if (durationMs > 0) scrub((change.position.x / size.width * durationMs).toLong().coerceIn(0, durationMs))
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val thickness = if (active) 8.dp else 4.dp
        Box(Modifier.fillMaxWidth().height(thickness).clip(RoundedCornerShape(50)).background(Color(0x40FFFFFF))) {
            if (cached.isNotEmpty()) {
                val tint = Cyan.copy(alpha = 0.30f)
                androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                    val slice = size.width / cached.size
                    var start = -1
                    for (i in 0..cached.size) {
                        val on = i < cached.size && cached[i]
                        if (on && start < 0) start = i
                        if (!on && start >= 0) {
                            drawRect(tint, androidx.compose.ui.geometry.Offset(start * slice, 0f), androidx.compose.ui.geometry.Size((i - start) * slice, size.height))
                            start = -1
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth(buffered).fillMaxHeight().background(Color(0x55FFFFFF)))
            Box(Modifier.fillMaxWidth(played).fillMaxHeight().background(Brush.horizontalGradient(listOf(Violet, Accent))))
            marks.filter { it in 0.005f..0.995f }.forEach { mark ->
                Box(Modifier.fillMaxWidth(mark).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(Color(0xCC05060F)))
                }
            }
        }
        // The handle rides the end of the played part.
        Box(Modifier.fillMaxWidth(played.coerceAtLeast(0.004f)).height(18.dp), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.size(if (active) 18.dp else 12.dp).clip(CircleShape).background(Color.White))
        }
    }
}

// ---------- side panel ----------

sealed interface PanelItem

/** One of several options, or an action. */
class ChoiceRow(
    val label: String,
    val detail: String? = null,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
) : PanelItem

/** On or off, shown as a switch. */
class ToggleRow(
    val label: String,
    val detail: String? = null,
    val on: Boolean,
    val enabled: Boolean = true,
    val onToggle: (Boolean) -> Unit,
) : PanelItem

/** A value on a scale: a slider to drag or tap, with minus and plus buttons. The remote's left and right step it. */
class StepperRow(
    val label: String,
    val value: String,
    val fraction: Float,
    val detail: String? = null,
    val enabled: Boolean = true,
    val onSet: ((Float) -> Unit)? = null,
    /** Puts this one slider back to normal; the reset button shows only while [changed]. */
    val onReset: (() -> Unit)? = null,
    val changed: Boolean = false,
    val onStep: (Int) -> Unit,
) : PanelItem

/** A handful of options side by side; tap one. */
class ChipsRow(val label: String?, val options: List<String>, val selected: Int, val onSelect: (Int) -> Unit) : PanelItem

class NoteRow(val text: String) : PanelItem

class CustomRow(val content: @Composable () -> Unit) : PanelItem

@Composable
fun GlassSwitch(on: Boolean) {
    val track = if (on) listOf(Violet, Cyan) else listOf(SwitchOff, SwitchOff)
    Box(
        modifier = Modifier
            .size(48.dp, 28.dp)
            .clip(RoundedCornerShape(50))
            .background(Brush.horizontalGradient(track))
            .border(1.dp, Color(0x59FFFFFF), RoundedCornerShape(50))
            .padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(Color.White))
    }
}

@Composable
private fun PanelSurface(
    modifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) PanelFocused else Color.Transparent)
            .border(2.dp, if (focused) Accent else Color.Transparent, shape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun RowText(label: String, detail: String?, enabled: Boolean, bold: Boolean, modifier: Modifier) {
    Column(modifier) {
        Text(
            label,
            color = if (enabled) Color.White else Muted,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!detail.isNullOrEmpty()) Text(detail, color = Muted, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/** Round minus / plus button. Touch only: the remote steps the whole row with left and right. */
@Composable
private fun StepButton(symbol: String, onClick: () -> Unit) {
    val click by rememberUpdatedState(onClick)
    Box(
        modifier = Modifier
            .size(36.dp)
            .glass(CircleShape)
            .pointerInput(Unit) { detectTapGestures { click() } },
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StepperView(item: StepperRow, modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val step by rememberUpdatedState(item.onStep)
    val set by rememberUpdatedState(item.onSet)
    val reset by rememberUpdatedState(item.onReset)
    val canReset = item.enabled && item.onReset != null && item.changed
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) PanelFocused else Color.Transparent)
            .border(2.dp, if (focused) Accent else Color.Transparent, shape)
            .onKeyEvent { event ->
                if (!item.enabled || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> step(-1).let { true }
                    Key.DirectionRight -> step(1).let { true }
                    // On a remote, OK puts the slider back to normal.
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> if (canReset) reset?.invoke().let { true } else false
                    else -> false
                }
            }
            .focusable(enabled = item.enabled, interactionSource = interaction)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.label, color = if (item.enabled) Color.White else Muted, modifier = Modifier.weight(1f))
            Text(item.value, color = if (item.enabled) Accent else Muted, fontWeight = FontWeight.SemiBold)
            if (canReset) {
                Box(
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(0x26FFFFFF))
                        .clickable { reset?.invoke() },
                    contentAlignment = Alignment.Center,
                ) { Text("↺", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            }
        }
        if (item.enabled) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StepButton("−") { step(-1) }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(30.dp)
                        .pointerInput(Unit) { detectTapGestures { offset -> set?.invoke((offset.x / size.width).coerceIn(0f, 1f)) } }
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures { change, _ -> set?.invoke((change.position.x / size.width).coerceIn(0f, 1f)) }
                        },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    val fraction = item.fraction.coerceIn(0f, 1f)
                    Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50)).background(Color(0x40FFFFFF))) {
                        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Brush.horizontalGradient(listOf(Violet, Accent))))
                    }
                    Box(Modifier.fillMaxWidth(fraction.coerceAtLeast(0.03f)).height(18.dp), contentAlignment = Alignment.CenterEnd) {
                        Box(Modifier.size(16.dp).clip(CircleShape).background(Color.White))
                    }
                }
                StepButton("+") { step(1) }
            }
        }
        if (!item.detail.isNullOrEmpty()) Text(item.detail, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun ChipsView(item: ChipsRow, focusModifier: Modifier) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (item.label != null) Text(item.label, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item.options.forEachIndexed { index, option ->
                val interaction = remember { MutableInteractionSource() }
                val focused by interaction.collectIsFocusedAsState()
                val chosen = index == item.selected
                val shape = RoundedCornerShape(50)
                Text(
                    option,
                    fontSize = 14.sp,
                    fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
                    color = if (focused) OnAccent else if (chosen) Night else Color.White,
                    maxLines = 1,
                    modifier = (if (index == item.selected.coerceAtLeast(0)) focusModifier else Modifier)
                        .then(
                            when {
                                focused -> Modifier.clip(shape).background(Accent)
                                chosen -> Modifier.clip(shape).background(Color.White)
                                else -> Modifier.glass(shape)
                            },
                        )
                        .clickable(interactionSource = interaction, indication = null) { item.onSelect(index) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** Options that slide over the right side of the video. Everything in it works by tap, and by remote. */
@Composable
fun SidePanel(
    title: String,
    items: List<PanelItem>,
    modifier: Modifier = Modifier,
    width: Dp = 400.dp,
    autoFocus: Boolean = true,
    /** Resets everything in this panel; the button in the heading is dimmed while [canReset] is false. */
    onReset: (() -> Unit)? = null,
    canReset: Boolean = true,
) {
    val first = remember { FocusRequester() }
    // Focus starts on the current choice, or else on the first thing that can be operated.
    val start = remember(title) {
        items.indexOfFirst { it is ChoiceRow && it.selected }.takeIf { it >= 0 }
            ?: items.indexOfFirst { it is ChoiceRow || it is ToggleRow || it is StepperRow || it is ChipsRow }
    }
    LaunchedEffect(title) {
        if (!autoFocus) return@LaunchedEffect
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(width)
            // Liquid glass over the video: clearer towards the picture, denser where the text sits, with a bright edge.
            // The picture side follows the transparency setting; under the text it never gets too clear to read.
            .background(
                Brush.horizontalGradient(
                    listOf(
                        Color(0x660B0B1A).glassy(),
                        Color(0xB30B0B1A).glassy().let { it.copy(alpha = it.alpha.coerceAtLeast(0.6f)) },
                        Color(0xC70B0B1A).glassy().let { it.copy(alpha = it.alpha.coerceAtLeast(0.68f)) },
                    ),
                ),
            )
            .background(Brush.verticalGradient(listOf(Color(0x1FFFFFFF), Color.Transparent, Color(0x0DFFFFFF))))
            .border(1.dp, Brush.verticalGradient(listOf(Color(0x99FFFFFF), Color(0x1AFFFFFF), Color(0x40FFFFFF))), androidx.compose.ui.graphics.RectangleShape)
            .padding(top = 20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (onReset != null) ResetPill(canReset, onReset)
        }
        // A fresh scroll position for every panel, so each one opens at its top.
        val listState = remember(title) { androidx.compose.foundation.lazy.LazyListState() }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            // Rows fade out as they scroll up under the heading instead of being cut off beneath it.
            modifier = Modifier
                .graphicsLayer(compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = 0f, endY = 18.dp.toPx()),
                        blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                    )
                },
        ) {
            itemsIndexed(items) { index, item ->
                val focusModifier = if (index == start) Modifier.focusRequester(first) else Modifier
                when (item) {
                    is ChoiceRow -> PanelSurface(focusModifier, item.enabled, item.onClick) {
                        RowText(item.label, item.detail, item.enabled, item.selected, Modifier.weight(1f))
                        if (item.selected) Text("✓", color = Accent, fontWeight = FontWeight.Bold)
                    }
                    is ToggleRow -> PanelSurface(focusModifier, item.enabled, { item.onToggle(!item.on) }) {
                        RowText(item.label, item.detail, item.enabled, false, Modifier.weight(1f))
                        GlassSwitch(item.on)
                    }
                    is StepperRow -> StepperView(item, focusModifier)
                    is ChipsRow -> ChipsView(item, focusModifier)
                    is NoteRow -> Text(item.text, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
                    is CustomRow -> item.content()
                }
            }
        }
    }
}

/** "Reset" in a panel's heading: puts everything in that panel back to normal. */
@Composable
private fun ResetPill(enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(if (focused) Color.White else Color(0x26FFFFFF))
            .border(1.dp, if (focused) Accent else Color(0x33FFFFFF), shape)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val colour = when {
            focused -> Color.Black
            enabled -> Color.White
            else -> Color(0x66FFFFFF)
        }
        Text("↺", color = colour, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text("Reset", color = colour, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Says the Wi-Fi cannot keep up with this video and how to switch to the sharing phone's hotspot. */
@Composable
fun SlowWifiCard(
    senderName: String,
    needBitsPerSecond: Long,
    gettingMbps: Double?,
    band: String?,
    isTv: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val need = needBitsPerSecond / 1e6
    val detail = when {
        need > 0 && gettingMbps != null && gettingMbps < need -> "It needs about ${mbpsLabel(need)}; the connection gives about ${mbpsLabel(gettingMbps)}."
        need > 0 -> "It needs about ${mbpsLabel(need)}, and the connection keeps dropping out."
        else -> "The connection keeps dropping out."
    }
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .widthIn(max = 600.dp)
            .background(GlassDark, shape)
            .border(1.dp, Color(0x33FFFFFF), shape)
            .clickable(onClick = onDismiss)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("The Wi-Fi is too slow for this video", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(detail, color = Muted)
        Text(
            "For a direct link, switch to $senderName's hotspot:\n" +
                "1. On $senderName, open Hassasin's Stream, Share this device, and turn on the hotspot.\n" +
                "2. On this device, join that hotspot in the Wi-Fi settings.\n" +
                "3. Come back here. The app finds $senderName again and carries on where you were.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (band == "2.4 GHz") {
            Text("This device is on 2.4 GHz Wi-Fi. A 5 GHz network is usually several times faster.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Text(if (isTv) "This note hides by itself." else "Tap to hide.", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}
