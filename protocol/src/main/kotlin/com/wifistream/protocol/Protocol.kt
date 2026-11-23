package com.wifistream.protocol

import kotlinx.serialization.Serializable

/** Everything the sender and the receiver must agree on. */
object WifiStream {
    /** Bumped whenever a breaking change is made to the API below. */
    const val PROTOCOL_VERSION = 1

    /** mDNS / NSD service type the sender advertises and the receiver looks for. */
    const val SERVICE_TYPE = "_wifistream._tcp"
    const val DEFAULT_PORT = 8765

    object Path {
        /** GET -> [ServerInfo] */
        const val INFO = "/api/info"

        /** GET ?root=<id>&path=<relative dir> -> [Listing] */
        const val LIST = "/api/list"

        /** GET ?root=<id>&path=<relative file>, honours HTTP Range -> file bytes */
        const val FILE = "/file"

        /** GET ?root=<id> -> [Library]: every video, music and subtitle file on that storage */
        const val LIBRARY = "/api/library"

        /** GET ?root=<id>&path=<audio file> -> cover image (embedded art, else cover.jpg next to it) */
        const val ART = "/api/art"

        /** GET ?root=<id>&path=<video file>&t=<milliseconds> -> a small JPEG of the picture near that time */
        const val FRAME = "/api/frame"

        /**
         * GET ?root=<id>&path=<video file> -> every seek-bar picture the sharing device has already made
         * for that video, in one go: records of an 8-byte time (ms), a 4-byte length and the JPEG bytes.
         */
        const val PREVIEWS = "/api/previews"

        /** GET ?recheck=1 -> [KeyStatus]: whether the sharing device's TMDB and OpenSubtitles keys work */
        const val KEYS = "/api/keys"

        /** GET ?p=<TMDB v3 path>&<its parameters> -> TMDB's answer, asked with the sharing device's key */
        const val TMDB = "/api/tmdb"

        /** GET <OpenSubtitles search parameters> -> OpenSubtitles' answer, asked with the sharing device's key */
        const val SUBTITLE_SEARCH = "/api/subtitles/search"

        /** GET ?file_id=<id> -> the subtitle file's text, downloaded with the sharing device's key */
        const val SUBTITLE_DOWNLOAD = "/api/subtitles/download"

        /** GET ?mb=<megabytes> -> that many bytes of filler, to measure the connection's speed */
        const val SPEED = "/api/speed"
    }
}

@Serializable
data class ServerInfo(
    val name: String,
    /** Stays the same for one sharing device for as long as the app is installed on it. */
    val deviceId: String = "",
    val protocolVersion: Int = WifiStream.PROTOCOL_VERSION,
    val roots: List<StorageRoot>,
    /** Changes whenever a media file is added, removed or replaced on a shared storage; 0 when unknown. */
    val libraryStamp: Long = 0,
    /** The sharing phone's battery temperature in °C, 0 when unknown. */
    val temperatureC: Float = 0f,
    /** True while the phone is too warm and has paused making seek-bar pictures. */
    val previewsPaused: Boolean = false,
)

/** Where a key stands, as last checked by the device that holds it. */
@Serializable
enum class KeyState { MISSING, CHECKING, ACTIVE, REJECTED, OFFLINE }

@Serializable
data class KeyStatus(
    val tmdb: KeyState = KeyState.MISSING,
    val openSubtitles: KeyState = KeyState.MISSING,
)

@Serializable
enum class StorageKind { INTERNAL, USB, SD_CARD }

@Serializable
data class StorageRoot(
    val id: String,
    val label: String,
    val kind: StorageKind,
    val totalBytes: Long = 0,
    val freeBytes: Long = 0,
)

@Serializable
data class Listing(
    val root: String,
    val path: String,
    val entries: List<Entry>,
)

@Serializable
data class Library(
    val root: String,
    val entries: List<Entry>,
    /** Files still being scanned on the phone; their [Entry.media] is a guess or missing for now. */
    val pendingScans: Int = 0,
)

@Serializable
enum class EntryKind { FOLDER, VIDEO, AUDIO, IMAGE, SUBTITLE, OTHER }

@Serializable
data class Entry(
    val name: String,
    /** Path relative to the storage root, always with forward slashes. */
    val path: String,
    val kind: EntryKind,
    val sizeBytes: Long = 0,
    val modifiedMs: Long = 0,
    /** Filled in by the scanner on the sender for video and audio files; null until scanned. */
    val media: MediaInfo? = null,
)

@Serializable
enum class HdrType { SDR, HDR10, HDR10_PLUS, HLG, DOLBY_VISION }

@Serializable
data class MediaInfo(
    val durationMs: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val videoCodec: String? = null,
    val hdr: HdrType = HdrType.SDR,
    /** Dolby Vision profile number (5, 7, 8 ...) when [hdr] is DOLBY_VISION. */
    val dolbyVisionProfile: Int? = null,
    val audioCodec: String? = null,
    val audioChannels: Int = 0,
    val atmos: Boolean = false,
    /** Music tags. */
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    /** ReplayGain loudness corrections from the music file's tags, in dB. */
    val replayGainTrackDb: Float? = null,
    val replayGainAlbumDb: Float? = null,
    /** True once the tags above have been looked for (older scans did not). */
    val loudnessRead: Boolean = false,
    /** True when the values were guessed from the file name instead of read from the file. */
    val estimated: Boolean = false,
)
