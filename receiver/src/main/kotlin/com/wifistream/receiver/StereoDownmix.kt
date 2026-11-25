package com.wifistream.receiver

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Mixes mono or multichannel sound to stereo for a two-channel DAC, in any PCM format (16, 24 or
 * 32-bit, or float). Channels are in the standard order (front left, front right, centre, LFE, back
 * left, back right, side left, side right). The centre (dialogue) and the surrounds go equally into
 * both sides at -3 dB; the result is scaled down so it cannot clip.
 */
@OptIn(UnstableApi::class)
class StereoDownmix : BaseAudioProcessor() {
    private var encoding = C.ENCODING_PCM_16BIT
    private var channels = 2
    private var left = FloatArray(0)
    private var right = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding !in setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT)) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        encoding = inputAudioFormat.encoding
        channels = inputAudioFormat.channelCount
        if (channels == 2) return AudioProcessor.AudioFormat.NOT_SET
        val h = 0.70710677f
        // Weights per input channel for the left and right outputs.
        val (l, r) = when (channels) {
            1 -> floatArrayOf(1f) to floatArrayOf(1f)
            3 -> floatArrayOf(1f, 0f, h) to floatArrayOf(0f, 1f, h)                     // L R C
            4 -> floatArrayOf(1f, 0f, h, 0f) to floatArrayOf(0f, 1f, 0f, h)             // L R BL BR
            5 -> floatArrayOf(1f, 0f, h, h, 0f) to floatArrayOf(0f, 1f, h, 0f, h)       // L R C BL BR
            6 -> floatArrayOf(1f, 0f, h, 0f, h, 0f) to floatArrayOf(0f, 1f, h, 0f, 0f, h) // L R C LFE BL BR
            7 -> floatArrayOf(1f, 0f, h, 0f, h, 0f, h) to floatArrayOf(0f, 1f, h, 0f, 0f, h, h)
            8 -> floatArrayOf(1f, 0f, h, 0f, h, 0f, h, 0f) to floatArrayOf(0f, 1f, h, 0f, 0f, h, 0f, h)
            else -> throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        val peak = maxOf(l.sum(), r.sum())
        left = FloatArray(channels) { l[it] / peak }
        right = FloatArray(channels) { r[it] / peak }
        return AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, 2, encoding)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val src = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val bytes = when (encoding) { C.ENCODING_PCM_16BIT -> 2; C.ENCODING_PCM_24BIT -> 3; else -> 4 }
        val frames = src.remaining() / (bytes * channels)
        val out = replaceOutputBuffer(frames * 2 * bytes).order(ByteOrder.LITTLE_ENDIAN)
        val sample = FloatArray(channels)
        repeat(frames) {
            for (c in 0 until channels) sample[c] = read(src)
            var l = 0f
            var r = 0f
            for (c in 0 until channels) { l += sample[c] * left[c]; r += sample[c] * right[c] }
            write(out, l)
            write(out, r)
        }
        out.flip()
    }

    /** One sample as a float in -1..1. */
    private fun read(b: ByteBuffer): Float = when (encoding) {
        C.ENCODING_PCM_16BIT -> b.short / 32768f
        C.ENCODING_PCM_24BIT -> {
            val v = (b.get().toInt() and 0xFF) or ((b.get().toInt() and 0xFF) shl 8) or (b.get().toInt() shl 16)
            v / 8388608f
        }
        C.ENCODING_PCM_32BIT -> (b.int / 2147483648.0).toFloat()
        else -> b.float
    }

    private fun write(b: ByteBuffer, v: Float) {
        val x = v.coerceIn(-1f, 1f)
        when (encoding) {
            C.ENCODING_PCM_16BIT -> b.putShort((x * 32767f).toInt().toShort())
            C.ENCODING_PCM_24BIT -> {
                val i = (x * 8388607f).toInt()
                b.put(i.toByte()); b.put((i shr 8).toByte()); b.put((i shr 16).toByte())
            }
            C.ENCODING_PCM_32BIT -> b.putInt((x * 2147483647.0).toLong().toInt())
            else -> b.putFloat(x)
        }
    }
}
