package com.wifistream.receiver

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wifistream.protocol.Entry
import com.wifistream.protocol.EntryKind
import com.wifistream.protocol.KeyState
import com.wifistream.protocol.KeyStatus
import com.wifistream.protocol.Listing
import com.wifistream.protocol.ServerInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class SubtitleFile(val url: String, val fileName: String)

class PlayItem(
    val url: String,
    val title: String,
    /** Watch-history key; null for music, which is not tracked. */
    val key: String? = null,
    val startMs: Long = 0,
    val subtitles: List<SubtitleFile> = emptyList(),
    val audio: Boolean = false,
    /** Average bit rate of the file, to tell whether the connection can keep up; 0 when unknown. */
    val bitsPerSecond: Long = 0,
    /** Episode name, shown under the title ("Fishes"). */
    val episodeName: String? = null,
    /** Plot and "2024 · 2h 46m" line for the pause screen. */
    val overview: String = "",
    val meta: String = "",
    /** What the sharing phone read from the file: resolution, HDR type, Dolby Vision profile, audio. */
    val media: com.wifistream.protocol.MediaInfo? = null,
    /** For episodes: the show, so its audio and subtitle choices carry over between episodes. */
    val showKey: String? = null,
    /** What online subtitle search needs: the TMDB id, and season and episode for a show. */
    val tmdbId: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

enum class Tab(val label: String) {
    HOME("Home"), MOVIES("Movies"), SHOWS("Shows"), MUSIC("Music"), FOLDERS("Folders"), SETTINGS("Settings")
}

const val HERO = "hero"

sealed interface Screen {
    data object Connect : Screen

    /** Share this device's folders with other devices (phones only). */
    data object Share : Screen

    /** This phone's TMDB and OpenSubtitles keys, used when it shares. */
    data object Keys : Screen

    /** What this device and its screen can play. */
    data object Capabilities : Screen

    /** Every detail of one file. */
    class Properties(val file: VideoFile) : Screen

    /** Keeps scroll positions and the last focused tile, so Back returns to where you were. */
    class Home : Screen {
        var tab by mutableStateOf(Tab.HOME)
        val homeList = androidx.compose.foundation.lazy.LazyListState()
        val grids = Tab.entries.associateWith { androidx.compose.foundation.lazy.grid.LazyGridState() }
        var lastFocus: String = HERO
    }

    class MovieDetail(val titleKey: String) : Screen

    class ShowDetail(val titleKey: String) : Screen {
        var season by mutableStateOf<Int?>(null)
    }

    class AlbumDetail(val albumKey: String) : Screen

    /** Keeps its listing and focus so coming back from a file or sub-folder lands where you were. */
    class Folder(val root: String, val path: String, val title: String) : Screen {
        var listing: Listing? = null
        var lastFocused: String? = null
    }

    /** [askResume] is false after switching engines: the film simply carries on. */
    class Player(val items: List<PlayItem>, val startIndex: Int, val askResume: Boolean = true) : Screen

    /**
     * The compatibility engine (VLC), for files the main player cannot play. [startMs] overrides the
     * item's own resume point; [reason] says why it is in use.
     */
    class CompatPlayer(val items: List<PlayItem>, val startIndex: Int, val startMs: Long? = null, val reason: String? = null) : Screen
}

class Connection(val client: SenderClient, val info: ServerInfo)

/** How the link to the sharing phone is doing, checked every few seconds. */
enum class LinkState { OK, LOST, DRIVE_MISSING }

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("receiver", Context.MODE_PRIVATE)
    private val discovery = SenderDiscovery(app)
    /** This phone's own keys, for when it shares. Watching never needs them. */
    val keys = ApiKeys.get(app)

    /** Whether the connected phone's keys work; null until it has answered. */
    var remoteKeys by mutableStateOf<KeyStatus?>(null)
        private set

    private fun usable(state: KeyState?) = state != KeyState.MISSING && state != KeyState.REJECTED
    val onlineSubtitles = OpenSubtitles(
        base = { connection?.client?.baseUrl },
        available = { usable(remoteKeys?.openSubtitles) },
        folder = java.io.File(app.filesDir, "subtitles"),
    )
    val capabilities by lazy { DeviceCapabilities(app) }

    fun urlOf(file: VideoFile): String? = connection?.client?.fileUrl(file.root, file.entry.path)
    private val tmdb = Tmdb { connection?.client?.baseUrl }

    /** Wide picture cards on a phone too (TVs and tablets always have them). */
    var wideCards by mutableStateOf(prefs.getBoolean(KEY_WIDE_CARDS, false))
        private set

    fun changeWideCards(on: Boolean) {
        wideCards = on
        prefs.edit().putBoolean(KEY_WIDE_CARDS, on).apply()
    }

    /** Whether the sharing phone is answering, and whether its shared drives are all there. */
    var linkState by mutableStateOf(LinkState.OK)
        private set

    /** True while the library is being re-read or titles looked up. */
    var refreshing by mutableStateOf(false)
        private set

    /** Files the phone is still reading details from. */
    var pendingScans by mutableStateOf(0)
        private set
    private var loadedAt = 0L

    var speedTesting by mutableStateOf(false)
        private set
    var speedMessage by mutableStateOf<String?>(null)
        private set

    /** True when the connected phone is this phone itself. */
    val connectedToSelf: Boolean
        get() = connection?.info?.deviceId?.let { it.isNotEmpty() && it == com.wifistream.sender.Storage(getApplication()).deviceId() } == true

    /** Last result of saving, checking or importing keys, shown on the keys screen. */
    var keyStatus by mutableStateOf<String?>(null)
        private set

    val store = LibraryStore(app, viewModelScope)
    val found = discovery.senders
    val backStack = mutableStateListOf<Screen>(Screen.Connect)

    var connection by mutableStateOf<Connection?>(null)
        private set
    var connecting by mutableStateOf(false)
        private set
    var connectError by mutableStateOf<String?>(null)
        private set
    var library by mutableStateOf(LibraryUi())
        private set

    /** Artwork of the title being looked at; the whole app's backdrop is a soft wash of it. */
    var ambient by mutableStateOf<String?>(null)
        private set

    fun showArt(info: TitleInfo?) {
        tmdbImage(info?.backdrop ?: info?.poster, "w300")?.let { ambient = it }
    }

    private var videos: List<VideoFile> = emptyList()
    private var audio: List<AudioFile> = emptyList()
    private var subtitles: Map<String, List<Entry>> = emptyMap()
    private var refreshJob: Job? = null
    private val seasonsLoading = mutableSetOf<String>()

    private val networkWatch = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            viewModelScope.launch { followPhone() }
        }
    }

    private var thumbKeys: List<String> = emptyList()

    /** Tells the picture cache about the library, just-watched videos first. Only when it changes. */
    private fun feedThumbs() {
        val client = connection?.client ?: return
        val device = connection?.info?.deviceId.orEmpty().ifEmpty { connection?.info?.name.orEmpty() }
        val watched = continueWatching()
        val rest = videos.filter { v -> watched.none { it.key == v.key } }.sortedByDescending { it.entry.modifiedMs }
        val ordered = (watched + rest).filter { (it.entry.media?.durationMs ?: 0) > 0 }
        val keys = ordered.map { it.key }
        if (keys == thumbKeys) return
        thumbKeys = keys
        Thumbs.setLibrary(device, ordered.map { ThumbTarget(it.key, displayTitle(it), client.fileUrl(it.root, it.entry.path), it.entry.media?.durationMs ?: 0) })
    }

    init {
        Thumbs.init(app)
        discovery.start()
        runCatching {
            app.getSystemService(android.net.ConnectivityManager::class.java).registerDefaultNetworkCallback(networkWatch)
        }
        // On a phone's hotspot the sharing phone is the network's gateway, and announcements often
        // do not cross a hotspot. Asking the gateway directly finds it with no typing.
        viewModelScope.launch {
            while (true) {
                if (connection == null) {
                    gatewayAddress()?.let { url ->
                        runCatching { SenderClient(url).info() }.onSuccess { discovery.add(FoundSender(it.name, url)) }
                    }
                }
                delay(6_000)
            }
        }
        // Heartbeat: notice within seconds when the sharing phone or its drive goes away, keep trying
        // to reach it (also at a new address), and refresh the library the moment it is back.
        viewModelScope.launch {
            var misses = 0
            var knownDevice = ""
            var knownRoots = emptySet<String>()
            while (true) {
                delay(3_000)
                val current = connection ?: continue
                val deviceId = current.info.deviceId.ifEmpty { current.info.name }
                if (deviceId != knownDevice) {
                    knownDevice = deviceId
                    knownRoots = current.info.roots.map { it.id }.toSet()
                }
                val info = runCatching { current.client.ping() }.getOrNull()
                if (info == null) {
                    misses++
                    if (misses >= 2 && linkState != LinkState.LOST) {
                        linkState = LinkState.LOST
                        followPhone()
                    }
                    if (misses >= 2 && following?.isActive != true) followPhone()
                    continue
                }
                misses = 0
                val wasLost = linkState == LinkState.LOST
                val wasMissing = linkState == LinkState.DRIVE_MISSING
                val before = current.info.roots.map { it.id }.toSet()
                val now = info.roots.map { it.id }.toSet()
                connection = Connection(current.client, info)
                val driveGone = knownRoots.isNotEmpty() && !now.containsAll(knownRoots)
                linkState = if (driveGone) LinkState.DRIVE_MISSING else LinkState.OK
                Thumbs.holdOff = driveGone
                knownRoots = knownRoots + now
                // While a drive is missing the library stays as it was; once everything is back, re-read.
                if (!driveGone && (wasLost || wasMissing || before != now)) refreshLibrary(recheckKeys = wasLost)
            }
        }
        viewModelScope.launch {
            // Reconnect to the last phone; otherwise take the first one found on the network.
            val last = prefs.getString(KEY_LAST, null)
            if (last != null && tryConnect(last)) return@launch
            val first = withTimeoutOrNull(8_000) { found.first { it.isNotEmpty() }.first() }
            if (first != null && connection == null && !connecting) tryConnect(first.address)
        }
    }

    private fun gatewayAddress(): String? {
        val manager = getApplication<Application>().getSystemService(android.net.ConnectivityManager::class.java)
        val link = manager.getLinkProperties(manager.activeNetwork ?: return null) ?: return null
        val gateway = link.routes.firstOrNull { it.isDefaultRoute && it.gateway is java.net.Inet4Address }?.gateway?.hostAddress
        return gateway?.let { "http://$it:${com.wifistream.protocol.WifiStream.DEFAULT_PORT}" }
    }

    // ---------- connection ----------

    fun connect(address: String) {
        val url = SenderClient.normalizeAddress(address)
        if (url == null) {
            connectError = "That address does not look right. Use the one shown on the phone, like 192.168.1.5"
            return
        }
        viewModelScope.launch {
            connectError = null
            if (!tryConnect(url)) {
                connectError = "Could not reach $url. Check that sharing is on and both devices use the same Wi-Fi."
            }
        }
    }

    private suspend fun tryConnect(url: String): Boolean {
        connecting = true
        return try {
            val client = SenderClient(url)
            val info = client.info()
            connection = Connection(client, info)
            remoteKeys = null
            prefs.edit().putString(KEY_LAST, url).apply()
            backStack.clear()
            backStack.add(Screen.Home())
            library = LibraryUi()
            refreshLibrary()
            true
        } catch (e: Exception) {
            false
        } finally {
            connecting = false
        }
    }

    fun open(screen: Screen) {
        backStack.add(screen)
    }

    fun back() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    // ---------- network ----------

    private var following: Job? = null

    /**
     * After the network changes (for example the TV joins the phone's hotspot), finds the same phone
     * at its new address and carries on there. Tries for about half a minute.
     */
    private fun followPhone() {
        if (connection == null || following?.isActive == true) return
        following = viewModelScope.launch {
            // Keeps looking for as long as the phone is missing.
            while (true) {
                delay(2_000)
                if (relocate()) return@launch
            }
        }
    }

    private suspend fun relocate(): Boolean {
        val current = connection ?: return true
        if (runCatching { current.client.info() }.isSuccess) return true
        val id = current.info.deviceId.ifEmpty { current.info.name }
        val candidates = (listOfNotNull(gatewayAddress()) + found.value.map { it.address }).distinct()
        for (url in candidates) {
            if (url == current.client.baseUrl) continue
            val info = runCatching { SenderClient(url).info() }.getOrNull() ?: continue
            if (info.deviceId.ifEmpty { info.name } != id) continue
            Link.relocate(current.client.baseUrl, url)
            connection = Connection(SenderClient(url), info)
            linkState = LinkState.OK
            prefs.edit().putString(KEY_LAST, url).apply()
            refreshLibrary()
            return true
        }
        return false
    }

    /** Measures how fast files come from the phone and says what that is good for. */
    fun runSpeedTest() {
        val client = connection?.client ?: return
        if (speedTesting) return
        viewModelScope.launch {
            speedTesting = true
            speedMessage = "Testing for about 3 seconds…"
            speedMessage = runCatching { client.speedTest() }.fold(
                onSuccess = { mbps ->
                    Link.lastSpeedMbps = mbps
                    val band = Link.wifiBand(getApplication())
                    "${mbpsLabel(mbps)}. ${Link.verdict(mbps)}" +
                        if (band == "2.4 GHz") " This device is on 2.4 GHz Wi-Fi; 5 GHz is usually several times faster." else ""
                },
                onFailure = { "Could not test: the phone did not answer." },
            )
            speedTesting = false
        }
    }

    // ---------- API keys ----------

    fun showKeyStatus(message: String) {
        keyStatus = message
    }

    fun saveKeys(tmdbKey: String, openSubtitlesKey: String) {
        keys.save(tmdbKey, openSubtitlesKey)
        checkOwnKeys()
    }

    fun importKeys(fileText: String) {
        if (keys.importJson(fileText)) checkOwnKeys() else keyStatus = "That file has no Hassasin's Stream keys in it."
    }

    /** Checks this phone's keys with TMDB and OpenSubtitles, then updates a library that is reading from this phone. */
    fun checkOwnKeys() {
        viewModelScope.launch {
            keyStatus = "Checking the keys…"
            val result = keys.check(force = true)
            keyStatus = "TMDB: ${result.tmdb.label()}  ·  OpenSubtitles: ${result.openSubtitles.label()}"
            if (connectedToSelf) refreshLibrary(rematch = true, recheckKeys = true)
        }
    }

    /** Asks the connected phone whether its keys work. */
    fun checkRemoteKeys() {
        viewModelScope.launch {
            val client = connection?.client ?: return@launch
            val before = remoteKeys
            remoteKeys = before?.copy(tmdb = KeyState.CHECKING, openSubtitles = KeyState.CHECKING)
            remoteKeys = runCatching { client.keyStatus(recheck = true) }.getOrNull()
            if (before?.tmdb != KeyState.ACTIVE && remoteKeys?.tmdb == KeyState.ACTIVE) refreshLibrary(rematch = true)
        }
    }

    /** Called when the app comes back to the screen: picks up files added on the phone meanwhile. */
    fun onResume() {
        if (connection != null && refreshJob?.isActive != true && System.currentTimeMillis() - loadedAt > 60_000) {
            refreshLibrary(recheckKeys = remoteKeys?.tmdb != KeyState.ACTIVE)
        }
    }

    // ---------- library ----------

    /**
     * Re-reads the phone's files. [rematch] also asks TMDB again about titles it did not know, and
     * [recheckKeys] has the phone check its keys again. Starting a refresh while one runs restarts it.
     */
    fun refreshLibrary(rematch: Boolean = false, recheckKeys: Boolean = false) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            refreshing = true
            try {
                val client = connection?.client
                if (client != null && (recheckKeys || remoteKeys == null)) {
                    remoteKeys = runCatching { client.keyStatus(recheck = recheckKeys) }.getOrNull()
                }
                if (rematch) store.clearFailedMatches()
                // The phone scans files in the background; come back for the finished details.
                for (round in 0 until 40) {
                    val pending = loadOnce()
                    pendingScans = pending.coerceAtLeast(0)
                    if (pending <= 0) break
                    delay(if (round < 4) 5_000 else 15_000)
                }
            } finally {
                refreshing = false
                loadedAt = System.currentTimeMillis()
            }
        }
    }

    /** Returns how many files the phone is still scanning, or -1 when it could not be reached. */
    private suspend fun loadOnce(): Int {
        val client = connection?.client ?: return -1
        val info = runCatching { client.info() }.getOrNull()
        if (info == null) {
            library = library.copy(loaded = true, error = "Cannot reach the phone. Is sharing still on?")
            return -1
        }
        connection = Connection(client, info)
        val libraries = coroutineScope {
            info.roots.map { root -> async { runCatching { client.library(root.id) }.getOrNull() } }.awaitAll()
        }.filterNotNull()

        videos = libraries.flatMap { lib -> lib.entries.filter { it.kind == EntryKind.VIDEO }.map { VideoFile(lib.root, it) } }
        audio = libraries.flatMap { lib -> lib.entries.filter { it.kind == EntryKind.AUDIO }.map { AudioFile(lib.root, it) } }
        subtitles = libraries.flatMap { lib -> lib.entries.filter { it.kind == EntryKind.SUBTITLE }.map { lib.root to it } }
            .groupBy({ (root, entry) -> "$root|${entry.path.substringBeforeLast('/', "")}" }, { it.second })
        publish()
        feedThumbs()
        // Every shared storage was read: pictures of videos that are gone can go too.
        if (libraries.size == info.roots.size && info.roots.isNotEmpty() && videos.isNotEmpty()) {
            Thumbs.prune(info.deviceId.ifEmpty { info.name }, videos.map { it.key }.toSet())
        }
        matchTitles()
        return libraries.sumOf { it.pendingScans }
    }

    private suspend fun matchTitles() {
        if (!usable(remoteKeys?.tmdb)) return
        val unknown = videos.filter { it.looksLikeTitle }.groupBy { it.group }.filterKeys { store.match(it) == null }
        coroutineScope {
            unknown.forEach { (group, files) ->
                launch {
                    // A network failure leaves the group unknown, so the next refresh tries again.
                    val hit = runCatching { tmdb.search(files.first().parsed) }.getOrElse { return@launch }
                    if (hit == null) {
                        store.setMatch(group, "")
                    } else {
                        val key = "${hit.first}:${hit.second}"
                        if (store.title(key) == null) {
                            store.putTitle(runCatching { tmdb.details(hit.first, hit.second) }.getOrElse { return@launch })
                        }
                        store.setMatch(group, key)
                    }
                    publish()
                }
            }
        }
    }

    private fun publish() {
        val client = connection?.client ?: return
        val byTitle = videos.groupBy { store.match(it.group).orEmpty() }
        val movies = mutableListOf<MovieItem>()
        val shows = mutableListOf<ShowItem>()
        for ((key, files) in byTitle) {
            val info = store.title(key) ?: continue
            if (info.isMovie) {
                movies += MovieItem(info, files.sortedByDescending { it.entry.sizeBytes })
            } else {
                shows += ShowItem(info, files.filter { it.parsed.isEpisode }.sortedWith(compareBy({ it.parsed.season }, { it.parsed.episode })))
            }
        }
        val matched = (movies.flatMap { it.files } + shows.flatMap { it.episodes }).mapTo(HashSet()) { it.key }
        val others = videos
            .filter { it.key !in matched }
            .filter { it.entry.sizeBytes >= 50L * 1024 * 1024 || (it.entry.media?.durationMs ?: 0) >= 5 * 60_000 }
            .sortedByDescending { it.entry.modifiedMs }

        // An album is the album tag when the files have one, otherwise the folder they sit in.
        val albums = audio
            .groupBy { it.entry.media?.album?.lowercase()?.let { tag -> "album|$tag" } ?: "folder|${it.root}|${it.folder}" }
            .map { (key, tracks) ->
                val sorted = tracks.sortedBy { it.entry.path.lowercase() }
                Album(
                    key = key,
                    title = sorted.first().entry.media?.album ?: sorted.first().folder.substringAfterLast('/').ifEmpty { "Music" },
                    artist = sorted.mapNotNull { it.entry.media?.artist }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key,
                    tracks = sorted,
                    artUrl = client.artUrl(sorted.first().root, sorted.first().entry.path),
                )
            }
            .sortedBy { it.title.lowercase() }

        library = LibraryUi(
            movies = movies.sortedBy { it.info.title.lowercase() },
            shows = shows.filter { it.episodes.isNotEmpty() }.sortedBy { it.info.title.lowercase() },
            others = others,
            albums = albums,
            loaded = true,
        )
    }

    /** Episode names for one season, fetched once and then kept on the device. */
    fun ensureSeason(show: TitleInfo, season: Int) {
        val key = "${show.key}:$season"
        if (!usable(remoteKeys?.tmdb) || store.season(key) != null || !seasonsLoading.add(key)) return
        viewModelScope.launch {
            runCatching { tmdb.season(show.id, season) }.onSuccess { store.putSeason(key, it) }
            seasonsLoading.remove(key)
        }
    }

    fun titleOf(file: VideoFile): TitleInfo? = store.match(file.group)?.takeIf { it.isNotEmpty() }?.let(store::title)

    /** "Dune: Part Two" or "The Bear · S2 E5"; the file name when TMDB does not know it. */
    fun displayTitle(file: VideoFile): String {
        val info = titleOf(file) ?: return file.entry.name.substringBeforeLast('.')
        return if (info.isMovie) info.title else "${info.title} · S${file.parsed.season} E${file.parsed.episode}"
    }

    /** Unfinished videos, most recently watched first. */
    fun continueWatching(): List<VideoFile> {
        val byKey = videos.associateBy { it.key }
        return store.allProgress()
            .filter { (_, p) -> !p.finished && p.positionMs > 30_000 }
            .entries.sortedByDescending { it.value.updatedAt }
            .mapNotNull { byKey[it.key] }
    }

    // ---------- playback ----------

    /** Plays [file]; anything after it in [queue] follows automatically (next episodes). */
    fun playVideo(file: VideoFile, queue: List<VideoFile> = listOf(file)) {
        val client = connection?.client ?: return
        val from = queue.indexOf(file).coerceAtLeast(0)
        // The whole queue goes to the player, so its episode list can jump backwards as well as forwards.
        val items = queue.mapIndexed { index, video ->
            val base = video.entry.name.substringBeforeLast('.')
            val info = titleOf(video)
            val episode = if (info != null && !info.isMovie) {
                store.season("${info.key}:${video.parsed.season}")?.firstOrNull { it.number == video.parsed.episode }
            } else {
                null
            }
            PlayItem(
                url = client.fileUrl(video.root, video.entry.path),
                title = displayTitle(video),
                bitsPerSecond = video.entry.media?.durationMs?.takeIf { it > 0 }?.let { video.entry.sizeBytes * 8_000 / it } ?: 0,
                key = video.key,
                tmdbId = titleOf(video)?.id,
                season = video.parsed.season.takeIf { titleOf(video)?.isMovie == false },
                episode = video.parsed.episode.takeIf { titleOf(video)?.isMovie == false },
                startMs = if (index == from) store.resumePosition(video.key) else 0,
                episodeName = episode?.name?.takeIf { it.isNotBlank() },
                overview = episode?.overview?.takeIf { it.isNotBlank() } ?: info?.overview.orEmpty(),
                meta = info?.let { metaLine(it) }.orEmpty(),
                media = video.entry.media,
                showKey = info?.takeIf { !it.isMovie }?.key,
                subtitles = subtitles["${video.root}|${video.folder}"].orEmpty()
                    .filter { it.name.startsWith(base) }
                    .map { SubtitleFile(client.fileUrl(video.root, it.path), it.name) },
            )
        }
        // Formats the main player is known not to handle go straight to the compatibility engine.
        val ext = file.entry.name.substringAfterLast('.', "").lowercase()
        if (ext in COMPAT_ONLY) {
            open(Screen.CompatPlayer(items, from, reason = "${ext.uppercase()} files play with the compatibility engine."))
        } else {
            open(Screen.Player(items, from))
        }
    }

    /** Swaps the screen on top (a player) for the other engine, keeping the place. */
    fun switchEngine(to: Screen) {
        if (backStack.isNotEmpty()) backStack.removeAt(backStack.lastIndex)
        backStack.add(to)
    }

    fun playAlbum(album: Album, startIndex: Int) {
        val client = connection?.client ?: return
        open(
            Screen.Player(
                items = album.tracks.map { PlayItem(client.fileUrl(it.root, it.entry.path), it.title, audio = true) },
                startIndex = startIndex,
            ),
        )
    }

    override fun onCleared() {
        discovery.stop()
        runCatching {
            getApplication<Application>().getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(networkWatch)
        }
        store.flush()
    }

    private companion object {
        const val KEY_LAST = "last_sender"
        const val KEY_WIDE_CARDS = "wide_cards"

        /** Containers the main player cannot open at all. */
        val COMPAT_ONLY = setOf("wmv", "asf", "rm", "rmvb", "vob", "divx", "ogm")
    }
}
