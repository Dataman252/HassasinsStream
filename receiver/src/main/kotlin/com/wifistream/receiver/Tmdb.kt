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

/** A movie or show as shown in the library. [type] is "movie" or "tv". */
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
    val isMovie: Boolean get() = type == "movie"
}

@Serializable
data class EpisodeInfo(val number: Int, val name: String, val overview: String = "", val still: String? = null)

fun tmdbImage(path: String?, size: String): String? = path?.let { "https://image.tmdb.org/t/p/$size$it" }

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

    /** Returns ("movie" | "tv", id) of the best match, or null when TMDB knows nothing by that name. */
    suspend fun search(name: ParsedName): Pair<String, Int>? {
        val type = if (name.isEpisode) "tv" else "movie"
        suspend fun query(year: Int?): Int? {
            val params = buildList {
                add("query" to name.title)
                if (year != null) add((if (type == "movie") "year" else "first_air_date_year") to year.toString())
            }
            return get("search/$type", params).arr("results").firstOrNull()?.int("id")
        }
        // A wrong year in a file name should not hide an otherwise good match.
        val id = query(name.year) ?: if (name.year != null) query(null) else null
        return id?.let { type to it }
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
            poster = o.str("poster_path"),
            backdrop = o.str("backdrop_path"),
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
