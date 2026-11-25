package com.wifistream.receiver

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** The native streaming engine (usbaudio.cpp). */
object UsbAudioNative {
    init { System.loadLibrary("usbaudio") }
    @JvmStatic external fun start(fd: Int, endpoint: Int, frameBytes: Int, rate: Int, maxPacket: Int, packetsPerSecond: Int, bufferMs: Int, urbCount: Int, cpuMode: Int): Long
    @JvmStatic external fun maxGapUs(handle: Long): Long
    @JvmStatic external fun priorityResult(handle: Long): Int
    @JvmStatic external fun cpuMask(handle: Long): Int
    @JvmStatic external fun write(handle: Long, data: ByteArray, offset: Int, length: Int): Int
    @JvmStatic external fun queuedFrames(handle: Long): Long
    @JvmStatic external fun framesPlayed(handle: Long): Long
    @JvmStatic external fun underruns(handle: Long): Long
    @JvmStatic external fun isRunning(handle: Long): Boolean
    @JvmStatic external fun lastError(handle: Long): Int
    @JvmStatic external fun latePackets(handle: Long): Long
    @JvmStatic external fun minInFlight(handle: Long): Int
    @JvmStatic external fun setPaused(handle: Long, paused: Boolean)
    @JvmStatic external fun flush(handle: Long)
    @JvmStatic external fun ringFrames(handle: Long): Long
    @JvmStatic external fun stop(handle: Long)
    @JvmStatic external fun reattachKernelDriver(fd: Int, interfaceNumber: Int): Int
    @JvmStatic external fun resetDevice(fd: Int): Int
}

/** How a USB Audio Class 2 DAC's playback path is wired, read from its descriptors. */
data class DacLayout(
    val controlInterface: Int,
    val streamingInterface: Int,
    val clockId: Int,
    val featureUnit: Int,
    val volumeChannels: List<Int>,
    val alts: List<Alt>,
) {
    data class Alt(val alt: Int, val channels: Int, val slotBytes: Int, val bits: Int, val endpoint: Int, val maxPacket: Int, val interval: Int) {
        val frameBytes get() = channels * slotBytes
        /** High-speed isochronous: 8000 packets a second at interval 1. */
        val packetsPerSecond get() = 8000 shr (interval - 1).coerceIn(0, 3)
        val maxRate get() = maxPacket / frameBytes * packetsPerSecond
    }

    companion object {
        private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
        private fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
        private fun u32(b: ByteArray, i: Int) = u16(b, i) or (u16(b, i + 2) shl 16)

        fun parse(raw: ByteArray): DacLayout? {
            var ac = -1
            var uac2 = false
            data class Terminal(val id: Int, val type: Int, val clock: Int)
            data class Unit(val id: Int, val source: Int, val controls: List<Int>)
            val inputs = mutableListOf<Terminal>()
            val units = mutableListOf<Unit>()
            val streams = mutableMapOf<Int, Int>()  // interface -> terminal link
            val alts = mutableMapOf<Int, MutableList<Alt>>()

            var ifNum = -1; var alt = 0; var cls = 0; var sub = 0
            var channels = 0; var slot = 0; var bits = 0
            var i = 0
            while (i + 1 < raw.size) {
                val len = u8(raw, i)
                if (len < 2 || i + len > raw.size) break
                val d = raw.copyOfRange(i, i + len)
                when (u8(d, 1)) {
                    0x04 -> {
                        ifNum = u8(d, 2); alt = u8(d, 3); cls = u8(d, 5); sub = u8(d, 6)
                        channels = 0; slot = 0; bits = 0
                        if (cls == 1 && sub == 1) { ac = ifNum; uac2 = u8(d, 7) == 0x20 }
                    }
                    0x24 -> if (cls == 1 && len >= 3) {
                        val st = u8(d, 2)
                        if (sub == 1) {
                            if (st == 0x02 && len >= 8) inputs += Terminal(u8(d, 3), u16(d, 4), u8(d, 7))
                            if (st == 0x06 && len >= 10) units += Unit(u8(d, 3), u8(d, 4), (0 until (len - 6) / 4).map { u32(d, 5 + it * 4) })
                        } else if (sub == 2) {
                            if (st == 0x01 && len >= 11) { streams[ifNum] = u8(d, 3); channels = u8(d, 10) }
                            if (st == 0x02 && len >= 6) { slot = u8(d, 4); bits = u8(d, 5) }
                        }
                    }
                    0x05 -> if (cls == 1 && sub == 2 && alt > 0) {
                        val addr = u8(d, 2)
                        val attrs = u8(d, 3)
                        if (addr and 0x80 == 0 && attrs and 3 == 1 && (attrs shr 4) and 3 == 0 && slot > 0) {
                            val mp = u16(d, 4)
                            alts.getOrPut(ifNum) { mutableListOf() } += Alt(alt, channels, slot, bits, addr, (mp and 0x7FF) * (((mp shr 11) and 3) + 1), u8(d, 6))
                        }
                    }
                }
                i += len
            }
            if (!uac2 || ac < 0) return null
            val usbIn = inputs.firstOrNull { it.type == 0x0101 } ?: return null
            val asIf = streams.entries.firstOrNull { it.value == usbIn.id && alts.containsKey(it.key) }?.key ?: return null
            val fu = units.firstOrNull { it.source == usbIn.id }
            val volChannels = fu?.controls?.withIndex()?.filter { (it.value and 0xC) == 0xC }?.map { it.index }.orEmpty()
            return DacLayout(ac, asIf, usbIn.clock, fu?.id ?: -1, volChannels, alts.getValue(asIf).sortedBy { it.bits })
        }
    }
}

/**
 * Owns a USB DAC while the app plays to it directly. Android loses the DAC for that time (its
 * audio driver is detached) and gets it back on [close].
 */
class UsbDacSession private constructor(
    val device: UsbDevice,
    private val conn: UsbDeviceConnection,
    val layout: DacLayout,
) {
    private val claimed = mutableListOf<UsbInterface>()
    private var handle = 0L
    var current: DacLayout.Alt? = null
        private set
    var rate = 0
        private set
    var volumeRange: Triple<Double, Double, Double>? = null
        private set

    companion object {
        private const val TAG = "UsbDac"

        /** Which cores the streaming thread may use: 0 fastest only, 1 any, 2 all but the fastest. For tests. */
        @Volatile var cpuMode = 1

        /** Overrides the USB queue length for tests. */
        @Volatile var queueOverride: Int? = null

        /** One line per finished stream: rate, real gaps, longest pause. Read by the tests. */
        val streamStats: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

        /** Set once by the app, so a session can ask Android for its mixer rate. */
        @Volatile var context: Context? = null

        fun open(usb: UsbManager, device: UsbDevice): UsbDacSession? {
            if (!usb.hasPermission(device)) return null
            val conn = usb.openDevice(device) ?: return null
            val layout = conn.rawDescriptors?.let { DacLayout.parse(it) }
            if (layout == null) { conn.close(); return null }
            val s = UsbDacSession(device, conn, layout)
            if (!s.claim()) { s.close(); return null }
            s.rememberAndroidState()
            s.volumeRange = s.readVolumeRange()
            return s
        }
    }

    private fun claim(): Boolean {
        // Every audio interface: the kernel's audio driver lets go of the whole card.
        val seen = mutableSetOf<Int>()
        for (k in 0 until device.interfaceCount) {
            val intf = device.getInterface(k)
            if (intf.interfaceClass != UsbConstants.USB_CLASS_AUDIO || !seen.add(intf.id)) continue
            if (!conn.claimInterface(intf, true)) { Log.e(TAG, "could not claim interface ${intf.id}"); return false }
            claimed += intf
        }
        return true
    }

    private fun altInterface(alt: Int): UsbInterface? = (0 until device.interfaceCount).map { device.getInterface(it) }
        .firstOrNull { it.id == layout.streamingInterface && it.alternateSetting == alt }

    /** UAC2 clock frequency, set then read back. */
    private fun setClock(hz: Int): Int {
        val index = (layout.clockId shl 8) or layout.controlInterface
        val data = byteArrayOf(hz.toByte(), (hz shr 8).toByte(), (hz shr 16).toByte(), (hz shr 24).toByte())
        conn.controlTransfer(0x21, 0x01, 0x0100, index, data, 4, 1000)
        val back = ByteArray(4)
        if (conn.controlTransfer(0xA1, 0x01, 0x0100, index, back, 4, 1000) != 4) return -1
        return (back[0].toInt() and 0xFF) or ((back[1].toInt() and 0xFF) shl 8) or ((back[2].toInt() and 0xFF) shl 16) or ((back[3].toInt() and 0xFF) shl 24)
    }

    /** The DAC's current clock rate, or -1. */
    private fun readClock(): Int {
        val back = ByteArray(4)
        val index = (layout.clockId shl 8) or layout.controlInterface
        if (conn.controlTransfer(0xA1, 0x01, 0x0100, index, back, 4, 1000) != 4) return -1
        return (back[0].toInt() and 0xFF) or ((back[1].toInt() and 0xFF) shl 8) or ((back[2].toInt() and 0xFF) shl 16) or ((back[3].toInt() and 0xFF) shl 24)
    }

    /** Raw volume value (1/256 dB) of one channel, or null. */
    private fun readVolumeRaw(ch: Int): ByteArray? {
        val b = ByteArray(2)
        val n = conn.controlTransfer(0xA1, 0x01, (0x02 shl 8) or ch, (layout.featureUnit shl 8) or layout.controlInterface, b, 2, 1000)
        return if (n == 2) b else null
    }

    // How Android left the DAC, put back on close: its audio driver remembers the rate it set and
    // does not send it again, so a changed clock would make Android's sound play at the wrong speed.
    private var androidRate = -1
    private val androidVolume = mutableMapOf<Int, ByteArray>()
    private var androidMute: Boolean? = null

    private fun rememberAndroidState() {
        val seen = readClock()
        // Once taken from Android the DAC may already sit at an idle rate (the D07 drops to 8 kHz), so the
        // rate given back is the one Android's mixer runs at, if the DAC offers it.
        val mixer = (context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
            ?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
        androidRate = mixer
        Log.i(TAG, "DAC clock when taken: $seen Hz; will give it back at $mixer Hz")
        layout.volumeChannels.forEach { ch -> readVolumeRaw(ch)?.let { androidVolume[ch] = it } }
        androidMute = readMute()
    }

    private fun restoreAndroidState() {
        if (androidRate > 0) setClock(androidRate)
        for ((ch, raw) in androidVolume) {
            conn.controlTransfer(0x21, 0x01, (0x02 shl 8) or ch, (layout.featureUnit shl 8) or layout.controlInterface, raw, 2, 1000)
        }
        androidMute?.let { setMute(it) }
    }

    /** Volume limits in dB (min, max, step), from the first volume channel. */
    private fun readVolumeRange(): Triple<Double, Double, Double>? {
        val ch = layout.volumeChannels.firstOrNull() ?: return null
        val buf = ByteArray(8)
        val n = conn.controlTransfer(0xA1, 0x02, (0x02 shl 8) or ch, (layout.featureUnit shl 8) or layout.controlInterface, buf, 8, 1000)
        if (n < 8) return null
        fun s16(i: Int) = ((buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)).toShort().toInt()
        return Triple(s16(2) / 256.0, s16(4) / 256.0, s16(6) / 256.0)
    }

    /** The DAC's own mute (master channel). Used around rate changes so the DAC re-locking does not pop. */
    fun setMute(on: Boolean) {
        if (layout.featureUnit < 0) return
        conn.controlTransfer(0x21, 0x01, 0x0100, (layout.featureUnit shl 8) or layout.controlInterface, byteArrayOf(if (on) 1 else 0), 1, 1000)
    }

    /** Sets the DAC's own volume on every channel; returns the value it took, in dB. */
    fun setVolumeDb(db: Double): Double? {
        val range = volumeRange ?: return null
        volumeDb = db
        val clamped = db.coerceIn(range.first, range.second)
        val value = (clamped * 256).roundToInt()
        val data = byteArrayOf(value.toByte(), (value shr 8).toByte())
        for (ch in layout.volumeChannels) {
            conn.controlTransfer(0x21, 0x01, (0x02 shl 8) or ch, (layout.featureUnit shl 8) or layout.controlInterface, data, 2, 1000)
        }
        return clamped
    }

    /**
     * Starts streaming at [hz] using the deepest format the DAC offers (or [bits] if given).
     * Returns false if the DAC refused the rate.
     */
    /** Last volume asked for, in dB. */
    var volumeDb = 0.0
        private set

    /** How rate switches are hidden: 0 = DAC mute, 1 = silence only, 2 = glide the DAC volume down and up. */
    var switchMode = 0

    private fun writeVolume(db: Double) {
        val range = volumeRange ?: return
        val value = (db.coerceIn(range.first, range.second) * 256).roundToInt()
        val data = byteArrayOf(value.toByte(), (value shr 8).toByte())
        for (ch in layout.volumeChannels) {
            conn.controlTransfer(0x21, 0x01, (0x02 shl 8) or ch, (layout.featureUnit shl 8) or layout.controlInterface, data, 2, 1000)
        }
    }

    /** Glides the DAC's volume between two levels in small steps over [ms]. */
    private fun glide(fromDb: Double, toDb: Double, ms: Int) {
        val steps = 12
        for (k in 1..steps) {
            writeVolume(fromDb + (toDb - fromDb) * k / steps)
            SystemClock.sleep((ms / steps).toLong())
        }
    }

    private fun hide() {
        when (switchMode) {
            0 -> { setMute(true); if (handle != 0L) SystemClock.sleep(30) }
            1 -> if (handle != 0L) SystemClock.sleep(100)  // the audio already faded out; let silence play
            2 -> if (handle != 0L) glide(volumeDb, volumeRange?.first ?: -74.0, 60) else writeVolume(volumeRange?.first ?: -74.0)
        }
    }

    private fun reveal() {
        when (switchMode) {
            0 -> { SystemClock.sleep(150); setMute(false) }
            1 -> SystemClock.sleep(100)
            2 -> { SystemClock.sleep(150); glide(volumeRange?.first ?: -74.0, volumeDb, 100) }
        }
    }

    /** Transfers of 1 ms queued in the USB controller. */
    var queueMs = 30

    /** Reads the DAC's mute back (null if it cannot be read). */
    fun readMute(): Boolean? {
        if (layout.featureUnit < 0) return null
        val b = ByteArray(1)
        val n = conn.controlTransfer(0xA1, 0x01, 0x0100, (layout.featureUnit shl 8) or layout.controlInterface, b, 1, 1000)
        return if (n == 1) b[0].toInt() != 0 else null
    }

    fun start(hz: Int, bits: Int = 0): Boolean {
        // Hide the switch, so neither the cut nor the DAC re-locking to a new clock is heard.
        hide()
        stopStream()
        val alt = (if (bits > 0) layout.alts.firstOrNull { it.bits == bits } else null) ?: layout.alts.last()
        altInterface(0)?.let { conn.setInterface(it) }
        val got = setClock(hz)
        if (got != hz) { Log.e(TAG, "DAC runs at $got Hz, not $hz"); return false }
        val intf = altInterface(alt.alt) ?: return false
        if (!conn.setInterface(intf)) { Log.e(TAG, "alt ${alt.alt} refused"); return false }
        handle = UsbAudioNative.start(conn.fileDescriptor, alt.endpoint, alt.frameBytes, hz, alt.maxPacket, alt.packetsPerSecond, 500, queueOverride ?: queueMs, cpuMode)
        current = alt
        rate = hz
        val ok = handle != 0L && UsbAudioNative.isRunning(handle)
        // A little silence while the DAC locks to the new clock, then sound.
        reveal()
        return ok
    }

    fun write(data: ByteArray, offset: Int, length: Int): Int = if (handle != 0L) UsbAudioNative.write(handle, data, offset, length) else -1
    fun ringFrames(): Long = if (handle != 0L) UsbAudioNative.ringFrames(handle) else 0
    fun queuedFrames(): Long = if (handle != 0L) UsbAudioNative.queuedFrames(handle) else 0
    fun framesPlayed(): Long = if (handle != 0L) UsbAudioNative.framesPlayed(handle) else 0
    fun underruns(): Long = if (handle != 0L) UsbAudioNative.underruns(handle) else 0
    fun maxGapUs(): Long = if (handle != 0L) UsbAudioNative.maxGapUs(handle) else 0
    fun cpuMask(): Int = if (handle != 0L) UsbAudioNative.cpuMask(handle) else 0
    fun priorityResult(): Int = if (handle != 0L) UsbAudioNative.priorityResult(handle) else -1
    fun latePackets(): Long = if (handle != 0L) UsbAudioNative.latePackets(handle) else 0
    fun minInFlight(): Int = if (handle != 0L) UsbAudioNative.minInFlight(handle) else 0
    fun isRunning(): Boolean = handle != 0L && UsbAudioNative.isRunning(handle)
    fun setPaused(p: Boolean) { if (handle != 0L) UsbAudioNative.setPaused(handle, p) }
    fun flush() { if (handle != 0L) UsbAudioNative.flush(handle) }

    private fun stopStream() {
        if (handle != 0L) {
            streamStats += "$rate Hz: ${UsbAudioNative.underruns(handle)} gaps, longest pause ${UsbAudioNative.maxGapUs(handle) / 1000.0} ms, late packets ${UsbAudioNative.latePackets(handle)}, queue ${queueOverride ?: queueMs} ms, cores ${(0 until 16).filter { (UsbAudioNative.cpuMask(handle) shr it) and 1 == 1 }}"
            UsbAudioNative.stop(handle)
            handle = 0
        }
    }

    /** Stops, then hands the DAC back to Android. */
    fun close() {
        hide()
        stopStream()
        runCatching { altInterface(0)?.let { conn.setInterface(it) } }
        runCatching { restoreAndroidState() }
        val fd = conn.fileDescriptor
        for (intf in claimed) conn.releaseInterface(intf)
        claimed.clear()
        // (A USB reset would also work but makes Android forget the app's USB access, so: reattach.)
        for (intf in (0 until device.interfaceCount).map { device.getInterface(it) }.filter { it.interfaceClass == UsbConstants.USB_CLASS_AUDIO }.map { it.id }.distinct()) {
            UsbAudioNative.reattachKernelDriver(fd, intf)
        }
        conn.close()
    }
}

/**
 * Plays a quiet test tone straight to the DAC at several rates, then hands it back to Android.
 * `am start -n com.wifistream.receiver/.UsbDacTestActivity`; log tag UsbDacTest, ends with DONE.
 */
class UsbDacTestActivity : Activity() {
    private lateinit var text: TextView
    private val action = "com.wifistream.receiver.USB_TEST_PERMISSION"
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { if (intent.action == action) Thread { run() }.start() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this).apply { setTextColor(-1); setBackgroundColor(-0x1000000); textSize = 13f; setPadding(32, 48, 32, 32) }
        setContentView(ScrollView(this).apply { addView(text) })
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, IntentFilter(action), RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(receiver, IntentFilter(action))
        val usb = getSystemService(USB_SERVICE) as UsbManager
        val dac = UsbDacProbe.findDac(usb)
        when {
            dac == null -> show("No USB DAC.\nDONE")
            usb.hasPermission(dac) -> Thread { run() }.start()
            else -> usb.requestPermission(dac, PendingIntent.getBroadcast(this, 0, Intent(action).setPackage(packageName), PendingIntent.FLAG_MUTABLE))
        }
    }

    override fun onDestroy() { unregisterReceiver(receiver); super.onDestroy() }

    private val lines = StringBuilder()
    private val t0 = SystemClock.elapsedRealtime()
    private fun show(s: String) {
        val line = "[${SystemClock.elapsedRealtime() - t0} ms] $s"
        Log.i("UsbDacTest", line)
        synchronized(lines) {
            lines.appendLine(line)
            runCatching { java.io.File(filesDir, "usb-dac-test.txt").writeText(lines.toString()) }
        }
        runOnUiThread { text.text = lines }
    }

    private fun run() {
        val usb = getSystemService(USB_SERVICE) as UsbManager
        val dac = UsbDacProbe.findDac(usb) ?: return show("No USB DAC.\nDONE")
        val session = UsbDacSession.open(usb, dac) ?: return show("Could not open the DAC.\nDONE")
        try {
            val l = session.layout
            show("Layout: control if ${l.controlInterface}, stream if ${l.streamingInterface}, clock ${l.clockId}, volume unit ${l.featureUnit} ch ${l.volumeChannels}")
            l.alts.forEach { show("  alt ${it.alt}: ${it.channels}ch ${it.bits}-bit/${it.slotBytes}B, ep 0x${it.endpoint.toString(16)}, ${it.maxPacket} B, ${it.packetsPerSecond}/s, max ${it.maxRate} Hz") }
            show("Volume range: ${session.volumeRange}")
            show("Volume set to ${session.setVolumeDb(intent.getIntExtra("vol", if (intent.getBooleanExtra("loud", false)) -20 else -40).toDouble().coerceAtMost(-4.0))} dB")
            session.setMute(true)
            show("Mute check: set on, DAC reports ${session.readMute()}")
            session.setMute(false)
            show("Mute check: set off, DAC reports ${session.readMute()}")
            session.queueMs = intent.getIntExtra("queue", 24)
            session.switchMode = intent.getIntExtra("switch", 0)
            show("Switch mode: ${listOf("DAC mute", "silence only", "volume glide")[session.switchMode]}")
            show("USB queue: ${session.queueMs} ms")
            for (hz in listOf(44100, 96000, 48000, 192000)) tone(session, hz)
        } finally {
            session.close()
        }
        SystemClock.sleep(2500)
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val back = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE }
        show("Android has the DAC back: $back")
        show("DONE")
    }

    /** Three seconds of 1 kHz at -20 dBFS, 32-bit stereo. */
    private fun tone(s: UsbDacSession, hz: Int) {
        show("$hz Hz: switching")
        if (!s.start(hz)) return show("$hz Hz: refused")
        show("$hz Hz: unmuted, tone starts")
        val alt = s.current!!
        val seconds = 3
        val frames = hz * seconds
        val chunk = hz / 100
        val buf = ByteArray(chunk * alt.frameBytes)
        val amp = 0.1 * (if (alt.slotBytes == 4) Int.MAX_VALUE.toDouble() else if (alt.slotBytes == 3) 8388607.0 else 32767.0)
        var n = 0
        val t0 = SystemClock.elapsedRealtime()
        while (n < frames && s.isRunning()) {
            var o = 0
            for (f in 0 until chunk) {
                val k = n + f
                val ramp = minOf(1.0, k / (hz / 20.0), (frames - k) / (hz / 20.0)).coerceAtLeast(0.0)
                val v = (amp * ramp * sin(2 * PI * 1000 * k / hz)).toLong()
                repeat(alt.channels) {
                    for (b in 0 until alt.slotBytes) buf[o++] = (v shr (8 * b)).toByte()
                }
            }
            var off = 0
            var waitedMs = 0
            while (off < buf.size && s.isRunning() && waitedMs < 2000) {
                val w = s.write(buf, off, buf.size - off)
                if (w <= 0) { SystemClock.sleep(3); waitedMs += 3 } else { off += w; waitedMs = 0 }
            }
            if (waitedMs >= 2000) return show("$hz Hz: STALLED, the DAC stopped taking audio (played ${s.framesPlayed()} frames)")
            n += chunk
        }
        while (s.framesPlayed() < frames && s.isRunning() && SystemClock.elapsedRealtime() - t0 < seconds * 1000L + 2000) SystemClock.sleep(10)
        val ms = SystemClock.elapsedRealtime() - t0
        show("$hz Hz: ${alt.bits}-bit, played ${s.framesPlayed()} frames in $ms ms (expected ${frames * 1000L / hz} ms), late packets ${s.latePackets()}, longest stall ${s.maxGapUs() / 1000.0} ms (queue ${s.queueMs} ms), cores ${(0 until 16).filter { (s.cpuMask() shr it) and 1 == 1 }}")
    }
}
