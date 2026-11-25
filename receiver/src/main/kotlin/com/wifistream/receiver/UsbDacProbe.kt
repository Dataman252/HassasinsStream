package com.wifistream.receiver

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * What a USB DAC says about itself, read only from its USB descriptors (nothing is sent to it,
 * and Android keeps playing through it). Used to plan direct (bit-perfect) USB output.
 */
data class UsbDacInfo(
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val uacVersion: Int,
    val controlInterface: Int,
    val clockSources: List<String>,
    val volumeUnits: List<String>,
    val streamingModes: List<String>,
)

object UsbDacProbe {
    private const val CS_INTERFACE = 0x24
    private const val INTERFACE = 0x04
    private const val ENDPOINT = 0x05

    /** The first attached device with a USB Audio streaming interface, if any. */
    fun findDac(usb: UsbManager): UsbDevice? = usb.deviceList.values.firstOrNull { d ->
        (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO && d.getInterface(it).interfaceSubclass == 2 }
    }

    fun read(usb: UsbManager, device: UsbDevice): UsbDacInfo? {
        val conn = usb.openDevice(device) ?: return null
        try {
            val raw = conn.rawDescriptors ?: return null
            return parse(device, raw)
        } finally {
            conn.close()
        }
    }

    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun u24(b: ByteArray, i: Int) = u16(b, i) or ((b[i + 2].toInt() and 0xFF) shl 16)
    private fun u32(b: ByteArray, i: Int) = u16(b, i) or (u16(b, i + 2) shl 16)

    fun parse(device: UsbDevice, raw: ByteArray): UsbDacInfo {
        var uac = 1
        var acInterface = -1
        val clocks = mutableListOf<String>()
        val volumes = mutableListOf<String>()
        val modes = mutableListOf<String>()

        var ifNum = -1
        var alt = 0
        var ifClass = 0
        var ifSub = 0
        var current: StringBuilder? = null
        fun closeMode() {
            current?.let { if (it.isNotEmpty()) modes += it.toString() }
            current = null
        }

        var i = 0
        while (i + 1 < raw.size) {
            val len = raw[i].toInt() and 0xFF
            if (len < 2 || i + len > raw.size) break
            val type = raw[i + 1].toInt() and 0xFF
            val d = raw.copyOfRange(i, i + len)
            when (type) {
                INTERFACE -> {
                    closeMode()
                    ifNum = d[2].toInt() and 0xFF
                    alt = d[3].toInt() and 0xFF
                    ifClass = d[5].toInt() and 0xFF
                    ifSub = d[6].toInt() and 0xFF
                    val protocol = d[7].toInt() and 0xFF
                    if (ifClass == 1 && ifSub == 1) {
                        acInterface = ifNum
                        if (protocol == 0x20) uac = 2
                    }
                    if (ifClass == 1 && ifSub == 2 && alt > 0) current = StringBuilder("interface $ifNum alt $alt:")
                }
                CS_INTERFACE -> if (ifClass == 1 && len >= 3) {
                    val sub = d[2].toInt() and 0xFF
                    if (ifSub == 1) {
                        // Audio control interface.
                        if (uac == 2 && sub == 0x0A && len >= 8) {
                            val attrs = d[4].toInt() and 0xFF
                            val controls = d[5].toInt() and 0xFF
                            val kind = when (attrs and 3) { 0 -> "external"; 1 -> "internal fixed"; 2 -> "internal variable"; else -> "internal programmable" }
                            val freq = when (controls and 3) { 3 -> "rate settable"; 1 -> "rate read-only"; else -> "rate not controllable" }
                            clocks += "clock ${d[3].toInt() and 0xFF}: $kind, $freq"
                        }
                        if (sub == 0x06) {
                            val unit = d[3].toInt() and 0xFF
                            if (uac == 2 && len >= 10) {
                                val channels = (len - 6) / 4
                                val master = u32(d, 5)
                                val parts = mutableListOf<String>()
                                if (master and 0x3 != 0) parts += "mute" + if (master and 0x3 == 3) " (settable)" else " (read-only)"
                                if (master and 0xC != 0) parts += "master volume" + if (master and 0xC == 0xC) " (settable)" else " (read-only)"
                                for (ch in 1 until channels) {
                                    val c = u32(d, 5 + ch * 4)
                                    if (c and 0xC != 0) parts += "ch$ch volume" + if (c and 0xC == 0xC) " (settable)" else " (read-only)"
                                }
                                volumes += "feature unit $unit: " + (parts.joinToString().ifEmpty { "no volume or mute" })
                            } else if (uac == 1 && len >= 7) {
                                val size = d[5].toInt() and 0xFF
                                val parts = mutableListOf<String>()
                                var off = 6
                                var ch = 0
                                while (size > 0 && off + size <= len - 1) {
                                    val c = d[off].toInt() and 0xFF
                                    if (c and 0x1 != 0) parts += (if (ch == 0) "master" else "ch$ch") + " mute"
                                    if (c and 0x2 != 0) parts += (if (ch == 0) "master" else "ch$ch") + " volume"
                                    off += size
                                    ch++
                                }
                                volumes += "feature unit $unit: " + (parts.joinToString().ifEmpty { "no volume or mute" })
                            }
                        }
                    } else if (ifSub == 2) {
                        // Audio streaming interface.
                        val m = current
                        if (m != null) when {
                            sub == 0x01 && uac == 2 && len >= 16 -> m.append(" ${d[10].toInt() and 0xFF}ch")
                            sub == 0x01 && uac == 1 && len >= 7 -> m.append(" format tag ${u16(d, 5)}")
                            sub == 0x02 && uac == 2 && len >= 6 -> m.append(" ${d[5].toInt() and 0xFF}-bit in ${d[4].toInt() and 0xFF}-byte slots")
                            sub == 0x02 && uac == 1 && len >= 8 -> {
                                m.append(" ${d[4].toInt() and 0xFF}ch ${d[6].toInt() and 0xFF}-bit")
                                val n = d[7].toInt() and 0xFF
                                if (n == 0 && len >= 14) m.append(" ${u24(d, 8)}–${u24(d, 11)} Hz")
                                else m.append(" rates " + (0 until n).filter { 8 + it * 3 + 2 < len }.joinToString("/") { "${u24(d, 8 + it * 3)}" })
                            }
                        }
                    }
                }
                ENDPOINT -> current?.let { m ->
                    val addr = d[2].toInt() and 0xFF
                    val attrs = d[3].toInt() and 0xFF
                    val maxPacket = u16(d, 4) and 0x7FF
                    val extra = (u16(d, 4) shr 11) and 3
                    val dir = if (addr and 0x80 != 0) "IN" else "OUT"
                    val sync = when ((attrs shr 2) and 3) { 1 -> "async"; 2 -> "adaptive"; 3 -> "sync"; else -> "none" }
                    val usage = when ((attrs shr 4) and 3) { 1 -> "feedback"; 2 -> "implicit feedback"; else -> "data" }
                    m.append(" | ep 0x${addr.toString(16)} $dir $usage, $sync, ${maxPacket * (extra + 1)} B, interval ${d[6].toInt() and 0xFF}")
                }
            }
            i += len
        }
        closeMode()
        val name = listOfNotNull(device.manufacturerName, device.productName).joinToString(" ").ifEmpty { device.deviceName }
        return UsbDacInfo(name, device.vendorId, device.productId, uac, acInterface, clocks, volumes, modes)
    }

    fun describe(info: UsbDacInfo): String = buildString {
        appendLine("${info.name} (USB ${"%04x".format(info.vendorId)}:${"%04x".format(info.productId)})")
        appendLine("USB Audio Class ${info.uacVersion}.0, control interface ${info.controlInterface}")
        info.clockSources.forEach { appendLine(it) }
        info.volumeUnits.forEach { appendLine(it) }
        info.streamingModes.forEach { appendLine(it) }
    }
}

/**
 * Started with `am start -n com.wifistream.receiver/.UsbDacProbeActivity`; asks for USB access once,
 * reads the DAC's descriptors, shows them and logs them (tag UsbDacProbe, ends with DONE).
 */
class UsbDacProbeActivity : Activity() {
    private lateinit var text: TextView
    private val action = "com.wifistream.receiver.USB_PROBE_PERMISSION"
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == action) probe()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF000000.toInt())
            textSize = 13f
            setPadding(32, 48, 32, 32)
        }
        setContentView(ScrollView(this).apply { addView(text) })
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, IntentFilter(action), RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(receiver, IntentFilter(action))
        val usb = getSystemService(USB_SERVICE) as UsbManager
        val dac = UsbDacProbe.findDac(usb)
        when {
            dac == null -> show("No USB audio device found.\nDONE")
            usb.hasPermission(dac) -> probe()
            else -> {
                show("Asking for access to ${dac.productName ?: "the DAC"}…")
                val pi = PendingIntent.getBroadcast(this, 0, Intent(action).setPackage(packageName), PendingIntent.FLAG_MUTABLE)
                usb.requestPermission(dac, pi)
            }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    private fun probe() {
        val usb = getSystemService(USB_SERVICE) as UsbManager
        val dac = UsbDacProbe.findDac(usb) ?: return show("The DAC was unplugged.\nDONE")
        if (!usb.hasPermission(dac)) return show("USB access was not allowed.\nDONE")
        val info = UsbDacProbe.read(usb, dac) ?: return show("Could not read the DAC.\nDONE")
        val report = UsbDacProbe.describe(info)
        File(filesDir, "usb-dac.txt").writeText(report)
        show(report + "DONE")
    }

    private fun show(s: String) {
        s.lines().forEach { Log.i("UsbDacProbe", it) }
        text.text = s
    }
}
