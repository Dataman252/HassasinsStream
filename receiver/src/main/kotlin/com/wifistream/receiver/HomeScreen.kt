package com.wifistream.receiver

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.wifistream.protocol.KeyState
import com.wifistream.protocol.KeyStatus
import com.wifistream.protocol.badges

@Composable
fun HomeScreen(vm: AppViewModel, screen: Screen.Home) {
    Column(Modifier.fillMaxSize()) {
        // Preview caching shows its progress here, above the tabs, and folds away when done.
        ThumbProgressBar(Modifier.align(Alignment.CenterHorizontally))
        TabBar(vm, screen.tab) { screen.tab = it }
        when (screen.tab) {
            Tab.HOME -> HomeTab(vm, screen)
            Tab.MOVIES -> PosterGrid(vm, screen, vm.library.movies, "No movies found yet.")
            Tab.SHOWS -> PosterGrid(vm, screen, vm.library.shows, "No shows found yet.")
            Tab.MUSIC -> AlbumGrid(vm, screen)
            Tab.FOLDERS -> RootsList(vm)
            Tab.SETTINGS -> SettingsTab(vm)
        }
    }
}

@Composable
private fun TabBar(vm: AppViewModel, selected: Tab, onSelect: (Tab) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandMark(34.dp, Modifier.padding(start = screenMargin()))
        // The tabs sit in one floating pill of glass; the box keeps the refresh button at the far right.
        Box(Modifier.weight(1f)) { LazyRow(
            modifier = Modifier
                .padding(start = 12.dp, end = 12.dp)
                .glass(RoundedCornerShape(50)),
            contentPadding = PaddingValues(5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(Tab.entries) { tab ->
                val interaction = remember { MutableInteractionSource() }
                val focused by interaction.collectIsFocusedAsState()
                val shape = RoundedCornerShape(50)
                Text(
                    tab.label,
                    fontWeight = if (tab == selected) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        focused -> OnAccent
                        tab == selected -> Ink
                        else -> Muted
                    },
                    modifier = Modifier
                        .clip(shape)
                        .background(if (focused) Amber else if (tab == selected) PanelFocused else Color.Transparent)
                        .clickable(interactionSource = interaction, indication = LocalIndication.current) { onSelect(tab) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        } }
        if (LocalIsTv.current && LocalIsTvSnapshot.realTv) {
            Text(vm.connection?.info?.name.orEmpty(), color = Muted, maxLines = 1, modifier = Modifier.padding(end = 12.dp))
        }
        RefreshButton(vm.refreshing, Modifier.padding(end = screenMargin())) { vm.refreshLibrary(rematch = true, recheckKeys = true) }
    }
}

/** Re-reads the phone's library; spins while that is happening. */
@Composable
private fun RefreshButton(busy: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val spin = androidx.compose.animation.core.rememberInfiniteTransition(label = "refresh")
    val angle by spin.animateFloat(
        0f,
        360f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1_000, easing = androidx.compose.animation.core.LinearEasing)),
        label = "angle",
    )
    Box(
        modifier
            .size(44.dp)
            .clip(RoundedCornerShape(50))
            .then(if (focused) Modifier.background(Amber) else Modifier.glass(RoundedCornerShape(50)))
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_refresh),
            contentDescription = "Refresh library",
            tint = if (focused) OnAccent else Ink,
            modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = if (busy) angle else 0f },
        )
    }
}

/** What to tell the viewer when the connected phone's TMDB key is not working; null when it is fine. */
private fun keyNotice(vm: AppViewModel): Pair<String, String>? {
    val phone = vm.connection?.info?.name ?: return null
    val where = if (vm.connectedToSelf) "Press here to add one." else "Add one on $phone: Settings, Share this device, API keys. Then press here."
    return when (vm.remoteKeys?.tmdb) {
        KeyState.MISSING -> "$phone has no TMDB key, so there are no posters or details" to where
        KeyState.REJECTED -> "TMDB did not accept $phone's key" to "Check the key on $phone. Then press here to check again."
        KeyState.OFFLINE -> "$phone cannot reach the internet" to "Posters and details load once it is online. Press here to check again."
        else -> null
    }
}

/**
 * Remembers which tile had focus and hands [requester] to that tile, so the screen can
 * put focus back on it after returning from a detail page or the player.
 */
private fun Modifier.restoreFocus(home: Screen.Home, key: String, requester: FocusRequester): Modifier =
    onFocusChanged { if (it.hasFocus) home.lastFocus = key }
        .then(if (home.lastFocus == key) Modifier.focusRequester(requester) else Modifier)

// ---------- Home tab ----------

@Composable
private fun HomeTab(vm: AppViewModel, home: Screen.Home) {
    val lib = vm.library
    val version = vm.store.version
    val continuing = remember(lib, version) { vm.continueWatching() }
    val recent = remember(lib) {
        (lib.movies.map { it to it.files.maxOf { f -> f.entry.modifiedMs } } +
            lib.shows.map { it to it.episodes.maxOf { f -> f.entry.modifiedMs } })
            .sortedByDescending { it.second }.take(20).map { it.first }
    }
    val heroFile = continuing.firstOrNull { vm.titleOf(it) != null }
    val heroInfo = heroFile?.let(vm::titleOf) ?: when (val newest = recent.firstOrNull()) {
        is MovieItem -> newest.info
        is ShowItem -> newest.info
        else -> null
    }
    val restore = remember { FocusRequester() }
    // With a remote, start on the hero's main button; after Back, return to the tile that was left.
    LaunchedEffect(heroInfo?.key, lib.loaded) {
        // Rows appear above the fold as titles are recognised; keep the top in view until the viewer moves.
        if (heroInfo != null && vm.ambient == null) vm.showArt(heroInfo)
        if (home.lastFocus == HERO) home.homeList.scrollToItem(0)
        withFrameNanos { }
        runCatching { restore.requestFocus() }
    }

    // Everything is sized from the space this screen really has: the poster takes a share of the
    // height, and cards are as wide as fits a whole number across with the next one peeking in.
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
    val wide = LocalWide.current
    val usable = maxWidth - screenMargin()
    val gap = 14.dp
    val across = when {
        !wide -> 1.45f
        maxWidth >= 700.dp -> 3.3f
        else -> 2.3f
    }
    val cardWidth = (usable - gap * across.toInt()) / across
    val posterWidth = (usable - gap * 3) / 3.25f
    val heroHeight = if (wide) (maxHeight * 0.6f).coerceIn(196.dp, 460.dp) else null
    androidx.compose.runtime.CompositionLocalProvider(
        LocalCardWidth provides cardWidth,
        LocalPosterWidth provides posterWidth,
        LocalHeroHeight provides heroHeight,
    ) {
    LazyColumn(
        state = home.homeList,
        contentPadding = PaddingValues(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (!lib.loaded) {
            item {
                Row(Modifier.padding(horizontal = screenMargin(), vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("  Reading the phone's library…", color = Muted)
                }
            }
        }
        keyNotice(vm)?.let { (headline, detail) ->
            item {
                FocusRow(
                    onClick = { if (vm.connectedToSelf && vm.remoteKeys?.tmdb == KeyState.MISSING) vm.open(Screen.Keys) else vm.checkRemoteKeys() },
                    modifier = Modifier.padding(horizontal = screenMargin()),
                ) {
                    Column {
                        Text(headline, style = MaterialTheme.typography.titleMedium)
                        Text(detail, color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (lib.loaded && vm.pendingScans > 0) {
            item {
                Text(
                    "${vm.connection?.info?.name ?: "The phone"} is still reading ${vm.pendingScans} files. Quality tags fill in as it goes.",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = screenMargin()),
                )
            }
        }
        lib.error?.let { message ->
            item { Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = screenMargin())) }
        }
        if (heroInfo != null) {
            item(key = "hero") { Hero(vm, heroInfo, heroFile, Modifier.restoreFocus(home, HERO, restore)) }
        }
        if (continuing.isNotEmpty()) {
            section("Continue watching") {
                items(continuing, key = { "c" + it.key }) { file ->
                    val info = vm.titleOf(file)
                    val p = vm.store.progress(file.key)
                    WideCard(
                        title = info?.title ?: file.entry.name.substringBeforeLast('.'),
                        subtitle = listOfNotNull(
                            file.parsed.takeIf { it.isEpisode && info != null }?.let { "S${it.season} E${it.episode}" },
                            p?.let { formatDuration(it.durationMs - it.positionMs)?.let { left -> "$left left" } },
                        ).joinToString("  ·  ").ifEmpty { null },
                        imageUrl = tmdbImage(info?.backdrop, "w780") ?: vm.stillOf(file, p?.positionMs),
                        progress = p?.let { it.positionMs.toFloat() / it.durationMs },
                        onClick = { vm.playVideo(file, vm.queueFor(file)) },
                        modifier = Modifier.restoreFocus(home, "c" + file.key, restore),
                    )
                }
            }
        }
        if (recent.isNotEmpty()) {
            section("Recently added") {
                items(recent) { item -> TitlePoster(vm, item, Modifier.restoreFocus(home, "r" + titleKeyOf(item), restore)) }
            }
        }
        if (lib.movies.isNotEmpty()) {
            section("Movies") {
                items(lib.movies, key = { "m" + it.info.key }) { TitlePoster(vm, it, Modifier.restoreFocus(home, "m" + it.info.key, restore)) }
            }
        }
        if (lib.shows.isNotEmpty()) {
            section("Shows") {
                items(lib.shows, key = { "s" + it.info.key }) { TitlePoster(vm, it, Modifier.restoreFocus(home, "s" + it.info.key, restore)) }
            }
        }
        if (lib.others.isNotEmpty()) {
            section("Other videos") {
                items(lib.others.take(30), key = { "o" + it.key }) { file ->
                    val p = vm.store.progress(file.key)
                    WideCard(
                        title = file.entry.name.substringBeforeLast('.'),
                        subtitle = file.entry.media?.badges()?.joinToString("  ·  ")?.ifEmpty { null },
                        imageUrl = vm.stillOf(file),
                        progress = p?.takeIf { !it.finished && it.durationMs > 0 }?.let { it.positionMs.toFloat() / it.durationMs },
                        onClick = { vm.playVideo(file) },
                        modifier = Modifier.restoreFocus(home, "o" + file.key, restore),
                    )
                }
            }
        }
        if (lib.loaded && lib.movies.isEmpty() && lib.shows.isEmpty() && lib.others.isEmpty()) {
            item {
                val sharer = vm.connection?.info
                Text(
                    // Nothing shared at all usually means the drive was unplugged, not an empty library.
                    if (sharer != null && sharer.roots.isEmpty()) {
                        "${sharer.name} is not sharing any folders or drives right now. If the videos are on a USB drive, " +
                            "check it is plugged in. The library comes back by itself once it is."
                    } else {
                        "No movies or shows found on the phone yet. Everything is still reachable under Folders."
                    },
                    color = Muted,
                    modifier = Modifier.padding(horizontal = screenMargin(), vertical = 12.dp),
                )
            }
        }
    }
    }
    }
}

private fun LazyListScope.section(title: String, content: LazyListScope.() -> Unit) {
    item(key = "section-$title") {
        Column {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = screenMargin()),
            )
            // Vertical room so a focused, enlarged card is not cut off by the row.
            LazyRow(
                contentPadding = PaddingValues(horizontal = screenMargin(), vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun Hero(vm: AppViewModel, info: TitleInfo, resumeFile: VideoFile?, playModifier: Modifier) {
    val tall = LocalIsTv.current
    // Its height is a share of the screen (on TVs and tablets); a short one uses tighter type.
    val heroHeight = LocalHeroHeight.current
    val compact = heroHeight != null && heroHeight < 260.dp
    val heroShape = RoundedCornerShape(28.dp)
    Box(
        Modifier
            .padding(horizontal = screenMargin())
            .fillMaxWidth()
            // At least this tall, and taller when a long title needs the room, so the buttons are never squeezed.
            .then(if (heroHeight != null) Modifier.height(heroHeight) else Modifier.heightIn(min = 230.dp))
            .clip(heroShape)
            .border(1.dp, Brush.linearGradient(listOf(Color(0x8CFFFFFF), Color(0x14FFFFFF))), heroShape),
    ) {
        AsyncImage(
            tmdbImage(info.backdrop, "w1280"),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier.matchParentSize(),
        )
        // A cinematic shade in both themes: darker behind the words, the picture left clear on the right.
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(Color(0xE607080F), Color(0x8007080F), Color.Transparent))))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x1A07080F), Color(0xCC07080F)))))
        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 24.dp, end = 24.dp, top = if (heroHeight != null) 16.dp else 64.dp, bottom = if (compact) 14.dp else 18.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 8.dp),
        ) {
            Text(
                info.title,
                style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(metaLine(info, resumeFile?.parsed?.takeIf { it.isEpisode }?.let { "S${it.season} E${it.episode}" }), color = Color(0xFFCBD5E1))
            val file = resumeFile ?: vm.firstFileOf(info)
            file?.entry?.media?.let { BadgeRow(it.badges(), onDark = true) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                if (file != null) {
                    PillButton(
                        if (resumeFile != null) "Resume" else "Play",
                        onClick = { vm.playVideo(file, vm.queueFor(file)) },
                        modifier = playModifier,
                        primary = true,
                        onDark = true,
                    )
                }
                PillButton("Details", onClick = { vm.openDetails(info) }, modifier = if (file == null) playModifier else Modifier, onDark = true)
            }
        }
    }
}

fun metaLine(info: TitleInfo, lead: String? = null): String = listOfNotNull(
    lead,
    info.year,
    info.runtimeMin?.takeIf { it > 0 }?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" },
    info.rating.takeIf { it > 0 }?.let { "★ %.1f".format(it) },
    info.genres.take(2).joinToString(", ").ifEmpty { null },
).joinToString("  ·  ")

/** Poster for a movie or a show, with its quality tag and watch state. */
private fun titleKeyOf(item: Any): String = when (item) {
    is MovieItem -> item.info.key
    is ShowItem -> item.info.key
    else -> ""
}

@Composable
fun TitlePoster(vm: AppViewModel, item: Any, modifier: Modifier = Modifier, inGrid: Boolean = false) {
    val version = vm.store.version
    if (LocalWide.current) {
        TitleLandscape(vm, item, modifier, inGrid)
        return
    }
    when (item) {
        is MovieItem -> {
            val best = item.files.first()
            val state = remember(item, version) { item.files.mapNotNull { vm.store.progress(it.key) } }
            PosterCard(
                title = item.info.title,
                imageUrl = tmdbImage(item.info.poster, "w342"),
                tag = best.qualityTag,
                progress = state.firstOrNull { !it.finished && it.durationMs > 0 }?.let { it.positionMs.toFloat() / it.durationMs },
                watched = state.any { it.finished },
                onClick = { vm.openDetails(item.info) },
                modifier = modifier,
                // The backdrop takes on the colours of whichever poster the remote is on.
                onFocused = { vm.showArt(item.info) },
            )
        }
        is ShowItem -> {
            val seen = remember(item, version) { item.episodes.count { vm.store.progress(it.key)?.finished == true } }
            PosterCard(
                title = item.info.title,
                imageUrl = tmdbImage(item.info.poster, "w342"),
                tag = "${item.episodes.size} ep",
                progress = if (seen in 1 until item.episodes.size) seen.toFloat() / item.episodes.size else null,
                watched = seen == item.episodes.size,
                onClick = { vm.openDetails(item.info) },
                modifier = modifier,
                // The backdrop takes on the colours of whichever poster the remote is on.
                onFocused = { vm.showArt(item.info) },
            )
        }
    }
}

/** The landscape version of [TitlePoster], for TVs and tablets. */
@Composable
private fun TitleLandscape(vm: AppViewModel, item: Any, modifier: Modifier, inGrid: Boolean) {
    val version = vm.store.version
    val width = if (inGrid) androidx.compose.ui.unit.Dp.Unspecified else wideCardWidth()
    when (item) {
        is MovieItem -> {
            val state = remember(item, version) { item.files.mapNotNull { vm.store.progress(it.key) } }
            LandscapeCard(
                title = item.info.title,
                imageUrl = tmdbImage(item.info.backdrop ?: item.info.poster, "w780"),
                tag = item.files.first().qualityTag,
                progress = state.firstOrNull { !it.finished && it.durationMs > 0 }?.let { it.positionMs.toFloat() / it.durationMs },
                watched = state.any { it.finished },
                onClick = { vm.openDetails(item.info) },
                modifier = modifier,
                width = width,
                onFocused = { vm.showArt(item.info) },
            )
        }
        is ShowItem -> {
            val seen = remember(item, version) { item.episodes.count { vm.store.progress(it.key)?.finished == true } }
            LandscapeCard(
                title = item.info.title,
                imageUrl = tmdbImage(item.info.backdrop ?: item.info.poster, "w780"),
                tag = "${item.episodes.size} ep",
                progress = if (seen in 1 until item.episodes.size) seen.toFloat() / item.episodes.size else null,
                watched = seen == item.episodes.size,
                onClick = { vm.openDetails(item.info) },
                modifier = modifier,
                width = width,
                onFocused = { vm.showArt(item.info) },
            )
        }
    }
}

@Composable
private fun PosterGrid(vm: AppViewModel, home: Screen.Home, items: List<Any>, emptyText: String) {
    if (items.isEmpty()) {
        Text(
            if (vm.library.loaded) emptyText else "Reading the phone's library…",
            color = Muted,
            modifier = Modifier.padding(horizontal = screenMargin(), vertical = 12.dp),
        )
        return
    }
    val restore = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { restore.requestFocus() }
    }
    LazyVerticalGrid(
        state = home.grids.getValue(home.tab),
        columns = GridCells.Adaptive(if (LocalWide.current) 250.dp else 120.dp),
        contentPadding = PaddingValues(start = screenMargin(), end = screenMargin(), top = 10.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(items) { TitlePoster(vm, it, Modifier.restoreFocus(home, "g" + titleKeyOf(it), restore), inGrid = true) }
    }
}

// ---------- Music ----------

@Composable
private fun AlbumGrid(vm: AppViewModel, home: Screen.Home) {
    val albums = vm.library.albums
    if (albums.isEmpty()) {
        Text(
            if (vm.library.loaded) "No music found on the phone." else "Reading the phone's library…",
            color = Muted,
            modifier = Modifier.padding(horizontal = screenMargin(), vertical = 12.dp),
        )
        return
    }
    val restore = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { restore.requestFocus() }
    }
    LazyVerticalGrid(
        state = home.grids.getValue(Tab.MUSIC),
        columns = GridCells.Adaptive(140.dp),
        contentPadding = PaddingValues(start = screenMargin(), end = screenMargin(), top = 10.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(albums, key = { it.key }) { album ->
            Column(Modifier.restoreFocus(home, "a" + album.key, restore)) {
                FocusCard(onClick = { vm.open(Screen.AlbumDetail(album.key)) }, Modifier.fillMaxWidth().aspectRatio(1f)) {
                    Icon(painterResource(R.drawable.ic_music), null, tint = Muted, modifier = Modifier.align(Alignment.Center).size(40.dp))
                    AsyncImage(album.artUrl, contentDescription = album.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                Text(
                    album.artist ?: "${album.tracks.size} tracks",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---------- Settings ----------

@Composable
private fun SettingsTab(vm: AppViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isTv = LocalIsTv.current
    // Settings open on a menu of categories (tiles on a TV, a short list on a phone); each has its own page.
    var section by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<SettingsSection?>(null) }
    androidx.activity.compose.BackHandler(enabled = section != null) { section = null }
    fun shows(which: SettingsSection) = section == which
    if (section == null) {
        if (isTv) SettingsGrid { section = it } else SettingsList { section = it }
        return
    }
    val version = vm.store.version
    val dvOff = remember(version) { vm.store.dvAsHdr10 }
    LazyColumn(
        contentPadding = PaddingValues(start = screenMargin(), end = screenMargin(), top = 8.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        section?.let { open ->
            item {
                ScreenTitle(open.label, subtitle = if (isTv) "Press Back for all settings" else null, onBack = { section = null })
            }
        }
        if (shows(SettingsSection.LOOK)) item { LookPanel() }
        // Connection: speed, the phone's keys, and the hotspot sit together.
        if (shows(SettingsSection.CONNECTION)) item {
            FocusRow(onClick = vm::runSpeedTest) {
                Column {
                    Text("Test connection speed", style = MaterialTheme.typography.titleMedium)
                    Text(
                        vm.speedMessage ?: "Checks whether the Wi-Fi between this device and the phone can keep up with 4K files.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (shows(SettingsSection.CONNECTION)) item {
            // Watching needs no keys: the connected phone looks things up with its own.
            FocusRow(onClick = vm::checkRemoteKeys) {
                Column(Modifier.weight(1f)) {
                    Text("Posters and subtitles", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Looked up with ${vm.connection?.info?.name ?: "the sharing phone"}'s keys. Press to check them again.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    KeyStates(vm.remoteKeys, Modifier.padding(top = 6.dp), Alignment.Start)
                }
            }
        }
        if (shows(SettingsSection.CONNECTION)) item {
            FocusRow(onClick = {
                runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)) }
            }) {
                Column(Modifier.weight(1f)) {
                    Text("Slow Wi-Fi? Use the phone's hotspot", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "On ${vm.connection?.info?.name ?: "the sharing phone"}: Share this device, then turn on its hotspot. " +
                            "Join it here in Wi-Fi settings; the app finds the phone again and carries on.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text("Wi-Fi", fontWeight = FontWeight.SemiBold)
            }
        }
        if (shows(SettingsSection.PLAYBACK) && !LocalIsTvSnapshot.isTv) {
            item {
                FocusRow(onClick = { vm.changeWideCards(!vm.wideCards) }) {
                    Column(Modifier.weight(1f)) {
                        Text("Wide picture cards", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Show movies and shows as wide pictures, like on a TV, instead of tall posters. Tablets always use them.",
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    GlassSwitch(vm.wideCards)
                }
            }
        }
        if (shows(SettingsSection.PLAYBACK)) item {
            FocusRow(onClick = { vm.store.setDvAsHdr10(!dvOff) }) {
                Column(Modifier.weight(1f)) {
                    Text("Play Dolby Vision as HDR10", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Turn on if Dolby Vision files show a black screen or wrong colours.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(if (dvOff) "On" else "Off", fontWeight = FontWeight.SemiBold)
            }
        }
        if (shows(SettingsSection.LIBRARY)) item {
            FocusRow(onClick = { vm.refreshLibrary(rematch = true, recheckKeys = true) }) {
                Column {
                    Text("Refresh library", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (vm.refreshing) "Refreshing…" else "Re-read the phone, check its keys, and look up titles that were not recognised.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (shows(SettingsSection.LIBRARY)) item {
            FocusRow(onClick = { vm.store.clearHistory() }) {
                Column {
                    Text("Clear watch history", style = MaterialTheme.typography.titleMedium)
                    Text("Forget what has been watched and where each video was stopped.", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (shows(SettingsSection.LIBRARY)) item {
            FocusRow(onClick = { Thumbs.changeEnabled(!Thumbs.enabled) }) {
                Column(Modifier.weight(1f)) {
                    Text("Cache seek-bar previews", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Saves a small picture every 10 seconds of every video on this device, so dragging the seek bar is instant. " +
                            "The video playing goes first, then the ones you just watched.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                GlassSwitch(Thumbs.enabled)
            }
        }
        if (shows(SettingsSection.LIBRARY)) item {
            Column(
                Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OptionRow(
                    "Preview speed",
                    Thumbs.speed.hint,
                    PreviewSpeed.entries.map { LookOption(it.label) },
                    PreviewSpeed.entries.indexOf(Thumbs.speed),
                ) { Thumbs.changeSpeed(PreviewSpeed.entries[it]) }
                val steps = listOf(10_000L, 20_000L, 30_000L)
                OptionRow(
                    "Preview detail",
                    "A picture every so many seconds. Fewer pictures finish sooner.",
                    steps.map { LookOption("Every ${it / 1000} s") },
                    steps.indexOf(Thumbs.stepMs),
                ) { Thumbs.changeStep(steps[it]) }
            }
        }
        if (shows(SettingsSection.LIBRARY)) item {
            FocusRow(onClick = { Thumbs.cacheAllNow() }) {
                Column(Modifier.weight(1f)) {
                    Text("Cache all previews now", style = MaterialTheme.typography.titleMedium)
                    Text(
                        Thumbs.progress?.let { "Working: ${(it.fraction * 100).toInt()}% (${it.title})" }
                            ?: "Prepares every video's previews straight away, even while something is playing.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (shows(SettingsSection.LIBRARY)) item {
            FocusRow(onClick = { Thumbs.clear() }) {
                Column(Modifier.weight(1f)) {
                    Text("Clear preview cache", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Using ${"%.1f".format(Thumbs.sizeBytes / 1_048_576.0)} MB. Previews of videos that are no longer shared are removed by themselves.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (shows(SettingsSection.DEVICE)) item {
            FocusRow(onClick = { vm.open(Screen.Capabilities) }) {
                Column {
                    Text("What this device can play", style = MaterialTheme.typography.titleMedium)
                    Text("This setup: ${vm.capabilities.headline()}", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (shows(SettingsSection.SOURCES) && canShareFromThisDevice()) {
            item {
                FocusRow(onClick = { vm.open(Screen.Keys) }) {
                    Column(Modifier.weight(1f)) {
                        Text("This phone's API keys", style = MaterialTheme.typography.titleMedium)
                        Text("Used when this phone shares, for every device watching from it.", color = Muted, style = MaterialTheme.typography.bodySmall)
                        KeyStates(vm.keys.status, Modifier.padding(top = 6.dp), Alignment.Start)
                    }
                }
            }
        }
        if (shows(SettingsSection.SOURCES) && canShareFromThisDevice()) {
            item {
                FocusRow(onClick = { vm.open(Screen.Share) }) {
                    Column {
                        Text("Share this device", style = MaterialTheme.typography.titleMedium)
                        Text("Let other devices play chosen folders from this one.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (shows(SettingsSection.SOURCES)) item {
            FocusRow(onClick = { vm.open(Screen.Connect) }) {
                Column {
                    Text("Play from another device", style = MaterialTheme.typography.titleMedium)
                    Text("Connected to ${vm.connection?.info?.name.orEmpty()}", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (shows(SettingsSection.ABOUT)) item {
            Spacer(Modifier.height(8.dp))
            Text(
                "Film and show information and images come from TMDB. " +
                    "This product uses the TMDB API but is not endorsed or certified by TMDB.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** "TMDB Active / OpenSubtitles No key", coloured by state. */
@Composable
fun KeyStates(status: KeyStatus?, modifier: Modifier = Modifier, alignment: Alignment.Horizontal = Alignment.End) {
    Column(modifier, horizontalAlignment = alignment) {
        val tmdb = status?.tmdb
        val subs = status?.openSubtitles
        Text("TMDB: " + (tmdb?.label() ?: "Unknown"), color = tmdb?.colour() ?: Muted, style = MaterialTheme.typography.bodySmall)
        Text("OpenSubtitles: " + (subs?.label() ?: "Unknown"), color = subs?.colour() ?: Muted, style = MaterialTheme.typography.bodySmall)
    }
}

/** A picture from inside the video, for files TMDB does not know: a tenth of the way in, or where it was left. */
fun AppViewModel.stillOf(file: VideoFile, atMs: Long? = null): String? {
    val url = urlOf(file) ?: return null
    val duration = file.entry.media?.durationMs ?: 0
    val at = atMs?.takeIf { it > 0 } ?: if (duration > 0) duration / 10 else 120_000
    return frameUrl(url, at)
}

// ---------- helpers shared with the detail pages ----------

fun AppViewModel.openDetails(info: TitleInfo) =
    open(if (info.isMovie) Screen.MovieDetail(info.key) else Screen.ShowDetail(info.key))

/** For an episode: the whole show in order, so the next one follows. Otherwise just the file. */
fun AppViewModel.queueFor(file: VideoFile): List<VideoFile> =
    library.shows.firstOrNull { show -> show.episodes.any { it.key == file.key } }?.episodes ?: listOf(file)

/** The file Play starts: the movie's best copy, or the first episode not yet finished. */
fun AppViewModel.firstFileOf(info: TitleInfo): VideoFile? =
    if (info.isMovie) {
        library.movies.firstOrNull { it.info.key == info.key }?.files?.firstOrNull()
    } else {
        library.shows.firstOrNull { it.info.key == info.key }?.episodes?.let { episodes ->
            episodes.firstOrNull { store.progress(it.key)?.finished != true } ?: episodes.firstOrNull()
        }
    }

/** The sections of Settings, shown as icon tiles on a TV. */
enum class SettingsSection(val label: String, val detail: String, val icon: Int) {
    LOOK("Look", "Theme, colours, glass, icon", R.drawable.ic_palette),
    CONNECTION("Connection", "Speed test, keys, hotspot", R.drawable.ic_wifi),
    PLAYBACK("Playback", "Dolby Vision, card style", R.drawable.ic_movie),
    LIBRARY("Library", "Refresh, watch history", R.drawable.ic_history),
    DEVICE("This device", "What it can play", R.drawable.ic_tv),
    SOURCES("Sources", "Phones to play from", R.drawable.ic_phone),
    ABOUT("About", "Credits", R.drawable.ic_info),
}

/** The phone version of the settings menu: one compact card per category. */
@Composable
private fun SettingsList(onOpen: (SettingsSection) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(start = screenMargin(), end = screenMargin(), top = 8.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(SettingsSection.entries) { s ->
            FocusRow(onClick = { onOpen(s) }) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(BrandGradient)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(s.icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(s.label, style = MaterialTheme.typography.titleMedium)
                    Text(s.detail, color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                Text("›", color = Muted, style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

/** Large icon tiles, easy to reach with a remote. Sized to fit the screen, so nothing needs scrolling. */
@Composable
private fun SettingsGrid(onOpen: (SettingsSection) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.fillMaxSize().padding(start = screenMargin(), end = screenMargin(), top = 8.dp, bottom = 16.dp),
    ) {
        val gap = 16.dp
        val count = SettingsSection.entries.size
        // Wide screens take four across in two rows; narrower ones three or two across.
        val columns = when {
            maxWidth > maxHeight * 1.3f -> 4
            maxWidth > 560.dp -> 3
            else -> 2
        }
        val rows = (count + columns - 1) / columns
        val tileWidth = (maxWidth - gap * (columns - 1)) / columns
        val tileHeight = ((maxHeight - gap * (rows - 1)) / rows).coerceIn(84.dp, 170.dp)
        val roomy = tileHeight >= 120.dp
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            SettingsSection.entries.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { s ->
                        FocusCard(
                            onClick = { onOpen(s) },
                            modifier = (if (s == SettingsSection.LOOK) Modifier.focusRequester(first) else Modifier).size(tileWidth, tileHeight),
                        ) {
                            Column(
                                Modifier.fillMaxSize().padding(if (roomy) 16.dp else 12.dp),
                                verticalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Box(
                                    Modifier.size(if (roomy) 48.dp else 36.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(BrandGradient)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(painterResource(s.icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(if (roomy) 28.dp else 22.dp))
                                }
                                Column {
                                    Text(s.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    if (roomy) Text(s.detail, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
