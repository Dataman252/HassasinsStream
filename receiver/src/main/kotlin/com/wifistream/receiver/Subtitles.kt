package com.wifistream.receiver

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

// ---------- cues ----------

class SubCue(val startMs: Long, val endMs: Long, val text: String)

private val TIMING = Regex("(\\d+):(\\d+):(\\d+)[,.](\\d+)\\s*-->\\s*(\\d+):(\\d+):(\\d+)[,.](\\d+)")
private val MARKUP = Regex("<[^>]+>|\\{[^}]*\\}")

/** Reads SRT (and WebVTT with hours) text into timed lines, dropping styling tags. */
fun parseSubtitles(text: String): List<SubCue> {
    val cues = mutableListOf<SubCue>()
    val lines = text.replace("\r", "").removePrefix("﻿").split('\n')
    var i = 0
    while (i < lines.size) {
        val match = TIMING.find(lines[i])
        if (match == null) {
            i++
            continue
        }
        val n = match.groupValues.drop(1).map { it.toLong() }
        fun ms(h: Long, m: Long, s: Long, frac: String) = ((h * 60 + m) * 60 + s) * 1000 + frac.padEnd(3, '0').take(3).toLong()
        val start = ms(n[0], n[1], n[2], match.groupValues[4])
        val end = ms(n[4], n[5], n[6], match.groupValues[8])
        i++
        val body = StringBuilder()
        while (i < lines.size && lines[i].isNotBlank()) {
            if (body.isNotEmpty()) body.append('\n')
            body.append(lines[i].replace(MARKUP, "").trim())
            i++
        }
        if (body.isNotBlank()) cues += SubCue(start, end, body.toString())
    }
    return cues.sortedBy { it.startMs }
}

/** The line to show at [timeMs], or null between lines. */
fun List<SubCue>.at(timeMs: Long): String? {
    var low = 0
    var high = size - 1
    var candidate = -1
    while (low <= high) {
        val mid = (low + high) / 2
        if (this[mid].startMs <= timeMs) {
            candidate = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    // Overlapping lines are rare; checking the latest starter and the one before covers them.
    for (index in candidate downTo maxOf(candidate - 1, 0)) {
        if (index >= 0 && timeMs < this[index].endMs) return this[index].text
    }
    return null
}

/**
 * The line to jump to when swiping through subtitles from [timeMs]: the next one to start,
 * or going back, the one before the line now showing.
 */
fun List<SubCue>.neighbour(timeMs: Long, forward: Boolean): SubCue? =
    if (forward) {
        firstOrNull { it.startMs > timeMs + 200 }
    } else {
        val current = lastOrNull { it.startMs <= timeMs + 200 }
        // More than a second into the current line: go back to its start first, like a music player does.
        if (current != null && timeMs - current.startMs > 1_000) current
        else lastOrNull { it.startMs < (current?.startMs ?: timeMs) - 1 }
    }

// ---------- look ----------

val SUBTITLE_SIZES = listOf("Small" to 18, "Medium" to 24, "Large" to 31, "Extra large" to 40)
val SUBTITLE_COLOURS = listOf("White" to Color.White, "Yellow" to Color(0xFFFFE14D), "Green" to Color(0xFF8CF08C))
val SUBTITLE_BACKGROUNDS = listOf("Outline only", "Dark box")
val SUBTITLE_LANGUAGES = listOf(
    "en", "hi", "ur", "ar", "bn", "ta", "te", "ml", "gu", "pa", "es", "fr", "de", "it", "pt-br", "ru", "tr", "id", "zh-cn", "ja", "ko",
)

fun languageName(code: String): String =
    Locale.forLanguageTag(code).getDisplayName(Locale.ENGLISH).ifBlank { code }

@Composable
fun SubtitleText(text: String, size: Int, colour: Int, background: Int, modifier: Modifier = Modifier) {
    Text(
        text,
        color = SUBTITLE_COLOURS.getOrElse(colour) { SUBTITLE_COLOURS[0] }.second,
        fontSize = SUBTITLE_SIZES.getOrElse(size) { SUBTITLE_SIZES[1] }.second.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        style = TextStyle(shadow = Shadow(Color.Black, Offset(2f, 2f), 6f)),
        modifier = modifier
            .background(if (background == 1) Color(0xA6000000) else Color.Transparent, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 2.dp),
    )
}

// ---------- OpenSubtitles ----------

class SubtitleHit(val fileId: Int, val release: String, val language: String, val downloads: Int, val hearingImpaired: Boolean)

/** A subtitle already on this device for a video: a label for the list, and the file. */
class SavedSubtitle(val label: String, val file: File)

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.number(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

/**
 * Finds and downloads subtitles from opensubtitles.com, and keeps them on the device. The requests
 * go through the connected sharing phone, which uses its own key.
 * [base] is that phone's address; [available] is false when it has no working key.
 */
class OpenSubtitles(private val base: () -> String?, private val available: () -> Boolean, private val folder: File) {
    val enabled: Boolean get() = base() != null && available()

    private fun request(url: String) = Request.Builder().url(url).header("Accept", "application/json")

    private fun endpoint(path: String): okhttp3.HttpUrl.Builder =
        ((base() ?: throw IOException("Not connected to a sharing device")) + path).toHttpUrl().newBuilder()

    suspend fun search(item: PlayItem, language: String): List<SubtitleHit> = withContext(Dispatchers.IO) {
        val url = endpoint(com.wifistream.protocol.WifiStream.Path.SUBTITLE_SEARCH).apply {
            addQueryParameter("languages", language)
            addQueryParameter("order_by", "download_count")
            when {
                item.tmdbId != null && item.season != null && item.episode != null -> {
                    addQueryParameter("parent_tmdb_id", item.tmdbId.toString())
                    addQueryParameter("season_number", item.season.toString())
                    addQueryParameter("episode_number", item.episode.toString())
                }
                item.tmdbId != null -> addQueryParameter("tmdb_id", item.tmdbId.toString())
                // Not matched to TMDB: fall back to searching by the name.
                else -> addQueryParameter("query", item.title)
            }
        }.build().toString()
        http.newCall(request(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException(errorText(response.code))
            val root = Json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
            (root["data"] as? JsonArray).orEmpty().mapNotNull { element ->
                val attributes = (element as? JsonObject)?.get("attributes") as? JsonObject ?: return@mapNotNull null
                val file = (attributes["files"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return@mapNotNull null
                SubtitleHit(
                    fileId = file.number("file_id") ?: return@mapNotNull null,
                    release = attributes.text("release").orEmpty().ifBlank { file.text("file_name").orEmpty() },
                    language = attributes.text("language") ?: language,
                    downloads = attributes.number("download_count") ?: 0,
                    hearingImpaired = (attributes["hearing_impaired"] as? JsonPrimitive)?.booleanOrNull == true,
                )
            }
        }
    }

    /** Downloads [hit] for the video identified by [videoKey] and returns its text. Counts against the daily allowance. */
    suspend fun download(hit: SubtitleHit, videoKey: String): String = withContext(Dispatchers.IO) {
        val url = endpoint(com.wifistream.protocol.WifiStream.Path.SUBTITLE_DOWNLOAD)
            .addQueryParameter("file_id", hit.fileId.toString()).build().toString()
        val text = http.newCall(request(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException(errorText(response.code))
            response.body?.string().orEmpty()
        }
        val safeName = hit.release.replace(Regex("[^A-Za-z0-9 ._-]"), "").take(60).ifBlank { "subtitle" }
        File(folderFor(videoKey).apply { mkdirs() }, "${hit.language}_${hit.fileId}_$safeName.srt").writeText(text)
        text
    }

    /** Subtitles downloaded earlier for this video. */
    fun saved(videoKey: String): List<SavedSubtitle> =
        folderFor(videoKey).listFiles().orEmpty().filter { it.extension == "srt" }.sortedBy { it.name }.map { file ->
            val parts = file.nameWithoutExtension.split('_', limit = 3)
            SavedSubtitle("${languageName(parts[0])}  ·  ${parts.getOrElse(2) { "" }}", file)
        }

    /** Any subtitle text over HTTP: a download link, or a file sitting next to the video on the sharing device. */
    suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).header("User-Agent", "HassasinsStream v1.1").build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val bytes = response.body?.bytes() ?: ByteArray(0)
            val utf8 = bytes.toString(Charsets.UTF_8)
            // Older subtitle files are often in a Western code page rather than UTF-8.
            if (utf8.count { it == '�' } > 3) bytes.toString(Charsets.ISO_8859_1) else utf8
        }
    }

    private fun folderFor(videoKey: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(videoKey.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(folder, hash.take(16))
    }

    private fun errorText(code: Int): String = when (code) {
        401, 403 -> "OpenSubtitles did not accept the sharing phone's key. Check it there: Share this device, API keys."
        424 -> "The sharing phone has no OpenSubtitles key. Add one there: Share this device, API keys."
        502 -> "The sharing phone could not reach OpenSubtitles. Is it online?"
        406, 429 -> "Today's OpenSubtitles download allowance is used up. Try again tomorrow."
        else -> "OpenSubtitles answered with error $code."
    }

    private companion object {
        val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
