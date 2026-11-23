package com.wifistream.sender

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.MetadataRetriever
import com.wifistream.protocol.EntryKind
import com.wifistream.protocol.HdrType
import com.wifistream.protocol.MediaInfo
import com.wifistream.protocol.guessMediaInfo
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/**
 * Reads resolution, HDR type, codecs and music tags from files on the phone, where
 * disk access is fast, so the receiver never has to probe files over Wi-Fi.
 * Results are cached on disk and keyed by path + size + modified time.
 */
@OptIn(UnstableApi::class)
class MediaScanner(private val context: Context, private val scope: CoroutineScope) {

    private val cacheFile = File(context.filesDir, "scan-cache.json")
    private val cache = ConcurrentHashMap<String, MediaInfo>()
    private val inFlight = ConcurrentHashMap<String, Deferred<Unit>>()

    @kotlin.OptIn(ExperimentalCoroutinesApi::class)
    private val worker = Dispatchers.IO.limitedParallelism(2)

    @Volatile
    private var dirty = false

    val scannedCount = MutableStateFlow(0)

    init {
        runCatching {
            if (cacheFile.exists()) {
                cache.putAll(json.decodeFromString<Map<String, MediaInfo>>(cacheFile.readText()))
            }
        }
        scannedCount.value = cache.size
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(20_000)
                save()
            }
        }
    }

    fun cached(file: File): MediaInfo? = cache[key(file)]?.takeIf { info ->
        // Music scanned before loudness tags were read is scanned once more.
        info.loudnessRead || com.wifistream.protocol.kindForFileName(file.name) != com.wifistream.protocol.EntryKind.AUDIO
    }

    /** Starts a scan unless one is already running for this file. */
    fun scanAsync(file: File, kind: EntryKind): Deferred<Unit> {
        val key = key(file)
        return inFlight.computeIfAbsent(key) {
            scope.async(worker) {
                try {
                    val info = if (kind == EntryKind.VIDEO) scanVideo(file) else scanAudio(file)
                    cache[key] = info
                    scannedCount.value = cache.size
                    dirty = true
                } finally {
                    inFlight.remove(key)
                }
            }
        }
    }

    fun save() {
        if (!dirty) return
        dirty = false
        runCatching { cacheFile.writeText(json.encodeToString(cache.toMap())) }
    }

    private fun key(file: File) = "${file.absolutePath}|${file.length()}|${file.lastModified()}"

    private suspend fun scanVideo(file: File): MediaInfo {
        val guess = guessMediaInfo(file.name)
        val tracks = runCatching {
            withTimeoutOrNull(15_000) {
                MetadataRetriever.retrieveMetadata(context, MediaItem.fromUri(Uri.fromFile(file))).await()
            }
        }.getOrNull() ?: return guess.copy(durationMs = platformTags(file).durationMs)

        var video: Format? = null
        var audio: Format? = null
        for (g in 0 until tracks.length) {
            val group = tracks[g]
            for (t in 0 until group.length) {
                val format = group.getFormat(t)
                val mime = format.sampleMimeType
                if (video == null && MimeTypes.isVideo(mime)) video = format
                if (audio == null && MimeTypes.isAudio(mime)) audio = format
            }
        }

        val dolbyVision = video?.let {
            it.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION || it.codecs.orEmpty().startsWith("dv") ||
                it.codecs.orEmpty().startsWith("dav1")
        } == true
        val hdr = when {
            dolbyVision -> HdrType.DOLBY_VISION
            video?.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 ->
                if (guess.hdr == HdrType.HDR10_PLUS) HdrType.HDR10_PLUS else HdrType.HDR10
            video?.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> HdrType.HLG
            // No colour information in the header: fall back to what the file name says.
            video?.colorInfo == null -> guess.hdr
            else -> HdrType.SDR
        }
        val audioMime = audio?.sampleMimeType
        return MediaInfo(
            durationMs = platformTags(file).durationMs,
            width = video?.width?.takeIf { it > 0 } ?: guess.width,
            height = video?.height?.takeIf { it > 0 } ?: guess.height,
            videoCodec = videoCodecName(video) ?: guess.videoCodec,
            hdr = hdr,
            // Codec strings look like "dvhe.08.06": the middle part is the profile.
            dolbyVisionProfile = if (dolbyVision) video?.codecs?.split('.')?.getOrNull(1)?.toIntOrNull() else null,
            audioCodec = audioCodecName(audioMime) ?: guess.audioCodec,
            audioChannels = audio?.channelCount?.takeIf { it > 0 } ?: guess.audioChannels,
            atmos = audioMime == MimeTypes.AUDIO_E_AC3_JOC || guess.atmos,
            estimated = false,
        )
    }

    private fun scanAudio(file: File): MediaInfo = platformTags(file)

    /** Duration and music tags through the platform retriever, which is quick for local files. */
    private fun platformTags(file: File): MediaInfo {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            fun tag(key: Int) = retriever.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
            MediaInfo(
                durationMs = tag(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0,
                title = tag(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = tag(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                album = tag(MediaMetadataRetriever.METADATA_KEY_ALBUM),
            ).let { info ->
                val (track, album) = replayGain(file)
                info.copy(replayGainTrackDb = track, replayGainAlbumDb = album, loudnessRead = true)
            }
        } catch (e: Exception) {
            MediaInfo(estimated = true)
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * ReplayGain values, found by looking for their tag names near the start and end of the file.
     * This covers FLAC/Ogg comments, MP3 ID3 frames (including UTF-16 ones) and MP4 atoms.
     */
    private fun replayGain(file: File): Pair<Float?, Float?> = runCatching {
        val length = file.length()
        val head = minOf(length, 512L * 1024).toInt()
        val tail = minOf(length - head, 256L * 1024).coerceAtLeast(0).toInt()
        val bytes = java.io.RandomAccessFile(file, "r").use { raf ->
            val out = ByteArray(head + tail)
            raf.readFully(out, 0, head)
            if (tail > 0) {
                raf.seek(length - tail)
                raf.readFully(out, head, tail)
            }
            out
        }
        // Dropping zero bytes turns UTF-16 text into plain text as well.
        val text = String(bytes.filter { it != 0.toByte() }.toByteArray(), Charsets.ISO_8859_1)
        fun find(which: String): Float? =
            Regex("""replaygain_${which}_gain[^0-9+\-]{0,12}([+\-]?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1)?.toFloatOrNull()?.takeIf { it in -30f..30f }
        find("track") to find("album")
    }.getOrDefault(null to null)

    private fun videoCodecName(format: Format?): String? = when (format?.sampleMimeType) {
        null -> null
        MimeTypes.VIDEO_H265 -> "HEVC"
        MimeTypes.VIDEO_H264 -> "H.264"
        MimeTypes.VIDEO_AV1 -> "AV1"
        MimeTypes.VIDEO_VP9 -> "VP9"
        MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
        MimeTypes.VIDEO_DOLBY_VISION -> when {
            format.codecs.orEmpty().startsWith("dav1") -> "AV1"
            format.codecs.orEmpty().startsWith("dva") -> "H.264"
            else -> "HEVC"
        }
        else -> format.sampleMimeType?.substringAfter('/')?.uppercase()
    }

    private fun audioCodecName(mime: String?): String? = when (mime) {
        null -> null
        MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "E-AC3"
        MimeTypes.AUDIO_AC3 -> "AC3"
        MimeTypes.AUDIO_AC4 -> "AC4"
        MimeTypes.AUDIO_TRUEHD -> "TrueHD"
        MimeTypes.AUDIO_DTS -> "DTS"
        MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_X -> "DTS-HD"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_VORBIS -> "Vorbis"
        MimeTypes.AUDIO_MPEG -> "MP3"
        MimeTypes.AUDIO_RAW -> "PCM"
        else -> mime.substringAfter('/').uppercase()
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
