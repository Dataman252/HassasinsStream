package com.wifistream.receiver

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Progress(val positionMs: Long, val durationMs: Long, val updatedAt: Long, val finished: Boolean)

/** Choices remembered for one show, so every episode starts the same way. */
@Serializable
data class PictureEntry(val name: String, val settings: PictureSettings)

@Serializable
data class ShowChoices(val audioLanguage: String = "", val subtitleLanguage: String = "", val subtitleDelayMs: Int = 0)

@Serializable
private data class StoreData(
    /** File-name group ("movie|dune part two|2024") -> title key ("movie:693134"), or "" when TMDB had no match. */
    val matches: Map<String, String> = emptyMap(),
    /** Groups whose match was chosen by hand (Fix match): never re-matched automatically. */
    val manual: Set<String> = emptySet(),
    /** Groups already re-checked once by the better matcher, so they are not searched again. */
    val rechecked: Set<String> = emptySet(),
    val titles: Map<String, TitleInfo> = emptyMap(),
    /** "tv:1396:2" -> episodes of that season. */
    val seasons: Map<String, List<EpisodeInfo>> = emptyMap(),
    /** File key ("root|path") -> how far it was watched. */
    val progress: Map<String, Progress> = emptyMap(),
    val dvAsHdr10: Boolean = false,
    val matchFrameRate: Boolean = false,
    val passthrough: Boolean = true,
    val boostDb: Int = 0,
    // Older equalizer settings, read once to carry the volume boost over.
    val eqPreset: String = "FLAT",
    val eqBands: List<kotlinx.serialization.json.JsonElement> = emptyList(),
    /** The sound engine's settings; null until first changed. */
    val sound: SoundSettings? = null,
    /** "My preset" name -> saved equalizer. */
    val userPresets: Map<String, EqSnapshot> = emptyMap(),
    val subLanguage: String = "en",
    val subSize: Int = 1,
    val subColour: Int = 0,
    val subBackground: Int = 0,
    /** Languages picked last time in the player, used as the starting choice for the next video. */
    val audioLanguage: String = "",
    val subtitleLanguage: String = "",
    /** Show key ("tv:1396") -> its own audio, subtitle and subtitle-timing choices. */
    val shows: Map<String, ShowChoices> = emptyMap(),
    /** Show the big format badge (Dolby Vision, HDR10...) when a video starts. */
    val formatBadge: Boolean = true,
    /** Picture adjustments per show or film (key: show key, or the film file's key). Original = excluded from "all". */
    val picture: Map<String, PictureEntry> = emptyMap(),
    /** Picture adjustments for every video that has none of its own; null when off. */
    val pictureAll: PictureSettings? = null,
    /** The viewer's own saved picture looks, by name, in the order made. */
    val pictureProfiles: Map<String, PictureSettings> = emptyMap(),
)

/**
 * Everything the receiver remembers between launches: TMDB matches and details,
 * watch history and settings. One small JSON file on the device.
 */
class LibraryStore(context: Context, private val scope: CoroutineScope) {
    private val file = File(context.filesDir, "library.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val backup = File(context.filesDir, "library.json.bak")
    private val writeLock = Any()

    // If the main file is ever unreadable, fall back to the previous good copy instead of starting empty.
    private var data: StoreData =
        runCatching { json.decodeFromString<StoreData>(file.readText()) }
            .recoverCatching { json.decodeFromString<StoreData>(backup.readText()) }
            .getOrDefault(StoreData())
    private var saveJob: Job? = null

    /** Bumped on every change; screens read it so they redraw when history or matches change. */
    var version by mutableIntStateOf(0)
        private set

    fun match(group: String): String? = data.matches[group]

    /** Every file group currently matched to [titleKey]. */
    fun groupsFor(titleKey: String): List<String> = data.matches.filterValues { it == titleKey }.keys.toList()
    fun title(key: String): TitleInfo? = data.titles[key]
    fun season(key: String): List<EpisodeInfo>? = data.seasons[key]
    fun progress(fileKey: String): Progress? = data.progress[fileKey]
    fun allProgress(): Map<String, Progress> = data.progress
    val dvAsHdr10: Boolean get() = data.dvAsHdr10
    val matchFrameRate: Boolean get() = data.matchFrameRate
    val passthrough: Boolean get() = data.passthrough
    val sound: SoundSettings get() = data.sound ?: SoundSettings(boostDb = data.boostDb)
    fun setSound(settings: SoundSettings) = change { copy(sound = settings) }

    /** The picture a title plays with: its own, else the "all videos" one, else the file as it is. */
    fun pictureFor(key: String?): PictureSettings = key?.let { data.picture[it]?.settings } ?: data.pictureAll ?: PictureSettings()
    fun hasOwnPicture(key: String?): Boolean = key != null && key in data.picture
    val pictureAll: PictureSettings? get() = data.pictureAll
    val pictureEntries: Map<String, PictureEntry> get() = data.picture
    fun setPicture(key: String, name: String, settings: PictureSettings) = change { copy(picture = picture + (key to PictureEntry(name, settings))) }
    fun removePicture(key: String) = change { copy(picture = picture - key) }
    val pictureProfiles: Map<String, PictureSettings> get() = data.pictureProfiles

    /** Saves [settings] as a new look ("My look 1", "My look 2"…) and returns its name. */
    fun savePictureProfile(settings: PictureSettings): String {
        var n = data.pictureProfiles.size + 1
        while ("My look $n" in data.pictureProfiles) n++
        val name = "My look $n"
        change { copy(pictureProfiles = pictureProfiles + (name to settings)) }
        return name
    }
    fun deletePictureProfile(name: String) = change { copy(pictureProfiles = pictureProfiles - name) }
    fun renamePictureProfile(old: String, new: String) = change {
        val value = pictureProfiles[old] ?: return@change this
        val clean = new.trim().ifEmpty { return@change this }
        copy(pictureProfiles = pictureProfiles.toList().map { if (it.first == old) clean to value else it }.toMap())
    }

    fun setPictureAll(settings: PictureSettings?) = change { copy(pictureAll = settings?.takeIf { !it.isOriginal }) }
    val userPresets: Map<String, EqSnapshot> get() = data.userPresets
    fun saveUserPreset(name: String, snapshot: EqSnapshot) = change { copy(userPresets = userPresets + (name to snapshot)) }
    fun deleteUserPreset(name: String) = change { copy(userPresets = userPresets - name) }
    val subLanguage: String get() = data.subLanguage
    val subSize: Int get() = data.subSize
    val subColour: Int get() = data.subColour
    val subBackground: Int get() = data.subBackground
    val audioLanguage: String get() = data.audioLanguage
    val subtitleLanguage: String get() = data.subtitleLanguage
    fun setAudioLanguage(code: String) = change { copy(audioLanguage = code) }
    fun setSubtitleLanguage(code: String) = change { copy(subtitleLanguage = code) }
    fun showChoices(showKey: String?): ShowChoices? = showKey?.let { data.shows[it] }
    fun setShowChoices(showKey: String, update: ShowChoices.() -> ShowChoices) =
        change { copy(shows = shows + (showKey to (shows[showKey] ?: ShowChoices()).update())) }
    val formatBadge: Boolean get() = data.formatBadge
    fun setFormatBadge(on: Boolean) = change { copy(formatBadge = on) }

    /** Where to resume from, or 0 to start at the beginning. */
    fun resumePosition(fileKey: String): Long =
        data.progress[fileKey]?.takeIf { !it.finished && it.positionMs > 30_000 }?.positionMs ?: 0

    fun setMatch(group: String, titleKey: String) = change { copy(matches = matches + (group to titleKey)) }
    fun setManualMatch(group: String, titleKey: String) = change { copy(matches = matches + (group to titleKey), manual = manual + group) }
    fun isManual(group: String) = group in data.manual
    fun wasRechecked(group: String) = group in data.rechecked
    fun markRechecked(group: String) = change { copy(rechecked = rechecked + group) }
    fun putTitle(info: TitleInfo) = change { copy(titles = titles + (info.key to info)) }
    fun putSeason(key: String, episodes: List<EpisodeInfo>) = change { copy(seasons = seasons + (key to episodes)) }
    fun setDvAsHdr10(on: Boolean) = change { copy(dvAsHdr10 = on) }
    fun setMatchFrameRate(on: Boolean) = change { copy(matchFrameRate = on) }
    fun setPassthrough(on: Boolean) = change { copy(passthrough = on) }

    fun setSubtitleLook(language: String, size: Int, colour: Int, background: Int) =
        change { copy(subLanguage = language, subSize = size, subColour = colour, subBackground = background) }

    /** Forget "no match" answers so a refresh asks TMDB again. */
    /** Forgets misses, and titles found only by the free fallbacks, so TMDB gets another go. */
    fun clearFailedMatches() = change {
        copy(matches = matches.filterValues { it.isNotEmpty() && !it.startsWith("tvmaze:") && !it.startsWith("wiki:") })
    }

    fun setProgress(fileKey: String, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        val finished = positionMs >= durationMs * 0.92
        change { copy(progress = progress + (fileKey to Progress(positionMs, durationMs, System.currentTimeMillis(), finished))) }
    }

    fun setWatched(fileKeys: List<String>, watched: Boolean) = change {
        val now = System.currentTimeMillis()
        copy(
            progress = if (watched) {
                progress + fileKeys.associateWith { Progress(0, progress[it]?.durationMs ?: 0, now, finished = true) }
            } else {
                progress - fileKeys.toSet()
            },
        )
    }

    fun clearHistory() = change { copy(progress = emptyMap()) }

    private fun change(block: StoreData.() -> StoreData) {
        synchronized(this) { data = data.block() }
        version++
        saveJob?.cancel()
        saveJob = scope.launch(Dispatchers.IO) {
            delay(1_500)
            flush()
        }
    }

    /**
     * Saves to disk. One writer at a time, and never onto the live file directly: the new copy is
     * written beside it and swapped in whole, with the previous copy kept as a backup. Two saves
     * overlapping, or the app being killed mid-save, can therefore no longer leave a broken file.
     */
    fun flush() {
        synchronized(writeLock) {
            val snapshot = synchronized(this) { data }
            runCatching {
                val fresh = File(file.parentFile, "library.json.new")
                fresh.writeText(json.encodeToString(StoreData.serializer(), snapshot))
                if (file.exists()) file.copyTo(backup, overwrite = true)
                if (!fresh.renameTo(file)) {
                    file.writeText(fresh.readText())
                    fresh.delete()
                }
            }
        }
    }
}
