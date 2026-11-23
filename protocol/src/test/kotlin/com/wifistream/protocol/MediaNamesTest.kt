package com.wifistream.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaNamesTest {
    @Test
    fun kinds() {
        assertEquals(EntryKind.VIDEO, kindForFileName("Movie.2024.MKV"))
        assertEquals(EntryKind.AUDIO, kindForFileName("01 - Track.flac"))
        assertEquals(EntryKind.SUBTITLE, kindForFileName("Movie.en.srt"))
        assertEquals(EntryKind.OTHER, kindForFileName("README"))
    }

    @Test
    fun fullReleaseName() {
        val i = guessMediaInfo("Dune.Part.Two.2024.2160p.UHD.BluRay.DV.HDR10+.TrueHD.7.1.Atmos.x265.mkv")
        assertEquals(2160, i.height)
        assertEquals(HdrType.DOLBY_VISION, i.hdr)
        assertEquals("HEVC", i.videoCodec)
        assertEquals("TrueHD", i.audioCodec)
        assertEquals(8, i.audioChannels)
        assertTrue(i.atmos)
        assertTrue(i.estimated)
    }

    @Test
    fun hdr10PlusAndWebNames() {
        val i = guessMediaInfo("Show.S01E02.1080p.WEB-DL.HDR10Plus.DDP5.1.H.264.mkv")
        assertEquals(1080, i.height)
        assertEquals(HdrType.HDR10_PLUS, i.hdr)
        assertEquals("H.264", i.videoCodec)
        assertEquals("E-AC3", i.audioCodec)
        assertEquals(6, i.audioChannels)
    }

    @Test
    fun plainNamesStayEmpty() {
        val i = guessMediaInfo("Advice from David.mp4")
        assertEquals(0, i.height)
        assertEquals(HdrType.SDR, i.hdr)
        assertEquals(null, i.audioCodec)
        assertFalse(i.atmos)
    }
}
