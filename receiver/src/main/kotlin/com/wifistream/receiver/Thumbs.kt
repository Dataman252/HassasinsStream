package com.wifistream.receiver

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

/** How hard preview caching works: pictures at once, pause between them, and a hint to the sharing phone. */
enum class PreviewSpeed(val label: String, val workers: Int, val pauseMs: Long, val hint: String) {
    GENTLE("Gentle", 1, 350, "Safest while the drive runs off the phone's battery."),
    FAST("Fast", 2, 0, "About 3× quicker. Best with the drive on a powered hub."),
    SUPER_FAST("Super fast", 4, 0, "About 5–6× quicker. Works the phone and drive hardest: use a powered hub."),
}

/** One video whose seek-bar pictures can be cached. */
class ThumbTarget(val key: String, val title: String, val fileUrl: String, val durationMs: Long)

/** How far caching has got, for the progress bar at the top of the screen. */
class ThumbProgress(val done: Int, val total: Int, val title: String) {
    val fraction: Float get() = if (total > 0) done.toFloat() / total else 0f
}

/**
 * Seek-bar pictures saved on this device, like the "trickplay" files of Netflix, Plex and Jellyfin:
 * one small picture every 10 seconds of every video. A background job fills the cache: the video
 * playing first, then the ones just watched, then the rest. Pictures of videos that are no longer
 * shared are removed.
 */
object Thumbs {
    const val STEP_MS = 10_000L

    private lateinit var root: File
    private lateinit var prefs: android.content.SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private var job: Job? = null

    var speed by mutableStateOf(PreviewSpeed.GENTLE)
        private set

    /** Time between pictures: 10, 20 or 30 seconds. */
    var stepMs by mutableStateOf(STEP_MS)
        private set

    fun changeSpeed(new: PreviewSpeed) {
        speed = new
        prefs.edit().putString("speed", new.name).apply()
        restart()
    }

    fun changeStep(ms: Long) {
        stepMs = ms
        prefs.edit().putLong("step", ms).apply()
        version++
        restart()
    }

    /** Cache pictures for the whole library in the background. */
    var enabled by mutableStateOf(true)
        private set

    /** Null when there is nothing left to do (the progress bar hides). */
    var progress by mutableStateOf<ThumbProgress?>(null)
        private set

    /** Bumped whenever a picture is saved, so the seek bar can redraw its "cached" band. */
    var version by mutableStateOf(0)
        private set

    /** Bytes used on this device. */
    var sizeBytes by mutableStateOf(0L)
        private set

    @Volatile private var device = ""
    @Volatile private var library: List<ThumbTarget> = emptyList()
    @Volatile private var playing: ThumbTarget? = null
    @Volatile private var forceAll = false

    /** True while the player is waiting for data: picture downloads step aside. */
    @Volatile var holdOff = false

    fun init(context: Context) {
        if (::root.isInitialized) return
        root = File(context.filesDir, "thumbs")
        prefs = context.getSharedPreferences("thumbs", Context.MODE_PRIVATE)
        enabled = prefs.getBoolean("enabled", true)
        speed = PreviewSpeed.entries.firstOrNull { it.name == prefs.getString("speed", null) } ?: PreviewSpeed.GENTLE
        stepMs = prefs.getLong("step", STEP_MS).takeIf { it in listOf(10_000L, 20_000L, 30_000L) } ?: STEP_MS
        scope.launch { sizeBytes = measure() }
    }

    private fun hash(text: String) = MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    private fun deviceDir() = File(root, hash(device.ifEmpty { "unknown" }))
    private fun folder(key: String) = File(deviceDir(), hash(key))

    /** The saved picture for [key] near [ms], or null. */
    fun local(key: String?, ms: Long): File? {
        if (key == null || !::root.isInitialized) return null
        val dir = folder(key)
        val t = ms.coerceAtLeast(0)
        return File(dir, "${t / STEP_MS * STEP_MS}.jpg").takeIf { it.isFile }
            ?: File(dir, "${t / stepMs * stepMs}.jpg").takeIf { it.isFile }
    }

    /** Which 10-second steps of a video have a saved picture, for the seek bar's lighter band. */
    fun coverage(key: String?, durationMs: Long): BooleanArray {
        if (key == null || durationMs <= 0 || !::root.isInitialized) return BooleanArray(0)
        val names = folder(key).list()?.toHashSet().orEmpty()
        val step = stepMs
        return BooleanArray(((durationMs + step - 1) / step).toInt()) { i -> "${i * step}.jpg" in names }
    }

    /** The connected sharing phone and its videos, in priority order (just watched first). */
    fun setLibrary(deviceId: String, targets: List<ThumbTarget>) {
        device = deviceId
        library = targets
        restart()
    }

    /** The video now playing jumps the queue; while it plays, only it is cached. */
    fun setPlaying(target: ThumbTarget?) {
        playing = target
        restart()
    }

    fun changeEnabled(on: Boolean) {
        enabled = on
        prefs.edit().putBoolean("enabled", on).apply()
        restart()
    }

    /** "Cache all now": everything, even while a video plays. */
    fun cacheAllNow() {
        forceAll = true
        if (!enabled) changeEnabled(true) else restart()
    }

    fun clear() {
        job?.cancel()
        scope.launch {
            root.deleteRecursively()
            sizeBytes = 0
            version++
            restart()
        }
    }

    /** Removes the pictures of videos the connected phone no longer shares. */
    fun prune(deviceId: String, keepKeys: Set<String>) {
        if (!::root.isInitialized || keepKeys.isEmpty()) return
        device = deviceId
        scope.launch {
            val keep = keepKeys.map(::hash).toSet()
            deviceDir().listFiles()?.filter { it.isDirectory && it.name !in keep }?.forEach { it.deleteRecursively() }
            sizeBytes = measure()
        }
    }

    private fun measure(): Long = if (!::root.isInitialized) 0 else root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Coarse pass first (every 30 s) so the whole video is covered quickly, then every 10 s. */
    private fun steps(durationMs: Long): List<Long> {
        val step = stepMs
        val coarse = (0L until durationMs step 30_000L).toList()
        val fine = (0L until durationMs step step).filter { it % 30_000L != 0L }
        return coarse + fine
    }

    private fun fetch(target: ThumbTarget, dir: File, at: Long, out: File, pace: PreviewSpeed): Boolean = runCatching {
        // Tells the sharing phone how hard it may work.
        val url = frameUrl(target.fileUrl, at) + "&pace=${pace.workers}"
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            val bytes = response.body?.bytes()
            if (response.isSuccessful && bytes != null && bytes.isNotEmpty()) {
                val temp = File(dir, "$at.part")
                temp.writeBytes(bytes)
                temp.renameTo(out)
                sizeBytes += bytes.size
                true
            } else {
                false
            }
        }
    }.getOrDefault(false)

    private fun restart() {
        if (!::root.isInitialized) return
        job?.cancel()
        if (!enabled && playing == null) {
            progress = null
            return
        }
        job = scope.launch {
            delay(1_500)
            val current = playing
            val queue = when {
                // While watching, only the video on screen (unless "cache all now" was pressed).
                current != null && !forceAll -> listOf(current)
                current != null -> listOf(current) + library.filter { it.key != current.key }
                enabled -> library
                else -> emptyList()
            }.filter { it.durationMs > 0 }
            val total = queue.sumOf { steps(it.durationMs).size }
            var done = queue.sumOf { t -> coverage(t.key, t.durationMs).count { it } }
            val pace = speed
            val gate = kotlinx.coroutines.sync.Semaphore(pace.workers)
            val counter = java.util.concurrent.atomic.AtomicInteger(done)
            for (target in queue) {
                val dir = folder(target.key).apply { mkdirs() }
                kotlinx.coroutines.coroutineScope {
                    for (at in steps(target.durationMs)) {
                        if (!isActive) return@coroutineScope
                        val out = File(dir, "$at.jpg")
                        if (out.isFile) continue
                        while (holdOff && isActive) delay(500)
                        gate.acquire()
                        progress = ThumbProgress(counter.get(), total, target.title)
                        launch {
                            try {
                                val ok = fetch(target, dir, at, out, pace)
                                if (ok) {
                                    if (counter.incrementAndGet() % 5 == 0) version++
                                    if (pace.pauseMs > 0) delay(if (playing != null) 250 else pace.pauseMs)
                                } else {
                                    // The phone is away or busy: wait a little and carry on.
                                    delay(4_000)
                                }
                            } finally {
                                gate.release()
                            }
                        }
                    }
                }
                version++
            }
            forceAll = false
            progress = null
        }
    }
}
