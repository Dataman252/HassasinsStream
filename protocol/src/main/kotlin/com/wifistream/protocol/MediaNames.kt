package com.wifistream.protocol

private val VIDEO_EXT = setOf(
    "mkv", "mp4", "m4v", "mov", "avi", "webm", "ts", "m2ts", "mpg", "mpeg", "wmv", "flv", "3gp",
)
private val AUDIO_EXT = setOf(
    "mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "alac", "aiff", "ape", "mka",
)
private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic")
private val SUBTITLE_EXT = setOf("srt", "ass", "ssa", "vtt", "sub")

/** Classifies a file by its extension. */
fun kindForFileName(name: String): EntryKind =
    when (name.substringAfterLast('.', "").lowercase()) {
        in VIDEO_EXT -> EntryKind.VIDEO
        in AUDIO_EXT -> EntryKind.AUDIO
        in IMAGE_EXT -> EntryKind.IMAGE
        in SUBTITLE_EXT -> EntryKind.SUBTITLE
        else -> EntryKind.OTHER
    }

private fun String.hasTag(pattern: String): Boolean =
    Regex("(?<![a-z0-9])(?:$pattern)(?![a-z0-9])", RegexOption.IGNORE_CASE).containsMatchIn(this)

/**
 * Best-effort quality tags read from a release-style file name, e.g.
 * `Dune.Part.Two.2024.2160p.DV.HDR10+.Atmos.mkv`. Used until the real scan finishes,
 * and for details the file header does not carry (HDR10+, Atmos inside TrueHD).
 */
fun guessMediaInfo(fileName: String): MediaInfo {
    val n = fileName.substringBeforeLast('.')
    val (w, h) = when {
        n.hasTag("2160p|4k|uhd") -> 3840 to 2160
        n.hasTag("1440p") -> 2560 to 1440
        n.hasTag("1080[pi]|fhd") -> 1920 to 1080
        n.hasTag("720p") -> 1280 to 720
        n.hasTag("576p|480p|dvdrip|sd") -> 720 to 480
        else -> 0 to 0
    }
    val hdr = when {
        n.hasTag("dv|dovi|dolby[ ._-]?vision") -> HdrType.DOLBY_VISION
        Regex("hdr10(\\+|[ ._-]?plus)", RegexOption.IGNORE_CASE).containsMatchIn(n) -> HdrType.HDR10_PLUS
        n.hasTag("hdr|hdr10") -> HdrType.HDR10
        n.hasTag("hlg") -> HdrType.HLG
        else -> HdrType.SDR
    }
    val videoCodec = when {
        n.hasTag("hevc|[hx][ ._]?265") -> "HEVC"
        n.hasTag("avc|[hx][ ._]?264") -> "H.264"
        n.hasTag("av1") -> "AV1"
        else -> null
    }
    val audioCodec = when {
        n.hasTag("truehd") -> "TrueHD"
        n.hasTag("dts[ ._-]?hd([ ._-]?ma)?|dts[ ._-]?x") -> "DTS-HD"
        n.hasTag("dts") -> "DTS"
        n.hasTag("ddp|dd\\+|e[ ._-]?ac[ ._-]?3") || Regex("ddp\\d", RegexOption.IGNORE_CASE).containsMatchIn(n) -> "E-AC3"
        n.hasTag("ac3|dd") || Regex("(?<![a-z])dd\\d", RegexOption.IGNORE_CASE).containsMatchIn(n) -> "AC3"
        n.hasTag("aac") -> "AAC"
        n.hasTag("flac") -> "FLAC"
        else -> null
    }
    val channels = when {
        Regex("7[ .]1").containsMatchIn(n) -> 8
        Regex("5[ .]1").containsMatchIn(n) -> 6
        Regex("(?<!\\d)2[ .]0(?!\\d)").containsMatchIn(n) -> 2
        else -> 0
    }
    return MediaInfo(
        width = w,
        height = h,
        videoCodec = videoCodec,
        hdr = hdr,
        audioCodec = audioCodec,
        audioChannels = channels,
        atmos = n.hasTag("atmos"),
        estimated = true,
    )
}
