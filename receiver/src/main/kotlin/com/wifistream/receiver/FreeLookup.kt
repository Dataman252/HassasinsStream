package com.wifistream.receiver

import com.wifistream.protocol.ParsedName
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Posters and plots when TMDB cannot help (no key on the sharing phone, or no match):
 * TVmaze for shows and Wikipedia for films. Neither needs a key. Answers are kept by
 * [LibraryStore] like TMDB's, so each title is looked up once.
 */
object FreeLookup {
    /** A show with its episodes by season, or null when TVmaze does not know it. */
    suspend fun tvmazeShow(name: ParsedName): Pair<TitleInfo, Map<Int, List<EpisodeInfo>>>? {
        val q = URLEncoder.encode(name.title, "UTF-8")
        val o = getJson("https://api.tvmaze.com/singlesearch/shows?q=$q&embed=episodes") as? JsonObject ?: return null
        val id = o.int("id") ?: return null
        val episodes = ((o["_embedded"] as? JsonObject)?.get("episodes") as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { e ->
                val season = e.int("season") ?: return@mapNotNull null
                val number = e.int("number") ?: return@mapNotNull null
                season to EpisodeInfo(
                    number = number,
                    name = e.str("name").orEmpty(),
                    overview = stripHtml(e.str("summary").orEmpty()),
                    still = (e["image"] as? JsonObject)?.str("original") ?: (e["image"] as? JsonObject)?.str("medium"),
                )
            }
            .groupBy({ it.first }, { it.second })
        val image = o["image"] as? JsonObject
        val info = TitleInfo(
            type = "tvmaze",
            id = id,
            title = o.str("name") ?: name.title,
            year = o.str("premiered")?.take(4)?.takeIf { it.length == 4 },
            overview = stripHtml(o.str("summary").orEmpty()),
            poster = image?.str("original") ?: image?.str("medium"),
            // TVmaze has no wide picture in this answer; the first episode still serves as the backdrop.
            backdrop = episodes.values.flatten().firstNotNullOfOrNull { it.still },
            rating = (o["rating"] as? JsonObject)?.dbl("average") ?: 0.0,
            runtimeMin = o.int("averageRuntime") ?: o.int("runtime"),
            genres = (o["genres"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        )
        return info to episodes
    }

    /** A film's summary and poster from Wikipedia, or null when no article matches. */
    suspend fun wikipediaFilm(name: ParsedName): TitleInfo? {
        val query = URLEncoder.encode("${name.title} ${name.year ?: ""} film".trim(), "UTF-8")
        val search = getJson(
            "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$query&srlimit=3&format=json",
        ) as? JsonObject ?: return null
        val hits = ((search["query"] as? JsonObject)?.get("search") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        // Only accept an article whose title starts with the film's name, so a random page is never shown.
        val wanted = normal(name.title)
        val hit = hits.firstOrNull { normal(it.str("title").orEmpty()).startsWith(wanted) } ?: return null
        val pageTitle = hit.str("title") ?: return null
        val summary = getJson(
            "https://en.wikipedia.org/api/rest_v1/page/summary/" + URLEncoder.encode(pageTitle.replace(' ', '_'), "UTF-8").replace("+", "%20"),
        ) as? JsonObject ?: return null
        val extract = summary.str("extract").orEmpty()
        val year = name.year?.toString() ?: Regex("\\b(19|20)\\d{2}\\b").find(extract)?.value
        return TitleInfo(
            type = "wiki",
            id = hit.int("pageid") ?: return null,
            title = pageTitle.replace(Regex("\\s*\\((\\d{4} )?film\\)$"), ""),
            year = year,
            overview = extract,
            poster = (summary["originalimage"] as? JsonObject)?.str("source") ?: (summary["thumbnail"] as? JsonObject)?.str("source"),
        )
    }

    private fun normal(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun stripHtml(s: String) = s.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&quot;", "\"").trim()

    private suspend fun getJson(url: String): JsonElement? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", "HassasinsStream/1.2 (media player app)").build()
        http.newCall(request).execute().use { response ->
            when {
                response.code == 404 -> null
                !response.isSuccessful -> throw IOException("HTTP ${response.code}")
                else -> Json.parseToJsonElement(response.body?.string().orEmpty())
            }
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.dbl(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
}
