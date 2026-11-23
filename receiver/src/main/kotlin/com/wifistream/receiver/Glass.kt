package com.wifistream.receiver

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * The frosted-glass surface used across the app: a faint light fill that is brighter at the top,
 * with a hairline highlight edge. Focused glass is brighter and takes the accent edge, so the
 * remote's position is always obvious.
 */
fun Modifier.glass(shape: Shape, focused: Boolean = false): Modifier {
    val light = AppTheme.light
    // Fill (top, bottom) and rim (bright corner, dim corner) for each style, dark and light.
    val (fill, rim) = when (AppTheme.glass) {
        GlassStyle.LIQUID -> if (light) {
            listOf(Color(0x73FFFFFF), Color(0x38FFFFFF)) to listOf(Color(0xFFFFFFFF), Color(0x26000000))
        } else {
            listOf(Color(0x2EFFFFFF), Color(0x08FFFFFF)) to listOf(Color(0xA6FFFFFF), Color(0x0FFFFFFF))
        }
        GlassStyle.CLEAR -> if (light) {
            listOf(Color(0x59FFFFFF), Color(0x26FFFFFF)) to listOf(Color(0xCCFFFFFF), Color(0x1A000000))
        } else {
            listOf(Color(0x14FFFFFF), Color(0x05FFFFFF)) to listOf(Color(0x66FFFFFF), Color(0x0AFFFFFF))
        }
        GlassStyle.FROSTED -> if (light) {
            listOf(Color(0xBFFFFFFF), Color(0x8CFFFFFF)) to listOf(Color(0xFFFFFFFF), Color(0x1F000000))
        } else {
            listOf(Color(0x4DFFFFFF), Color(0x29FFFFFF)) to listOf(Color(0x80FFFFFF), Color(0x1AFFFFFF))
        }
    }
    val focusFill = if (light) listOf(Color(0xF2FFFFFF), Color(0xCCFFFFFF)) else listOf(Color(0x5CFFFFFF), Color(0x33FFFFFF))
    return this
        .clip(shape)
        .background(Brush.verticalGradient((if (focused) focusFill else fill).map { it.glassy() }))
        // The sheen: light catching the top of the glass.
        .drawWithContent {
            drawRect(
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = if (light) 0.22f else 0.10f), Color.Transparent),
                    startY = 0f,
                    endY = size.height * 0.45f,
                ),
            )
            drawContent()
        }
        .border(
            if (focused) 2.dp else 1.dp,
            Brush.linearGradient(if (focused) listOf(Accent, Violet) else rim),
            shape,
        )
}

/**
 * What sits behind every screen: a deep night gradient, two soft pools of the brand colours, and
 * a heavily softened piece of artwork from the library so the glass has something to float over.
 */
@Composable
fun AppBackdrop(artUrl: String?) {
    val light = AppTheme.light
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                BaseGradient,
            ),
        ),
    ) {
        // The selected title's artwork, melted into colour, fading from one title to the next.
        androidx.compose.animation.Crossfade(targetState = artUrl, animationSpec = tween(600), label = "ambient") { url ->
            if (url != null) {
                AsyncImage(
                    model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                        .data(url)
                        .transformations(SoftenTransformation)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().blur(24.dp).alpha(if (light) 0.75f else 0.62f),
                )
            }
        }
        // Darker toward the bottom so text and glass stay readable over bright artwork.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    if (light) listOf(Color(0x14E4E9F2), Color(0x99E4E9F2)) else listOf(Color(0x3303040A), Color(0xB303040A)),
                ),
            ),
        )
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                Brush.radialGradient(listOf(Violet.copy(alpha = if (light) 0.18f else 0.38f), Color.Transparent), Offset(size.width * 0.12f, size.height * 0.08f), size.maxDimension * 0.55f),
                radius = size.maxDimension * 0.55f,
                center = Offset(size.width * 0.12f, size.height * 0.08f),
            )
            drawCircle(
                Brush.radialGradient(listOf(Cyan.copy(alpha = if (light) 0.16f else 0.22f), Color.Transparent), Offset(size.width * 0.95f, size.height * 0.85f), size.maxDimension * 0.5f),
                radius = size.maxDimension * 0.5f,
                center = Offset(size.width * 0.95f, size.height * 0.85f),
            )
        }
    }
}

/**
 * Shrinks artwork to a few dozen pixels. Stretched back over the screen it becomes a smooth wash
 * of the picture's colours on every device, including ones too old for a real blur effect.
 */
object SoftenTransformation : coil.transform.Transformation {
    override val cacheKey: String = "soften-48"

    override suspend fun transform(input: android.graphics.Bitmap, size: coil.size.Size): android.graphics.Bitmap {
        val width = 48
        val height = (input.height * width / input.width.coerceAtLeast(1)).coerceAtLeast(1)
        return android.graphics.Bitmap.createScaledBitmap(input, width, height, true)
    }
}

/** Softened artwork for the backdrop: the first title in the library that has any. */
fun AppViewModel.ambientArt(): String? =
    (library.shows.map { it.info } + library.movies.map { it.info }).firstOrNull { it.backdrop != null }
        ?.let { tmdbImage(it.backdrop, "w300") }

/** The Hassasin's Stream mark: an H whose crossbar is a play button, on the brand gradient. */
@Composable
fun BrandMark(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        drawRoundRect(
            Brush.linearGradient(BrandGradient, Offset.Zero, Offset(w, h)),
            cornerRadius = CornerRadius(w * 0.24f),
        )
        val bar = Size(w * 0.14f, h * 0.56f)
        val radius = CornerRadius(w * 0.04f)
        drawRoundRect(Color.White, Offset(w * 0.22f, h * 0.22f), bar, radius)
        drawRoundRect(Color.White, Offset(w * 0.64f, h * 0.22f), bar, radius)
        drawPath(
            Path().apply {
                moveTo(w * 0.41f, h * 0.36f)
                lineTo(w * 0.61f, h * 0.50f)
                lineTo(w * 0.41f, h * 0.64f)
                close()
            },
            Color.White,
        )
    }
}

private fun segment(progress: Float, from: Float, to: Float): Float = ((progress - from) / (to - from)).coerceIn(0f, 1f)

/**
 * The opening: under two seconds. The mark lands, the name tightens into place with a streak
 * of light across it, and everything fades into the app. Any key or tap skips it.
 */
@Composable
fun IntroScreen(onDone: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        progress.animateTo(1f, tween(durationMillis = 2_000, easing = LinearEasing))
        onDone()
    }
    val p = progress.value
    val land = FastOutSlowInEasing.transform(segment(p, 0f, 0.30f))
    val name = FastOutSlowInEasing.transform(segment(p, 0.18f, 0.58f))
    val sweep = segment(p, 0.34f, 0.74f)
    val leave = segment(p, 0.84f, 1f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF04050C))
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent {
                onDone()
                true
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDone)
            .graphicsLayer { alpha = 1f - leave },
        contentAlignment = Alignment.Center,
    ) {
        // Glow that swells behind the mark as it lands.
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension * (0.25f + 0.45f * land)
            drawCircle(
                Brush.radialGradient(listOf(Violet.copy(alpha = 0.55f * land), Cyan.copy(alpha = 0.12f * land), Color.Transparent), center, radius),
                radius = radius,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            BrandMark(
                96.dp,
                Modifier.graphicsLayer {
                    val scale = 1.7f - 0.7f * land
                    scaleX = scale
                    scaleY = scale
                    alpha = land
                },
            )
            Box(
                Modifier
                    .graphicsLayer {
                        alpha = name
                        // Off-screen layer so the light streak below can be blended onto the letters only.
                        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        val x = size.width * (sweep * 1.6f - 0.3f)
                        drawRect(
                            Brush.linearGradient(
                                listOf(Color.Transparent, Color.White, Color.Transparent),
                                start = Offset(x - size.width * 0.18f, 0f),
                                end = Offset(x + size.width * 0.18f, size.height),
                            ),
                            blendMode = BlendMode.SrcAtop,
                        )
                    },
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "HASSASIN'S",
                        color = Color(0xFFE8ECFF),
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (20 - 12 * name).sp,
                        modifier = Modifier.padding(start = (20 - 12 * name).dp),
                    )
                    Text(
                        "STREAM",
                        color = Accent,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = (22 - 10 * name).sp,
                        modifier = Modifier.padding(start = (22 - 10 * name).dp),
                    )
                }
            }
        }
    }
}
