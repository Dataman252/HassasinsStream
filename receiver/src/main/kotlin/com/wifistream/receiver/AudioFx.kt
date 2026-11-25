package com.wifistream.receiver

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import java.nio.ByteBuffer
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlinx.serialization.Serializable

// ---------- what the sound settings are ----------

/** The shape of one filter. [short] is the name AutoEQ files use. */
@Serializable
enum class FilterType(val label: String, val short: String) {
    PEAK("Peak", "PK"),
    LOW_SHELF("Low shelf", "LSC"),
    HIGH_SHELF("High shelf", "HSC"),
    LOW_PASS("Low-pass", "LP"),
    HIGH_PASS("High-pass", "HP"),
    NOTCH("Notch", "NO"),
}

/** One filter of the equalizer: where it sits, how much it lifts or cuts, how wide it is, and its shape. */
@Serializable
data class EqBand(val hz: Float, val gainDb: Float, val q: Float, val type: FilterType = FilterType.PEAK, val on: Boolean = true) {
    /** Shelves and bells do nothing at 0 dB; the other shapes always act. */
    val acts: Boolean get() = on && (abs(gainDb) > 0.05f || type == FilterType.LOW_PASS || type == FilterType.HIGH_PASS || type == FilterType.NOTCH)
}

/** The ten fixed frequencies of the graphic equalizer. */
val GRAPHIC_HZ = listOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
const val MAX_EQ_BANDS = 10

@Serializable
enum class EqMode(val label: String) { GRAPHIC("Graphic"), PARAMETRIC("Parametric") }

val DEFAULT_PARAMETRIC = listOf(
    EqBand(80f, 0f, 0.7f, FilterType.LOW_SHELF),
    EqBand(250f, 0f, 1f),
    EqBand(1000f, 0f, 1f),
    EqBand(3500f, 0f, 1f),
    EqBand(10000f, 0f, 0.7f, FilterType.HIGH_SHELF),
)

/**
 * Tone shaping: broad musical controls, each from −10 to +10, that are turned into gentle filters.
 * Our own take on the "describe the sound" sliders of audiophile players.
 */
@Serializable
data class Tone(
    val warmth: Float = 0f,
    val bassExtension: Float = 0f,
    val bassPunch: Float = 0f,
    val vocal: Float = 0f,
    val upperVocal: Float = 0f,
    val sibilance: Float = 0f,
    val detail: Float = 0f,
    val air: Float = 0f,
    val thickness: Float = 0f,
) {
    fun bands(): List<EqBand> = listOfNotNull(
        EqBand(250f, 0.4f * warmth, 0.7f, FilterType.LOW_SHELF).takeIf { warmth != 0f },
        EqBand(5000f, -0.2f * warmth, 0.7f, FilterType.HIGH_SHELF).takeIf { warmth != 0f },
        EqBand(45f, 0.6f * bassExtension, 0.7f, FilterType.LOW_SHELF).takeIf { bassExtension != 0f },
        EqBand(90f, 0.5f * bassPunch, 1.1f).takeIf { bassPunch != 0f },
        EqBand(1500f, 0.4f * vocal, 0.9f).takeIf { vocal != 0f },
        EqBand(3800f, 0.4f * upperVocal, 1.2f).takeIf { upperVocal != 0f },
        EqBand(7000f, 0.6f * sibilance, 2.5f).takeIf { sibilance != 0f },
        EqBand(5000f, 0.3f * detail, 0.7f).takeIf { detail != 0f },
        EqBand(12000f, 0.6f * air, 0.7f, FilterType.HIGH_SHELF).takeIf { air != 0f },
        EqBand(350f, 0.4f * thickness, 0.8f).takeIf { thickness != 0f },
    )
}

/** A saved equalizer: what "My preset" and imported files hold. */
@Serializable
data class EqSnapshot(
    val mode: EqMode = EqMode.PARAMETRIC,
    val graphic: List<Float> = List(10) { 0f },
    val bands: List<EqBand> = DEFAULT_PARAMETRIC,
    val preampDb: Float = 0f,
    val autoPreamp: Boolean = true,
)

/** Everything the sound engine does. Saved, so it is the same for every video and song. */
@Serializable
data class SoundSettings(
    val eqOn: Boolean = false,
    val mode: EqMode = EqMode.GRAPHIC,
    /** Name of the preset in use, or "Custom". */
    val preset: String = "Flat",
    val graphic: List<Float> = List(10) { 0f },
    val bands: List<EqBand> = DEFAULT_PARAMETRIC,
    val preampDb: Float = 0f,
    val autoPreamp: Boolean = true,
    val toneOn: Boolean = false,
    val tone: Tone = Tone(),
    /** −1 (left only) to +1 (right only). */
    val balance: Float = 0f,
    val mono: Boolean = false,
    val swap: Boolean = false,
    /** 0 (mono) to 2 (extra wide); 1 is unchanged. */
    val width: Float = 1f,
    /** 0 off, 1 light, 2 strong: lets each ear hear a little of the other side, like speakers. */
    val crossfeed: Int = 0,
    val boostDb: Int = 0,
    /** Evens out loud and quiet parts. */
    val night: Boolean = false,
    /** 0 off to 3 high: lifts speech. */
    val dialogue: Int = 0,
    val limiter: Boolean = true,
    /** 0 off, 1 track, 2 album. */
    val replayGain: Int = 1,
    /** Quick sound: −2 (less) to +3 (most). */
    val bass: Int = 0,
    val treble: Int = 0,
    /** Convolution with an impulse response (a .wav kept in the app), HiBy style. */
    val convOn: Boolean = false,
    val convFile: String = "",
    val convMix: Int = 100,
    val convGainDb: Float = 0f,
) {
    /** The quick bass and treble, as two shelves. */
    fun quickBands(): List<EqBand> = listOfNotNull(
        EqBand(110f, 2.5f * bass, 0.7f, FilterType.LOW_SHELF).takeIf { bass != 0 },
        EqBand(7000f, 2f * treble, 0.7f, FilterType.HIGH_SHELF).takeIf { treble != 0 },
    )

    fun eqBands(): List<EqBand> = if (!eqOn) emptyList() else when (mode) {
        EqMode.GRAPHIC -> graphic.mapIndexed { i, g -> EqBand(GRAPHIC_HZ[i], g, 1.41f) }
        EqMode.PARAMETRIC -> bands.filter { it.on }
    }

    /** Every layer, added together: quick bass and treble, the equalizer, and tone tuning. */
    fun allBands(): List<EqBand> = quickBands() + eqBands() + if (toneOn) tone.bands() else emptyList()

    /** True when any of this changes the sound, which needs it decoded here (passthrough off). */
    val shapesSound: Boolean
        get() = allBands().any { it.acts } || balance != 0f || mono || swap || width != 1f || crossfeed > 0 ||
            boostDb > 0 || night || dialogue > 0 || (eqOn && !autoPreamp && preampDb != 0f) || (convOn && convFile.isNotBlank())

    fun snapshot() = EqSnapshot(mode, graphic, bands, preampDb, autoPreamp)
    fun apply(snapshot: EqSnapshot, name: String) = copy(
        eqOn = true, mode = snapshot.mode, graphic = snapshot.graphic, bands = snapshot.bands,
        preampDb = snapshot.preampDb, autoPreamp = snapshot.autoPreamp, preset = name,
    )
}

/** Built-in presets, as gains for the ten graphic bands (31 Hz to 16 kHz). */
val EQ_PRESETS: List<Pair<String, List<Float>>> = listOf(
    "Flat" to listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
    "Bass" to listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f),
    "Bass & treble" to listOf(5f, 4f, 2f, 0f, -1f, -1f, 0f, 2f, 4f, 5f),
    "Treble" to listOf(0f, 0f, 0f, 0f, 0f, 0f, 1f, 3f, 5f, 6f),
    "Vocal" to listOf(-2f, -2f, -1f, 0f, 2f, 3f, 4f, 3f, 1f, 0f),
    "Rock" to listOf(4f, 3f, 2f, 0f, -1f, -1f, 1f, 3f, 4f, 4f),
    "Pop" to listOf(-1f, 0f, 2f, 3f, 3f, 1f, 0f, -1f, -1f, -1f),
    "Jazz" to listOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f),
    "Classical" to listOf(3f, 2f, 1f, 0f, 0f, 0f, -1f, 1f, 2f, 3f),
    "Electronic" to listOf(5f, 4f, 1f, 0f, -2f, 1f, 0f, 1f, 4f, 5f),
    "Hip-hop" to listOf(6f, 5f, 2f, 3f, -1f, -1f, 1f, 0f, 2f, 3f),
    "Acoustic" to listOf(3f, 3f, 2f, 1f, 1f, 1f, 2f, 3f, 2f, 1f),
    "Cinema" to listOf(4f, 3f, 1f, 0f, 0f, 1f, 2f, 2f, 2f, 1f),
    "Dialogue" to listOf(-4f, -3f, -1f, 1f, 3f, 4f, 4f, 2f, 0f, -1f),
    "Night" to listOf(-6f, -4f, -2f, 0f, 2f, 3f, 3f, 2f, 0f, -1f),
    "Loudness" to listOf(6f, 4f, 2f, 0f, -1f, -1f, 0f, 2f, 4f, 5f),
)

/** A built-in preset in the current mode's form. */
fun SoundSettings.withPreset(name: String, gains: List<Float>): SoundSettings = when (mode) {
    EqMode.GRAPHIC -> copy(eqOn = true, preset = name, graphic = gains)
    EqMode.PARAMETRIC -> copy(eqOn = true, preset = name, bands = gains.mapIndexed { i, g -> EqBand(GRAPHIC_HZ[i], g, 1.41f) })
}

// ---------- AutoEQ / preset files ----------

/**
 * Reads the "ParametricEQ.txt" format used by AutoEQ and Equalizer APO:
 * `Preamp: -6.2 dB` and `Filter 1: ON PK Fc 105 Hz Gain 5.8 dB Q 0.70`.
 */
fun parseEqFile(text: String): EqSnapshot? {
    val preamp = Regex("""Preamp:\s*([+\-]?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toFloatOrNull()
    val bands = Regex(
        """Filter\s*\d*:\s*(ON|OFF)\s+([A-Z]+)\s+Fc\s+([\d.]+)\s*Hz(?:\s+Gain\s+([+\-]?[\d.]+)\s*dB)?(?:\s+Q\s+([\d.]+))?""",
        RegexOption.IGNORE_CASE,
    ).findAll(text).mapNotNull { m ->
        val type = when (m.groupValues[2].uppercase()) {
            "PK", "PEQ" -> FilterType.PEAK
            "LS", "LSC", "LSQ" -> FilterType.LOW_SHELF
            "HS", "HSC", "HSQ" -> FilterType.HIGH_SHELF
            "LP", "LPQ" -> FilterType.LOW_PASS
            "HP", "HPQ" -> FilterType.HIGH_PASS
            "NO" -> FilterType.NOTCH
            else -> return@mapNotNull null
        }
        EqBand(
            hz = m.groupValues[3].toFloatOrNull()?.coerceIn(20f, 20_000f) ?: return@mapNotNull null,
            gainDb = m.groupValues[4].toFloatOrNull()?.coerceIn(-20f, 20f) ?: 0f,
            q = m.groupValues[5].toFloatOrNull()?.coerceIn(0.1f, 10f) ?: 0.71f,
            type = type,
            on = m.groupValues[1].equals("ON", true),
        )
    }.take(MAX_EQ_BANDS).toList()
    if (bands.isEmpty()) return null
    return EqSnapshot(mode = EqMode.PARAMETRIC, bands = bands, preampDb = preamp ?: 0f, autoPreamp = preamp == null)
}

/** The same format, for saving or sharing the current equalizer. */
fun SoundSettings.toEqFile(): String = buildString {
    val list = if (mode == EqMode.GRAPHIC) graphic.mapIndexed { i, g -> EqBand(GRAPHIC_HZ[i], g, 1.41f) } else bands
    append("Preamp: %.1f dB\n".format(if (autoPreamp) SoundProcessor.autoPreamp(list) else preampDb))
    list.forEachIndexed { i, b ->
        append("Filter ${i + 1}: ${if (b.on) "ON" else "OFF"} ${b.type.short} Fc %.0f Hz Gain %.1f dB Q %.2f\n".format(b.hz, b.gainDb, b.q))
    }
}

// ---------- the engine ----------

/**
 * The sound engine, inside the player: equalizer, tone shaping, balance, mono, swap, stereo width,
 * crossfeed, dialogue boost, night mode, volume boost, ReplayGain and a limiter. It works on decoded
 * sound only, so it cannot touch a Dolby/DTS stream passed through untouched.
 */
@OptIn(UnstableApi::class)
class SoundProcessor : BaseAudioProcessor() {
    private class Config(
        val filters: Array<FloatArray>,
        val gain: Float,
        val left: Float,
        val right: Float,
        val mono: Boolean,
        val swap: Boolean,
        val width: Float,
        val crossfeed: Float,
        val dialogue: FloatArray?,
        val centreGain: Float,
        val night: Boolean,
        val limiter: Boolean,
    )

    // Declared first: [build] below reads the sample rate.
    private var sampleRate = 48_000
    private var channels = 2

    @Volatile
    private var settings = SoundSettings()

    @Volatile
    private var trackGainDb = 0f

    @Volatile
    private var config = build()

    // Filter memory per channel and filter: x1, x2, y1, y2. One extra set for the dialogue filter on stereo.
    private var memory: Array<Array<FloatArray>> = emptyArray()
    private val dialogueMemory = FloatArray(4)
    private var crossL = 0f
    private var crossR = 0f
    private var envelope = 0f
    private var limit = 1f

    @Volatile
    private var impulse: Impulse? = null

    @Volatile
    private var convolver: Convolver? = null
    private val pair = FloatArray(2)

    /** The impulse response to convolve with, or null for none. */
    fun setImpulse(ir: Impulse?) {
        impulse = ir
        rebuildConvolver()
    }

    private fun rebuildConvolver() {
        val ir = impulse
        val s = settings
        convolver = if (ir != null && s.convOn) runCatching { Convolver(ir, sampleRate) }.getOrNull()?.also { tune(it, s) } else null
    }

    private fun tune(c: Convolver, s: SoundSettings) {
        c.mix = s.convMix / 100f
        c.gain = 10f.pow(s.convGainDb / 20f)
    }

    fun set(new: SoundSettings) {
        val before = settings
        settings = new
        if (new.convOn != before.convOn) rebuildConvolver() else convolver?.let { tune(it, new) }
        config = build()
    }

    /** Loudness correction for the song now playing (ReplayGain), in dB. */
    fun setTrackGain(db: Float) {
        trackGainDb = db
        config = build()
    }

    private fun build(): Config {
        val s = settings
        val bands = s.allBands().filter { it.acts }
        val preamp = if (s.eqOn && !s.autoPreamp) s.preampDb else autoPreamp(bands)
        val totalDb = preamp + s.boostDb + trackGainDb
        return Config(
            filters = bands.take(MAX_FILTERS).map { coefficients(it, sampleRate) }.toTypedArray(),
            gain = 10f.pow(totalDb / 20f),
            left = (1f - s.balance).coerceIn(0f, 1f),
            right = (1f + s.balance).coerceIn(0f, 1f),
            mono = s.mono,
            swap = s.swap,
            width = s.width,
            crossfeed = when (s.crossfeed) {
                1 -> 0.25f
                2 -> 0.45f
                else -> 0f
            },
            dialogue = if (s.dialogue > 0) coefficients(EqBand(2200f, 3f * s.dialogue, 0.7f), sampleRate) else null,
            centreGain = 10f.pow(3f * s.dialogue / 20f),
            night = s.night,
            limiter = s.limiter,
        )
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // Only plain 16-bit sound is handled; anything else passes by untouched.
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        memory = Array(channels) { Array(MAX_FILTERS) { FloatArray(4) } }
        config = build()
        rebuildConvolver()
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val c = config
        val out = replaceOutputBuffer(inputBuffer.remaining())
        val frame = FloatArray(channels)
        val attack = exp(-1.0 / (0.005 * sampleRate)).toFloat()
        val release = exp(-1.0 / (0.25 * sampleRate)).toFloat()
        val crossCoef = exp(-2.0 * PI * 700.0 / sampleRate).toFloat()
        val limitRelease = exp(-1.0 / (0.08 * sampleRate)).toFloat()
        while (inputBuffer.remaining() >= 2 * channels) {
            for (ch in 0 until channels) frame[ch] = inputBuffer.short.toFloat()

            if (channels == 2) {
                var l = frame[0]
                var r = frame[1]
                if (c.swap) l = r.also { r = l }
                // Mid (what both sides share, where voices sit) and side (the stereo spread).
                var mid = (l + r) * 0.5f
                var side = (l - r) * 0.5f * c.width
                c.dialogue?.let { mid = biquad(it, dialogueMemory, mid) }
                if (c.mono) side = 0f
                l = mid + side
                r = mid - side
                if (c.crossfeed > 0f) {
                    crossL = crossL * crossCoef + l * (1 - crossCoef)
                    crossR = crossR * crossCoef + r * (1 - crossCoef)
                    val norm = 1f / (1f + c.crossfeed)
                    val nl = (l + c.crossfeed * crossR) * norm
                    val nr = (r + c.crossfeed * crossL) * norm
                    l = nl
                    r = nr
                }
                frame[0] = l * c.left
                frame[1] = r * c.right
            } else if (channels >= 6) {
                // 5.1 order: front left, front right, centre, LFE, back left, back right. Speech lives in the centre.
                frame[2] *= c.centreGain
                frame[0] *= c.left
                frame[4] *= c.left
                frame[1] *= c.right
                frame[5] *= c.right
            }

            var peak = 0f
            for (ch in 0 until channels) {
                var x = frame[ch]
                val state = memory[ch]
                for (f in c.filters.indices) x = biquad(c.filters[f], state[f], x)
                x *= c.gain
                frame[ch] = x
                peak = maxOf(peak, abs(x))
            }

            // Convolution with the impulse response, on the front left and right.
            convolver?.let { conv ->
                conv.process(frame[0], if (channels >= 2) frame[1] else frame[0], pair)
                frame[0] = pair[0]
                if (channels >= 2) frame[1] = pair[1]
                peak = maxOf(peak, abs(pair[0]), abs(pair[1]))
            }

            var g = 1f
            if (c.night) {
                // Gentle compressor above −20 dBFS, ratio 3:1, with 6 dB of make-up so quiet talk comes up.
                envelope = if (peak > envelope) attack * envelope + (1 - attack) * peak else release * envelope + (1 - release) * peak
                if (envelope > NIGHT_THRESHOLD) g = (NIGHT_THRESHOLD * (envelope / NIGHT_THRESHOLD).pow(1f / 3f)) / envelope
                g *= 2f
            }
            if (c.limiter) {
                // Fast peak limiter just below full scale.
                val needed = if (peak * g > LIMIT_LEVEL) LIMIT_LEVEL / (peak * g) else 1f
                limit = if (needed < limit) needed else limitRelease * limit + (1 - limitRelease) * 1f
                if (limit > needed) limit = needed
                g *= limit
            }

            for (ch in 0 until channels) {
                var x = frame[ch] * g
                // Round the peaks off instead of clipping them hard.
                val magnitude = abs(x)
                if (magnitude > KNEE) {
                    val over = KNEE + (CEILING - KNEE) * tanh((magnitude - KNEE) / (CEILING - KNEE))
                    x = if (x < 0) -over else over
                }
                out.putShort(x.toInt().toShort())
            }
        }
        // A partial frame (should not happen) is passed through so nothing is lost.
        while (inputBuffer.remaining() >= 2) out.putShort(inputBuffer.short)
        out.flip()
    }

    override fun onFlush() {
        memory.forEach { perChannel -> perChannel.forEach { it.fill(0f) } }
        dialogueMemory.fill(0f)
        crossL = 0f
        crossR = 0f
        envelope = 0f
        limit = 1f
        convolver?.reset()
    }

    companion object {
        private const val MAX_FILTERS = 24
        private const val KNEE = 28_000f
        private const val CEILING = 32_767f
        private const val LIMIT_LEVEL = 30_000f
        private const val NIGHT_THRESHOLD = 3_277f

        private fun biquad(c: FloatArray, m: FloatArray, x: Float): Float {
            val y = c[0] * x + c[1] * m[0] + c[2] * m[1] - c[3] * m[2] - c[4] * m[3]
            m[1] = m[0]
            m[0] = x
            m[3] = m[2]
            m[2] = y
            return y
        }

        /** Filter coefficients (RBJ audio-EQ cookbook), already divided by a0: b0, b1, b2, a1, a2. */
        fun coefficients(band: EqBand, sampleRate: Int): FloatArray {
            val a = 10.0.pow(band.gainDb / 40.0)
            val w0 = 2 * PI * band.hz.coerceIn(10f, sampleRate * 0.45f) / sampleRate
            val cs = cos(w0)
            val alpha = sin(w0) / (2 * band.q.coerceIn(0.1f, 12f))
            val sq = 2 * sqrt(a) * alpha
            val (b, den) = when (band.type) {
                FilterType.PEAK -> doubleArrayOf(1 + alpha * a, -2 * cs, 1 - alpha * a) to doubleArrayOf(1 + alpha / a, -2 * cs, 1 - alpha / a)
                FilterType.LOW_SHELF -> doubleArrayOf(
                    a * ((a + 1) - (a - 1) * cs + sq), 2 * a * ((a - 1) - (a + 1) * cs), a * ((a + 1) - (a - 1) * cs - sq),
                ) to doubleArrayOf((a + 1) + (a - 1) * cs + sq, -2 * ((a - 1) + (a + 1) * cs), (a + 1) + (a - 1) * cs - sq)
                FilterType.HIGH_SHELF -> doubleArrayOf(
                    a * ((a + 1) + (a - 1) * cs + sq), -2 * a * ((a - 1) + (a + 1) * cs), a * ((a + 1) + (a - 1) * cs - sq),
                ) to doubleArrayOf((a + 1) - (a - 1) * cs + sq, 2 * ((a - 1) - (a + 1) * cs), (a + 1) - (a - 1) * cs - sq)
                FilterType.LOW_PASS -> doubleArrayOf((1 - cs) / 2, 1 - cs, (1 - cs) / 2) to doubleArrayOf(1 + alpha, -2 * cs, 1 - alpha)
                FilterType.HIGH_PASS -> doubleArrayOf((1 + cs) / 2, -(1 + cs), (1 + cs) / 2) to doubleArrayOf(1 + alpha, -2 * cs, 1 - alpha)
                FilterType.NOTCH -> doubleArrayOf(1.0, -2 * cs, 1.0) to doubleArrayOf(1 + alpha, -2 * cs, 1 - alpha)
            }
            val a0 = den[0]
            return floatArrayOf((b[0] / a0).toFloat(), (b[1] / a0).toFloat(), (b[2] / a0).toFloat(), (den[1] / a0).toFloat(), (den[2] / a0).toFloat())
        }

        /** How many dB the whole set of filters lifts or cuts at [hz], for drawing the curve. */
        fun responseDb(bands: List<EqBand>, hz: Float, sampleRate: Int = 48_000): Float {
            val w = 2 * PI * hz / sampleRate
            var total = 0.0
            for (band in bands) {
                if (!band.acts) continue
                val c = coefficients(band, sampleRate)
                val numRe = c[0] + c[1] * cos(w) + c[2] * cos(2 * w)
                val numIm = -(c[1] * sin(w) + c[2] * sin(2 * w))
                val denRe = 1 + c[3] * cos(w) + c[4] * cos(2 * w)
                val denIm = -(c[3] * sin(w) + c[4] * sin(2 * w))
                total += 20 * log10((sqrt(numRe * numRe + numIm * numIm) / sqrt(denRe * denRe + denIm * denIm)).coerceAtLeast(1e-6))
            }
            return total.toFloat()
        }

        /** Enough negative pre-amp that the curve's highest lift cannot push the sound into clipping. */
        fun autoPreamp(bands: List<EqBand>): Float {
            var highest = 0f
            for (i in 0..96) highest = maxOf(highest, responseDb(bands, 20f * 1000f.pow(i / 96f)))
            return -highest
        }
    }
}

/**
 * How this app builds its decoders.
 *
 * - [passthrough] on: Dolby/DTS streams go untouched to the TV or soundbar when it accepts them.
 *   Off: everything is decoded here, which is what the sound engine needs.
 * - [dvAsHdr10]: hide the Dolby Vision decoder so the file's HDR10 base video is used.
 * - [audioOffsetUs]: lip-sync correction. Positive plays the sound later relative to the picture.
 * - [engine]: shapes the decoded sound on its way out.
 */
@OptIn(UnstableApi::class)
class AppRenderersFactory(
    context: Context,
    private val passthrough: Boolean,
    dvAsHdr10: Boolean,
    private val audioOffsetUs: AtomicLong,
    private val engine: SoundProcessor,
    /** Direct USB DAC output settings; null keeps Android's output only. */
    private val usbOutput: (() -> UsbOutputSettings)? = null,
) : DefaultRenderersFactory(context) {

    init {
        setEnableDecoderFallback(true)
        // Device decoders first; the bundled software decoders only when the device has none for the format.
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
        setMediaCodecSelector { mime, secure, tunneling ->
            if (dvAsHdr10 && mime == MimeTypes.VIDEO_DOLBY_VISION) emptyList()
            else MediaCodecUtil.getDecoderInfos(mime, secure, tunneling)
        }
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink? {
        val builder = DefaultAudioSink.Builder(context)
            .setAudioProcessors(arrayOf<AudioProcessor>(engine))
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        // Telling the sink the output only takes plain PCM makes every format get decoded on this device.
        @Suppress("DEPRECATION")
        if (!passthrough) builder.setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
        val android = builder.build()
        return usbOutput?.let { UsbAudioSink(context, android, engine, it) } ?: android
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        super.buildAudioRenderers(
            context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, out,
        )
        // The audio renderer is the player's clock. Reporting the clock ahead by the offset makes the
        // picture run ahead of the sound by that much, which is how the sound gets "delayed".
        val index = out.indexOfFirst { it is MediaCodecAudioRenderer }
        if (index >= 0) {
            out[index] = object : MediaCodecAudioRenderer(
                context, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, audioSink,
            ) {
                override fun getPositionUs(): Long = super.getPositionUs() + audioOffsetUs.get()
            }
        }
    }
}
