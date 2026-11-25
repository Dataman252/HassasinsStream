package com.wifistream.receiver

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Puts channels in the order the player asks for (output channel i = input channel map[i]). Some
 * decoders hand over 5.1 in their own order; without this, the centre (dialogue) can land on the left.
 */
@OptIn(UnstableApi::class)
class ChannelOrder(private val map: IntArray) : BaseAudioProcessor() {
    private var bytes = 2
    private var inChannels = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        bytes = when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        inChannels = inputAudioFormat.channelCount
        if (map.any { it >= inChannels }) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        val identity = map.size == inChannels && map.indices.all { map[it] == it }
        return if (identity) AudioProcessor.AudioFormat.NOT_SET
        else AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, map.size, inputAudioFormat.encoding)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frameIn = inChannels * bytes
        val frames = inputBuffer.remaining() / frameIn
        val out = replaceOutputBuffer(frames * map.size * bytes)
        val start = inputBuffer.position()
        for (f in 0 until frames) {
            val base = start + f * frameIn
            for (c in map) for (b in 0 until bytes) out.put(inputBuffer.get(base + c * bytes + b))
        }
        inputBuffer.position(start + frames * frameIn)
        out.flip()
    }
}
