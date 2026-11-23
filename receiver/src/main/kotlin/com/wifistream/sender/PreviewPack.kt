package com.wifistream.sender

import java.io.File
import java.io.RandomAccessFile

/**
 * All the seek-bar pictures of one film in a single file, so a drive with large blocks (exFAT uses
 * 128 KB) does not spend a whole block on every 10 KB picture.
 *
 * The file is a list of records: an 8-byte time (milliseconds), a 4-byte length, then the bytes.
 * The first record has time -1 and holds the film's "size|date", so pictures of a replaced file are
 * never shown for the new one.
 * Records are only ever added at the end; a record cut short (drive pulled out mid-write) is
 * dropped the next time the file is opened.
 */
class PreviewPack private constructor(val file: File) {
    private val index = HashMap<Long, Pair<Long, Int>>()

    /** The film's "size|date" this pack was made for, from the first record. */
    var film: String? = null
        private set

    fun has(atMs: Long): Boolean = synchronized(this) { atMs in index }

    fun read(atMs: Long): ByteArray? = synchronized(this) {
        val (offset, length) = index[atMs] ?: return null
        runCatching {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                ByteArray(length).also { raf.readFully(it) }
            }
        }.getOrNull()
    }

    fun write(atMs: Long, bytes: ByteArray): Unit = synchronized(this) {
        if (atMs in index) return
        runCatching<Unit> {
            RandomAccessFile(file, "rw").use { raf ->
                val at = raf.length()
                raf.seek(at)
                raf.writeLong(atMs)
                raf.writeInt(bytes.size)
                raf.write(bytes)
                index[atMs] = (at + HEADER) to bytes.size
            }
        }
    }

    private fun load() {
        if (!file.isFile) return
        RandomAccessFile(file, "rw").use { raf ->
            val size = raf.length()
            var pos = 0L
            while (pos + HEADER <= size) {
                raf.seek(pos)
                val time = raf.readLong()
                val length = raf.readInt()
                if (length < 0 || length > MAX_PICTURE || pos + HEADER + length > size) break
                if (time == -1L) {
                    film = ByteArray(length).also { raf.readFully(it) }.toString(Charsets.UTF_8)
                } else {
                    index[time] = (pos + HEADER) to length
                }
                pos += HEADER + length
            }
            // Anything after the last whole record was cut off: remove it.
            if (pos < size) raf.setLength(pos)
        }
    }

    companion object {
        private const val HEADER = 12
        private const val MAX_PICTURE = 2_000_000

        /** Opens the pack for a film whose "size|date" is [signature]; a pack for an older version is started afresh. */
        fun open(file: File, signature: String): PreviewPack {
            var pack = PreviewPack(file)
            runCatching { pack.load() }
            if (pack.film != null && pack.film != signature) {
                file.delete()
                pack = PreviewPack(file)
            }
            if (pack.film == null) {
                runCatching {
                    file.parentFile?.mkdirs()
                    val name = signature.toByteArray(Charsets.UTF_8)
                    RandomAccessFile(file, "rw").use { raf ->
                        if (raf.length() == 0L) {
                            raf.writeLong(-1L)
                            raf.writeInt(name.size)
                            raf.write(name)
                        }
                    }
                    pack.film = signature
                }
            }
            return pack
        }

        /** The "size|date" recorded in a pack file, without loading its pictures. */
        fun filmOf(file: File): String? = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.readLong() != -1L) return null
                val length = raf.readInt()
                if (length !in 0..4096) return null
                ByteArray(length).also { raf.readFully(it) }.toString(Charsets.UTF_8)
            }
        }.getOrNull()
    }
}
