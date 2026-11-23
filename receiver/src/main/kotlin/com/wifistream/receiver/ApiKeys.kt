package com.wifistream.receiver

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wifistream.protocol.KeyState
import com.wifistream.protocol.KeyStatus
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Name of the file that backs the keys up and brings them back after a reset of the app. */
const val KEY_FILE_NAME = "hassasin-keys.json"

@Serializable
private data class KeyFile(
    val app: String = "Hassasin's Stream",
    @SerialName("tmdb_key") val tmdb: String = "",
    @SerialName("opensubtitles_api_key") val openSubtitles: String = "",
)

/**
 * This phone's TMDB and OpenSubtitles keys. Nothing is built into the app.
 *
 * Only a sharing phone needs keys. A device watching from it never holds them: it asks the
 * sharing phone, which looks things up with its own keys and passes the answers on. So a TV
 * connected to a friend's phone uses that friend's keys, and nothing is left behind afterwards.
 */
class ApiKeys private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("keys", Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    var tmdb by mutableStateOf(prefs.getString(TMDB, "").orEmpty())
        private set
    var openSubtitles by mutableStateOf(prefs.getString(OPEN_SUBTITLES, "").orEmpty())
        private set

    /** Result of the last check; checked on saving and whenever sharing starts. */
    var status by mutableStateOf(blankStatus())
        private set
    private var checkedAt = 0L

    private fun blankStatus() = KeyStatus(
        tmdb = if (tmdb.isBlank()) KeyState.MISSING else KeyState.CHECKING,
        openSubtitles = if (openSubtitles.isBlank()) KeyState.MISSING else KeyState.CHECKING,
    )

    fun save(tmdbKey: String, openSubtitlesKey: String) {
        tmdb = tmdbKey.trim()
        openSubtitles = openSubtitlesKey.trim()
        prefs.edit().putString(TMDB, tmdb).putString(OPEN_SUBTITLES, openSubtitles).apply()
        status = blankStatus()
        checkedAt = 0
    }

    /** A key file chosen on this phone. Returns false if it held no keys. */
    fun importJson(text: String): Boolean {
        val file = runCatching { json.decodeFromString(KeyFile.serializer(), text) }.getOrNull()
            ?.takeIf { it.tmdb.isNotBlank() || it.openSubtitles.isNotBlank() } ?: return false
        save(file.tmdb.ifBlank { tmdb }, file.openSubtitles.ifBlank { openSubtitles })
        return true
    }

    fun exportJson(): String = json.encodeToString(KeyFile.serializer(), KeyFile(tmdb = tmdb, openSubtitles = openSubtitles))

    /** Asks TMDB and OpenSubtitles whether the keys work. A recent answer is reused unless [force] is set. */
    suspend fun check(force: Boolean = false): KeyStatus {
        val fresh = System.currentTimeMillis() - checkedAt < RECHECK_MS
        if (!force && fresh && KeyState.CHECKING !in listOf(status.tmdb, status.openSubtitles) &&
            KeyState.OFFLINE !in listOf(status.tmdb, status.openSubtitles)
        ) return status
        status = blankStatus()
        val result = coroutineScope {
            val t = async { stateOf(tmdb) { tmdbRequest("configuration", emptyList()) } }
            val o = async { stateOf(openSubtitles) { subtitlesRequest("$OPEN_SUBTITLES_API/infos/formats").build() } }
            KeyStatus(t.await(), o.await())
        }
        status = result
        checkedAt = System.currentTimeMillis()
        return result
    }

    private suspend fun stateOf(key: String, request: () -> Request): KeyState {
        if (key.isBlank()) return KeyState.MISSING
        return withContext(Dispatchers.IO) {
            try {
                http.newCall(request()).execute().use { response ->
                    when {
                        response.isSuccessful -> KeyState.ACTIVE
                        response.code == 401 || response.code == 403 -> KeyState.REJECTED
                        else -> KeyState.OFFLINE
                    }
                }
            } catch (e: IOException) {
                KeyState.OFFLINE
            }
        }
    }

    /** A TMDB request carrying this phone's key. TMDB issues a short "API key" and a long "read access token"; either works. */
    fun tmdbRequest(path: String, params: List<Pair<String, String>>): Request {
        val longToken = tmdb.length > 60
        val url = "https://api.themoviedb.org/3/$path".toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
            if (!longToken) addQueryParameter("api_key", tmdb)
        }.build()
        return Request.Builder().url(url)
            .apply { if (longToken) header("Authorization", "Bearer $tmdb") }
            .header("accept", "application/json")
            .build()
    }

    /** An OpenSubtitles request carrying this phone's key. */
    fun subtitlesRequest(url: String): Request.Builder = Request.Builder().url(url)
        .header("Api-Key", openSubtitles)
        .header("User-Agent", "HassasinsStream v1.1")
        .header("Accept", "application/json")

    companion object {
        const val OPEN_SUBTITLES_API = "https://api.opensubtitles.com/api/v1"
        private const val TMDB = "tmdb"
        private const val OPEN_SUBTITLES = "opensubtitles"
        private const val RECHECK_MS = 10 * 60_000L

        val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        @Volatile
        private var instance: ApiKeys? = null

        /** One set of keys per app, shared by the screens and the sharing service. */
        fun get(context: Context): ApiKeys =
            instance ?: synchronized(this) { instance ?: ApiKeys(context.applicationContext).also { instance = it } }
    }
}

/** Status colour: green works, red needs fixing, yellow could not be checked. */
fun KeyState.colour(): androidx.compose.ui.graphics.Color = when (this) {
    KeyState.ACTIVE -> androidx.compose.ui.graphics.Color(if (AppTheme.light) 0xFF15803D else 0xFF4ADE80)
    KeyState.MISSING, KeyState.REJECTED -> androidx.compose.ui.graphics.Color(if (AppTheme.light) 0xFFB91C1C else 0xFFF87171)
    KeyState.OFFLINE -> androidx.compose.ui.graphics.Color(if (AppTheme.light) 0xFFB45309 else 0xFFFBBF24)
    KeyState.CHECKING -> Muted
}

/** Plain words for a key's state. */
fun KeyState.label(): String = when (this) {
    KeyState.MISSING -> "No key"
    KeyState.CHECKING -> "Checking…"
    KeyState.ACTIVE -> "Active"
    KeyState.REJECTED -> "Key not accepted"
    KeyState.OFFLINE -> "Could not check: no internet"
}
