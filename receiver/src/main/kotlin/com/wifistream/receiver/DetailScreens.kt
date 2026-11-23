package com.wifistream.receiver

import androidx.compose.foundation.layout.height

import androidx.compose.foundation.border

import androidx.compose.ui.graphics.Brush

import androidx.compose.foundation.background

import androidx.compose.ui.graphics.Color

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.wifistream.protocol.badges

/** Opens the trailer in the YouTube app (Fire TV, Android TV, phones), else in a browser. */
private fun openTrailer(context: Context, youtubeId: String) {
    val attempts = listOf("vnd.youtube:$youtubeId", "https://www.youtube.com/watch?v=$youtubeId")
    for (uri in attempts) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: ActivityNotFoundException) {
            // try the next way
        }
    }
    Toast.makeText(context, "Install the YouTube app to watch trailers.", Toast.LENGTH_LONG).show()
}

/** Shared page frame: faint backdrop behind a scrolling column. */
@Composable
private fun DetailPage(backdrop: String?, onBack: () -> Unit, content: LazyListScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(start = screenMargin(), end = screenMargin(), top = 20.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!LocalIsTvSnapshot.isTv) {
                item {
                    Icon(
                        painterResource(R.drawable.ic_back),
                        contentDescription = "Back",
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onBack).padding(8.dp).size(24.dp),
                    )
                }
            }
            content()
        }
    }
}

@Composable
private fun TitleHeader(info: TitleInfo, badges: List<String> = emptyList()) {
    val tv = LocalIsTv.current
    val bannerHeight = if (tv) 380.dp else 300.dp
    val posterWidth = if (tv) 210.dp else 124.dp
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // A big banner: the title's wide artwork (or its poster), with the poster and name over it.
        Box(
            Modifier
                .fillMaxWidth()
                .height(bannerHeight)
                .clip(RoundedCornerShape(26.dp))
                .background(Panel),
        ) {
            AsyncImage(
                tmdbImage(info.backdrop ?: info.poster, "w1280"),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x3307080F), Color(0xF207080F)))))
            Row(
                Modifier.align(Alignment.BottomStart).padding(18.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                if (info.poster != null) {
                    AsyncImage(
                        tmdbImage(info.poster, "w500"),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(posterWidth)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.dp, Color(0x40FFFFFF), RoundedCornerShape(14.dp)),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        info.title,
                        style = if (tv) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(metaLine(info), color = Color(0xFFCBD5E1), style = MaterialTheme.typography.bodyMedium)
                    // The file's format, up top with the title: resolution, codec, HDR and sound.
                    if (badges.isNotEmpty()) BadgeRow(badges, onDark = true)
                }
            }
        }
        if (info.overview.isNotEmpty()) {
            Text(info.overview, maxLines = if (tv) 4 else 6, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
        }
        if (info.cast.isNotEmpty()) {
            Text("With ${info.cast.joinToString(", ")}", color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
    }
}

@Composable
private fun Gone(onBack: () -> Unit) {
    Column(Modifier.padding(screenMargin()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("This title is no longer on the phone.", color = Muted)
        PillButton("Back", onClick = onBack)
    }
}

@Composable
fun MovieDetailScreen(vm: AppViewModel, screen: Screen.MovieDetail) {
    val item = vm.library.movies.firstOrNull { it.info.key == screen.titleKey } ?: return Gone(vm::back)
    val context = LocalContext.current
    val version = vm.store.version
    val best = item.files.first()
    val progress = remember(item, version) { vm.store.progress(best.key) }
    val watched = remember(item, version) { item.files.any { vm.store.progress(it.key)?.finished == true } }
    val play = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { play.requestFocus() } }

    LaunchedEffect(item.info.key) { vm.showArt(item.info) }
    DetailPage(item.info.backdrop, vm::back) {
        item { TitleHeader(item.info, best.entry.media?.badges().orEmpty()) }
        item { PlayabilityBlock(vm, best) }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val resume = progress?.takeIf { !it.finished && it.positionMs > 30_000 }
                PillButton(
                    if (resume != null) "Resume  ${formatDuration(resume.positionMs)}" else "Play",
                    onClick = { vm.playVideo(best) },
                    modifier = Modifier.focusRequester(play),
                    primary = true,
                )
                item.info.trailerKey?.let { key -> PillButton("Trailer", onClick = { openTrailer(context, key) }) }
                PillButton(
                    if (watched) "Mark as not watched" else "Mark as watched",
                    onClick = { vm.store.setWatched(item.files.map { it.key }, !watched) },
                )
                PillButton("Fix match", onClick = { vm.open(Screen.FixMatch(item.info.key, best.parsed.title, isShow = false)) })
            }
        }
        // More than one copy on the phone (for example 4K and 1080p): let the viewer choose.
        if (item.files.size > 1) {
            item { Text("Versions", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp)) }
            itemsIndexed(item.files) { _, file ->
                FocusRow(onClick = { vm.playVideo(file) }) {
                    RowIcon(R.drawable.ic_movie)
                    Column(Modifier.weight(1f)) {
                        Text(file.entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(Formatter.formatShortFileSize(context, file.entry.sizeBytes), color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    file.entry.media?.let { BadgeRow(it.badges()) }
                }
            }
        }
    }
}

@Composable
fun ShowDetailScreen(vm: AppViewModel, screen: Screen.ShowDetail) {
    val show = vm.library.shows.firstOrNull { it.info.key == screen.titleKey } ?: return Gone(vm::back)
    val context = LocalContext.current
    val version = vm.store.version
    val next = remember(show, version) {
        show.episodes.firstOrNull { vm.store.progress(it.key)?.finished != true } ?: show.episodes.first()
    }
    val season = screen.season ?: next.parsed.season ?: show.seasons.first()
    val episodes = remember(show, season) { show.episodes.filter { it.parsed.season == season } }
    val names = remember(show, season, version) {
        vm.store.season("${show.info.key}:$season").orEmpty().associateBy { it.number }
    }
    LaunchedEffect(show.info.key, season) { vm.ensureSeason(show.info, season) }
    val play = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { play.requestFocus() } }

    LaunchedEffect(show.info.key) { vm.showArt(show.info) }
    DetailPage(show.info.backdrop, vm::back) {
        item { TitleHeader(show.info) }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val resuming = vm.store.resumePosition(next.key) > 0
                PillButton(
                    "${if (resuming) "Resume" else "Play"}  S${next.parsed.season} E${next.parsed.episode}",
                    onClick = { vm.playVideo(next, show.episodes) },
                    modifier = Modifier.focusRequester(play),
                    primary = true,
                )
                show.info.trailerKey?.let { key -> PillButton("Trailer", onClick = { openTrailer(context, key) }) }
                PillButton("Fix match", onClick = { vm.open(Screen.FixMatch(show.info.key, next.parsed.title, isShow = true)) })
            }
        }
        item { PlayabilityBlock(vm, next) }
        if (show.seasons.size > 1) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    show.seasons.forEach { number ->
                        PillButton("Season $number", onClick = { screen.season = number }, primary = number == season)
                    }
                }
            }
        }
        itemsIndexed(episodes, key = { _, file -> file.key }) { _, file ->
            val p = vm.store.progress(file.key)
            val info = names[file.parsed.episode]
            val tv = LocalIsTv.current
            FocusRow(onClick = { vm.playVideo(file, show.episodes) }) {
                // The episode's still from TMDB, or a picture from inside the video when there is none.
                Box(
                    Modifier
                        .size(if (tv) 208.dp else 144.dp, if (tv) 117.dp else 81.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Panel),
                ) {
                    AsyncImage(
                        tmdbImage(info?.still, "w300") ?: vm.stillOf(file, p?.positionMs?.takeIf { !p.finished }),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Text(
                        "E${file.parsed.episode}",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                            .background(Color(0xB3000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    ProgressEdge(p?.takeIf { !it.finished && it.durationMs > 0 }?.let { it.positionMs.toFloat() / it.durationMs })
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        info?.name?.ifEmpty { null } ?: "Episode ${file.parsed.episode}",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    info?.overview?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    val state = when {
                        p == null -> null
                        p.finished -> "Watched"
                        p.durationMs > 0 -> formatDuration(p.durationMs - p.positionMs)?.let { "$it left" }
                        else -> null
                    }
                    val details = listOfNotNull(formatDuration(file.entry.media?.durationMs ?: 0), state).joinToString("  ·  ")
                    if (details.isNotEmpty()) Text(details, color = Muted, style = MaterialTheme.typography.bodySmall)
                    // Under the text, so the episode name keeps the full width on a phone.
                    file.entry.media?.let { Box(Modifier.padding(top = 4.dp)) { BadgeRow(it.badges()) } }
                }
            }
        }
    }
}

@Composable
fun AlbumDetailScreen(vm: AppViewModel, screen: Screen.AlbumDetail) {
    val album = vm.library.albums.firstOrNull { it.key == screen.albumKey } ?: return Gone(vm::back)
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    DetailPage(backdrop = null, onBack = vm::back) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(120.dp).clip(RoundedCornerShape(10.dp))) {
                    Icon(painterResource(R.drawable.ic_music), null, tint = Muted, modifier = Modifier.align(Alignment.Center).size(40.dp))
                    AsyncImage(album.artUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(album.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(listOfNotNull(album.artist, "${album.tracks.size} tracks").joinToString("  ·  "), color = Muted)
                }
            }
        }
        itemsIndexed(album.tracks) { index, track ->
            FocusRow(
                onClick = { vm.playAlbum(album, index) },
                modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
            ) {
                Text("${index + 1}", color = Muted, modifier = Modifier.width(28.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    track.entry.media?.artist?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                }
                formatDuration(track.entry.media?.durationMs ?: 0)?.let { Text(it, color = Muted) }
            }
        }
    }
}
