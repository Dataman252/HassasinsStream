package com.wifistream.receiver

import com.wifistream.protocol.Entry
import com.wifistream.protocol.ParsedName
import com.wifistream.protocol.hdrLabel
import com.wifistream.protocol.parseMediaName
import com.wifistream.protocol.resolutionLabel

class VideoFile(val root: String, val entry: Entry) {
    val parsed: ParsedName = parseMediaName(entry.path)
    val key: String = "$root|${entry.path}"
    val folder: String = entry.path.substringBeforeLast('/', "")

    /** Files that are the same movie or the same show share a group, so TMDB is asked once per title. */
    val group: String =
        if (parsed.isEpisode) "tv|${parsed.title.lowercase()}"
        else "movie|${parsed.title.lowercase()}|${parsed.year ?: ""}"

    /**
     * Worth asking TMDB about: looks like a film or episode rather than a phone clip.
     * Camera and chat videos are short, small and carry no year or quality tag.
     */
    val looksLikeTitle: Boolean
        get() {
            val media = entry.media
            val named = parsed.title.count(Char::isLetter) >= 2 && !CAMERA_NAME.containsMatchIn(entry.name)
            val substantial = parsed.isEpisode || parsed.year != null || (media?.estimated == true && media.height > 0) ||
                entry.sizeBytes >= 100L * 1024 * 1024 || (media?.durationMs ?: 0) >= 10 * 60_000
            return named && substantial
        }

    /** "4K DV" style tag for poster corners. */
    val qualityTag: String?
        get() = entry.media?.let { listOfNotNull(it.resolutionLabel(), it.hdrLabel()).joinToString(" ") }?.ifEmpty { null }

    private companion object {
        val CAMERA_NAME = Regex("(?i)^(vid|img|pxl|mvi|dsc|mov|screen|video|whatsapp|trim|rec)[-_ ]?\\d")
    }
}

class AudioFile(val root: String, val entry: Entry) {
    val folder: String = entry.path.substringBeforeLast('/', "")
    val title: String get() = entry.media?.title ?: entry.name.substringBeforeLast('.')
}

class MovieItem(val info: TitleInfo, val files: List<VideoFile>)

class ShowItem(val info: TitleInfo, val episodes: List<VideoFile>) {
    val seasons: List<Int> get() = episodes.mapNotNull { it.parsed.season }.distinct().sorted()
}

class Album(val key: String, val title: String, val artist: String?, val tracks: List<AudioFile>, val artUrl: String)

data class LibraryUi(
    val movies: List<MovieItem> = emptyList(),
    val shows: List<ShowItem> = emptyList(),
    /** Videos TMDB does not know: home videos, lectures, recordings. */
    val others: List<VideoFile> = emptyList(),
    val albums: List<Album> = emptyList(),
    val loaded: Boolean = false,
    val error: String? = null,
)
