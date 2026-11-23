package com.wifistream.receiver

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** One choice in a row of options: its name and the colours of its swatch (none for plain text). */
class LookOption(val label: String, val colours: List<Color> = emptyList(), val icon: Boolean = false)

/**
 * A sideways row of options. Works by touch and with a remote: focus shows the accent ring, the
 * chosen one is filled.
 */
@Composable
fun OptionRow(title: String, note: String?, options: List<LookOption>, selected: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (note != null) Text(note, color = Muted, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            options.forEachIndexed { index, option ->
                val interaction = remember { MutableInteractionSource() }
                val focused by interaction.collectIsFocusedAsState()
                val chosen = index == selected
                val shape = RoundedCornerShape(50)
                Row(
                    Modifier
                        .clip(shape)
                        .then(if (chosen) Modifier.background(PanelFocused) else Modifier)
                        .border(if (focused) 2.dp else 1.dp, if (focused) Accent else if (chosen) Ink.copy(alpha = 0.6f) else Panel, shape)
                        .clickable(interactionSource = interaction, indication = LocalIndication.current) { onSelect(index) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when {
                        option.icon -> IconPreview(option.colours)
                        option.colours.isNotEmpty() -> Canvas(Modifier.size(22.dp).clip(CircleShape)) {
                            drawRect(Brush.linearGradient(option.colours, Offset.Zero, Offset(size.width, size.height)))
                        }
                    }
                    Text(option.label, fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}

/** The launcher mark in a given pair of colours. */
@Composable
private fun IconPreview(colours: List<Color>) {
    Canvas(Modifier.size(28.dp)) {
        val w = size.width
        val h = size.height
        drawRoundRect(Brush.linearGradient(colours, Offset.Zero, Offset(w, h)), cornerRadius = CornerRadius(w * 0.24f))
        val bar = androidx.compose.ui.geometry.Size(w * 0.14f, h * 0.56f)
        drawRoundRect(Color.White, Offset(w * 0.22f, h * 0.22f), bar, CornerRadius(w * 0.04f))
        drawRoundRect(Color.White, Offset(w * 0.64f, h * 0.22f), bar, CornerRadius(w * 0.04f))
        drawPath(Path().apply {
            moveTo(w * 0.41f, h * 0.36f)
            lineTo(w * 0.61f, h * 0.50f)
            lineTo(w * 0.41f, h * 0.64f)
            close()
        }, Color.White)
    }
}

/** Settings → Look: colour theme, accent, light or dark, glass style and transparency, app icon. */
@Composable
fun LookPanel() {
    val context = LocalContext.current
    val t = AppTheme
    Column(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        val presets = ColorPreset.entries
        OptionRow(
            "Colour theme",
            t.preset.note,
            presets.map { p ->
                LookOption(
                    p.label,
                    if (p == ColorPreset.CUSTOM) CUSTOM_ACCENTS.take(4).map { Color(it.second) } else p.brand.map { Color(it) },
                )
            },
            presets.indexOf(t.preset),
        ) { t.change(context, preset = presets[it]) }

        if (t.preset == ColorPreset.CUSTOM) {
            OptionRow(
                "Accent colour",
                "Used for focus, selection and progress. Made deeper automatically in light mode so it stays readable.",
                CUSTOM_ACCENTS.map { LookOption(it.first, listOf(Color(it.second), Color(it.second))) },
                t.customAccent,
            ) { t.change(context, customAccent = it) }
        }

        OptionRow(
            "Appearance",
            "Videos always play on dark.",
            ThemeMode.entries.map { LookOption(it.label) },
            ThemeMode.entries.indexOf(t.mode),
        ) { t.change(context, mode = ThemeMode.entries[it]) }

        OptionRow(
            "Glass style",
            "Liquid catches the light, Clear barely shows, Frosted is easiest to read.",
            GlassStyle.entries.map { LookOption(it.label) },
            GlassStyle.entries.indexOf(t.glass),
        ) { t.change(context, glass = GlassStyle.entries[it]) }

        OptionRow(
            "Transparency",
            "How much shows through the glass, in the app and over videos.",
            Clarity.entries.map { LookOption(it.label) },
            Clarity.entries.indexOf(t.clarity),
        ) { t.change(context, clarity = Clarity.entries[it]) }

        if (!LocalIsTvSnapshot.realTv) {
            OptionRow(
                "TV layout (for testing)",
                "Shows this phone the screens a TV gets: icon tiles in Settings, wide cards, remote-style focus.",
                listOf(LookOption("Off"), LookOption("On")),
                if (t.tvLayout) 1 else 0,
            ) { t.setTvLayout(context, it == 1) }
        }

        OptionRow(
            "App icon",
            "The new icon appears on the home screen after you leave the app.",
            AppIcon.entries.map { LookOption(it.label, it.colours.map { c -> Color(c) }, icon = true) },
            AppIcon.entries.indexOf(t.icon),
        ) { t.changeIcon(context, AppIcon.entries[it]) }
    }
}
