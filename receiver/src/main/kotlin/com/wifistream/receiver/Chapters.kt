package com.wifistream.receiver

import android.net.Uri
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** One chapter: where it starts and its name ("Chapter 3", "Opening", "End credits"). */
data class Chapter(val startMs: Long, val title: String) {
    /** What this chapter is, judged by its name. */
    val kind: ChapterKind
        get() {
            val t = title.lowercase().trim()
            return when {
                Regex("""\b(intro|opening|op|title sequence|main titles?)\b""").containsMatchIn(t) -> ChapterKind.INTRO
                Regex("""\b(recap|previously)\b""").containsMatchIn(t) -> ChapterKind.RECAP
                Regex("""\b(credits?|ending|ed|outro|end titles?)\b""").containsMatchIn(t) -> ChapterKind.CREDITS
                else -> ChapterKind.NORMAL
            }
        }
}

enum class ChapterKind { NORMAL, INTRO, RECAP, CREDITS }

/** The chapter playing at [ms], with where it ends. */
fun List<Chapter>.at(ms: Long, durationMs: Long): Pair<Chapter, Long>? {
    val index = indexOfLast { it.startMs <= ms }.takeIf { it >= 0 } ?: return null
    val end = getOrNull(index + 1)?.startMs ?: durationMs
    return this[index] to end
}

/**
 * Reads chapter names and times from MKV files on the sharing phone, a few small reads at the
 * start and wherever the file says its chapter list is. The main player does not expose MKV chapters.
 */
object MkvChapters {
    private const val ID_EBML = 0x1A45DFA3
    private const val ID_SEGMENT = 0x18538067
    private const val ID_SEEK_HEAD = 0x114D9B74
    private const val ID_SEEK = 0x4DBB
    private const val ID_SEEK_ID = 0x53AB
    private const val ID_SEEK_POSITION = 0x53AC
    private const val ID_CHAPTERS = 0x1043A770
    private const val ID_EDITION = 0x45B9
    private const val ID_ATOM = 0xB6
    private const val ID_TIME_START = 0x91
    private const val ID_HIDDEN = 0x98
    private const val ID_DISPLAY = 0x80
    private const val ID_STRING = 0x85
    private const val ID_CLUSTER = 0x1F43B675

    private val known = ConcurrentHashMap<String, List<Chapter>>()
    private val http = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()

    suspend fun load(url: String): List<Chapter> = withContext(Dispatchers.IO) {
        val name = Uri.parse(url).getQueryParameter("path") ?: url
        if (!name.endsWith(".mkv", true) && !name.endsWith(".mka", true) && !name.endsWith(".webm", true)) return@withContext emptyList()
        known.getOrPut(url) { runCatching { read(url) }.getOrNull().orEmpty() }
    }

    private fun read(url: String): List<Chapter> {
        val head = fetch(url, 0, 16_384) ?: return emptyList()
        val r = Reader(head, 0)
        if (r.id() != ID_EBML) return emptyList()
        val headerSize = r.size() ?: return emptyList()
        r.pos += headerSize.toInt()
        if (r.id() != ID_SEGMENT) return emptyList()
        r.size() ?: return emptyList()
        val segmentStart = r.pos.toLong()

        // Walk the top of the file: chapters may sit right there, or a seek table says where they are.
        var seekEntries: Map<Int, Long> = emptyMap()
        while (r.pos < head.size - 12) {
            val elementStart = r.pos
            val id = r.id() ?: break
            val size = r.size() ?: break
            if (id == ID_CLUSTER || size < 0) break
            if (id == ID_CHAPTERS) {
                val bytes = if (r.pos + size <= head.size) head else fetch(url, elementStart.toLong(), (r.pos - elementStart + size).toInt()) ?: break
                val start = if (bytes === head) r.pos else r.pos - elementStart
                return parseChapters(bytes, start, (start + size).toInt())
            }
            if (id == ID_SEEK_HEAD && seekEntries.isEmpty()) seekEntries = seekTable(head, r.pos, minOf(head.size.toLong(), r.pos + size).toInt())
            r.pos += size.toInt()
        }
        // Some files keep a second seek table at the end; follow it once.
        var position = seekEntries[ID_CHAPTERS]
        if (position == null) {
            val tail = seekEntries[ID_SEEK_HEAD] ?: return emptyList()
            val bytes = fetch(url, segmentStart + tail, 16_384) ?: return emptyList()
            val t = Reader(bytes, 0)
            if (t.id() != ID_SEEK_HEAD) return emptyList()
            val size = t.size() ?: return emptyList()
            position = seekTable(bytes, t.pos, minOf(bytes.size.toLong(), t.pos + size).toInt())[ID_CHAPTERS] ?: return emptyList()
        }
        val probe = fetch(url, segmentStart + position, 12) ?: return emptyList()
        val p = Reader(probe, 0)
        if (p.id() != ID_CHAPTERS) return emptyList()
        val size = p.size() ?: return emptyList()
        if (size <= 0 || size > 2_000_000) return emptyList()
        val bytes = fetch(url, segmentStart + position + p.pos, size.toInt()) ?: return emptyList()
        return parseChapters(bytes, 0, bytes.size)
    }

    private fun seekTable(data: ByteArray, start: Int, end: Int): Map<Int, Long> {
        val out = mutableMapOf<Int, Long>()
        val r = Reader(data, start)
        while (r.pos < end - 4) {
            val id = r.id() ?: break
            val size = (r.size() ?: break).toInt()
            val next = r.pos + size
            if (id == ID_SEEK && next <= data.size) {
                var target = 0
                var position = -1L
                while (r.pos < next - 2) {
                    val field = r.id() ?: break
                    val length = (r.size() ?: break).toInt()
                    if (r.pos + length > data.size) break
                    when (field) {
                        ID_SEEK_ID -> target = r.uint(length).toInt()
                        ID_SEEK_POSITION -> position = r.uint(length)
                    }
                    r.pos += length
                }
                if (position >= 0 && target !in out) out[target] = position
            }
            r.pos = next
        }
        return out
    }

    /** The first edition's visible chapters, in time order. */
    private fun parseChapters(data: ByteArray, start: Int, end: Int): List<Chapter> {
        val r = Reader(data, start)
        while (r.pos < end - 2) {
            val id = r.id() ?: break
            val size = (r.size() ?: break).toInt()
            val next = minOf(r.pos + size, end)
            if (id == ID_EDITION) {
                val chapters = mutableListOf<Chapter>()
                while (r.pos < next - 2) {
                    val atomId = r.id() ?: break
                    val atomSize = (r.size() ?: break).toInt()
                    val atomEnd = minOf(r.pos + atomSize, next)
                    if (atomId == ID_ATOM) atom(r, atomEnd)?.let { chapters += it }
                    r.pos = atomEnd
                }
                if (chapters.isNotEmpty()) {
                    return chapters.sortedBy { it.startMs }.mapIndexed { i, c -> if (c.title.isBlank()) c.copy(title = "Chapter ${i + 1}") else c }
                }
            }
            r.pos = next
        }
        return emptyList()
    }

    private fun atom(r: Reader, end: Int): Chapter? {
        var startNs = -1L
        var hidden = false
        var title = ""
        while (r.pos < end - 1) {
            val id = r.id() ?: break
            val size = (r.size() ?: break).toInt()
            val next = minOf(r.pos + size, end)
            when (id) {
                ID_TIME_START -> startNs = r.uint(size)
                ID_HIDDEN -> hidden = r.uint(size) == 1L
                ID_DISPLAY -> if (title.isEmpty()) {
                    while (r.pos < next - 1) {
                        val f = r.id() ?: break
                        val l = (r.size() ?: break).toInt()
                        if (f == ID_STRING && r.pos + l <= r.data.size) title = String(r.data, r.pos, l, Charsets.UTF_8).trim()
                        r.pos += l
                    }
                }
            }
            r.pos = next
        }
        return if (startNs < 0 || hidden) null else Chapter(startNs / 1_000_000, title)
    }

    private class Reader(val data: ByteArray, var pos: Int) {
        fun id(): Int? {
            if (pos >= data.size) return null
            val first = data[pos].toInt() and 0xFF
            val length = when {
                first and 0x80 != 0 -> 1
                first and 0x40 != 0 -> 2
                first and 0x20 != 0 -> 3
                first and 0x10 != 0 -> 4
                else -> return null
            }
            if (pos + length > data.size) return null
            var value = 0
            repeat(length) { value = (value shl 8) or (data[pos++].toInt() and 0xFF) }
            return value
        }

        fun size(): Long? {
            if (pos >= data.size) return null
            val first = data[pos].toInt() and 0xFF
            if (first == 0) return null
            val length = Integer.numberOfLeadingZeros(first) - 24 + 1
            if (pos + length > data.size) return null
            var value = (first and (0xFF shr length)).toLong()
            var allOnes = value == (0xFF shr length).toLong()
            pos++
            repeat(length - 1) {
                val b = data[pos++].toInt() and 0xFF
                if (b != 0xFF) allOnes = false
                value = (value shl 8) or b.toLong()
            }
            return if (allOnes) -1 else value
        }

        fun uint(length: Int): Long {
            var v = 0L
            for (i in 0 until length) if (pos + i < data.size) v = (v shl 8) or (data[pos + i].toLong() and 0xFF)
            return v
        }
    }

    private fun fetch(url: String, from: Long, count: Int): ByteArray? =
        http.newCall(Request.Builder().url(Link.map(url)).header("Range", "bytes=$from-${from + count - 1}").build()).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.bytes()
        }
}
