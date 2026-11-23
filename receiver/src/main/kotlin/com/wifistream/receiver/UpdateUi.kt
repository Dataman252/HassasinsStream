package com.wifistream.receiver

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** "Version 1.3 is available" on Home, with Update and Later. Hidden when there is nothing to show. */
@Composable
fun UpdateCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = Updater.state
    val info = when (state) {
        is UpdateState.Available -> state.info
        is UpdateState.Downloading -> state.info
        is UpdateState.Installing -> state.info
        is UpdateState.Failed -> state.info
        else -> null
    } ?: return
    if (state is UpdateState.Available && Updater.dismissedVersion == info.version) return
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .fillMaxWidth()
            .background(Accent.copy(alpha = 0.12f), shape)
            .border(1.dp, Accent.copy(alpha = 0.6f), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Version ${info.version} is available", style = MaterialTheme.typography.titleMedium)
        Text(
            "You have ${Updater.installedVersion(context)}. Your library, settings and watch history stay.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall,
        )
        val notes = info.notes.lines().filter { it.trim().startsWith("-") }.take(4).joinToString("\n") { "•" + it.trim().removePrefix("-").replace("**", "") }
        if (notes.isNotBlank()) Text(notes, style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis)
        when (state) {
            is UpdateState.Downloading -> {
                Text("Downloading… ${(state.fraction * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                Box(Modifier.fillMaxWidth().height(6.dp).background(Panel, RoundedCornerShape(3.dp))) {
                    Box(Modifier.fillMaxWidth(state.fraction.coerceIn(0f, 1f)).height(6.dp).background(Accent, RoundedCornerShape(3.dp)))
                }
            }
            is UpdateState.Installing -> Text("Opening the installer… confirm Update on the screen.", style = MaterialTheme.typography.bodySmall)
            else -> {
                if (state is UpdateState.Failed) Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Update", onClick = { scope.launch { Updater.downloadAndInstall(context, info) } }, primary = true)
                    PillButton("Later", onClick = { Updater.dismiss(context, info.version) })
                }
            }
        }
    }
}

/** Settings › About: the installed version and a button to check now. */
@Composable
fun UpdateRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = Updater.state
    FocusRow(onClick = { scope.launch { Updater.check(context, force = true) } }) {
        Column(Modifier.weight(1f)) {
            Text("Check for updates", style = MaterialTheme.typography.titleMedium)
            Text(
                when (state) {
                    UpdateState.Checking -> "Checking GitHub…"
                    UpdateState.UpToDate -> "Version ${Updater.installedVersion(context)} is the newest."
                    is UpdateState.Available -> "Version ${state.info.version} is available: see the card on Home."
                    is UpdateState.Failed -> state.message
                    else -> "Version ${Updater.installedVersion(context)}. Checks by itself when the app opens."
                },
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
