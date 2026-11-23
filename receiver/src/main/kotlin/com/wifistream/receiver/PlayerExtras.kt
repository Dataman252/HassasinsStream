package com.wifistream.receiver

import android.graphics.BitmapFactory
import android.view.Display
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.wifistream.protocol.HdrType
import com.wifistream.protocol.MediaInfo
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

// ---------- format badge ----------

private const val Format_NO_VALUE = androidx.media3.common.Format.NO_VALUE

/** What is really playing, the way a TV announces it: badges, and a note when something was converted. */
class FormatReport(val badges: List<String>, val note: String?, val resolution: String? = null) {
    val key: String get() = badges.joinToString("|") + note
}

/**
 * Works out the badges from what the decoders actually received and what this screen can show,
 * not just from the file's label.
 */
@OptIn(UnstableApi::class)
fun formatReport(player: ExoPlayer, stats: PlaybackStats, media: MediaInfo?, display: Display?, fileName: String = ""): FormatReport? {
    val video = player.videoFormat
    val audio = player.audioFormat
    if (video == null && audio == null) return null
    val badges = mutableListOf<String>()
    var note: String? = null

    if (video != null) {
        val decoder = stats.videoDecoder.orEmpty()
        val playingDv = video.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION || decoder.contains("dolby", true) ||
            video.codecs?.let { it.startsWith("dvh") || it.startsWith("dva") || it.startsWith("dav1") } == true
        val transfer = video.colorInfo?.colorTransfer
        val picture = when {
            playingDv -> "DOLBY VISION"
            transfer == C.COLOR_TRANSFER_ST2084 -> if (media?.hdr == HdrType.HDR10_PLUS) "HDR10+" else "HDR10"
            transfer == C.COLOR_TRANSFER_HLG -> "HLG"
            // Some files only say they are HDR inside the video stream; trust what the sharing phone read.
            transfer == null || transfer == Format_NO_VALUE -> when (media?.hdr) {
                HdrType.HDR10 -> "HDR10"
                HdrType.HDR10_PLUS -> "HDR10+"
                HdrType.HLG -> "HLG"
                else -> null
            }
            else -> null
        }
        val w = maxOf(video.width, media?.width ?: 0)
        val h = maxOf(video.height, media?.height ?: 0)
        badges += when {
            w >= 3800 || h >= 2100 -> "4K"
            w >= 2500 || h >= 1400 -> "1440p"
            w >= 1900 || h >= 1000 -> "1080p"
            w >= 1260 || h >= 700 -> "720p"
            w > 0 -> "SD"
            else -> null
        } ?: ""
        // The codec really being decoded, so a change of format (HEVC, AV1…) is visible at once.
        when (video.sampleMimeType) {
            MimeTypes.VIDEO_H265 -> "HEVC"
            MimeTypes.VIDEO_H264 -> "H.264"
            MimeTypes.VIDEO_AV1 -> "AV1"
            MimeTypes.VIDEO_VP9 -> "VP9"
            MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
            MimeTypes.VIDEO_VC1 -> "VC-1"
            MimeTypes.VIDEO_DOLBY_VISION -> when {
                video.codecs.orEmpty().startsWith("dav1") -> "AV1"
                video.codecs.orEmpty().startsWith("dva") -> "H.264"
                else -> "HEVC"
            }
            else -> null
        }?.let { badges += it }
        if (picture != null) badges += picture

        // The screen's own limits: an HDR picture on an SDR screen is converted by the device.
        @Suppress("DEPRECATION")
        val screenTypes = display?.hdrCapabilities?.supportedHdrTypes?.toSet().orEmpty()
        val needs = when (picture) {
            "DOLBY VISION" -> Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION
            "HDR10" -> Display.HdrCapabilities.HDR_TYPE_HDR10
            "HDR10+" -> Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS
            "HLG" -> Display.HdrCapabilities.HDR_TYPE_HLG
            else -> null
        }
        note = when {
            media?.hdr == HdrType.DOLBY_VISION && !playingDv && media.dolbyVisionProfile == 5 ->
                "Dolby Vision profile 5 needs a Dolby Vision screen. Colours may look wrong here."
            media?.hdr == HdrType.DOLBY_VISION && !playingDv ->
                "Dolby Vision → playing as ${picture ?: "SDR"}"
            needs != null && screenTypes.isNotEmpty() && needs !in screenTypes && !(needs == Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS && Display.HdrCapabilities.HDR_TYPE_HDR10 in screenTypes) ->
                "$picture → shown as SDR on this screen"
            needs != null && screenTypes.isEmpty() -> "$picture → shown as SDR on this screen"
            else -> null
        }
    }

    if (audio != null) {
        val atmos = media?.atmos == true || audio.codecs?.contains("joc", true) == true || audio.sampleMimeType == MimeTypes.AUDIO_E_AC3_JOC
        val sound = when (audio.sampleMimeType) {
            MimeTypes.AUDIO_TRUEHD -> if (atmos) "DOLBY ATMOS" else "DOLBY TRUEHD"
            MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> if (atmos) "DOLBY ATMOS" else "DOLBY DIGITAL+"
            MimeTypes.AUDIO_AC3 -> "DOLBY DIGITAL"
            MimeTypes.AUDIO_DTS_X -> "DTS:X"
            MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
            MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_EXPRESS -> "DTS"
            MimeTypes.AUDIO_AAC -> when (audio.codecs) {
                "mp4a.40.5" -> "HE-AAC"
                "mp4a.40.29" -> "HE-AAC v2"
                else -> "AAC"
            }
            MimeTypes.AUDIO_FLAC -> "FLAC"
            MimeTypes.AUDIO_OPUS -> "OPUS"
            else -> null
        }
        if (sound != null) {
            badges += sound
            if (audio.channelCount >= 6) channelLabel(audio.channelCount)?.let { badges += it }
        }
        if (stats.passthrough == false && sound != null && sound.startsWith("D") && note == null &&
            (sound.contains("ATMOS") || sound.startsWith("DTS") || sound.contains("TRUEHD"))
        ) {
            note = "$sound decoded on this device" + if (sound.contains("ATMOS")) " (Atmos height channels are lost)" else ""
        }
    }
    val clean = badges.filter { it.isNotBlank() }
    // In plain words: the picture class and the frame's shape, e.g. "4K UHD · Scope 2.39:1".
    val resolution = video?.takeIf { it.width > 0 && it.height > 0 }?.let { v -> pictureDescription(v.width, v.height, v.pixelWidthHeightRatio, v.frameRate, fileName) }
    return if (clean.isEmpty()) null else FormatReport(clean, note, resolution)
}

/** "4K UHD · Scope 2.39:1", "Full HD · 16:9 Open Matte", "HD · 4:3 · HFR 60 fps". */
fun pictureDescription(width: Int, height: Int, pixelRatio: Float, fps: Float, fileName: String): String {
    val name = fileName.lowercase()
    val quality = when {
        width >= 3800 || height >= 2100 -> "4K UHD"
        width >= 2500 || height >= 1400 -> "QHD"
        width >= 1900 || height >= 1000 -> "Full HD"
        width >= 1260 || height >= 700 -> "HD"
        else -> "SD"
    }
    val aspect = width * (if (pixelRatio > 0) pixelRatio else 1f) / height
    val openMatte = Regex("""open[ ._-]?matte|om""").containsMatchIn(name)
    val imax = "imax" in name
    val shape = when {
        aspect >= 2.30f -> "Scope 2.39:1"
        aspect >= 2.10f -> "Wide ${"%.2f".format(aspect)}:1"
        aspect >= 1.95f -> "2.00:1"
        aspect >= 1.87f -> if (imax || aspect < 1.92f) "IMAX 1.90:1" else "%.2f:1".format(aspect)
        aspect >= 1.82f -> "Flat 1.85:1"
        aspect >= 1.70f -> when {
            openMatte -> "16:9 Open Matte"
            imax -> "IMAX 16:9"
            else -> "16:9"
        }
        aspect >= 1.45f -> "%.2f:1".format(aspect)
        aspect >= 1.40f -> "IMAX 1.43:1"
        aspect >= 1.30f -> "4:3"
        else -> "%.2f:1".format(aspect)
    }
    val hfr = fps.takeIf { it >= 47f }?.let { "HFR ${it.toInt()} fps" }
    return listOfNotNull(quality, if (openMatte && "Open Matte" !in shape) "$shape · Open Matte" else shape, hfr).joinToString("  ·  ")
}

/** One badge, styled like a TV's: bold letters on dark glass; Dolby Vision and HDR get a glow of colour. */
@Composable
fun FormatChip(text: String, large: Boolean) {
    val shape = RoundedCornerShape(if (large) 10.dp else 6.dp)
    val glow = when (text) {
        "DOLBY VISION" -> Brush.horizontalGradient(listOf(Color(0xFF7C3AED), Color(0xFF22D3EE)))
        "HDR10", "HDR10+", "HLG" -> Brush.horizontalGradient(listOf(Color(0xFFF59E0B), Color(0xFFEF4444)))
        "DOLBY ATMOS", "DTS:X" -> Brush.horizontalGradient(listOf(Color(0xFF2563EB), Color(0xFF22D3EE)))
        else -> null
    }
    Text(
        text,
        fontSize = if (large) 17.sp else 11.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = if (large) 1.5.sp else 0.8.sp,
        color = Color.White,
        maxLines = 1,
        modifier = Modifier
            .background(Color(0xCC05060F), shape)
            .then(if (glow != null) Modifier.border(if (large) 2.dp else 1.dp, glow, shape) else Modifier.border(1.dp, Color(0x55FFFFFF), shape))
            .padding(horizontal = if (large) 14.dp else 7.dp, vertical = if (large) 8.dp else 3.dp),
    )
}

/** The big badge in the top corner for a few seconds as the picture starts. */
@Composable
fun FormatAnnounce(report: FormatReport?, visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible && report != null,
        enter = fadeIn() + slideInHorizontally { it / 3 },
        exit = fadeOut() + slideOutHorizontally { it / 3 },
        modifier = modifier,
    ) {
        if (report == null) return@AnimatedVisibility
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { report.badges.forEach { FormatChip(it, large = true) } }
            report.note?.let {
                Text(
                    it,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.background(Color(0xCC05060F), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

// ---------- resume, pause and loading ----------

/** "Resume from 1:12:30" or start again, before the video plays. */
@Composable
fun ResumePrompt(
    title: String,
    positionMs: Long,
    onResume: () -> Unit,
    onStartOver: () -> Unit,
    first: FocusRequester,
    onFocus: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) {
        // Twice, so the request lands after the player screen has settled its own focus.
        withFrameNanos { }
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Column(
        modifier = modifier
            .onFocusChanged { onFocus(it.hasFocus) }
            .widthIn(max = 520.dp)
            .background(GlassDark, RoundedCornerShape(24.dp))
            .border(1.dp, Color(0x40FFFFFF), RoundedCornerShape(24.dp))
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("You stopped at ${formatClock(positionMs)}.", color = Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Resume from ${formatClock(positionMs)}", onClick = onResume, modifier = Modifier.focusRequester(first), primary = true)
            PillButton("Start over", onClick = onStartOver)
        }
    }
}

/** Shown a few seconds into a pause, like Apple TV: the title and what it is about, over a darkened picture. */
@Composable
fun PausedOverlay(item: PlayItem, title: String, positionMs: Long, durationMs: Long, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.horizontalGradient(listOf(Color(0xE6000000), Color(0x99000000), Color(0x33000000)))),
    ) {
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 56.dp, end = 56.dp, bottom = 56.dp).widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Paused", color = Accent, fontWeight = FontWeight.SemiBold)
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            item.episodeName?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = Muted) }
            if (item.meta.isNotBlank()) Text(item.meta, color = Muted)
            if (item.overview.isNotBlank()) {
                Text(item.overview, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            if (durationMs > 0) {
                Text("${formatClock(positionMs)} of ${formatClock(durationMs)}  ·  ${formatClock(durationMs - positionMs)} left", color = Muted)
            }
        }
    }
}

/** A spinner with how much is already loaded, instead of a frozen picture. */
@Composable
fun LoadingBadge(aheadMs: Long, networkBitsPerSecond: Long, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(Color(0xB3000000), RoundedCornerShape(50))
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = Accent)
        Text(
            listOfNotNull(
                "Loading…",
                "${aheadMs.coerceAtLeast(0) / 1000} s ready".takeIf { aheadMs > 0 },
                networkBitsPerSecond.takeIf { it > 0 }?.let { mbpsLabel(it / 1e6) },
            ).joinToString("  ·  "),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Shown over a locked screen when it is touched. */
@Composable
fun LockedNotice(onUnlock: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(GlassDark, RoundedCornerShape(20.dp))
            .border(1.dp, Color(0x40FFFFFF), RoundedCornerShape(20.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Screen locked", fontWeight = FontWeight.SemiBold)
        Text("Touches are ignored so nothing changes by accident.", color = Muted, style = MaterialTheme.typography.bodySmall)
        Text(
            "Unlock",
            fontWeight = FontWeight.Bold,
            color = Night,
            modifier = Modifier
                .background(Color.White, RoundedCornerShape(50))
                .clickable(onClick = onUnlock)
                .padding(horizontal = 26.dp, vertical = 10.dp),
        )
    }
}

// ---------- black bars ----------

private val frameHttp = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

/**
 * How much of the picture's height is black bars baked into the video (0 to 0.5), judged from two
 * frames the sharing phone sends. Wide films in a 16:9 frame have about 0.25.
 */
suspend fun blackBarFraction(fileUrl: String, durationMs: Long): Float? = withContext(Dispatchers.IO) {
    val times = listOf(0.3, 0.6).map { (durationMs * it).toLong() }.ifEmpty { listOf(300_000L) }
    val results = times.mapNotNull { at ->
        runCatching {
            val bytes = frameHttp.newCall(Request.Builder().url(frameUrl(fileUrl, at)).build()).execute().use { it.body?.bytes() } ?: return@runCatching null
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
            fun dark(y: Int): Boolean {
                var sum = 0L
                var n = 0
                var x = 0
                while (x < bitmap.width) {
                    val c = bitmap.getPixel(x, y)
                    sum += ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
                    n++
                    x += 4
                }
                return n > 0 && sum / n < 22
            }
            var top = 0
            while (top < bitmap.height / 2 && dark(top)) top++
            var bottom = 0
            while (bottom < bitmap.height / 2 && dark(bitmap.height - 1 - bottom)) bottom++
            val fraction = (top + bottom).toFloat() / bitmap.height
            bitmap.recycle()
            fraction
        }.getOrNull()
    }
    // A dark scene looks like bars everywhere; trust only the smaller of the two readings.
    results.minOrNull()?.takeIf { it in 0.05f..0.45f }
}

/** Zoom that removes [bars] of the frame's height while keeping the whole width in view, on a screen of [screenAspect]. */
fun cropZoom(bars: Float, frameAspect: Float, screenAspect: Float): Float {
    // The frame as shown with "Fit", in screen-height units.
    val frameW = minOf(screenAspect, frameAspect)
    val frameH = frameW / frameAspect
    val contentH = frameH * (1f - bars)
    return minOf(screenAspect / frameW, 1f / contentH).coerceIn(1f, 2f)
}

