package com.wifistream.receiver

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wifistream.protocol.Entry
import com.wifistream.protocol.EntryKind
import com.wifistream.protocol.Listing
import com.wifistream.protocol.StorageKind
import com.wifistream.protocol.badges

/** The Folders tab: every storage the phone shares, for browsing files directly. */
@Composable
fun RootsList(vm: AppViewModel) {
    val connection = vm.connection ?: return
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = screenMargin(), vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (connection.info.roots.isEmpty()) {
            Text("The phone is not sharing any storage yet. On it, open Share this device and allow file access.", color = Muted)
        }
        connection.info.roots.forEach { root ->
            FocusRow(onClick = { vm.open(Screen.Folder(root.id, "", root.label)) }) {
                RowIcon(if (root.kind == StorageKind.INTERNAL) R.drawable.ic_phone else R.drawable.ic_storage)
                Column(Modifier.weight(1f)) {
                    Text(root.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${Formatter.formatShortFileSize(context, root.freeBytes)} free of " +
                            Formatter.formatShortFileSize(context, root.totalBytes),
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    when (root.kind) {
                        StorageKind.INTERNAL -> "Phone"
                        StorageKind.USB -> "USB"
                        StorageKind.SD_CARD -> "SD card"
                    },
                    color = Muted,
                )
            }
        }
    }
}

private sealed interface Load {
    data object Loading : Load
    class Failed(val message: String) : Load
    class Ready(val listing: Listing) : Load
}

@Composable
fun FolderScreen(vm: AppViewModel, screen: Screen.Folder) {
    val client = vm.connection?.client ?: return
    var load by remember(screen) { mutableStateOf(screen.listing?.let { Load.Ready(it) } ?: Load.Loading) }
    var attempt by remember(screen) { mutableIntStateOf(0) }

    LaunchedEffect(screen, attempt) {
        if (screen.listing != null) return@LaunchedEffect
        load = Load.Loading
        load = runCatching { client.list(screen.root, screen.path) }.fold(
            onSuccess = {
                screen.listing = it
                Load.Ready(it)
            },
            onFailure = { Load.Failed("Could not read this folder. Is sharing still on?") },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = screenMargin())
            .padding(top = 24.dp),
    ) {
        // The parent path gives context for nested folders; at the top level it would only repeat the title.
        ScreenTitle(screen.title, subtitle = screen.path.substringBeforeLast('/', "").ifEmpty { null }, onBack = vm::back)
        Spacer(Modifier.height(14.dp))
        when (val state = load) {
            Load.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is Load.Failed -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                Button(onClick = { attempt++ }) { Text("Try again") }
            }
            is Load.Ready -> FolderList(vm, screen, client, state.listing)
        }
    }
}

@Composable
private fun FolderList(vm: AppViewModel, screen: Screen.Folder, client: SenderClient, listing: Listing) {
    val context = LocalContext.current
    val entries = remember(listing) {
        listing.entries.filter { it.kind == EntryKind.FOLDER || it.kind == EntryKind.VIDEO || it.kind == EntryKind.AUDIO }
    }
    if (entries.isEmpty()) {
        Text("No videos or music in this folder.", color = Muted)
        return
    }

    // Land on the item that was focused before leaving this folder, else on the first one.
    val targetPath = remember(listing) { screen.lastFocused?.takeIf { p -> entries.any { it.path == p } } ?: entries.first().path }
    val target = remember(listing) { FocusRequester() }
    val listState = rememberLazyListState()
    LaunchedEffect(listing) {
        listState.scrollToItem(entries.indexOfFirst { it.path == targetPath }.coerceAtLeast(0))
        withFrameNanos { }
        runCatching { target.requestFocus() }
    }

    fun open(entry: Entry) {
        when (entry.kind) {
            EntryKind.FOLDER -> vm.open(Screen.Folder(screen.root, entry.path, entry.name))
            EntryKind.VIDEO -> {
                // Subtitle files next to the video with the same base name are offered in the player.
                val base = entry.name.substringBeforeLast('.')
                val subtitles = listing.entries
                    .filter { it.kind == EntryKind.SUBTITLE && it.name.startsWith(base) }
                    .map { SubtitleFile(client.fileUrl(screen.root, it.path), it.name) }
                val key = "${screen.root}|${entry.path}"
                vm.open(
                    Screen.Player(
                        listOf(
                            PlayItem(
                                url = client.fileUrl(screen.root, entry.path),
                                title = base,
                                key = key,
                                startMs = vm.store.resumePosition(key),
                                subtitles = subtitles,
                            ),
                        ),
                        0,
                    ),
                )
            }
            EntryKind.AUDIO -> {
                // Music plays on through the rest of the folder.
                val tracks = entries.filter { it.kind == EntryKind.AUDIO }
                vm.open(
                    Screen.Player(
                        items = tracks.map { PlayItem(client.fileUrl(screen.root, it.path), it.displayTitle(), audio = true) },
                        startIndex = tracks.indexOf(entry),
                    ),
                )
            }
            else -> Unit
        }
    }

    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        items(entries, key = { it.path }) { entry ->
            FocusRow(
                onClick = { open(entry) },
                modifier = Modifier
                    .then(if (entry.path == targetPath) Modifier.focusRequester(target) else Modifier)
                    .onFocusChanged { if (it.isFocused) screen.lastFocused = entry.path },
            ) {
                RowIcon(
                    when (entry.kind) {
                        EntryKind.FOLDER -> R.drawable.ic_folder
                        EntryKind.VIDEO -> R.drawable.ic_movie
                        else -> R.drawable.ic_music
                    },
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.displayTitle(),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val details = when (entry.kind) {
                        EntryKind.FOLDER -> null
                        EntryKind.AUDIO -> listOfNotNull(entry.media?.artist, entry.media?.album, formatDuration(entry.media?.durationMs ?: 0))
                        else -> listOfNotNull(
                            formatDuration(entry.media?.durationMs ?: 0),
                            Formatter.formatShortFileSize(context, entry.sizeBytes),
                        )
                    }?.joinToString("  ·  ")
                    if (!details.isNullOrEmpty()) {
                        Text(details, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                }
                if (entry.kind == EntryKind.VIDEO) entry.media?.let { BadgeRow(it.badges()) }
            }
        }
    }
}

private fun Entry.displayTitle(): String = when (kind) {
    EntryKind.FOLDER -> name
    EntryKind.AUDIO -> media?.title ?: name.substringBeforeLast('.')
    else -> name.substringBeforeLast('.')
}
