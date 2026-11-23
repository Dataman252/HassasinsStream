package com.wifistream.receiver

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * An impulse response: the recorded "sound" of a room, speaker or headphone correction.
 * [channels] holds 1 (mono), 2 (stereo: left, right) or 4 (true stereo: L→L, L→R, R→L, R→R) signals.
 */
class Impulse(val channels: Array<FloatArray>, val sampleRate: Int, val name: String) {
    val lengthMs: Long get() = channels.first().size * 1000L / sampleRate.coerceAtLeast(1)
    val kind: String
        get() = when (channels.size) {
            1 -> "Mono"
            4 -> "True stereo"
            else -> "Stereo"
        }
}

/** Reads WAV files: 16, 24 or 32-bit integer, or 32/64-bit float, any sample rate. */
object WavReader {
    const val MAX_SECONDS = 1.4

    fun read(file: File): Impulse? = runCatching { parse(file.readBytes(), file.nameWithoutExtension) }.getOrNull()

    /** "Stereo · 48 kHz · 320 ms" without keeping the whole file. */
    fun describe(file: File): String = read(file)?.let { "${it.kind} · ${it.sampleRate / 1000.0} kHz · ${it.lengthMs} ms".replace(".0 kHz", " kHz") }
        ?: "Not a readable WAV file"

    private fun parse(bytes: ByteArray, name: String): Impulse? {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 44 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
        var pos = 12
        var format = 0
        var channels = 0
        var rate = 0
        var bits = 0
        var dataStart = -1
        var dataSize = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = b.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    format = b.getShort(body).toInt() and 0xFFFF
                    channels = b.getShort(body + 2).toInt()
                    rate = b.getInt(body + 4)
                    bits = b.getShort(body + 14).toInt()
                    // WAVE_FORMAT_EXTENSIBLE: the real format is the first two bytes of the sub-format GUID.
                    if (format == 0xFFFE && size >= 26) format = b.getShort(body + 24).toInt() and 0xFFFF
                }
                "data" -> {
                    dataStart = body
                    dataSize = min(size, bytes.size - body)
                }
            }
            pos = body + size + (size and 1)
        }
        if (dataStart < 0 || channels <= 0 || rate <= 0) return null
        val bytesPer = bits / 8
        val frames = dataSize / (bytesPer * channels)
        val keep = min(frames, (rate * MAX_SECONDS).toInt())
        val out = Array(channels) { FloatArray(keep) }
        for (f in 0 until keep) {
            for (c in 0 until channels) {
                val at = dataStart + (f * channels + c) * bytesPer
                out[c][f] = when {
                    format == 3 && bits == 32 -> b.getFloat(at)
                    format == 3 && bits == 64 -> b.getDouble(at).toFloat()
                    bits == 16 -> b.getShort(at) / 32768f
                    bits == 24 -> ((bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or (bytes[at + 2].toInt() shl 16)) / 8388608f
                    bits == 32 -> b.getInt(at) / 2147483648f
                    bits == 8 -> ((bytes[at].toInt() and 0xFF) - 128) / 128f
                    else -> return null
                }
            }
        }
        // A short fade at the end, in case the file was cut off.
        val fade = min(keep, rate / 100)
        for (c in out.indices) for (i in 0 until fade) out[c][keep - 1 - i] *= i.toFloat() / fade
        val used = when (channels) {
            1, 2, 4 -> out
            else -> arrayOf(out[0], out[1])
        }
        return Impulse(used, rate, name)
    }
}

/** A plain radix-2 FFT on separate real and imaginary arrays. */
private class Fft(val n: Int) {
    private val cosTable = FloatArray(n / 2) { cos(2 * PI * it / n).toFloat() }
    private val sinTable = FloatArray(n / 2) { sin(2 * PI * it / n).toFloat() }
    private val reverse = IntArray(n).also { r ->
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) r[i] = Integer.reverse(i) ushr (32 - bits)
    }

    fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
        for (i in 0 until n) {
            val j = reverse[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosTable[k]
                    val wi = if (inverse) sinTable[k] else -sinTable[k]
                    val l = j + half
                    val tr = re[l] * wr - im[l] * wi
                    val ti = re[l] * wi + im[l] * wr
                    re[l] = re[j] - tr
                    im[l] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                start += size
            }
            size *= 2
        }
        if (inverse) {
            val s = 1f / n
            for (i in 0 until n) {
                re[i] *= s
                im[i] *= s
            }
        }
    }
}

/**
 * Convolution with an impulse response, by uniformly partitioned overlap-save FFT convolution:
 * the response is cut into blocks of [B] samples, each kept as a spectrum, and every incoming
 * block is multiplied with all of them. Cost stays low even for long responses; delay is [B] samples.
 * Works on the two front channels.
 */
class Convolver(ir: Impulse, sampleRate: Int) {
    companion object {
        const val B = 512
        private const val L = 2 * B
    }

    private val fft = Fft(L)

    // Filters by (input, output): mono and stereo use the straight paths; true stereo also crosses over.
    private val filters: List<Array<Pair<FloatArray, FloatArray>>>
    private val route: Array<IntArray> // route[input][output] = filter index, or -1
    private val partitions: Int
    private val normal: Float

    // Per input channel: the last two blocks of samples, and the spectra of recent blocks (newest at [head]).
    private val prev = Array(2) { FloatArray(B) }
    private val current = Array(2) { FloatArray(B) }
    private val history: Array<Array<Pair<FloatArray, FloatArray>>>
    private var head = 0

    // Per output channel: the finished wet block, and the dry block it lines up with.
    private val wet = Array(2) { FloatArray(B) }
    private val dry = Array(2) { FloatArray(B) }
    private var index = 0

    @Volatile var mix = 1f
    @Volatile var gain = 1f

    private val accRe = FloatArray(L)
    private val accIm = FloatArray(L)
    private val inRe = FloatArray(L)
    private val inIm = FloatArray(L)

    init {
        val signals = ir.channels.map { resample(it, ir.sampleRate, sampleRate) }
        val length = signals.maxOf { it.size }.coerceAtLeast(1)
        partitions = (length + B - 1) / B
        filters = signals.map { signal ->
            Array(partitions) { p ->
                val re = FloatArray(L)
                val im = FloatArray(L)
                for (i in 0 until B) {
                    val at = p * B + i
                    if (at < signal.size) re[i] = signal[at]
                }
                fft.transform(re, im, inverse = false)
                re to im
            }
        }
        route = when (signals.size) {
            1 -> arrayOf(intArrayOf(0, -1), intArrayOf(-1, 0))
            4 -> arrayOf(intArrayOf(0, 1), intArrayOf(2, 3))
            else -> arrayOf(intArrayOf(0, -1), intArrayOf(-1, 1))
        }
        normal = 1f / peakGain(signals)
        history = Array(2) { Array(partitions) { FloatArray(L) to FloatArray(L) } }
    }

    /** The loudest point of the response's spectrum, so the convolved sound cannot be louder than the original. */
    private fun peakGain(signals: List<FloatArray>): Float {
        var n = 1
        while (n < signals.maxOf { it.size } * 2) n *= 2
        n = min(n, 1 shl 17)
        val big = Fft(n)
        var peak = 1e-6f
        for (s in signals) {
            val re = FloatArray(n)
            val im = FloatArray(n)
            for (i in 0 until min(s.size, n)) re[i] = s[i]
            big.transform(re, im, inverse = false)
            for (i in 0 until n / 2) peak = max(peak, sqrt(re[i] * re[i] + im[i] * im[i]))
        }
        return peak
    }

    private fun resample(signal: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || from <= 0) return signal
        val outLength = (signal.size.toLong() * to / from).toInt().coerceAtLeast(1)
        return FloatArray(outLength) { i ->
            val src = i.toDouble() * from / to
            val a = src.toInt().coerceAtMost(signal.size - 1)
            val b2 = (a + 1).coerceAtMost(signal.size - 1)
            val t = (src - a).toFloat()
            signal[a] * (1 - t) + signal[b2] * t
        }
    }

    /** Clears the sound in flight, after a jump in the video. */
    fun reset() {
        for (c in 0..1) {
            prev[c].fill(0f)
            current[c].fill(0f)
            wet[c].fill(0f)
            dry[c].fill(0f)
            history[c].forEach { (re, im) ->
                re.fill(0f)
                im.fill(0f)
            }
        }
        index = 0
    }

    /** One stereo sample in, one stereo sample out (delayed by [B] samples). */
    fun process(left: Float, right: Float, out: FloatArray) {
        val m = mix
        val g = gain * normal
        out[0] = dry[0][index] * (1 - m) + wet[0][index] * m * g
        out[1] = dry[1][index] * (1 - m) + wet[1][index] * m * g
        current[0][index] = left
        current[1][index] = right
        index++
        if (index == B) {
            index = 0
            block()
        }
    }

    private fun block() {
        head = (head - 1 + partitions) % partitions
        for (c in 0..1) {
            // Overlap-save: the previous block followed by the new one.
            for (i in 0 until B) {
                inRe[i] = prev[c][i]
                inRe[B + i] = current[c][i]
                inIm[i] = 0f
                inIm[B + i] = 0f
            }
            fft.transform(inRe, inIm, inverse = false)
            val (hr, hi) = history[c][head]
            inRe.copyInto(hr)
            inIm.copyInto(hi)
        }
        for (o in 0..1) {
            accRe.fill(0f)
            accIm.fill(0f)
            for (i in 0..1) {
                val f = route[i][o]
                if (f < 0) continue
                val filter = filters[f]
                for (p in 0 until partitions) {
                    val (xr, xi) = history[i][(head + p) % partitions]
                    val (hr, hi) = filter[p]
                    // Real sound has a mirror-image spectrum: work out half, copy the rest below.
                    for (k in 0..L / 2) {
                        accRe[k] += xr[k] * hr[k] - xi[k] * hi[k]
                        accIm[k] += xr[k] * hi[k] + xi[k] * hr[k]
                    }
                }
            }
            for (k in L / 2 + 1 until L) {
                accRe[k] = accRe[L - k]
                accIm[k] = -accIm[L - k]
            }
            fft.transform(accRe, accIm, inverse = true)
            for (i in 0 until B) wet[o][i] = accRe[B + i]
        }
        for (c in 0..1) {
            current[c].copyInto(dry[c])
            current[c].copyInto(prev[c])
        }
    }
}
