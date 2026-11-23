package com.wifistream.receiver

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/** Search by hand and choose the right film or show; every file of the wrong match moves over. */
@Composable
fun FixMatchScreen(vm: AppViewModel, screen: Screen.FixMatch) {
    var query by remember { mutableStateOf(screen.query) }
    var searchCount by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf<List<TitleInfo>?>(null) }
    var saving by remember { mutableStateOf(false) }
    val current = remember { vm.store.title(screen.titleKey) }

    LaunchedEffect(searchCount) {
        results = null
        results = vm.matchCandidates(query.trim(), screen.isShow)
    }

    fun choose(info: TitleInfo?) {
        if (saving) return
        saving = true
        vm.applyMatch(screen.titleKey, info) { applied ->
            // Leave this page and the old details page; open the new title's page.
            vm.back()
            vm.back()
            if (applied != null) vm.openDetails(applied)
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(start = screenMargin(), end = screenMargin(), top = 20.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenTitle(
                "Fix a match",
                subtitle = current?.let { "Now matched as ${it.title}${it.year?.let { y -> " ($y)" }.orEmpty()} from ${it.sourceLabel}" },
                onBack = vm::back,
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(if (screen.isShow) "Show name" else "Film name, with the year if you know it") },
                    modifier = Modifier.weight(1f),
                )
                PillButton("Search", onClick = { searchCount++ }, primary = true)
            }
        }
        item {
            Text(
                "Results from TMDB come first, then from ${if (screen.isShow) "TVmaze" else "Wikipedia"}, which need no key.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        val found = results
        when {
            found == null -> item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("  Searching…", color = Muted)
                }
            }
            found.isEmpty() -> item { Text("Nothing found. Try a shorter name, or check the spelling.", color = Muted) }
            else -> items(found, key = { it.key }) { info ->
                FocusRow(onClick = { choose(info) }) {
                    Box(Modifier.size(54.dp, 80.dp).clip(RoundedCornerShape(8.dp)).background(Panel)) {
                        AsyncImage(tmdbImage(info.poster, "w185"), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            info.title + info.year?.let { " ($it)" }.orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(info.sourceLabel + if (info.key == screen.titleKey) "  ·  current match" else "", color = Accent, style = MaterialTheme.typography.bodySmall)
                        if (info.overview.isNotBlank()) {
                            Text(info.overview, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        item {
            FocusRow(onClick = { choose(null) }, modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    Text("Not a film or show", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Moves these files to Other videos, where they show a picture from inside the video.", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
