package com.wifistream.receiver

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.wifistream.protocol.badges

// ---------- shared pieces ----------

/** Solid tile colour for the Material and Bento layouts. */
private val TileFill: Color get() = if (AppTheme.light) Color(0x0F000000) else Color(0xFF18181D)

/** A tile that grows a little and gets an accent ring when the remote lands on it. */
@Composable
fun LayoutTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    fill: Color = TileFill,
    onFocused: () -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    androidx.compose.runtime.LaunchedEffect(focused) { if (focused) onFocused() }
    val scale by animateFloatAsState(if (pressed) 0.96f else if (focused) 1.04f else 1f, label = "tile")
    Box(
        modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(fill)
            .then(if (focused) Modifier.border(3.dp, Accent, shape) else Modifier)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick),
        content = content,
    )
}

/** Small capital label used on tiles: "RESUME", "SHARING". */
@Composable
private fun TileLabel(text: String, color: Color = Muted) {
    Text(text.uppercase(), color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, maxLines = 1)
}

/** A picture filling its box with a shade at the bottom so white text reads on any image. */
@Composable
private fun BoxScope.ShadedPicture(url: String?) {
    if (url != null) {
        AsyncImage(url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
    } else {
        Box(Modifier.matchParentSize().background(Brush.linearGradient(BrandGradient.map { it.copy(alpha = 0.7f) })))
    }
    Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x3307080F), Color(0xE607080F)))))
}

// ---------- Spotlight ----------

/** Spotlight's menu down the left side, for TVs and tablets. */
@Composable
fun SideRail(vm: AppViewModel, selected: Tab, onSelect: (Tab) -> Unit) {
    Column(
        Modifier
            .fillMaxHeight()
            .width(136.dp)
            // Mostly see-through: a light shade behind the words, fading into the artwork.
            .background(Brush.horizontalGradient(listOf(Color(0x66000000), Color(0x14000000))))
            .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        // The sharing device on top, with Refresh beside it.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Text(
                vm.connection?.info?.name ?: "Not connected",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 6.dp),
            )
            RefreshButton(vm.refreshing, Modifier, size = 34.dp) { vm.refreshLibrary(rematch = true, recheckKeys = true) }
        }
        // The menu shares out the height that is left, so it always fits without scrolling.
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.SpaceEvenly) {
            Tab.entries.forEach { tab ->
                val interaction = remember { MutableInteractionSource() }
                val focused by interaction.collectIsFocusedAsState()
                val on = tab == selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (focused) Accent else Color.Transparent)
                        .clickable(interactionSource = interaction, indication = LocalIndication.current) { onSelect(tab) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(width = 3.dp, height = 16.dp).background(if (on && !focused) Accent else Color.Transparent))
                    Text(
                        "  " + tab.label.uppercase(),
                        fontFamily = LayoutDisplayFont,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                        letterSpacing = 1.sp,
                        fontSize = 13.sp,
                        maxLines = 1,
                        color = when {
                            focused -> OnAccent
                            on -> Color.White
                            else -> Color(0xFFD6D3D1)
                        },
                    )
                }
            }
        }
    }
}

/** Spotlight's hero: the artwork edge to edge, a tall bold title and a gold Play. */
@Composable
fun SpotlightHero(vm: AppViewModel, info: TitleInfo, resumeFile: VideoFile?, playModifier: Modifier) {
    val base = LocalHeroHeight.current
    val height = base?.let { (it * 1.15f).coerceAtMost(560.dp) } ?: 420.dp
    // At least this tall, and taller when a long title needs it, so the buttons are never squeezed out.
    Box(Modifier.fillMaxWidth().heightIn(min = height)) {
        AsyncImage(
            tmdbImage(info.backdrop ?: info.poster, "w1280"),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier.matchParentSize(),
        )
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(Color(0xF20C0A09), Color(0x990C0A09), Color.Transparent))))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Night))))
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = screenMargin(), end = screenMargin(), top = 24.dp, bottom = 20.dp).widthIn(max = 720.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TileLabel(if (resumeFile != null) "Continue" else "Featured", Accent)
            Text(
                info.title.uppercase(),
                style = MaterialTheme.typography.displaySmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 40.sp,
            )
            Text(metaLine(info, resumeFile?.parsed?.takeIf { it.isEpisode }?.let { "S${it.season} E${it.episode}" }), color = Color(0xFFD6D3D1))
            if (info.overview.isNotBlank()) {
                Text(info.overview, color = Color(0xFFE7E5E4), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            }
            val file = resumeFile ?: vm.firstFileOf(info)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
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

// ---------- Material ----------

/** Material's featured carousel: big rounded cards that snap into place. */
@Composable
fun MaterialCarousel(vm: AppViewModel, featured: List<TitleInfo>, firstModifier: Modifier) {
    val state = rememberLazyListState()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = LocalWide.current
        val cardWidth = if (wide) maxWidth * 0.58f else maxWidth * 0.86f
        val cardHeight = if (wide) (LocalHeroHeight.current ?: 300.dp).coerceAtMost(360.dp) else 210.dp
        LazyRow(
            state = state,
            flingBehavior = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(state),
            contentPadding = PaddingValues(horizontal = screenMargin(), vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(featured, key = { _, it -> "f" + it.key }) { index, info ->
                LayoutTile(
                    onClick = { vm.openDetails(info) },
                    modifier = (if (index == 0) firstModifier else Modifier).width(cardWidth).height(cardHeight),
                    shape = RoundedCornerShape(28.dp),
                    onFocused = { vm.showArt(info) },
                ) {
                    ShadedPicture(tmdbImage(info.backdrop ?: info.poster, "w1280"))
                    Column(Modifier.align(Alignment.BottomStart).padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(info.title, style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(metaLine(info), color = Color(0xFFE6E0E9), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** "Top picks": the best-rated titles with large outlined numbers, as in Google's TV kit. */
@Composable
fun TopPicks(vm: AppViewModel, picks: List<Any>) {
    Column {
        Text("Top picks", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = screenMargin()))
        LazyRow(
            contentPadding = PaddingValues(horizontal = screenMargin(), vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(picks) { index, item ->
                val info = when (item) {
                    is MovieItem -> item.info
                    is ShowItem -> item.info
                    else -> return@itemsIndexed
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "${index + 1}",
                        fontSize = 88.sp,
                        fontWeight = FontWeight.Bold,
                        color = Accent,
                        style = TextStyle(drawStyle = Stroke(width = 4f)),
                        modifier = Modifier.padding(end = 2.dp),
                    )
                    LayoutTile(
                        onClick = { vm.openDetails(info) },
                        modifier = Modifier.width(104.dp).height(156.dp),
                        shape = RoundedCornerShape(16.dp),
                        onFocused = { vm.showArt(info) },
                    ) {
                        AsyncImage(tmdbImage(info.poster, "w342"), contentDescription = info.title, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                    }
                }
            }
        }
    }
}

// ---------- Bento ----------

/**
 * Bento's home: everything at a glance in tiles of different sizes. Resume, the newest episode,
 * the sharing phone's health, music and the size of the library.
 */
@Composable
fun BentoTiles(vm: AppViewModel, home: Screen.Home, resumeFile: VideoFile?, heroInfo: TitleInfo?, firstModifier: Modifier) {
    val lib = vm.library
    val latestEpisode = remember(lib) { lib.shows.flatMap { show -> show.episodes.map { show to it } }.maxByOrNull { it.second.entry.modifiedMs } }
    val album = lib.albums.firstOrNull()
    val unit = if (LocalWide.current) 128.dp else 118.dp
    val gap = 10.dp
    val m = Modifier.padding(horizontal = screenMargin())

    @Composable
    fun Resume(modifier: Modifier) {
        val info = resumeFile?.let(vm::titleOf) ?: heroInfo
        val file = resumeFile ?: info?.let(vm::firstFileOf)
        val p = resumeFile?.let { vm.store.progress(it.key) }
        LayoutTile(
            onClick = { if (file != null) vm.playVideo(file, vm.queueFor(file)) else info?.let(vm::openDetails) },
            modifier = firstModifier.then(modifier),
            onFocused = { info?.let(vm::showArt) },
        ) {
            ShadedPicture(tmdbImage(info?.backdrop ?: info?.poster, "w1280") ?: resumeFile?.let { vm.stillOf(it, p?.positionMs) })
            Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                TileLabel(if (resumeFile != null) "Resume" else "Watch now", Accent)
                Text(
                    info?.title ?: resumeFile?.entry?.name?.substringBeforeLast('.') ?: "Nothing to resume yet",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val left = p?.let { formatDuration(it.durationMs - it.positionMs)?.let { l -> "$l left" } }
                val tags = file?.entry?.media?.badges()?.joinToString(" ")
                listOfNotNull(left, tags?.ifEmpty { null }).joinToString(" · ").ifEmpty { null }?.let {
                    Text(it, color = Color(0xFFD4D4D8), style = MaterialTheme.typography.bodySmall)
                }
            }
            ProgressEdge(p?.let { it.positionMs.toFloat() / it.durationMs })
        }
    }

    @Composable
    fun NewEpisode(modifier: Modifier) {
        val pair = latestEpisode
        LayoutTile(
            onClick = { pair?.let { (_, f) -> vm.playVideo(f, vm.queueFor(f)) } ?: run { home.tab = Tab.SHOWS } },
            modifier = modifier,
            onFocused = { pair?.first?.info?.let(vm::showArt) },
        ) {
            if (pair == null) {
                Column(Modifier.padding(14.dp)) {
                    TileLabel("Shows")
                    Text("No episodes yet", style = MaterialTheme.typography.titleMedium)
                }
            } else {
                val (show, file) = pair
                ShadedPicture(tmdbImage(show.info.backdrop ?: show.info.poster, "w780"))
                val fresh = System.currentTimeMillis() - file.entry.modifiedMs < 3 * 24 * 3_600_000L
                Text(
                    if (fresh) "New episode" else "Latest episode",
                    color = OnAccent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(10.dp).background(Accent, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
                )
                Text(
                    "${show.info.title} S${file.parsed.season} E${file.parsed.episode}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                )
            }
        }
    }

    @Composable
    fun Sharing(modifier: Modifier) {
        val info = vm.connection?.info
        val (dot, state) = when (vm.linkState) {
            LinkState.OK -> Color(0xFF4ADE80) to "Steady"
            LinkState.DRIVE_MISSING -> Color(0xFFF59E0B) to "Drive missing"
            LinkState.LOST -> Color(0xFFF87171) to "Reconnecting"
        }
        LayoutTile(onClick = { home.tab = Tab.SETTINGS }, modifier = modifier) {
            Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
                TileLabel("Sharing")
                Column {
                    Text(info?.name ?: "Not connected", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).background(dot, CircleShape))
                        val temp = info?.temperatureC?.takeIf { it > 0f }?.let { " · ${"%.0f".format(it)} °C" }.orEmpty()
                        Text("  $state$temp", color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                }
            }
        }
    }

    @Composable
    fun Count(label: String, count: Int, tab: Tab, modifier: Modifier) {
        LayoutTile(onClick = { home.tab = tab }, modifier = modifier) {
            Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
                TileLabel(label)
                Text("$count", fontFamily = LayoutDisplayFont, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp)
            }
        }
    }

    @Composable
    fun Music(modifier: Modifier) {
        LayoutTile(onClick = { if (album != null) vm.playAlbum(album, 0) else home.tab = Tab.MUSIC }, modifier = modifier) {
            Row(Modifier.fillMaxSize().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(BrandGradient))) {
                    if (album != null) AsyncImage(album.artUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                }
                Column(Modifier.weight(1f)) {
                    TileLabel("Music")
                    Text(album?.title ?: "No music yet", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (album != null) {
                    Box(Modifier.size(44.dp).background(Accent, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_play), contentDescription = "Play music", tint = OnAccent, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }

    if (LocalWide.current) {
        Column(m, verticalArrangement = Arrangement.spacedBy(gap)) {
            Row(Modifier.height(unit * 2 + gap), horizontalArrangement = Arrangement.spacedBy(gap)) {
                Resume(Modifier.weight(2f).fillMaxHeight())
                Column(Modifier.weight(2f), verticalArrangement = Arrangement.spacedBy(gap)) {
                    Row(Modifier.height(unit), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        NewEpisode(Modifier.weight(1f).fillMaxHeight())
                        Sharing(Modifier.weight(1f).fillMaxHeight())
                    }
                    Row(Modifier.height(unit), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        Count("Films", lib.movies.size, Tab.MOVIES, Modifier.weight(1f).fillMaxHeight())
                        Count("Shows", lib.shows.size, Tab.SHOWS, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
            Music(Modifier.fillMaxWidth().height(unit * 0.7f))
        }
    } else {
        Column(m, verticalArrangement = Arrangement.spacedBy(gap)) {
            Resume(Modifier.fillMaxWidth().height(unit * 2 + gap))
            Row(Modifier.height(unit), horizontalArrangement = Arrangement.spacedBy(gap)) {
                NewEpisode(Modifier.weight(1f).fillMaxHeight())
                Sharing(Modifier.weight(1f).fillMaxHeight())
            }
            Music(Modifier.fillMaxWidth().height(unit * 0.7f))
            Row(Modifier.height(unit * 0.8f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                Count("Films", lib.movies.size, Tab.MOVIES, Modifier.weight(1f).fillMaxHeight())
                Count("Shows", lib.shows.size, Tab.SHOWS, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

/**
 * Bento's pause screen: the picture stays in a large framed tile, with format, sound, time and
 * what comes next in tiles beside and below it.
 */
@Composable
fun BentoPausedOverlay(item: PlayItem, title: String, positionMs: Long, durationMs: Long, upNext: PlayItem?) {
    val dim = Color(0xE60B0B0E)
    val tile = Color(0xF218181D)
    val gap = 12.dp
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val pad = if (LocalIsTv.current) 40.dp else 16.dp
        val side = (maxWidth - pad * 2 - gap * 3) / 4
        val pictureHeight = (maxHeight - pad * 2) * 0.62f
        Column(Modifier.fillMaxSize()) {
            // Top band
            Box(Modifier.fillMaxWidth().height(pad).background(dim))
            Row(Modifier.fillMaxWidth().height(pictureHeight)) {
                Box(Modifier.width(pad).fillMaxHeight().background(dim))
                // The window onto the paused picture.
                Box(Modifier.width(side * 3 + gap * 2).fillMaxHeight().border(4.dp, Accent, RoundedCornerShape(24.dp))) {
                    Column(Modifier.align(Alignment.BottomStart).padding(18.dp).background(Color(0x99000000), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                        TileLabel("Paused", Accent)
                        Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Column(Modifier.fillMaxHeight().weight(1f).background(dim).padding(start = gap), verticalArrangement = Arrangement.spacedBy(gap)) {
                    BentoInfo("Format", item.media?.badges()?.joinToString(" · ")?.ifEmpty { null } ?: "—", null, tile, Modifier.weight(1f))
                    val audio = item.media?.let { listOfNotNull(it.audioCodec?.uppercase(), it.audioChannels.takeIf { c -> c > 0 }?.let { c -> "$c ch" }, "Atmos".takeIf { _ -> it.atmos }).joinToString(" · ") }
                    BentoInfo("Sound", audio?.ifEmpty { null } ?: "—", null, tile, Modifier.weight(1f))
                }
                Box(Modifier.width(pad).fillMaxHeight().background(dim))
            }
            Row(Modifier.fillMaxWidth().weight(1f).background(dim).padding(start = pad, end = pad, top = gap, bottom = pad), horizontalArrangement = Arrangement.spacedBy(gap)) {
                val time = if (durationMs > 0) "${formatClock(positionMs)} of ${formatClock(durationMs)}" else formatClock(positionMs)
                BentoInfo("Time", time, if (durationMs > 0) "${formatClock(durationMs - positionMs)} left" else null, tile, Modifier.weight(1f).fillMaxHeight())
                BentoInfo(
                    if (upNext != null) "Up next" else "About",
                    // The title already carries "S1 E8"; add the episode's own name when TMDB has one.
                    upNext?.let { listOfNotNull(it.title, it.episodeName).joinToString(" · ") }
                        ?: item.episodeName ?: item.meta.ifEmpty { title },
                    if (upNext == null) item.overview.takeIf { it.isNotBlank() } else null,
                    tile,
                    Modifier.weight(2f).fillMaxHeight(),
                )
                val tv = LocalIsTv.current
                BentoInfo("Controls", if (tv) "Press OK to play" else "Tap to play", if (tv) "Back to leave" else "Swipe back to leave", tile, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun BentoInfo(label: String, value: String, note: String?, fill: Color, modifier: Modifier) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(fill).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TileLabel(label)
        Text(value, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (note != null) Text(note, color = Color(0xFFD4D4D8), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

// ---------- the chooser in Settings ----------

/** Settings › Layout: the four designs as cards with a small drawing of each. */
@Composable
fun LayoutPanel() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val wide = LocalWide.current || LocalIsTv.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "How the whole app looks on this device. Changes at once; your library, settings and watch history stay the same. " +
                "Each layout brings its own colours, which can still be changed under Look.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall,
        )
        val rows = if (wide) AppLayout.entries.chunked(2) else AppLayout.entries.map { listOf(it) }
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { layout ->
                    val on = AppTheme.layout == layout
                    LayoutTile(
                        onClick = { AppTheme.changeLayout(context, layout) },
                        modifier = Modifier.weight(1f).then(if (on) Modifier.border(2.dp, Accent, RoundedCornerShape(18.dp)) else Modifier),
                        shape = RoundedCornerShape(18.dp),
                        fill = if (on) Accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                    ) {
                        val content: @Composable () -> Unit = {
                            LayoutSketch(layout, Modifier.then(if (wide) Modifier.fillMaxWidth().height(110.dp) else Modifier.size(72.dp, 80.dp)))
                            Column(if (wide) Modifier.padding(top = 10.dp) else Modifier.padding(start = 14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(layout.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    if (on) {
                                        Text(
                                            "In use",
                                            color = OnAccent,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(start = 8.dp).background(Accent, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                                Text(layout.note, color = Muted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (wide) {
                            Column(Modifier.padding(14.dp)) { content() }
                        } else {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { content() }
                        }
                    }
                }
            }
        }
    }
}

/** A tiny drawing of a layout, so the choice can be seen before it is made. */
@Composable
private fun LayoutSketch(layout: AppLayout, modifier: Modifier) {
    val shape = RoundedCornerShape(12.dp)
    BoxWithConstraints(modifier.clip(shape)) {
        val w = maxWidth
        val h = maxHeight
        @Composable
        fun block(x: Float, y: Float, bw: Float, bh: Float, color: Color, corner: Dp = 5.dp) {
            Box(
                Modifier
                    .padding(start = w * x, top = h * y)
                    .size(w * bw, h * bh)
                    .background(color, RoundedCornerShape(corner)),
            )
        }
        when (layout) {
            AppLayout.GLASS -> {
                Box(Modifier.matchParentSize().background(Brush.linearGradient(listOf(Color(0xFF1B2A4A), Color(0xFF3B1F5C), Color(0xFF0B3B4A)))))
                block(0.32f, 0.07f, 0.36f, 0.09f, Color(0x59FFFFFF))
                block(0.07f, 0.25f, 0.5f, 0.36f, Color(0x40FFFFFF))
                block(0.07f, 0.7f, 0.86f, 0.22f, Color(0x2EFFFFFF))
            }
            AppLayout.SPOTLIGHT -> {
                Box(Modifier.matchParentSize().background(Brush.linearGradient(listOf(Color(0xFF1C1917), Color(0xFF3F2A14), Color(0xFF2A1A3F)))))
                block(0f, 0f, 0.18f, 1f, Color(0x80000000), 0.dp)
                block(0.24f, 0.2f, 0.42f, 0.3f, Color(0x8CF5B14C), 3.dp)
                block(0.24f, 0.68f, 0.7f, 0.24f, Color(0x2EFFFFFF), 3.dp)
            }
            AppLayout.MATERIAL -> {
                Box(Modifier.matchParentSize().background(Color(0xFF141218)))
                block(0.07f, 0.07f, 0.45f, 0.09f, Color(0x73D0BCFF))
                Box(
                    Modifier.padding(start = w * 0.07f, top = h * 0.22f).size(w * 0.86f, h * 0.4f)
                        .background(Brush.linearGradient(listOf(Color(0xFF4F378B), Color(0xFF1E3A5F))), RoundedCornerShape(12.dp)),
                )
                block(0.07f, 0.7f, 0.86f, 0.22f, Color(0x2EE6E0E9), 8.dp)
            }
            AppLayout.BENTO -> {
                Box(Modifier.matchParentSize().background(Color(0xFF0B0B0E)))
                Box(
                    Modifier.padding(start = w * 0.06f, top = h * 0.08f).size(w * 0.44f, h * 0.84f)
                        .background(Brush.linearGradient(listOf(Color(0xFF334A7A), Color(0xFF6B3A8C))), RoundedCornerShape(8.dp)),
                )
                block(0.54f, 0.08f, 0.4f, 0.38f, Color(0xFF26262C), 8.dp)
                block(0.54f, 0.54f, 0.4f, 0.38f, Color(0xFF26262C), 8.dp)
            }
        }
    }
}
