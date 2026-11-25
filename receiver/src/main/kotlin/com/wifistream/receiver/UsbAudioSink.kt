package com.wifistream.receiver

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.google.common.collect.ImmutableList
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** What the direct USB output is doing right now, for the Output panel and the volume keys. */
object UsbLive {
    @Volatile var session: UsbDacSession? = null
    @Volatile var active = false
    /** "44.1 kHz 24-bit → Audiocular D07 44.1 kHz 32-bit" */
    @Volatile var path = ""
    @Volatile var untouched = true
    /** The DAC stopped taking sound (unplugged); the player moves the sound back to Android. */
    @Volatile var lost = false
    /** When the current direct stream started (elapsedRealtime), to tell which player owns it. */
    @Volatile var activatedAt = 0L
    /** How the sound is changed on its way, when it is not untouched. */
    @Volatile var change = ""
}

/** What the direct USB output should do, read each time a file is set up. */
data class UsbOutputSettings(
    val enabled: Boolean,
    /** True: the sound engine (EQ, tone, quick sound) still runs. False: the file's samples go to the DAC unchanged. */
    val keepSound: Boolean,
    val volumeDb: Double,
    val maxDb: Double,
)

/**
 * Plays straight to a USB DAC through [UsbDacSession] instead of Android's mixer: no resampling,
 * no system volume, the DAC switched to each file's own rate. When direct output is off, or no
 * DAC with permission is plugged in, or the DAC refuses the rate, everything goes to [fallback]
 * (Android's normal output) as before.
 */
@OptIn(UnstableApi::class)
class UsbAudioSink(
    private val context: Context,
    fallback: AudioSink,
    private val engine: AudioProcessor,
    private val settings: () -> UsbOutputSettings,
) : ForwardingAudioSink(fallback) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    init { UsbDacSession.context = context.applicationContext }
    private var usb = false
    private var session: UsbDacSession? = null
    private var pipeline: AudioProcessingPipeline? = null
    private var inFormat = AudioProcessor.AudioFormat.NOT_SET
    private var outFormat = AudioProcessor.AudioFormat.NOT_SET
    private var rate = 0

    private var pending = ByteArray(0)
    private var pendingLen = 0
    private var pendingOff = 0

    private var startMediaUs = C.TIME_UNSET
    private var anchorFrames = 0L
    private var framesWritten = 0L
    private var draining = false

    companion object {
        private const val TAG = "UsbAudioSink"
        private val PCM = setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT)
    }

    /** A DAC we may use right now, if direct output is on. */
    private fun dacReady(): Boolean {
        if (!settings().enabled) return false
        val dac = UsbDacProbe.findDac(usbManager) ?: return false
        return usbManager.hasPermission(dac)
    }

    private fun usbSupport(format: Format): Int {
        if (format.sampleMimeType != MimeTypes.AUDIO_RAW) return AudioSink.SINK_FORMAT_UNSUPPORTED
        if (format.pcmEncoding !in PCM) return AudioSink.SINK_FORMAT_UNSUPPORTED
        // The sound engine only works on 16-bit sound, so with it on, decoders are asked for 16-bit.
        if (settings().keepSound && format.pcmEncoding != C.ENCODING_PCM_16BIT) return AudioSink.SINK_FORMAT_UNSUPPORTED
        return AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
    }

    override fun supportsFormat(format: Format): Boolean =
        if (dacReady()) usbSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED else super.supportsFormat(format)

    override fun getFormatSupport(format: Format): Int =
        if (dacReady()) usbSupport(format) else super.getFormatSupport(format)

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        if (!dacReady() || usbSupport(inputFormat) == AudioSink.SINK_FORMAT_UNSUPPORTED) {
            useFallback()
            super.configure(inputFormat, specifiedBufferSize, outputChannels)
            return
        }
        val s = settings()
        val input = AudioProcessor.AudioFormat(inputFormat.sampleRate, inputFormat.channelCount, inputFormat.pcmEncoding)
        val processors = mutableListOf<AudioProcessor>()
        if (outputChannels != null) processors += ChannelOrder(outputChannels)
        if (s.keepSound) processors += engine
        val mixedChannels = outputChannels?.size ?: input.channelCount
        if (mixedChannels != 2) {
            processors += StereoDownmix()
        }
        val p = AudioProcessingPipeline(ImmutableList.copyOf(processors))
        val out = try {
            p.configure(input)
        } catch (e: AudioProcessor.UnhandledAudioFormatException) {
            Log.w(TAG, "cannot process $input", e)
            useFallback()
            super.configure(inputFormat, specifiedBufferSize, outputChannels)
            return
        }
        p.flush()

        val dac = UsbDacProbe.findDac(usbManager)
        if (session == null) {
            val until = SystemClock.elapsedRealtime() + 2500
            while (UsbLive.session != null && SystemClock.elapsedRealtime() < until) SystemClock.sleep(50)
        }
        val current = session ?: dac?.let { UsbDacSession.open(usbManager, it) }?.also {
            it.switchMode = 1  // silence only: the DAC's mute makes a "ting", volume glides distort
            it.queueMs = 30  // the most the vivo's USB controller takes; covers its screen-off hiccups
            it.setVolumeDb(minOf(s.volumeDb, s.maxDb))
        }
        if (current == null) {
            useFallback()
            super.configure(inputFormat, specifiedBufferSize, outputChannels)
            return
        }
        session = current
        if (current.rate != out.sampleRate || !current.isRunning()) {
            // Let what is queued finish before the DAC changes rate.
            val until = SystemClock.elapsedRealtime() + 1500
            while (current.isRunning() && current.ringFrames() > 0 && SystemClock.elapsedRealtime() < until) SystemClock.sleep(5)
            if (!current.start(out.sampleRate)) {
                Log.w(TAG, "DAC refused ${out.sampleRate} Hz; using Android's output")
                useFallback()
                super.configure(inputFormat, specifiedBufferSize, outputChannels)
                return
            }
            // A fresh stream counts played frames from zero.
            framesWritten = 0
        }
        if (!usb) UsbKeepAliveService.start(context)
        usb = true
        UsbLive.session = current
        UsbLive.activatedAt = SystemClock.elapsedRealtime()
        primed = false
        current.setPaused(true)
        UsbLive.active = true
        UsbLive.untouched = !s.keepSound && input.channelCount == 2
        UsbLive.change = when {
            s.keepSound -> "with your sound settings"
            input.channelCount > 2 -> "mixed from ${input.channelCount} channels to stereo for the DAC"
            input.channelCount == 1 -> "mono sent to both channels"
            else -> ""
        }
        val bitsIn = when (input.encoding) { C.ENCODING_PCM_16BIT -> 16; C.ENCODING_PCM_24BIT -> 24; else -> 32 }
        UsbLive.path = "${khz(input.sampleRate)} $bitsIn-bit → ${current.device.productName ?: "DAC"} ${khz(current.rate)} ${current.current?.bits ?: 32}-bit"
        pipeline = p
        inFormat = input
        outFormat = out
        rate = out.sampleRate
        pendingLen = 0
        pendingOff = 0
        draining = false
        startMediaUs = C.TIME_UNSET
        anchorFrames = framesWritten
        Log.i(TAG, "channel order from the player: ${outputChannels?.joinToString()}; mixing $mixedChannels -> 2")
        Log.i(TAG, "direct USB: ${input.sampleRate} Hz ${input.channelCount}ch enc ${input.encoding} -> DAC ${current.rate} Hz ${current.current?.bits}-bit, engine ${s.keepSound}")
    }

    /** Hands the DAC back to Android. */
    private fun khz(hz: Int) = if (hz % 1000 == 0) "${hz / 1000} kHz" else "%.1f kHz".format(hz / 1000.0)

    private fun useFallback() {
        if (usb || session != null) UsbKeepAliveService.stop(context)
        UsbLive.active = false
        UsbLive.session = null
        UsbLive.path = ""
        usb = false
        pipeline = null
        session?.close()
        session = null
    }

    // ---- writing ----

    private fun ensure(capacity: Int) {
        if (pending.size < capacity) pending = pending.copyOf(maxOf(capacity, pending.size * 2))
    }

    /** Converts PCM in the pipeline's output format to the DAC's 32-bit little-endian stereo. */
    private fun convert(src: ByteBuffer) {
        val buf = src.order(ByteOrder.LITTLE_ENDIAN)
        val enc = outFormat.encoding
        val bytesPerSample = when (enc) { C.ENCODING_PCM_16BIT -> 2; C.ENCODING_PCM_24BIT -> 3; else -> 4 }
        val samples = buf.remaining() / bytesPerSample
        val start = pendingOff + pendingLen
        ensure(start + samples * 4)
        var o = start
        repeat(samples) {
            val v: Int = when (enc) {
                C.ENCODING_PCM_16BIT -> buf.short.toInt() shl 16
                C.ENCODING_PCM_24BIT -> {
                    val b0 = buf.get().toInt() and 0xFF
                    val b1 = buf.get().toInt() and 0xFF
                    val b2 = buf.get().toInt()
                    ((b2 shl 16) or (b1 shl 8) or b0) shl 8
                }
                C.ENCODING_PCM_FLOAT -> {
                    val f = buf.float.toDouble()
                    (f * 2147483648.0).coerceIn(-2147483648.0, 2147483647.0).toLong().toInt()
                }
                else -> buf.int
            }
            pending[o] = v.toByte(); pending[o + 1] = (v shr 8).toByte(); pending[o + 2] = (v shr 16).toByte(); pending[o + 3] = (v shr 24).toByte()
            o += 4
        }
        pendingLen = o - pendingOff
    }

    /** Sends what is waiting; true when nothing is left. */
    private fun drainPending(): Boolean {
        val s = session ?: return true
        while (pendingLen > 0) {
            if (!s.isRunning()) {
                // Unplugged: hold the sound back (no error) while the player switches to Android's output.
                UsbLive.lost = true
                return false
            }
            val n = s.write(pending, pendingOff, pendingLen)
            if (n <= 0) return false
            pendingOff += n
            pendingLen -= n
            framesWritten += n / 8
        }
        pendingOff = 0
        return true
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (!usb) return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (!drainPending()) return false
        if (startMediaUs == C.TIME_UNSET) startMediaUs = presentationTimeUs
        val now = SystemClock.elapsedRealtime()
        if (now - lastStatsAt > 5000) {
            lastStatsAt = now
            session?.let { s ->
                Log.i(TAG, "health: gaps ${s.underruns()}, longest pause ${s.maxGapUs() / 1000.0} ms, buffered ${s.ringFrames() * 1000 / rate.coerceAtLeast(1)} ms, convert ${convertNs / 1_000_000} ms per 5 s")
            }
            convertNs = 0
        }
        if (energyFrames < rate * 6 && inFormat.channelCount > 2 && (inFormat.encoding == C.ENCODING_PCM_16BIT || inFormat.encoding == C.ENCODING_PCM_FLOAT)) measureChannels(buffer)
        val t0 = System.nanoTime()
        val p = pipeline
        if (p == null || !p.isOperational) {
            convert(buffer)
        } else {
            var guard = 0
            while (buffer.hasRemaining() && guard++ < 64) {
                p.queueInput(buffer)
                val out = p.output
                if (out.hasRemaining()) convert(out)
            }
            while (true) {
                val out = p.output
                if (!out.hasRemaining()) break
                convert(out)
            }
        }
        convertNs += System.nanoTime() - t0
        drainPending()
        session?.let { s ->
            if (!primed && s.ringFrames() >= rate / 4) {
                primed = true
                if (wantPlay) s.setPaused(false)
            }
        }
        return !buffer.hasRemaining()
    }

    // After a start or a seek, sound plays only once a quarter of a second is waiting, so a busy start
    // (4K video starting up) does not make the DAC run dry and restart over and over.
    private var primed = false
    private var wantPlay = false
    private var lastStatsAt = 0L
    private var convertNs = 0L
    private var energy = DoubleArray(0)
    private var energyFrames = 0L

    /** Logs each input channel's level over the first seconds: the dialogue (centre) channel is the loudest. */
    private fun measureChannels(buffer: ByteBuffer) {
        val ch = inFormat.channelCount
        if (energy.size != ch) { energy = DoubleArray(ch); energyFrames = 0 }
        val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val float = inFormat.encoding == C.ENCODING_PCM_FLOAT
        val frames = b.remaining() / ((if (float) 4 else 2) * ch)
        for (f in 0 until frames) for (c in 0 until ch) { val v = if (float) b.float * 32768.0 else b.short.toDouble(); energy[c] += v * v }
        energyFrames += frames
        if (energyFrames >= rate * 6L) {
            val db = energy.map { 10 * Math.log10(it / energyFrames + 1e-9) }
            Log.i(TAG, "input channel levels (dB): " + db.joinToString { "%.1f".format(it) })
        }
    }

    override fun playToEndOfStream() {
        if (!usb) return super.playToEndOfStream()
        draining = true
        // A short ending may never fill the pre-fill: play what there is.
        if (!primed) {
            primed = true
            if (wantPlay) session?.setPaused(false)
        }
        drainPending()
    }

    override fun isEnded(): Boolean {
        if (!usb) return super.isEnded()
        val s = session ?: return true
        return draining && pendingLen == 0 && (s.ringFrames() == 0L || !s.isRunning())
    }

    override fun hasPendingData(): Boolean {
        if (!usb) return super.hasPendingData()
        val s = session ?: return false
        return pendingLen > 0 || s.ringFrames() > 0
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (!usb) return super.getCurrentPositionUs(sourceEnded)
        val s = session ?: return AudioSink.CURRENT_POSITION_NOT_SET
        if (startMediaUs == C.TIME_UNSET || rate == 0) return AudioSink.CURRENT_POSITION_NOT_SET
        val played = (s.framesPlayed() - anchorFrames).coerceAtLeast(0)
        return startMediaUs + played * 1_000_000L / rate
    }

    override fun play() {
        if (!usb) return super.play()
        wantPlay = true
        if (primed) session?.setPaused(false)
    }

    override fun pause() {
        if (!usb) return super.pause()
        wantPlay = false
        session?.setPaused(true)
    }

    override fun handleDiscontinuity() {
        if (!usb) super.handleDiscontinuity()
    }

    override fun flush() {
        if (!usb) return super.flush()
        primed = false
        session?.setPaused(true)
        session?.flush()
        pipeline?.flush()
        pendingLen = 0
        pendingOff = 0
        draining = false
        startMediaUs = C.TIME_UNSET
        framesWritten = 0
        anchorFrames = 0
    }

    // Direct output plays at 1x: changing speed would mean resampling, which is what it avoids.
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        if (!usb) super.setPlaybackParameters(playbackParameters)
    }

    override fun getPlaybackParameters(): PlaybackParameters =
        if (usb) PlaybackParameters.DEFAULT else super.getPlaybackParameters()

    // The DAC's own volume is the master volume; the player's volume would scale the samples.
    override fun setVolume(volume: Float) {
        if (!usb) super.setVolume(volume)
    }

    override fun reset() {
        if (usb) flush()
        super.reset()
    }

    override fun release() {
        useFallback()
        super.release()
    }
}
