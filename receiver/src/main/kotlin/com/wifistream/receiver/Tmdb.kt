package com.wifistream.receiver

import com.wifistream.protocol.ParsedName
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A movie or show as shown in the library. [type] is "movie" or "tv" (TMDB), or, when TMDB has
 * no key or no answer, "tvmaze" (a show from TVmaze) or "wiki" (a film from Wikipedia).
 */
@Serializable
data class TitleInfo(
    val type: String,
    val id: Int,
    val title: String,
    val year: String? = null,
    val overview: String = "",
    val poster: String? = null,
    val backdrop: String? = null,
    val rating: Double = 0.0,
    val runtimeMin: Int? = null,
    val genres: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    /** YouTube video id of the trailer. */
    val trailerKey: String? = null,
) {
    val key: String get() = "$type:$id"
    val isMovie: Boolean get() = type == "movie" || type == "wiki"

    /** True for TMDB titles; their id also works for online subtitle searches. */
    val isTmdb: Boolean get() = type == "movie" || type == "tv"

    /** Where the details came from, for the info screen. */
    val sourceLabel: String get() = when (type) {
        "tvmaze" -> "TVmaze"
        "wiki" -> "Wikipedia"
        else -> "TMDB"
    }
}

@Serializable
data class EpisodeInfo(val number: Int, val name: String, val overview: String = "", val still: String? = null)

/** A TMDB picture at [size]; pictures from the fallback sources are already full addresses. */
fun tmdbImage(path: String?, size: String): String? = path?.let { if (it.startsWith("http")) it else "https://image.tmdb.org/t/p/$size$it" }

private fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
private fun JsonObject.int(key: String): Int? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull
private fun JsonObject.dbl(key: String): Double? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull
private fun JsonObject.arr(key: String): List<JsonObject> =
    (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

/**
 * Looks up posters, plots, cast and trailers on themoviedb.org. The questions go through the
 * connected sharing phone, which adds its own key, so this device needs no key of its own.
 * [base] is the sharing phone's address, or null when not connected.
 */
class Tmdb(private val base: () -> String?) {
    private val gate = Semaphore(4)

    /**
     * Returns ("movie" | "tv", id) of the best match, or null when TMDB knows nothing by that name.
     * Candidates from a few searches are scored by how closely the title and the year agree, so a
     * re-release ("Avengers Endgame: Encore", 2026) is not mistaken for the original film (2019)
     * just because the file name has no colon.
     */
    suspend fun search(name: ParsedName): Pair<String, Int>? {
        val type = if (name.isEpisode) "tv" else "movie"
        val yearKey = if (type == "movie") "year" else "first_air_date_year"
        val dateKey = if (type == "movie") "release_date" else "first_air_date"
        val titleKey = if (type == "movie") "title" else "name"
        suspend fun query(text: String, year: Int?): List<JsonObject> {
            val params = buildList {
                add("query" to text)
                if (year != null) add(yearKey to year.toString())
            }
            return get("search/$type", params).arr("results").take(8)
        }
        val words = name.title.split(' ').filter { it.isNotBlank() }
        val candidates = LinkedHashMap<Int, JsonObject>()
        fun addAll(list: List<JsonObject>) = list.forEach { o ->
            o.int("id")?.let { id ->
                candidates.putIfAbsent(id, o)
                // Some new entries show artwork in search results before their own page has any.
                o.str("poster_path")?.let { searchPosters.putIfAbsent(id, it) }
                o.str("backdrop_path")?.let { searchBackdrops.putIfAbsent(id, it) }
            }
        }
        addAll(query(name.title, name.year))
        if (name.year != null) {
            // Punctuation in the real title (a colon, a dash) can hide it from a search with the year:
            // try the shorter name with the year too, then the full name without it.
            if (words.size > 2) addAll(query(words.dropLast(1).joinToString(" "), name.year))
            addAll(query(name.title, null))
        }
        if (candidates.isEmpty()) return null
        val wanted = normalTitle(name.title)
        val best = candidates.values.withIndex().maxByOrNull { (rank, o) ->
            val title = normalTitle(o.str(titleKey).orEmpty())
            val original = normalTitle(o.str(if (type == "movie") "original_title" else "original_name").orEmpty())
            val year = o.str(dateKey)?.take(4)?.toIntOrNull()
            var score = 0.0
            if (title == wanted || original == wanted) score += 6
            else if (title.startsWith(wanted) || wanted.startsWith(title)) score += 2
            val fileYear = name.year
            if (fileYear != null && year != null) {
                score += when (kotlin.math.abs(year - fileYear)) {
                    0 -> 5.0
                    1 -> 3.0
                    else -> -2.0
                }
            }
            // TMDB's own order breaks ties.
            score - rank * 0.05
        }?.value ?: return null
        return best.int("id")?.let { type to it }
    }

    private val searchPosters = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private val searchBackdrops = java.util.concurrent.ConcurrentHashMap<Int, String>()

    private fun normalTitle(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    /** Up to [limit] titles for a typed search, for choosing the right one by hand. */
    suspend fun searchMany(query: String, type: String, limit: Int = 6): List<TitleInfo> =
        get("search/$type", listOf("query" to query)).arr("results").take(limit).mapNotNull { o ->
            val movie = type == "movie"
            TitleInfo(
                type = type,
                id = o.int("id") ?: return@mapNotNull null,
                title = o.str(if (movie) "title" else "name").orEmpty(),
                year = o.str(if (movie) "release_date" else "first_air_date")?.take(4)?.takeIf { it.length == 4 },
                overview = o.str("overview").orEmpty(),
                poster = o.str("poster_path"),
                backdrop = o.str("backdrop_path"),
                rating = o.dbl("vote_average") ?: 0.0,
            )
        }

    suspend fun details(type: String, id: Int): TitleInfo {
        val o = get("$type/$id", listOf("append_to_response" to "videos,credits"))
        val movie = type == "movie"
        val videos = (o["videos"] as? JsonObject)?.arr("results").orEmpty().filter { it.str("site") == "YouTube" }
        val trailer = videos.firstOrNull { it.str("type") == "Trailer" && it.str("official") == "true" }
            ?: videos.firstOrNull { it.str("type") == "Trailer" }
            ?: videos.firstOrNull { it.str("type") == "Teaser" }
        return TitleInfo(
            type = type,
            id = id,
            title = o.str(if (movie) "title" else "name").orEmpty(),
            year = o.str(if (movie) "release_date" else "first_air_date")?.take(4)?.takeIf { it.length == 4 },
            overview = o.str("overview").orEmpty(),
            poster = o.str("poster_path") ?: searchPosters[id],
            backdrop = o.str("backdrop_path") ?: searchBackdrops[id],
            rating = o.dbl("vote_average") ?: 0.0,
            runtimeMin = if (movie) o.int("runtime") else (o["episode_run_time"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.intOrNull,
            genres = o.arr("genres").mapNotNull { it.str("name") },
            cast = (o["credits"] as? JsonObject)?.arr("cast").orEmpty().take(6).mapNotNull { it.str("name") },
            trailerKey = trailer?.str("key"),
        )
    }

    suspend fun season(showId: Int, season: Int): List<EpisodeInfo> =
        get("tv/$showId/season/$season", emptyList()).arr("episodes").mapNotNull { e ->
            EpisodeInfo(
                number = e.int("episode_number") ?: return@mapNotNull null,
                name = e.str("name").orEmpty(),
                overview = e.str("overview").orEmpty(),
                still = e.str("still_path"),
            )
        }

    private suspend fun get(path: String, params: List<Pair<String, String>>): JsonObject = gate.withPermit {
        withContext(Dispatchers.IO) {
            val address = base() ?: throw IOException("Not connected")
            val url = (address + com.wifistream.protocol.WifiStream.Path.TMDB).toHttpUrl().newBuilder()
                .addQueryParameter("p", path)
                .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
                .build()
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("TMDB HTTP ${response.code}")
                Json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
            }
        }
    }

    private companion object {
        val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
