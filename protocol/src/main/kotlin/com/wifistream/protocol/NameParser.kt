package com.wifistream.protocol

/** What a file name says about the title it holds. */
data class ParsedName(
    val title: String,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
) {
    val isEpisode: Boolean get() = season != null && episode != null
}

private val EPISODE = Regex("(?i)(?<![a-z0-9])s(\\d{1,2})[ ._-]?e(\\d{1,3})(?![0-9])")
private val EPISODE_X = Regex("(?i)(?<![a-z0-9])(\\d{1,2})x(\\d{2,3})(?![0-9])")
private val YEAR = Regex("(?<![0-9])(19[2-9]\\d|20[0-4]\\d)(?![0-9p])")
private val RELEASE_TAG = Regex(
    "(?i)(?<![a-z0-9])(2160p|1440p|1080[pi]|720p|576p|480p|4k|uhd|bluray|blu-ray|bdrip|brrip|remux|" +
        "web[ ._-]?dl|webrip|web|hdtv|dvdrip|hdrip|hdr|hdr10|dv|dovi|x264|x265|h[ ._]?264|h[ ._]?265|hevc|avc|" +
        "av1|aac|ac3|dts|ddp?\\d?|atmos|truehd|10bit|extended|unrated|proper|repack|imax|multi|dual)(?![a-z0-9])",
)
private val SEASON_FOLDER = Regex("(?i)^(season|series|s)[ ._-]*\\d+$|^specials$")
private val TRAILING_YEAR = Regex("[ (\\[]*(19|20)\\d{2}[)\\]]*$")

private fun clean(raw: String): String =
    raw.replace('.', ' ').replace('_', ' ')
        .replace(Regex("\\[[^\\]]*\\]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '-', '(', '[', '{', ',')

/**
 * Reads title, year and season/episode from a path such as
 * `Shows/The Bear/Season 2/The.Bear.S02E05.1080p.WEB.mkv` or `Dune.Part.Two.2024.2160p.DV.mkv`.
 */
fun parseMediaName(path: String): ParsedName {
    val parts = path.split('/')
    val file = parts.last().substringBeforeLast('.')

    val episode = EPISODE.find(file) ?: EPISODE_X.find(file)
    if (episode != null) {
        var title = clean(file.substring(0, episode.range.first))
        if (title.isEmpty()) {
            // "S01E02.mkv" inside "The Bear/Season 1": the show is the nearest folder that is not a season.
            title = parts.dropLast(1).lastOrNull { !SEASON_FOLDER.matches(it.trim()) }?.let(::clean).orEmpty()
        }
        return ParsedName(
            title = title.replace(TRAILING_YEAR, "").trim().ifEmpty { title },
            season = episode.groupValues[1].toInt(),
            episode = episode.groupValues[2].toInt(),
        )
    }

    val tag = RELEASE_TAG.find(file)
    val tagStart = tag?.range?.first ?: Int.MAX_VALUE
    // The release year is the last year before the quality tags; a year at the very start is part of the title.
    val year = YEAR.findAll(file).lastOrNull { it.range.first > 0 && it.range.first < tagStart }
    val cut = minOf(year?.range?.first ?: Int.MAX_VALUE, tagStart, file.length)
    val title = clean(file.substring(0, cut)).ifEmpty { clean(file) }
    return ParsedName(title = title, year = year?.value?.toInt())
}
