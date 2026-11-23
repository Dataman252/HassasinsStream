package com.wifistream.protocol

/** "4K", "FHD", "HD" or "SD"; null when the size is unknown. Width counts too, for wide-screen crops. */
fun MediaInfo.resolutionLabel(): String? = when {
    width <= 0 && height <= 0 -> null
    width >= 3800 || height >= 2000 -> "4K"
    width >= 1900 || height >= 1000 -> "FHD"
    width >= 1200 || height >= 700 -> "HD"
    else -> "SD"
}

fun MediaInfo.hdrLabel(): String? = when (hdr) {
    HdrType.DOLBY_VISION -> "DV"
    HdrType.HDR10_PLUS -> "HDR10+"
    HdrType.HDR10 -> "HDR10"
    HdrType.HLG -> "HLG"
    HdrType.SDR -> null
}

fun MediaInfo.audioLabel(): String? {
    if (atmos) return "ATMOS"
    val layout = when (audioChannels) {
        0 -> null
        1 -> "Mono"
        2 -> "2.0"
        6 -> "5.1"
        8 -> "7.1"
        else -> "${audioChannels}ch"
    }
    return listOfNotNull(audioCodec, layout).joinToString(" ").ifEmpty { null }
}

/** The quality tags shown next to a video, in display order. */
fun MediaInfo.badges(): List<String> = listOfNotNull(resolutionLabel(), hdrLabel(), audioLabel())
