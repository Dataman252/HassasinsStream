package com.wifistream.receiver

import android.net.Uri
import com.wifistream.protocol.KeyStatus
import com.wifistream.protocol.Library
import com.wifistream.protocol.Listing
import com.wifistream.protocol.ServerInfo
import com.wifistream.protocol.WifiStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** Talks to one sender. [baseUrl] looks like `http://192.168.1.5:8765`. */
class SenderClient(val baseUrl: String) {

    suspend fun info(): ServerInfo = json.decodeFromString(get(url(WifiStream.Path.INFO)))

    /** A quick "are you there?": gives up after about two seconds. */
    suspend fun ping(): ServerInfo = withContext(Dispatchers.IO) {
        quick.newCall(Request.Builder().url(url(WifiStream.Path.INFO)).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            json.decodeFromString<ServerInfo>(response.body?.string() ?: throw IOException("Empty reply"))
        }
    }

    suspend fun list(root: String, path: String): Listing =
        json.decodeFromString(get(url(WifiStream.Path.LIST, root, path)))

    suspend fun library(root: String): Library = json.decodeFromString(get(url(WifiStream.Path.LIBRARY, root)))

    /** Whether the phone's TMDB and OpenSubtitles keys work. [recheck] asks the services again instead of using the last answer. */
    suspend fun keyStatus(recheck: Boolean): KeyStatus =
        json.decodeFromString(get(baseUrl + WifiStream.Path.KEYS + if (recheck) "?recheck=1" else ""))

    /** Downloads filler from the phone for about three seconds and returns the speed in megabits per second. */
    suspend fun speedTest(): Double = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(baseUrl + WifiStream.Path.SPEED + "?mb=200").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val stream = response.body?.byteStream() ?: throw IOException("Empty reply")
            val buffer = ByteArray(64 * 1024)
            // The first moments include the connection starting up, so timing begins with the first data.
            var read = stream.read(buffer)
            val start = System.nanoTime()
            var bytes = 0L
            while (read >= 0) {
                read = stream.read(buffer)
                if (read > 0) bytes += read
                if (System.nanoTime() - start > 3_000_000_000L) break
            }
            val seconds = (System.nanoTime() - start) / 1e9
            bytes * 8 / seconds / 1e6
        }
    }

    /** Cover image for a music file. */
    fun artUrl(root: String, path: String): String = url(WifiStream.Path.ART, root, path)

    /** Address the player streams from. */
    fun fileUrl(root: String, path: String): String = url(WifiStream.Path.FILE, root, path)

    private fun url(endpoint: String, root: String? = null, path: String? = null): String =
        Uri.parse(baseUrl + endpoint).buildUpon().apply {
            if (root != null) appendQueryParameter("root", root)
            if (path != null) appendQueryParameter("path", path)
        }.build().toString()

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body?.string() ?: throw IOException("Empty reply")
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        private val quick = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()

        // Listing a folder waits for the phone to scan it; the library call walks the whole storage.
        private val http = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

        /** Accepts `192.168.1.5`, `192.168.1.5:8765` or a full `http://` address. */
        fun normalizeAddress(input: String): String? {
            val host = input.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
            if (host.isEmpty() || !Regex("[A-Za-z0-9.\\-\\[\\]:]+").matches(host)) return null
            val hasPort = Regex(":\\d+$").containsMatchIn(host) && !host.endsWith("]")
            return "http://" + if (hasPort) host else "$host:${WifiStream.DEFAULT_PORT}"
        }
    }
}
