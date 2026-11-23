package com.wifistream.receiver

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Where "Buy me a coffee" goes. Empty hides the pop-up and the Settings row. */
const val DONATE_URL = ""

/**
 * The app is free; once per new version it asks, gently, for a coffee. "Not now" asks again next
 * time the app opens; "Don't ask again" stays quiet until the next version.
 */
object Donate {
    private const val PREFS = "donate"

    var showing by mutableStateOf(false)

    fun shouldAsk(context: Context): Boolean {
        if (DONATE_URL.isBlank()) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString("quiet_for", null) != Updater.installedVersion(context)
    }

    fun quietForThisVersion(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("quiet_for", Updater.installedVersion(context)).apply()
    }

    fun open(context: Context): Boolean = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DONATE_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}

/** The coffee pop-up, a few seconds after the app opens, never over a video or the update screen. */
@Composable
fun DonatePrompt() {
    val context = LocalContext.current
    var asked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!asked && Donate.shouldAsk(context)) {
            kotlinx.coroutines.delay(6_000)
            asked = true
            Donate.showing = true
        }
    }
    if (!Donate.showing) return
    DonateCard(onClose = { Donate.showing = false })
}

/** Also opened from Settings › About › Support the developer. */
@Composable
fun DonateCard(onClose: () -> Unit) {
    val context = LocalContext.current
    val tv = LocalIsTv.current
    val first = remember { FocusRequester() }
    var qr by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    androidx.activity.compose.BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Color(0xB3000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 460.dp)
                .padding(20.dp)
                .background(Color(0xFF14141C), RoundedCornerShape(24.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("☕", style = MaterialTheme.typography.displaySmall)
            Text("Enjoying Hassasin's Stream?", style = MaterialTheme.typography.titleLarge, color = Color.White, textAlign = TextAlign.Center)
            Text(
                "It's free, with no ads and no accounts, and it's built in spare time. If it makes movie night better, a coffee keeps new versions coming.",
                color = Color(0xFFCBD5E1),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            if (qr || tv) {
                // TVs have no browser: show the link as a code for the phone's camera.
                val image = remember { qrBitmap(DONATE_URL, 480) }
                Box(Modifier.size(200.dp).background(Color.White, RoundedCornerShape(12.dp)).padding(8.dp)) {
                    Image(image, contentDescription = "Buy me a coffee link", modifier = Modifier.fillMaxSize())
                }
                Text("Scan with your phone's camera", color = Color(0xFF94A3B8), style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!tv) {
                    PillButton("Buy me a coffee", onClick = {
                        if (!Donate.open(context)) qr = true else onClose()
                    }, modifier = Modifier.focusRequester(first), primary = true, onDark = true)
                }
                PillButton("Not now", onClick = onClose, modifier = if (tv) Modifier.focusRequester(first) else Modifier, onDark = true)
            }
            PillButton("Don't ask again", onClick = {
                Donate.quietForThisVersion(context)
                onClose()
            }, onDark = true)
        }
    }
}
