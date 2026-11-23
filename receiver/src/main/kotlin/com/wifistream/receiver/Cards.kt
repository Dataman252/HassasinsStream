package com.wifistream.receiver

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage

/** A picture tile that grows slightly and gains a border when the remote lands on it. */
@Composable
fun FocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    LaunchedEffect(focused) { if (focused) onFocused() }
    val scale by animateFloatAsState(
        when {
            pressed -> 0.95f
            focused -> 1.06f
            else -> 1f
        },
        androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 500f),
        label = "focus",
    )
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .glass(shape, focused)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick),
        content = content,
    )
}

/** How far a video was watched, drawn along the bottom edge of its tile. */
@Composable
fun BoxScope.ProgressEdge(fraction: Float?) {
    if (fraction == null || fraction <= 0f) return
    Box(
        Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .height(4.dp)
            .background(Color(0x66000000)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(4.dp)
                .background(Amber),
        )
    }
}

@Composable
private fun CornerTag(text: String, modifier: Modifier) {
    Text(
        text,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        modifier = modifier
            .padding(6.dp)
            .background(Color(0xCC0B1220), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

@Composable
fun PosterCard(
    title: String,
    imageUrl: String?,
    tag: String?,
    progress: Float?,
    watched: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = Dp.Unspecified,
    onFocused: () -> Unit = {},
) {
    val w = if (width != Dp.Unspecified) width else LocalPosterWidth.current ?: 120.dp
    Column(modifier.width(w)) {
        FocusCard(onClick, Modifier.fillMaxWidth().aspectRatio(2f / 3f), onFocused) {
            if (imageUrl != null) {
                AsyncImage(imageUrl, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Text(title, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(8.dp))
            }
            if (tag != null) CornerTag(tag, Modifier.align(Alignment.TopStart))
            if (watched) CornerTag("Watched", Modifier.align(Alignment.TopEnd))
            ProgressEdge(progress)
        }
        Text(
            title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Width of a landscape card in a row: big on a TV, a little smaller on a tablet, and the old size on phones. */
@Composable
fun wideCardWidth(): Dp = LocalCardWidth.current ?: when {
    LocalIsTv.current -> 270.dp
    LocalWide.current -> 250.dp
    else -> 220.dp
}

/**
 * The TV and tablet tile for a movie or show: its wide artwork with the title over it, the way
 * streaming apps show their rows on a television. [width] unset fills the grid cell.
 */
@Composable
fun LandscapeCard(
    title: String,
    imageUrl: String?,
    tag: String?,
    progress: Float?,
    watched: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = Dp.Unspecified,
    onFocused: () -> Unit = {},
) {
    val sized = if (width != Dp.Unspecified) modifier.width(width) else modifier.fillMaxWidth()
    FocusCard(onClick, sized.aspectRatio(16f / 9f), onFocused) {
        if (imageUrl != null) {
            AsyncImage(imageUrl, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        // Darkens the lower part so the title reads on any picture.
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color.Transparent, 1f to Color(0xE6070A18)),
            ),
        )
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        )
        if (tag != null) CornerTag(tag, Modifier.align(Alignment.TopStart))
        if (watched) CornerTag("Watched", Modifier.align(Alignment.TopEnd))
        ProgressEdge(progress)
    }
}

/** Landscape tile for Continue watching and videos without a poster. */
@Composable
fun WideCard(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    progress: Float?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.width(wideCardWidth())) {
        FocusCard(onClick, Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            if (imageUrl != null) {
                AsyncImage(imageUrl, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(painterResource(R.drawable.ic_movie), null, tint = Muted, modifier = Modifier.align(Alignment.Center).size(36.dp))
            }
            ProgressEdge(progress)
        }
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (subtitle != null) {
            Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
    }
}

/** Action button: amber when focused or primary, so the remote's position is never in doubt. */
@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, onDark: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val bounce by animateFloatAsState(
        if (pressed) 0.92f else 1f,
        androidx.compose.animation.core.spring(dampingRatio = 0.4f, stiffness = 600f),
        label = "press",
    )
    val shape = RoundedCornerShape(50)
    Text(
        text,
        fontWeight = FontWeight.SemiBold,
        color = when {
            onDark -> if (focused || primary) Color(0xFF07080F) else Color.White
            focused -> OnAccent
            primary -> Night
            else -> Ink
        },
        maxLines = 1,
        modifier = modifier
            .graphicsLayer {
                scaleX = bounce
                scaleY = bounce
            }
            .then(
                when {
                    focused -> Modifier.clip(shape).background(Brush.horizontalGradient(listOf(Accent, Color(0xFFC4B5FD))))
                    primary -> Modifier.clip(shape).background(if (onDark) Color.White else Ink)
                    // Over artwork the glass stays dark, whatever the theme.
                    onDark -> Modifier.clip(shape).background(Color(0x3DFFFFFF)).border(1.dp, Color(0x80FFFFFF), shape)
                    else -> Modifier.glass(shape)
                },
            )
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    )
}
