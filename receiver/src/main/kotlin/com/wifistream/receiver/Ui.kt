package com.wifistream.receiver

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.foundation.LocalIndication
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** True on Android TV / Fire TV: wider screen margins and remote-first behaviour. */
val LocalIsTv = staticCompositionLocalOf { false }

/** Sizes worked out from the space the home screen actually has (null: use the defaults). */
val LocalCardWidth = androidx.compose.runtime.compositionLocalOf<Dp?> { null }
val LocalPosterWidth = androidx.compose.runtime.compositionLocalOf<Dp?> { null }
val LocalHeroHeight = androidx.compose.runtime.compositionLocalOf<Dp?> { null }

/** True on TVs and tablets: landscape picture cards instead of tall posters. */
val LocalWide = staticCompositionLocalOf { false }

/** The same answer for code that builds lists outside a composable (lazy list builders). */
object LocalIsTvSnapshot {
    var isTv: Boolean = false

    /** A real TV, not a phone showing the TV layout for testing. */
    var realTv: Boolean = false
}

/** Sharing needs Android 11 file access and makes sense on phones and tablets, not on TVs. */
fun canShareFromThisDevice(): Boolean = android.os.Build.VERSION.SDK_INT >= 30 && !LocalIsTvSnapshot.realTv




@Composable
fun ReceiverTheme(content: @Composable () -> Unit) {
    val scheme = if (AppTheme.light) {
        androidx.compose.material3.lightColorScheme(
            primary = Accent, onPrimary = Color.White, background = Night, surface = Night,
            onBackground = Ink, onSurface = Ink, surfaceVariant = Panel, onSurfaceVariant = Muted,
        )
    } else {
        darkColorScheme(
            primary = Amber, onPrimary = Night, background = Night, surface = Night,
            surfaceVariant = Panel, onSurfaceVariant = Muted,
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Screen side margin: TVs lose the picture edge to overscan, phones do not. */
@Composable
fun screenMargin(): Dp = if (LocalIsTv.current) 48.dp else 16.dp

/**
 * A row that works with a remote (focus highlight, OK button) and with touch.
 * Focus-related modifiers passed in [modifier] must come first, so they are applied before the click target.
 */
@Composable
fun FocusRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .glass(shape, focused)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

@Composable
fun RowIcon(drawable: Int) {
    Icon(painterResource(drawable), contentDescription = null, tint = Muted, modifier = Modifier.size(26.dp))
}

/** Quality tag such as 4K, DV or ATMOS. Neutral on purpose: the text carries the meaning, not a colour. */
@Composable
fun Badge(text: String, onDark: Boolean = false) {
    val light = AppTheme.light && !onDark
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = if (light) Ink else Color.White,
        maxLines = 1,
        modifier = Modifier
            .background(if (light) Color(0xB3FFFFFF) else Color(0x33000000), RoundedCornerShape(4.dp))
            .border(1.dp, if (light) Muted else Color(0x99FFFFFF), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun BadgeRow(badges: List<String>, onDark: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { badges.forEach { Badge(it, onDark) } }
}

@Composable
fun ScreenTitle(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // A remote has its own Back button; touch screens need one on screen.
        if (onBack != null && !LocalIsTv.current) {
            Icon(
                painterResource(R.drawable.ic_back),
                contentDescription = "Back",
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onBack)
                    .padding(8.dp)
                    .size(24.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (subtitle != null) {
            Spacer(Modifier.width(14.dp))
            Text(subtitle, color = Muted, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        }
    }
}

fun formatDuration(ms: Long): String? {
    if (ms <= 0) return null
    val seconds = ms / 1000
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "${h}h ${m}m" else "%d:%02d".format(m, s)
}

/** Draws above the screen content. */
fun Modifier.zIndexTop(): Modifier = this.then(Modifier.zIndex(10f))

/** Shown the moment the sharing phone stops answering or its drive goes missing; gone when it is back. */
@Composable
fun LinkBanner(vm: AppViewModel, modifier: Modifier = Modifier) {
    val state = vm.linkState
    val name = vm.connection?.info?.name ?: return
    androidx.compose.animation.AnimatedVisibility(
        visible = state != LinkState.OK,
        enter = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut(),
        modifier = modifier.padding(16.dp),
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(Color(0xE61A1406))
                .border(1.dp, Color(0xCCF59E0B), RoundedCornerShape(50))
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state == LinkState.LOST) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color(0xFFFBBF24))
            }
            Column {
                Text(
                    if (state == LinkState.LOST) "$name is not answering. Reconnecting…" else "$name's USB drive is disconnected",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (state == LinkState.LOST) "Check that sharing is on and both are on the same Wi-Fi. This updates by itself."
                    else "Plug it back in on $name; the library refreshes by itself.",
                    color = Color(0xFFFDE68A),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** A slim bar at the top while seek-bar pictures are being cached. */
@Composable
fun ThumbProgressBar(modifier: Modifier = Modifier) {
    val p = Thumbs.progress
    androidx.compose.animation.AnimatedVisibility(
        visible = p != null && p.total > 0,
        enter = androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.fadeOut(),
        modifier = modifier.padding(top = 4.dp),
    ) {
        if (p == null) return@AnimatedVisibility
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(Color(0xCC0B0B1A))
                .border(1.dp, Color(0x40FFFFFF), RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            androidx.compose.material3.LinearProgressIndicator(
                progress = { p.fraction },
                modifier = Modifier.width(90.dp).height(4.dp).clip(RoundedCornerShape(50)),
                color = Accent,
                trackColor = Color(0x33FFFFFF),
            )
            Text(
                "Preparing previews ${(p.fraction * 100).toInt()}%  ·  ${p.title}",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 260.dp),
            )
        }
    }
}
