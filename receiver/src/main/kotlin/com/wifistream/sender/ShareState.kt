package com.wifistream.sender

import kotlinx.coroutines.flow.MutableStateFlow

/** What the screen shows about the sharing service. Written by [ShareService]. */
data class ShareStatus(
    val running: Boolean = false,
    val deviceName: String = "",
    /** `http://ip:port`, or null when the phone has no Wi-Fi / hotspot address. */
    val address: String? = null,
    /** True once the phone is announced on the network for automatic discovery. */
    val discoverable: Boolean = false,
    val scannedFiles: Int = 0,
    /** Speed measured by the last watching device that ran a speed test against this phone, in Mb/s. */
    val lastSpeedMbps: Double? = null,
    /** Times a shared USB drive vanished without being ejected while sharing, and when it last did. */
    val driveDrops: Int = 0,
    val lastDropAt: Long = 0,
    /** True while a shared drive is missing right now. */
    val driveMissing: Boolean = false,
    val error: String? = null,
)

object ShareState {
    val status = MutableStateFlow(ShareStatus())
}
