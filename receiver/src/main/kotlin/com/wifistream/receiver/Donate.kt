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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** The developer's UPI payment link (the same one inside the QR code). Empty hides the pop-up. */
const val DONATE_URL = "upi://pay?pa=7016587945-2@axl&pn=HASSANKHAN%20PATHAN&cu=INR&tn=Coffee%20for%20Hassasin%27s%20Stream"

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

    /** Opens a UPI app (PhonePe, Google Pay, Paytm, BHIM…) with the payment filled in. */
    fun open(context: Context): Boolean = runCatching {
        val pay = Intent(Intent.ACTION_VIEW, Uri.parse(DONATE_URL))
        context.startActivity(Intent.createChooser(pay, "Pay with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
    var noApp by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    androidx.activity.compose.BackHandler(onBack = onClose)
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xB3000000)), contentAlignment = Alignment.Center) {
        // Side by side when the screen is wide (TVs, phones on their side); stacked on an upright phone.
        val wide = maxWidth > maxHeight
        val qrHeight = if (wide) (maxHeight * 0.78f).coerceAtMost(420.dp) else (maxHeight * 0.42f).coerceAtMost(343.dp)
        val code: @Composable () -> Unit = {
            // The developer's UPI code, as issued: scan it from any UPI app.
            Image(
                androidx.compose.ui.res.painterResource(R.drawable.upi_qr),
                contentDescription = "UPI code to pay the developer",
                modifier = Modifier.size(width = qrHeight * 0.525f, height = qrHeight).clip(RoundedCornerShape(16.dp)),
            )
        }
        val words: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = if (wide) Alignment.Start else Alignment.CenterHorizontally) {
                Text("☕  Enjoying Hassasin's Stream?", style = MaterialTheme.typography.titleLarge, color = Color.White, textAlign = if (wide) TextAlign.Start else TextAlign.Center)
                Text(
                    "It's free, with no ads and no accounts, and it's built in spare time. If it makes movie night better, a coffee keeps new versions coming.",
                    color = Color(0xFFCBD5E1),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = if (wide) TextAlign.Start else TextAlign.Center,
                )
                Text(
                    when {
                        noApp -> "No UPI app on this device: scan the code with your phone."
                        tv -> "Scan the code with any UPI app on your phone."
                        else -> "Scan the code, or pay from a UPI app on this phone."
                    },
                    color = Color(0xFF94A3B8),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!tv) {
                        PillButton("Pay with UPI app", onClick = {
                            if (Donate.open(context)) onClose() else noApp = true
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
        val card = Modifier
            .padding(16.dp)
            .background(Color(0xFF14141C), RoundedCornerShape(24.dp))
            .padding(20.dp)
        if (wide) {
            Row(card.widthIn(max = 860.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                code()
                Box(Modifier.weight(1f)) { words() }
            }
        } else {
            Column(card.widthIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                code()
                words()
            }
        }
    }
}
