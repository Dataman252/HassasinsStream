package com.wifistream.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class BadgesTest {
    @Test
    fun uhdDolbyVisionAtmos() {
        val info = MediaInfo(width = 3840, height = 1600, hdr = HdrType.DOLBY_VISION, audioCodec = "TrueHD", audioChannels = 8, atmos = true)
        assertEquals(listOf("4K", "DV", "ATMOS"), info.badges())
    }

    @Test
    fun plainFullHd() {
        val info = MediaInfo(width = 1920, height = 1080, audioCodec = "AAC", audioChannels = 2)
        assertEquals(listOf("FHD", "AAC 2.0"), info.badges())
    }

    @Test
    fun unknownShowsNothing() {
        assertEquals(emptyList(), MediaInfo().badges())
    }
}
