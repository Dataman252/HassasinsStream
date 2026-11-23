package com.wifistream.receiver

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Makes some MKV files seekable that the player would otherwise restart on every seek.
 *
 * An MKV's seek index ("Cues") usually sits at the end of the file, and a small table at the
 * start says where. Some muxers write that table in two parts: the one at the start only points
 * to a second table at the end, and that second one points to the index. The player reads only
 * the first table, finds no index, and treats the file as unseekable.
 *
 * The fix: follow the chain once, then, as the file streams past, rewrite the first table's
 * pointer so it names the index directly. The entry is overwritten in place with one of the same
 * length, so nothing else in the file moves.
 */
object MkvSeekFix {
    private const val ID_EBML = 0x1A45DFA3
    private const val ID_SEGMENT = 0x18538067
    private const val ID_SEEK_HEAD = 0x114D9B74
    private const val ID_SEEK = 0x4DBB
    private const val ID_SEEK_ID = 0x53AB
    private const val ID_SEEK_POSITION = 0x53AC
    private const val ID_CUES = 0x1C53BB6B
    private const val ID_CLUSTER = 0x1F43B675

    /** Bytes to substitute at absolute file offsets. */
    class Patch(private val edits: List<Pair<Long, ByteArray>>) {
        fun apply(buffer: ByteArray, offset: Int, streamPosition: Long, count: Int) {
            for ((at, bytes) in edits) {
                for (i in bytes.indices) {
                    val p = at + i
                    if (p >= streamPosition && p < streamPosition + count) buffer[offset + (p - streamPosition).toInt()] = bytes[i]
                }
            }
        }
    }

    private class Entry(val id: Int, val position: Long, val idOffset: Int, val positionOffset: Int, val positionWidth: Int)

    private val NONE = Patch(emptyList())
    private val known = ConcurrentHashMap<String, Patch>()
    private val http = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()

    /** The patch for this file, or null when it needs none. Works out the answer once per file. */
    fun patchFor(url: String): Patch? {
        val name = Uri.parse(url).getQueryParameter("path") ?: url
        if (!name.endsWith(".mkv", true) && !name.endsWith(".webm", true) && !name.endsWith(".mka", true)) return null
        return known.getOrPut(url) {
            runCatching { compute(url) }
                .onFailure { android.util.Log.w("SeekFix", "Could not examine the seek table", it) }
                .getOrNull()
                .also { android.util.Log.i("SeekFix", if (it == null) "No seek-table fix needed or possible" else "Seek table will be patched") }
                ?: NONE
        }.takeIf { it !== NONE }
    }

    private fun compute(url: String): Patch? {
        val head = fetch(url, 0, 8_192) ?: return null
        val reader = Reader(head, 0)
        if (reader.id() != ID_EBML) return null
        // Read the size first: reading it moves the position, and only then is the header skipped.
        val headerSize = reader.size() ?: return null
        reader.pos += headerSize.toInt()
        if (reader.id() != ID_SEGMENT) return null
        reader.size() ?: return null
        val segmentStart = reader.pos

        var first: List<Entry>? = null
        while (reader.pos < head.size - 12) {
            val id = reader.id() ?: break
            val size = reader.size() ?: break
            if (id == ID_CLUSTER || size < 0) break
            if (id == ID_SEEK_HEAD) {
                first = seekEntries(head, reader.pos, minOf(head.size.toLong(), reader.pos + size).toInt())
                break
            }
            reader.pos += size.toInt()
        }
        val entries = first ?: return null
        if (entries.any { it.id == ID_CUES }) return null // already points at the index: nothing to fix
        val link = entries.firstOrNull { it.id == ID_SEEK_HEAD } ?: return null

        val second = fetch(url, segmentStart + link.position, 16_384) ?: return null
        val tail = Reader(second, 0)
        if (tail.id() != ID_SEEK_HEAD) return null
        val tailSize = tail.size() ?: return null
        val cues = seekEntries(second, tail.pos, minOf(second.size.toLong(), tail.pos + tailSize).toInt())
            .firstOrNull { it.id == ID_CUES }?.position ?: return null
        // The new position has to fit in the bytes the old one occupied.
        if (link.positionWidth < 8 && cues >= (1L shl (8 * link.positionWidth))) return null

        val idBytes = byteArrayOf(0x1C, 0x53, 0xBB.toByte(), 0x6B)
        val positionBytes = ByteArray(link.positionWidth) { i -> (cues ushr (8 * (link.positionWidth - 1 - i))).toByte() }
        return Patch(listOf(link.idOffset.toLong() to idBytes, link.positionOffset.toLong() to positionBytes))
    }

    /** The entries of a seek table whose contents span [start, end) in [data]. */
    private fun seekEntries(data: ByteArray, start: Int, end: Int): List<Entry> {
        val entries = mutableListOf<Entry>()
        val reader = Reader(data, start)
        while (reader.pos < end - 4) {
            val id = reader.id() ?: break
            val size = (reader.size() ?: break).toInt()
            val next = reader.pos + size
            if (id == ID_SEEK && next <= data.size) {
                var targetId = 0
                var idOffset = -1
                var position = 0L
                var positionOffset = -1
                var width = 0
                while (reader.pos < next - 2) {
                    val field = reader.id() ?: break
                    val length = (reader.size() ?: break).toInt()
                    if (reader.pos + length > data.size) break
                    when (field) {
                        ID_SEEK_ID -> {
                            idOffset = reader.pos
                            targetId = (0 until length).fold(0) { acc, i -> (acc shl 8) or (data[reader.pos + i].toInt() and 0xFF) }
                        }
                        ID_SEEK_POSITION -> {
                            positionOffset = reader.pos
                            width = length
                            position = (0 until length).fold(0L) { acc, i -> (acc shl 8) or (data[reader.pos + i].toLong() and 0xFF) }
                        }
                    }
                    reader.pos += length
                }
                // Only 4-byte ids can be swapped for the index's 4-byte id.
                if (idOffset >= 0 && positionOffset >= 0) entries += Entry(targetId, position, idOffset, positionOffset, width)
            }
            reader.pos = next
        }
        return entries
    }

    /** Reads EBML element ids and sizes. */
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

        /** Element size; -1 when the file marks it as unknown. */
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
    }

    private fun fetch(url: String, from: Long, count: Int): ByteArray? =
        http.newCall(Request.Builder().url(url).header("Range", "bytes=$from-${from + count - 1}").build()).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.bytes()
        }
}

/** Streams a file from the sharing device, applying [MkvSeekFix] to the bytes as they pass. */
@OptIn(UnstableApi::class)
class SeekFixDataSource(private val upstream: DataSource) : DataSource {
    private var position = 0L
    private var patch: MkvSeekFix.Patch? = null

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        // If the phone has moved to a new address (for example onto its hotspot), go there.
        val moved = Link.map(dataSpec.uri.toString())
        val spec = if (moved != dataSpec.uri.toString()) dataSpec.withUri(Uri.parse(moved)) else dataSpec
        position = spec.position
        patch = MkvSeekFix.patchFor(moved)
        return upstream.open(spec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = upstream.read(buffer, offset, length)
        if (count > 0) {
            patch?.apply(buffer, offset, position, count)
            position += count
        }
        return count
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = SeekFixDataSource(upstream.createDataSource())
    }
}
