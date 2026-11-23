package com.wifistream.sender

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.wifistream.protocol.Entry
import com.wifistream.receiver.ApiKeys
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.wifistream.protocol.EntryKind
import com.wifistream.protocol.Library
import com.wifistream.protocol.Listing
import com.wifistream.protocol.ServerInfo
import com.wifistream.protocol.WifiStream
import com.wifistream.protocol.guessMediaInfo
import com.wifistream.protocol.kindForFileName
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingCall
import io.ktor.server.response.respondFile
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/** The HTTP server the receiver talks to: storage list, folder listings and file bytes with seeking. */
class FileServer(
    private val deviceName: String,
    private val storage: Storage,
    private val scanner: MediaScanner,
    private val keys: ApiKeys,
    /** Where seek-bar pictures go when a shared folder cannot be written to (the app's permanent storage). */
    private val fallbackPreviewDir: File,
) {
    private var server: EmbeddedServer<*, *>? = null

    /** Throws if the port is already taken. */
    fun start(port: Int) {
        server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
            install(PartialContent)
            install(AutoHeadResponse)
            routing {
                get(WifiStream.Path.INFO) {
                    val health = ShareState.status.value
                    call.respond(
                        ServerInfo(
                            name = deviceName,
                            deviceId = storage.deviceId(),
                            roots = storage.roots().map { it.info },
                            libraryStamp = ShareState.libraryStamp,
                            temperatureC = health.temperatureC,
                            previewsPaused = health.previewsPaused,
                        ),
                    )
                }
                get(WifiStream.Path.LIST) {
                    val listing = listing(
                        rootId = call.request.queryParameters["root"].orEmpty(),
                        path = call.request.queryParameters["path"].orEmpty(),
                    )
                    if (listing == null) call.respond(HttpStatusCode.NotFound) else call.respond(listing)
                }
                get(WifiStream.Path.LIBRARY) {
                    val library = library(call.request.queryParameters["root"].orEmpty())
                    if (library == null) call.respond(HttpStatusCode.NotFound) else call.respond(library)
                }
                get(WifiStream.Path.ART) {
                    val root = storage.root(call.request.queryParameters["root"].orEmpty())
                    val file = root?.let { storage.resolve(it, call.request.queryParameters["path"].orEmpty()) }
                    val embedded = file?.takeIf { it.isFile }?.let { embeddedArt(it) }
                    val cover = file?.parentFile?.let { dir -> COVER_NAMES.map { File(dir, it) }.firstOrNull { it.isFile } }
                    when {
                        embedded != null -> call.respondBytes(embedded, ContentType.Image.JPEG)
                        cover != null -> call.respondFile(cover)
                        else -> call.respond(HttpStatusCode.NotFound)
                    }
                }
                get(WifiStream.Path.FRAME) {
                    val root = storage.root(call.request.queryParameters["root"].orEmpty())
                    val file = root?.let { storage.resolve(it, call.request.queryParameters["path"].orEmpty()) }
                    val at = call.request.queryParameters["t"]?.toLongOrNull() ?: 0
                    pace = call.request.queryParameters["pace"]?.toIntOrNull()?.coerceIn(1, 4) ?: pace
                    val picture = file?.takeIf { it.isFile }?.let { frame(root, it, at) }
                    if (picture == null) call.respond(HttpStatusCode.NotFound) else call.respondBytes(picture, ContentType.Image.JPEG)
                }
                get(WifiStream.Path.KEYS) {
                    call.respond(keys.check(force = call.request.queryParameters["recheck"] == "1"))
                }
                // Lookups for watching devices, made with this phone's keys so they never need their own.
                get(WifiStream.Path.TMDB) {
                    val path = call.request.queryParameters["p"].orEmpty()
                    if (!TMDB_PATH.matches(path)) return@get call.respond(HttpStatusCode.BadRequest)
                    if (keys.tmdb.isBlank()) return@get call.respondText("No TMDB key on this phone", status = HttpStatusCode.FailedDependency)
                    val params = call.request.queryParameters.entries()
                        .filter { it.key != "p" }
                        .flatMap { (k, values) -> values.map { k to it } }
                    forward(call, keys.tmdbRequest(path, params))
                }
                get(WifiStream.Path.SUBTITLE_SEARCH) {
                    if (keys.openSubtitles.isBlank()) {
                        return@get call.respondText("No OpenSubtitles key on this phone", status = HttpStatusCode.FailedDependency)
                    }
                    val url = okhttp3.HttpUrl.Builder().scheme("https").host("api.opensubtitles.com").addPathSegments("api/v1/subtitles").apply {
                        call.request.queryParameters.entries().forEach { (k, values) -> values.forEach { addQueryParameter(k, it) } }
                    }.build()
                    forward(call, keys.subtitlesRequest(url.toString()).build())
                }
                get(WifiStream.Path.SUBTITLE_DOWNLOAD) {
                    if (keys.openSubtitles.isBlank()) {
                        return@get call.respondText("No OpenSubtitles key on this phone", status = HttpStatusCode.FailedDependency)
                    }
                    val id = call.request.queryParameters["file_id"]?.toIntOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val (code, text) = downloadSubtitle(id)
                    call.respondText(text, ContentType.Text.Plain.withParameter("charset", "utf-8"), HttpStatusCode.fromValue(code))
                }
                get(WifiStream.Path.SPEED) {
                    val megabytes = (call.request.queryParameters["mb"]?.toIntOrNull() ?: 20).coerceIn(1, 200)
                    val chunk = ByteArray(64 * 1024)
                    // Timed here as well, so this phone can show the result next to its hotspot switch.
                    val start = System.nanoTime()
                    var sent = 0L
                    call.respondOutputStream(ContentType.Application.OctetStream) {
                        try {
                            repeat(megabytes * 16) {
                                write(chunk)
                                sent += chunk.size
                            }
                        } catch (e: java.io.IOException) {
                            // The watching device stops reading after a few seconds.
                        }
                    }
                    val seconds = (System.nanoTime() - start) / 1e9
                    if (sent > 4L * 1024 * 1024 && seconds > 0.5) {
                        val mbps = sent * 8 / seconds / 1e6
                        ShareState.status.update { it.copy(lastSpeedMbps = mbps) }
                    }
                }
                get(WifiStream.Path.FILE) {
                    val root = storage.root(call.request.queryParameters["root"].orEmpty())
                    val file = root?.let { storage.resolve(it, call.request.queryParameters["path"].orEmpty()) }
                    if (file == null || !file.isFile) call.respond(HttpStatusCode.NotFound) else call.respondFile(file)
                }
            }
        }.also { it.start(wait = false) }
    }

    /** Passes another service's answer, and its status code, straight back to the watching device. */
    private suspend fun forward(call: RoutingCall, request: okhttp3.Request) {
        val (code, body) = withContext(Dispatchers.IO) {
            try {
                ApiKeys.http.newCall(request).execute().use { it.code to (it.body?.bytes() ?: ByteArray(0)) }
            } catch (e: java.io.IOException) {
                HttpStatusCode.BadGateway.value to ByteArray(0)
            }
        }
        call.respondBytes(body, ContentType.Application.Json, HttpStatusCode.fromValue(code))
    }

    /** Asks OpenSubtitles for a download link, then fetches the file. Returns (status code, text or reason). */
    private suspend fun downloadSubtitle(fileId: Int): Pair<Int, String> = withContext(Dispatchers.IO) {
        try {
            val body = """{"file_id":$fileId}""".toRequestBody("application/json".toMediaType())
            val link = ApiKeys.http.newCall(
                keys.subtitlesRequest("${ApiKeys.OPEN_SUBTITLES_API}/download").post(body).header("Content-Type", "application/json").build(),
            ).execute().use { response ->
                if (!response.isSuccessful) return@withContext response.code to "OpenSubtitles error ${response.code}"
                Json.parseToJsonElement(response.body?.string().orEmpty()).let { it as? kotlinx.serialization.json.JsonObject }
                    ?.get("link")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            } ?: return@withContext 502 to "OpenSubtitles sent no download link"
            ApiKeys.http.newCall(okhttp3.Request.Builder().url(link).header("User-Agent", "HassasinsStream v1.1").build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext response.code to "Download failed"
                val bytes = response.body?.bytes() ?: ByteArray(0)
                val utf8 = bytes.toString(Charsets.UTF_8)
                // Older subtitle files are often in a Western code page rather than UTF-8.
                200 to if (utf8.count { it == '�' } > 3) bytes.toString(Charsets.ISO_8859_1) else utf8
            }
        } catch (e: java.io.IOException) {
            502 to "This phone could not reach OpenSubtitles"
        }
    }

    /** Tidies the pictures shortly after sharing starts, then every half hour. */
    fun startPreviewTidy() {
        frameScope.launch {
            kotlinx.coroutines.delay(20_000)
            while (true) {
                runCatching { prunePreviews() }
                kotlinx.coroutines.delay(30 * 60_000L)
            }
        }
    }

    fun stop() {
        frameScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        synchronized(readers) {
            readers.values.flatten().forEach { runCatching { it.retriever.release() } }
            readers.clear()
        }
        server?.stop(gracePeriodMillis = 300, timeoutMillis = 1_000)
        server = null
    }

    private suspend fun listing(rootId: String, path: String): Listing? {
        val root = storage.root(rootId) ?: return null
        val dir = storage.resolve(root, path)?.takeIf { it.isDirectory } ?: return null
        val atRoot = dir == root.dir.canonicalFile
        val files = dir.listFiles().orEmpty()
            .filter { !it.name.startsWith(".") }
            // The app-private "Android" folder holds no user media and is slow to walk.
            .filter { !(atRoot && root.info.id == Storage.INTERNAL_ID && it.name == "Android") }
            .sortedWith(compareBy<File> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })

        val kinds = files.associateWith { if (it.isDirectory) EntryKind.FOLDER else kindForFileName(it.name) }
        val scans = files
            .filter { (kinds[it] == EntryKind.VIDEO || kinds[it] == EntryKind.AUDIO) && scanner.cached(it) == null }
            .map { scanner.scanAsync(it, kinds.getValue(it)) }
        // Wait a little for fresh scans; anything slower is answered with a file-name guess
        // now and finishes in the background for the next request.
        withTimeoutOrNull(SCAN_WAIT_MS) { scans.joinAll() }

        val base = root.dir.canonicalFile
        return Listing(
            root = root.info.id,
            path = dir.relativeTo(base).invariantSeparatorsPath,
            entries = files.map { file ->
                val kind = kinds.getValue(file)
                Entry(
                    name = file.name,
                    path = file.relativeTo(base).invariantSeparatorsPath,
                    kind = kind,
                    sizeBytes = if (file.isFile) file.length() else 0,
                    modifiedMs = file.lastModified(),
                    media = when (kind) {
                        EntryKind.VIDEO -> scanner.cached(file) ?: guessMediaInfo(file.name)
                        EntryKind.AUDIO -> scanner.cached(file)
                        else -> null
                    },
                )
            },
        )
    }

    /** Every video, music and subtitle file on one storage, for the receiver's library view. */
    private suspend fun library(rootId: String): Library? = withContext(Dispatchers.IO) {
        val root = storage.root(rootId) ?: return@withContext null
        val base = root.dir.canonicalFile
        val skipAtTop = if (root.info.id == Storage.INTERNAL_ID) SYSTEM_FOLDERS else emptySet()
        val files = base.walkTopDown()
            .maxDepth(12)
            .onEnter { dir ->
                dir == base || (!dir.name.startsWith(".") && !(dir.parentFile == base && dir.name in skipAtTop))
            }
            .filter { it.isFile && !it.name.startsWith(".") }
            .map { it to kindForFileName(it.name) }
            .filter { (_, kind) -> kind == EntryKind.VIDEO || kind == EntryKind.AUDIO || kind == EntryKind.SUBTITLE }
            .toList()

        // Videos first: their badges matter most, and music tags are quick to read afterwards.
        val unscanned = files
            .filter { (file, kind) -> kind != EntryKind.SUBTITLE && scanner.cached(file) == null }
            .sortedBy { (_, kind) -> kind != EntryKind.VIDEO }
        unscanned.forEach { (file, kind) -> scanner.scanAsync(file, kind) }

        Library(
            root = root.info.id,
            pendingScans = unscanned.size,
            entries = files.map { (file, kind) ->
                Entry(
                    name = file.name,
                    path = file.relativeTo(base).invariantSeparatorsPath,
                    kind = kind,
                    sizeBytes = file.length(),
                    modifiedMs = file.lastModified(),
                    media = when (kind) {
                        EntryKind.VIDEO -> scanner.cached(file) ?: guessMediaInfo(file.name)
                        EntryKind.AUDIO -> scanner.cached(file)
                        else -> null
                    },
                )
            },
        )
    }

    // ---------- seek-bar thumbnails ----------
    //
    // Opening a large film file is the slow part, so the file stays open (one reader per film,
    // the last two kept). Thumbnails are saved to the cache folder, and a background job prepares
    // them through the whole film once someone starts watching: every 30 s first, then every 10 s.
    // Live requests always go first.

    private class Reader(val retriever: MediaMetadataRetriever, val lock: kotlinx.coroutines.sync.Mutex, val durationMs: Long)

    // Film path -> its open readers. Several readers let "Fast" and "Super fast" decode in parallel.
    private val readers = object : LinkedHashMap<String, MutableList<Reader>>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableList<Reader>>?): Boolean {
            if (size > 2 && eldest != null) {
                eldest.value.forEach { runCatching { it.retriever.release() } }
                return true
            }
            return false
        }
    }

    /** How many readers the watching device asked for (1 = gentle). */
    @Volatile private var pace = 1
    private val recentFrames = object : LinkedHashMap<String, ByteArray>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > 400
    }
    private val frameScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val preparing = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val liveRequests = java.util.concurrent.atomic.AtomicInteger(0)

    private fun open(file: File): Reader? = runCatching {
        val r = MediaMetadataRetriever()
        r.setDataSource(file.absolutePath)
        val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        Reader(r, kotlinx.coroutines.sync.Mutex(), duration)
    }.getOrNull()

    /** The first reader of a film (opened if needed). */
    private fun reader(file: File): Reader? = synchronized(readers) {
        readers[file.absolutePath]?.firstOrNull() ?: open(file)?.also { readers[file.absolutePath] = mutableListOf(it) }
    }

    /** A free reader of the film, opening another one (up to [pace]) when all are busy. */
    private fun freeReader(file: File): Reader? = synchronized(readers) {
        val list = readers[file.absolutePath] ?: return@synchronized reader(file)
        list.firstOrNull { !it.lock.isLocked }
            ?: (if (list.size < pace.coerceIn(1, 3)) open(file)?.also { list += it } else null)
            ?: list.first()
    }

    // ---------- where seek-bar pictures are kept ----------
    // Beside each film: one file per film, "<film's folder>/.hassasin/<film name>.pack". The pictures
    // travel with the film (to another folder, another drive, another phone) and survive clearing the
    // app's cache or reinstalling it. Only where the film's folder cannot be written to do they go in
    // the app's own permanent storage instead.

    /** "size|date" of a film: a pack made for a different version of the file is thrown away. */
    private fun signature(file: File) = "${file.length()}|${file.lastModified()}"

    /** Film folders that could not be written to, so they are not tried for every picture. */
    private val readOnlyDirs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun packFile(root: Storage.Root, file: File): File {
        val folder = file.parentFile
        if (folder != null && folder.absolutePath !in readOnlyDirs) {
            val hidden = File(folder, HIDDEN_DIR)
            val writable = hidden.isDirectory || runCatching {
                hidden.mkdirs()
                // Keeps the gallery and music apps out of it.
                File(hidden, ".nomedia").createNewFile()
                hidden.isDirectory
            }.getOrDefault(false)
            if (writable) return File(hidden, file.name + ".pack")
            readOnlyDirs += folder.absolutePath
        }
        val id = java.security.MessageDigest.getInstance("SHA-1")
            .digest(file.absolutePath.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(20)
        return File(File(fallbackPreviewDir, root.info.id), "$id.pack")
    }

    /** Recently used packs, kept open. */
    private val packs = object : LinkedHashMap<String, PreviewPack>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PreviewPack>) = size > 12
    }

    private fun pack(root: Storage.Root, file: File): PreviewPack {
        val target = packFile(root, file)
        val sign = signature(file)
        return synchronized(packs) {
            packs[target.absolutePath]?.takeIf { it.film == sign }
                ?: PreviewPack.open(target, sign).also { packs[target.absolutePath] = it }
        }
    }

    /**
     * Removes the pictures of films that were deleted, renamed or replaced. Only shared folders that
     * are there right now are tidied, so an unplugged drive never loses its pictures.
     */
    fun prunePreviews() {
        for (root in storage.roots()) {
            if (!root.dir.isDirectory) continue
            // An earlier layout kept everything in one folder at the top of the share.
            File(root.dir, "$HIDDEN_DIR/previews").takeIf { it.isDirectory }?.deleteRecursively()
            root.dir.walkTopDown()
                .onEnter { it == root.dir || it.name == HIDDEN_DIR || !it.name.startsWith(".") }
                .filter { it.isDirectory && it.name == HIDDEN_DIR }
                .toList()
                .forEach { hidden ->
                    val folder = hidden.parentFile ?: return@forEach
                    hidden.listFiles()?.filter { it.isFile && it.name.endsWith(".pack") }?.forEach { packed ->
                        val film = File(folder, packed.name.removeSuffix(".pack"))
                        val keep = film.isFile && PreviewPack.filmOf(packed) == signature(film)
                        if (!keep) {
                            synchronized(packs) { packs.remove(packed.absolutePath) }
                            packed.delete()
                        }
                    }
                    // Nothing left but the .nomedia marker: remove the hidden folder too.
                    if (hidden.listFiles()?.none { it.name.endsWith(".pack") } == true) hidden.deleteRecursively()
                }
        }
    }

    /** A small JPEG of the picture near [atMs] (rounded to 10 seconds). */
    private suspend fun frame(root: Storage.Root, file: File, atMs: Long): ByteArray? {
        val bucket = atMs / 10_000 * 10_000
        val key = "${file.absolutePath}|$bucket"
        synchronized(recentFrames) { recentFrames[key] }?.let { return it }
        val saved = withContext(Dispatchers.IO) { runCatching { pack(root, file).read(bucket) }.getOrNull() }
        if (saved != null) {
            return saved.also { bytes ->
                synchronized(recentFrames) { recentFrames[key] = bytes }
                prepareAll(root, file)
            }
        }
        liveRequests.incrementAndGet()
        try {
            return decode(root, file, bucket)?.also { prepareAll(root, file) }
        } finally {
            liveRequests.decrementAndGet()
        }
    }

    private suspend fun decode(root: Storage.Root, file: File, bucket: Long): ByteArray? = withContext(Dispatchers.IO) {
        val r = freeReader(file) ?: return@withContext null
        val bytes = r.lock.withLock {
            runCatching {
                val bitmap = r.retriever.getScaledFrameAtTime(bucket * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
                    ?: return@runCatching null
                java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 70, it) }.toByteArray().also { bitmap.recycle() }
            }.getOrNull()
        } ?: return@withContext null
        synchronized(recentFrames) { recentFrames["${file.absolutePath}|$bucket"] = bytes }
        runCatching { pack(root, file).write(bucket, bytes) }
        bytes
    }

    /** Prepares every thumbnail of [file] in the background, once per sharing session. */
    private fun prepareAll(root: Storage.Root, file: File) {
        if (!preparing.add(file.absolutePath)) return
        frameScope.launch {
            val duration = reader(file)?.durationMs ?: return@launch
            if (duration <= 0) return@launch
            val coarse = (0 until duration step 30_000).toList()
            val fine = (0 until duration step 10_000).filter { it % 30_000 != 0L }
            for (bucket in coarse + fine) {
                // Someone is dragging the seek bar: let their request go first.
                while (liveRequests.get() > 0) kotlinx.coroutines.delay(150)
                // The phone is warm: wait until it cools down (decoding frames is what heats it).
                while (ShareState.status.value.previewsPaused) kotlinx.coroutines.delay(10_000)
                // The drive was unplugged or the file deleted: stop, and allow a fresh start later.
                if (!file.isFile) {
                    preparing.remove(file.absolutePath)
                    return@launch
                }
                if (pack(root, file).has(bucket)) continue
                decode(root, file, bucket)
                // Gentle on the drive and the battery, unless the watching device asked for speed.
                // Spread out so the phone stays cool while it also serves the film; faster only when asked for.
                kotlinx.coroutines.delay(
                    when {
                        pace >= 3 -> 60L
                        pace == 2 -> 250L
                        ShareState.status.value.temperatureC >= 37f -> 1_500L
                        else -> 600L
                    },
                )
            }
        }
    }

    private suspend fun embeddedArt(file: File): ByteArray? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.embeddedPicture
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private companion object {
        const val SCAN_WAIT_MS = 6_000L

        /** The app's hidden folder inside each shared folder. */
        const val HIDDEN_DIR = ".hassasin"

        /** Only TMDB's read-only lookups may be asked for through this phone. */
        val TMDB_PATH = Regex("(configuration|search/(movie|tv)|movie/\\d+|tv/\\d+(/season/\\d+)?)")

        /** Top-level phone folders that never hold a media library. */
        val SYSTEM_FOLDERS = setOf("Android", "Alarms", "Notifications", "Ringtones", "Recordings")
        val COVER_NAMES = listOf("cover.jpg", "folder.jpg", "front.jpg", "cover.png", "folder.png")
    }
}
