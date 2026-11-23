package com.wifistream.receiver

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import kotlinx.serialization.Serializable
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * Picture adjustments, like a TV's picture menu. Every value is -100..100 with 0 meaning
 * "as the file is" (hue is in degrees, -180..180).
 */
@Serializable
data class PictureSettings(
    val brightness: Int = 0,
    val contrast: Int = 0,
    val saturation: Int = 0,
    val vibrance: Int = 0,
    val brilliance: Int = 0,
    val gamma: Int = 0,
    val shadows: Int = 0,
    val highlights: Int = 0,
    val blackLevel: Int = 0,
    val warmth: Int = 0,
    val tint: Int = 0,
    val hue: Int = 0,
    val sharpness: Int = 0,
) {
    val isOriginal: Boolean get() = this == PictureSettings()
}

/** One adjustment as the panel shows it. */
class PictureControl(
    val label: String,
    val detail: String,
    val min: Int,
    val max: Int,
    val get: (PictureSettings) -> Int,
    val set: (PictureSettings, Int) -> PictureSettings,
)

val PICTURE_CONTROLS = listOf(
    PictureControl("Brightness", "The whole picture lighter or darker.", -100, 100, { it.brightness }, { s, v -> s.copy(brightness = v) }),
    PictureControl("Contrast", "The distance between darks and lights.", -100, 100, { it.contrast }, { s, v -> s.copy(contrast = v) }),
    PictureControl("Saturation", "Colour strength, every colour equally.", -100, 100, { it.saturation }, { s, v -> s.copy(saturation = v) }),
    PictureControl("Vibrance", "Boosts dull colours more than strong ones, so skin stays natural.", -100, 100, { it.vibrance }, { s, v -> s.copy(vibrance = v) }),
    PictureControl("Brilliance", "Lifts shadows and softens highlights: more detail in dark films.", -100, 100, { it.brilliance }, { s, v -> s.copy(brilliance = v) }),
    PictureControl("Gamma", "Mid-tones brighter or darker, without touching blacks and whites.", -100, 100, { it.gamma }, { s, v -> s.copy(gamma = v) }),
    PictureControl("Shadows", "The dark parts only.", -100, 100, { it.shadows }, { s, v -> s.copy(shadows = v) }),
    PictureControl("Highlights", "The bright parts only: tame skies and lamps.", -100, 100, { it.highlights }, { s, v -> s.copy(highlights = v) }),
    PictureControl("Black level", "Deeper or lifted blacks.", -100, 100, { it.blackLevel }, { s, v -> s.copy(blackLevel = v) }),
    PictureControl("Warmth", "Colour temperature: cool blue to warm amber.", -100, 100, { it.warmth }, { s, v -> s.copy(warmth = v) }),
    PictureControl("Tint", "Green to magenta balance.", -100, 100, { it.tint }, { s, v -> s.copy(tint = v) }),
    PictureControl("Hue", "Turns every colour around the colour wheel.", -180, 180, { it.hue }, { s, v -> s.copy(hue = v) }),
    PictureControl("Sharpness", "Crisper edges for soft or upscaled video.", 0, 100, { it.sharpness }, { s, v -> s.copy(sharpness = v) }),
)

/** Ready-made looks, like a TV's picture modes. */
enum class PicturePreset(val label: String, val settings: PictureSettings) {
    STANDARD("Standard", PictureSettings()),
    VIVID("Vivid", PictureSettings(contrast = 15, saturation = 20, vibrance = 25, sharpness = 25, brilliance = 10)),
    CINEMA("Cinema", PictureSettings(contrast = 5, saturation = -5, warmth = 15, gamma = -10, blackLevel = 10)),
    NIGHT("Night", PictureSettings(brightness = -15, contrast = -10, warmth = 35, highlights = -30, shadows = 10)),
    SPORTS("Sports", PictureSettings(brightness = 5, contrast = 10, saturation = 15, vibrance = 20, sharpness = 35)),
    DARK_SCENES("Dark scenes", PictureSettings(brilliance = 40, shadows = 25, gamma = 15, blackLevel = -10)),
}

/** The adjustments the playing video uses right now; the shader reads it every frame, so changes are live. */
object PictureLive {
    @Volatile var settings: PictureSettings = PictureSettings()
}

/** The whole picture menu in one GPU pass per frame. */
@OptIn(UnstableApi::class)
object PictureEffect : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = PictureShaderProgram(useHdr)
}

@OptIn(UnstableApi::class)
private class PictureShaderProgram(useHdr: Boolean) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity = */ 1) {
    private val program = GlProgram(VERTEX, FRAGMENT).apply {
        setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
    }
    private var width = 1
    private var height = 1

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        width = inputWidth
        height = inputHeight
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val s = PictureLive.settings
        program.use()
        program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
        program.setFloatsUniform("uTexel", floatArrayOf(1f / width, 1f / height))
        program.setFloatUniform("uBrightness", s.brightness / 100f * 0.35f)
        program.setFloatUniform("uContrast", 1f + s.contrast / 100f * 0.6f)
        program.setFloatUniform("uSaturation", 1f + s.saturation / 100f)
        program.setFloatUniform("uVibrance", s.vibrance / 100f)
        program.setFloatUniform("uBrilliance", s.brilliance / 100f)
        // Gamma above 0 brightens mid-tones: exponent below 1.
        program.setFloatUniform("uGamma", Math.pow(2.0, -s.gamma / 100.0).toFloat())
        program.setFloatUniform("uShadows", s.shadows / 100f)
        program.setFloatUniform("uHighlights", s.highlights / 100f)
        program.setFloatUniform("uBlack", s.blackLevel / 100f * 0.12f)
        program.setFloatUniform("uWarmth", s.warmth / 100f)
        program.setFloatUniform("uTint", s.tint / 100f)
        program.setFloatUniform("uHue", Math.toRadians(s.hue.toDouble()).toFloat())
        program.setFloatUniform("uSharp", s.sharpness / 100f)
        program.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    override fun release() {
        super.release()
        runCatching { program.delete() }
    }

    companion object {
        const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
}
"""
        const val FRAGMENT = """
precision mediump float;
uniform sampler2D uTexSampler;
uniform vec2 uTexel;
uniform float uBrightness;
uniform float uContrast;
uniform float uSaturation;
uniform float uVibrance;
uniform float uBrilliance;
uniform float uGamma;
uniform float uShadows;
uniform float uHighlights;
uniform float uBlack;
uniform float uWarmth;
uniform float uTint;
uniform float uHue;
uniform float uSharp;
varying vec2 vTexSamplingCoord;
const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

void main() {
  vec4 src = texture2D(uTexSampler, vTexSamplingCoord);
  vec3 c = src.rgb;
  // Sharpness: unsharp mask from the four neighbours.
  if (uSharp > 0.0) {
    vec3 blur = (texture2D(uTexSampler, vTexSamplingCoord + vec2(uTexel.x, 0.0)).rgb +
                 texture2D(uTexSampler, vTexSamplingCoord - vec2(uTexel.x, 0.0)).rgb +
                 texture2D(uTexSampler, vTexSamplingCoord + vec2(0.0, uTexel.y)).rgb +
                 texture2D(uTexSampler, vTexSamplingCoord - vec2(0.0, uTexel.y)).rgb) * 0.25;
    c += (c - blur) * uSharp * 1.6;
  }
  // Black level.
  c = (c - uBlack) / (1.0 - uBlack);
  // Shadows, highlights and brilliance work on brightness ranges.
  float l = dot(clamp(c, 0.0, 1.0), LUMA);
  float shadowLift = uShadows + uBrilliance * 0.6;
  float highlightLift = uHighlights - uBrilliance * 0.4;
  c += shadowLift * (1.0 - smoothstep(0.0, 0.55, l)) * 0.35;
  c += highlightLift * smoothstep(0.45, 1.0, l) * 0.35;
  // Gamma.
  c = pow(max(c, 0.0), vec3(uGamma));
  // Brightness and contrast.
  c += uBrightness;
  c = (c - 0.5) * uContrast + 0.5;
  // Warmth and tint.
  c.r += uWarmth * 0.07;
  c.b -= uWarmth * 0.07;
  c.g -= uTint * 0.05;
  // Saturation, then vibrance (stronger on dull colours).
  l = dot(c, LUMA);
  c = mix(vec3(l), c, uSaturation);
  float sat = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
  c = mix(vec3(l), c, 1.0 + uVibrance * (1.0 - clamp(sat, 0.0, 1.0)));
  // Hue: rotate around the grey axis.
  if (uHue != 0.0) {
    float cs = cos(uHue);
    float sn = sin(uHue);
    vec3 k = vec3(0.57735);
    c = c * cs + cross(k, c) * sn + k * dot(k, c) * (1.0 - cs);
  }
  gl_FragColor = vec4(clamp(c, 0.0, 1.0), src.a);
}
"""
    }
}

/**
 * Settings › Playback: every saved picture look, with Undo. "All videos" applies to every title
 * without its own look; a title set to Original is kept out of it.
 */
@androidx.compose.runtime.Composable
fun PictureLooksPanel(store: LibraryStore, version: Int) {
    val entries = androidx.compose.runtime.remember(version) { store.pictureEntries.toList().sortedBy { it.second.name.lowercase() } }
    val all = androidx.compose.runtime.remember(version) { store.pictureAll }
    androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.Text("Picture adjustments", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        androidx.compose.material3.Text(
            "Set them in the player: Picture. Each show or film keeps its own look, or one look applies to all videos.",
            color = Muted,
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
        )
        FocusRow(onClick = { if (all != null) store.setPictureAll(null) }) {
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.weight(1f)) {
                androidx.compose.material3.Text("All videos", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                androidx.compose.material3.Text(all?.let { describe(it) } ?: "Off: videos play as the file is", color = Muted, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            }
            if (all != null) androidx.compose.material3.Text("Undo", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        }
        // The viewer's saved looks: rename or delete.
        val profiles = androidx.compose.runtime.remember(version) { store.pictureProfiles.toList() }
        if (profiles.isNotEmpty()) {
            androidx.compose.material3.Text("My looks", style = androidx.compose.material3.MaterialTheme.typography.titleSmall, modifier = androidx.compose.ui.Modifier.padding(top = 6.dp))
        }
        for ((name, settings) in profiles) {
            var editing by androidx.compose.runtime.remember(name) { androidx.compose.runtime.mutableStateOf(false) }
            var text by androidx.compose.runtime.remember(name) { androidx.compose.runtime.mutableStateOf(name) }
            if (editing) {
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                    androidx.compose.material3.OutlinedTextField(text, { text = it }, singleLine = true, modifier = androidx.compose.ui.Modifier.weight(1f))
                    PillButton("Save", onClick = {
                        store.renamePictureProfile(name, text)
                        editing = false
                    }, primary = true)
                }
            } else {
                FocusRow(onClick = { editing = true }) {
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.weight(1f)) {
                        androidx.compose.material3.Text(name, style = androidx.compose.material3.MaterialTheme.typography.titleMedium, maxLines = 1)
                        androidx.compose.material3.Text(describe(settings), color = Muted, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    }
                    androidx.compose.material3.Text("Rename", color = Muted)
                    PillButton("Delete", onClick = { store.deletePictureProfile(name) })
                }
            }
        }
        if (entries.isNotEmpty()) {
            androidx.compose.material3.Text("Titles with their own look", style = androidx.compose.material3.MaterialTheme.typography.titleSmall, modifier = androidx.compose.ui.Modifier.padding(top = 6.dp))
        }
        for ((key, entry) in entries) {
            FocusRow(onClick = { store.removePicture(key) }) {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.weight(1f)) {
                    androidx.compose.material3.Text(entry.name, style = androidx.compose.material3.MaterialTheme.typography.titleMedium, maxLines = 1)
                    androidx.compose.material3.Text(
                        if (entry.settings.isOriginal) "Original picture (kept out of All videos)" else describe(entry.settings),
                        color = Muted,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                }
                androidx.compose.material3.Text("Undo", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            }
        }
    }
}

private fun describe(s: PictureSettings): String =
    PicturePreset.entries.firstOrNull { it.settings == s }?.label
        ?: PICTURE_CONTROLS.filter { it.get(s) != 0 }.joinToString(" · ") { c -> "${c.label} ${c.get(s).let { if (it > 0) "+$it" else "$it" }}" }
