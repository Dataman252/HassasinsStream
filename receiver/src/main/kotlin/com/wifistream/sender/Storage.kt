package com.wifistream.sender

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import androidx.annotation.RequiresApi
import com.wifistream.protocol.StorageKind
import com.wifistream.protocol.StorageRoot
import java.io.File

/**
 * What this device shares: only the drives and folders the owner picked.
 * Nothing outside them can be listed, scanned or played by another device.
 */
@RequiresApi(30)
class Storage(private val context: Context) {

    data class Root(val info: StorageRoot, val dir: File)

    /** A mounted drive: internal storage, a USB drive or an SD card. */
    data class Volume(val id: String, val label: String, val kind: StorageKind, val dir: File)

    private val prefs = context.getSharedPreferences("share", Context.MODE_PRIVATE)

    /** Read fresh on every call so a USB drive plugged in mid-session shows up. */
    fun volumes(): List<Volume> {
        val manager = context.getSystemService(StorageManager::class.java)
        return manager.storageVolumes.mapNotNull { volume ->
            val mounted = volume.state == Environment.MEDIA_MOUNTED ||
                volume.state == Environment.MEDIA_MOUNTED_READ_ONLY
            val dir = volume.directory
            if (!mounted || dir == null) return@mapNotNull null
            val description = volume.getDescription(context).orEmpty()
            Volume(
                id = if (volume.isPrimary) INTERNAL_ID else (volume.uuid ?: dir.name),
                label = if (volume.isPrimary) "Internal storage" else description.ifBlank { "USB drive" },
                kind = when {
                    volume.isPrimary -> StorageKind.INTERNAL
                    description.contains("sd", ignoreCase = true) -> StorageKind.SD_CARD
                    else -> StorageKind.USB
                },
                dir = readablePath(dir, volume.uuid),
            )
        }
    }

    /**
     * Phones differ in where a USB drive can be read from. Try the path Android reports,
     * then the two usual mount points, and keep the first one that can be listed.
     */
    private fun readablePath(reported: File, uuid: String?): File {
        val candidates = listOfNotNull(reported, uuid?.let { File("/storage/$it") }, uuid?.let { File("/mnt/media_rw/$it") })
        return candidates.firstOrNull { it.listFiles() != null } ?: reported
    }

    /** Made once and kept: how other devices recognise this one again. */
    fun deviceId(): String = prefs.getString(KEY_DEVICE_ID, null) ?: java.util.UUID.randomUUID().toString().also {
        prefs.edit().putString(KEY_DEVICE_ID, it).apply()
    }

    /** Absolute paths picked in the Share screen. */
    fun sharedPaths(): List<String> = prefs.getStringSet(KEY_PATHS, emptySet()).orEmpty().sorted()

    fun isShared(dir: File): Boolean = dir.absolutePath in sharedPaths()

    fun setShared(dir: File, shared: Boolean) {
        val paths = sharedPaths().toMutableSet()
        if (shared) paths += dir.absolutePath else paths -= dir.absolutePath
        prefs.edit().putStringSet(KEY_PATHS, paths).apply()
    }

    /**
     * The shared folders as the other device sees them. A folder inside another shared
     * folder is dropped, and a folder on an unplugged drive is simply absent until it returns.
     */
    fun roots(): List<Root> {
        val volumes = volumes()
        val chosen = sharedPaths().map(::File).filter { it.isDirectory }
        val outermost = chosen.filter { dir -> chosen.none { other -> other != dir && dir.path.startsWith(other.path + File.separator) } }
        return outermost.mapNotNull { dir ->
            val volume = volumes.firstOrNull { dir == it.dir || dir.path.startsWith(it.dir.path + File.separator) }
                ?: return@mapNotNull null
            val wholeDrive = dir == volume.dir
            Root(
                info = StorageRoot(
                    // A whole drive keeps the drive's id; a folder gets an id that stays the same between runs.
                    id = if (wholeDrive) volume.id else "f" + Integer.toHexString(dir.absolutePath.hashCode()),
                    label = if (wholeDrive) volume.label else dir.name,
                    kind = volume.kind,
                    totalBytes = dir.totalSpace,
                    freeBytes = dir.freeSpace,
                ),
                dir = dir,
            )
        }
    }

    fun root(id: String): Root? = roots().firstOrNull { it.info.id == id }

    /**
     * Maps a path sent by the other device onto a real file, refusing anything that
     * escapes the shared folder (`..`, symlinks out of it).
     */
    fun resolve(root: Root, relativePath: String): File? {
        val base = root.dir.canonicalFile
        val target = File(base, relativePath).canonicalFile
        val inside = target == base || target.path.startsWith(base.path + File.separator)
        return if (inside && target.exists()) target else null
    }

    companion object {
        const val INTERNAL_ID = "internal"
        private const val KEY_PATHS = "shared_paths"
        private const val KEY_DEVICE_ID = "device_id"
    }
}
