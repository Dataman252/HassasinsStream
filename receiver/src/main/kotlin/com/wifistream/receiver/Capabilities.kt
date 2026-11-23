package com.wifistream.receiver

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.MetadataRetriever
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.wifistream.protocol.HdrType
import com.wifistream.protocol.MediaInfo
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** One video format this device can decode. */
class VideoDecoder(
    val label: String,
    val mime: String,
    val hardware: Boolean,
    val maxWidth: Int,
    val maxHeight: Int,
    val tenBit: Boolean,
    /** Dolby Vision profile numbers, for the Dolby Vision decoder only. */
    val dolbyVisionProfiles: List<Int> = emptyList(),
)

/** How one audio format reaches the speakers on this device. */
class AudioSupport(val label: String, val passthrough: Boolean, val deviceDecoder: Boolean, val softwareDecoder: Boolean)

/** What this device and the screen it is connected to can do. Read once per launch. */
class DeviceCapabilities(context: Context) {
    val screenWidth: Int
    val screenHeight: Int
    val refreshRates: List<Float>
    val hdr10: Boolean
    val hdr10Plus: Boolean
    val hlg: Boolean
    val dolbyVision: Boolean
    val video: List<VideoDecoder>
    val audio: List<AudioSupport>

    init {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val modes = display?.supportedModes.orEmpty()
        val largest = modes.maxByOrNull { it.physicalWidth.toLong() * it.physicalHeight }
        screenWidth = maxOf(largest?.physicalWidth ?: 0, largest?.physicalHeight ?: 0)
        screenHeight = minOf(largest?.physicalWidth ?: 0, largest?.physicalHeight ?: 0)
        refreshRates = modes.map { it.refreshRate }.distinctBy { (it * 100).toInt() }.sorted()
        @Suppress("DEPRECATION")
        val hdrTypes = display?.hdrCapabilities?.supportedHdrTypes?.toSet().orEmpty()
        hdr10 = Display.HdrCapabilities.HDR_TYPE_HDR10 in hdrTypes
        hdr10Plus = Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS in hdrTypes
        hlg = Display.HdrCapabilities.HDR_TYPE_HLG in hdrTypes
        dolbyVision = Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in hdrTypes

        val decoders = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder } }
            .getOrDefault(emptyList())
        video = VIDEO_FORMATS.mapNotNull { (label, mime) -> bestVideoDecoder(decoders, label, mime) }

        val outputs = runCatching { AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null) }.getOrNull()
        audio = AUDIO_FORMATS.map { format ->
            AudioSupport(
                label = format.label,
                passthrough = format.encoding != null && outputs?.supportsEncoding(format.encoding) == true,
                deviceDecoder = decoders.any { info -> info.supportedTypes.any { it.equals(format.mime, ignoreCase = true) } },
                softwareDecoder = format.bundled,
            )
        }
    }

    private fun bestVideoDecoder(decoders: List<MediaCodecInfo>, label: String, mime: String): VideoDecoder? {
        val candidates = decoders.filter { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
        if (candidates.isEmpty()) return null
        fun isHardware(info: MediaCodecInfo) =
            if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
            else !info.name.startsWith("OMX.google") && !info.name.startsWith("c2.android")
        val best = candidates.maxByOrNull { info ->
            val caps = runCatching { info.getCapabilitiesForType(mime).videoCapabilities }.getOrNull()
            (if (isHardware(info)) 1_000_000_000L else 0L) + (caps?.supportedWidths?.upper ?: 0)
        } ?: return null
        val caps = runCatching { best.getCapabilitiesForType(mime) }.getOrNull() ?: return null
        val profiles = caps.profileLevels.map { it.profile }.toSet()
        return VideoDecoder(
            label = label,
            mime = mime,
            hardware = isHardware(best),
            maxWidth = caps.videoCapabilities?.supportedWidths?.upper ?: 0,
            maxHeight = caps.videoCapabilities?.supportedHeights?.upper ?: 0,
            tenBit = when (mime) {
                "video/hevc" -> CodecProfileLevel.HEVCProfileMain10 in profiles || CodecProfileLevel.HEVCProfileMain10HDR10 in profiles
                "video/av01" -> CodecProfileLevel.AV1ProfileMain10 in profiles || CodecProfileLevel.AV1ProfileMain10HDR10 in profiles
                "video/x-vnd.on2.vp9" -> CodecProfileLevel.VP9Profile2 in profiles || CodecProfileLevel.VP9Profile2HDR in profiles
                "video/dolby-vision" -> true
                else -> false
            },
            dolbyVisionProfiles = if (mime == "video/dolby-vision") DV_PROFILES.filterKeys { it in profiles }.values.sorted() else emptyList(),
        )
    }

    fun videoDecoder(codecLabel: String?): VideoDecoder? = video.firstOrNull { it.label == codecLabel }
    fun audioSupport(codecLabel: String?): AudioSupport? = audio.firstOrNull { it.label == codecLabel }

    private class AudioFormat(val label: String, val mime: String, val encoding: Int?, val bundled: Boolean)

    private companion object {
        // Labels match the ones the sharing device's scanner writes into MediaInfo.
        val VIDEO_FORMATS = listOf(
            "H.264" to "video/avc",
            "HEVC" to "video/hevc",
            "AV1" to "video/av01",
            "VP9" to "video/x-vnd.on2.vp9",
            "MPEG-2" to "video/mpeg2",
            "Dolby Vision" to "video/dolby-vision",
        )
        val AUDIO_FORMATS = listOf(
            AudioFormat("AAC", "audio/mp4a-latm", null, true),
            AudioFormat("AC3", "audio/ac3", C.ENCODING_AC3, true),
            AudioFormat("E-AC3", "audio/eac3", C.ENCODING_E_AC3, true),
            AudioFormat("Atmos (E-AC3)", "audio/eac3-joc", C.ENCODING_E_AC3_JOC, false),
            AudioFormat("TrueHD", "audio/true-hd", C.ENCODING_DOLBY_TRUEHD, true),
            AudioFormat("DTS", "audio/vnd.dts", C.ENCODING_DTS, true),
            AudioFormat("DTS-HD", "audio/vnd.dts.hd", C.ENCODING_DTS_HD, true),
            AudioFormat("FLAC", "audio/flac", null, true),
            AudioFormat("Opus", "audio/opus", null, true),
            AudioFormat("MP3", "audio/mpeg", null, true),
        )
        val DV_PROFILES = mapOf(
            CodecProfileLevel.DolbyVisionProfileDvheDtr to 4,
            CodecProfileLevel.DolbyVisionProfileDvheStn to 5,
            CodecProfileLevel.DolbyVisionProfileDvheDtb to 7,
            CodecProfileLevel.DolbyVisionProfileDvheSt to 8,
            CodecProfileLevel.DolbyVisionProfileDvavSe to 9,
            CodecProfileLevel.DolbyVisionProfileDvav110 to 10,
        )
    }
}

private fun DeviceCapabilities.resolutionName(): String = when {
    screenWidth >= 3800 -> "4K"
    screenWidth >= 2500 -> "1440p"
    screenWidth >= 1900 -> "Full HD"
    screenWidth >= 1200 -> "HD"
    else -> "${screenWidth}×${screenHeight}"
}

/** The setup's ceiling in a few words, e.g. "4K · HDR10 · no Dolby Vision". */
fun DeviceCapabilities.headline(): String = listOf(
    resolutionName(),
    listOfNotNull("HDR10".takeIf { hdr10 }, "HDR10+".takeIf { hdr10Plus }, "HLG".takeIf { hlg }).joinToString(", ").ifEmpty { "no HDR" },
    if (dolbyVision && videoDecoder("Dolby Vision") != null) "Dolby Vision" else "no Dolby Vision",
).joinToString("  ·  ")

/**
 * The limits of this device together with whatever it is plugged into, in plain sentences.
 * The point is the difference between the two: a stick that can decode Dolby Vision still
 * cannot show it through a projector that only accepts HDR10.
 */
fun DeviceCapabilities.limits(): List<String> {
    val lines = mutableListOf<String>()
    lines += "Picture: up to ${resolutionName()} (${screenWidth}×${screenHeight}), at up to ${"%.0f".format(refreshRates.maxOrNull() ?: 60f)} Hz."

    val hdrKinds = listOfNotNull("HDR10".takeIf { hdr10 }, "HDR10+".takeIf { hdr10Plus }, "HLG".takeIf { hlg })
    lines += if (hdrKinds.isEmpty()) {
        "HDR: the connected screen does not accept HDR. HDR films play in standard range."
    } else {
        "HDR: the connected screen accepts ${hdrKinds.joinToString(", ")}."
    }

    val dvDecoder = videoDecoder("Dolby Vision")
    lines += when {
        dolbyVision && dvDecoder != null ->
            "Dolby Vision: yes" + dvDecoder.dolbyVisionProfiles.takeIf { it.isNotEmpty() }?.let { ", profiles ${it.joinToString(", ")}." }.orEmpty().ifEmpty { "." }
        dvDecoder != null ->
            "Dolby Vision: this device can decode it, but the connected screen does not accept it. " +
                "Dolby Vision films play as ${if (hdr10) "HDR10" else "standard range"} here."
        dolbyVision -> "Dolby Vision: the screen accepts it, but this device has no Dolby Vision decoder."
        else -> "Dolby Vision: not available on this device or screen."
    }

    val largest = video.filter { it.label != "Dolby Vision" }.maxByOrNull { it.maxWidth.toLong() * it.maxHeight }
    if (largest != null && (largest.maxWidth > screenWidth || largest.maxHeight > screenHeight)) {
        lines += "Larger files still play: the decoder takes up to ${largest.maxWidth}×${largest.maxHeight} and the picture is scaled to the screen."
    }
    val missing = listOf("HEVC", "AV1").filter { videoDecoder(it) == null }
    if (missing.isNotEmpty()) lines += "No decoder for ${missing.joinToString(" or ")}: files in that format will not play."

    val through = audio.filter { it.passthrough }.map { it.label }
    lines += if (through.isEmpty()) {
        "Sound: nothing is passed through on this connection. Dolby and DTS tracks are decoded on this device."
    } else {
        "Sound passed through untouched: ${through.joinToString(", ")}. Other formats are decoded on this device."
    }
    return lines
}

/** "Will it play here?" for one file. */
class Verdict(val playable: Boolean, val headline: String, val notes: List<String>)

fun DeviceCapabilities.verdictFor(media: MediaInfo, passthroughOn: Boolean): Verdict {
    val notes = mutableListOf<String>()
    var playable = true
    var degraded = false

    val decoder = videoDecoder(media.videoCodec)
    val tenBit = media.hdr != HdrType.SDR
    when {
        media.videoCodec == null -> notes += "Video format not known yet."
        decoder == null -> {
            playable = false
            notes += "This device has no ${media.videoCodec} video decoder."
        }
        media.width > decoder.maxWidth || media.height > decoder.maxHeight -> {
            playable = false
            notes += "${media.width}×${media.height} is larger than this device's ${media.videoCodec} decoder handles (${decoder.maxWidth}×${decoder.maxHeight})."
        }
        tenBit && !decoder.tenBit -> {
            playable = false
            notes += "This file is 10-bit and this device's ${media.videoCodec} decoder is 8-bit only."
        }
        !decoder.hardware -> {
            degraded = true
            notes += "${media.videoCodec} is decoded in software here, which may stutter at high resolutions."
        }
    }

    when (media.hdr) {
        HdrType.SDR -> Unit
        HdrType.DOLBY_VISION -> {
            val profile = media.dolbyVisionProfile
            val dvDecoder = videoDecoder("Dolby Vision")
            val native = dolbyVision && dvDecoder != null && (profile == null || profile in dvDecoder.dolbyVisionProfiles)
            when {
                native -> notes += "Plays in Dolby Vision."
                profile == 5 -> {
                    degraded = true
                    notes += "Dolby Vision profile 5 has no HDR10 layer to fall back on, so colours will look wrong on this device."
                }
                hdr10 -> {
                    degraded = true
                    notes += "No Dolby Vision here: plays as HDR10."
                }
                else -> {
                    degraded = true
                    notes += "No Dolby Vision or HDR on this screen: plays in standard range."
                }
            }
        }
        HdrType.HDR10, HdrType.HDR10_PLUS, HdrType.HLG -> {
            val supported = when (media.hdr) {
                HdrType.HLG -> hlg
                else -> hdr10
            }
            if (!supported) {
                degraded = true
                notes += "This screen does not report HDR support: plays in standard range."
            } else if (media.hdr == HdrType.HDR10_PLUS && !hdr10Plus) {
                notes += "Plays as HDR10 (this screen does not report HDR10+)."
            } else {
                notes += "Plays in HDR."
            }
        }
    }

    val sound = audioSupport(if (media.atmos && media.audioCodec == "E-AC3") "Atmos (E-AC3)" else media.audioCodec)
        ?: audioSupport(media.audioCodec)
    when {
        media.audioCodec == null -> Unit
        sound == null -> notes += "${media.audioCodec} sound: decoded on this device."
        sound.passthrough && passthroughOn -> notes += "${sound.label} sound passes through to the TV or soundbar."
        sound.deviceDecoder -> notes += "${sound.label} sound is decoded on this device."
        sound.softwareDecoder -> notes += "${sound.label} sound is decoded in software by the app."
        else -> {
            degraded = true
            notes += "${sound.label} sound cannot be passed through or decoded here; pick another audio track if the file has one."
        }
    }
    if (media.atmos && !(sound?.passthrough == true && passthroughOn)) {
        notes += "Atmos height effects need passthrough; otherwise it plays as regular surround."
    }
    if (media.estimated) notes += "Based on the file name only: the sharing device has not finished scanning this file."

    return Verdict(
        playable = playable,
        headline = when {
            !playable -> "Will not play on this device"
            degraded -> "Plays, with limits"
            else -> "Plays on this device"
        },
        notes = notes,
    )
}

// ---------- real playback test ----------

class TestResult(val passed: Boolean, val lines: List<String>)

/**
 * Actually starts the file with sound muted and the picture sent off-screen, for a few seconds,
 * and reports which decoders took it or why it failed.
 */
@OptIn(UnstableApi::class)
suspend fun testPlayback(context: Context, url: String, store: LibraryStore): TestResult = withContext(Dispatchers.Main) {
    val thread = HandlerThread("playback-test").apply { start() }
    // An off-screen target for decoded frames; frames are thrown away as they arrive.
    val reader = ImageReader.newInstance(1280, 720, ImageFormat.PRIVATE, 3)
    reader.setOnImageAvailableListener({ r -> runCatching { r.acquireLatestImage()?.close() } }, Handler(thread.looper))

    var videoDecoder: String? = null
    var audioDecoder: String? = null
    var passthrough: Boolean? = null
    var failure: PlaybackException? = null

    val player = ExoPlayer.Builder(context, AppRenderersFactory(context, store.passthrough, store.dvAsHdr10, AtomicLong(), SoundProcessor()))
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(context).setDataSourceFactory(
                DefaultHttpDataSource.Factory().setConnectTimeoutMs(8_000).setReadTimeoutMs(15_000),
            ),
        )
        .build()
    try {
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                failure = error
            }
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, a: Long, b: Long) {
                videoDecoder = decoderName
            }

            override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, a: Long, b: Long) {
                audioDecoder = decoderName
            }

            override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, config: AudioSink.AudioTrackConfig) {
                passthrough = !Util.isEncodingLinearPcm(config.encoding)
            }
        })
        player.volume = 0f
        player.setVideoSurface(reader.surface)
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.play()

        val started = withTimeoutOrNull(15_000) {
            while (failure == null && player.currentPosition < 1_500) delay(200)
            failure == null
        } == true

        val video = player.videoFormat
        val audio = player.audioFormat
        val lines = mutableListOf<String>()
        if (video != null) {
            lines += "Video: ${codecLabel(video.sampleMimeType)} ${video.width}×${video.height}" +
                (videoDecoder?.let { ", decoded by $it" } ?: ", decoder did not start")
        }
        if (audio != null) {
            lines += "Sound: ${codecLabel(audio.sampleMimeType)}, " + when (passthrough) {
                true -> "passed through to the TV or soundbar"
                false -> "decoded" + (audioDecoder?.let { " by $it" } ?: "")
                null -> "output did not start"
            }
        }
        failure?.let { lines += "Stopped with: ${it.errorCodeName}" }
        if (failure == null && !started) lines += "Nothing played within 15 seconds."
        TestResult(started, lines)
    } finally {
        player.release()
        reader.close()
        thread.quitSafely()
    }
}

/** Every track inside a file (video, audio, subtitles), read from its header over the network. */
@OptIn(UnstableApi::class)
suspend fun readTracks(context: Context, url: String): List<Format>? = runCatching {
    withTimeoutOrNull(25_000) {
        val groups = MetadataRetriever.retrieveMetadata(context, MediaItem.fromUri(url)).await()
        (0 until groups.length).flatMap { g -> (0 until groups[g].length).map { t -> groups[g].getFormat(t) } }
    }
}.getOrNull()
