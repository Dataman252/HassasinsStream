package com.wifistream.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class NameParserTest {
    @Test
    fun movieWithYearAndTags() {
        assertEquals(ParsedName("Dune Part Two", 2024), parseMediaName("Movies/Dune.Part.Two.2024.2160p.UHD.BluRay.DV.x265.mkv"))
    }

    @Test
    fun movieWithYearInBrackets() {
        assertEquals(ParsedName("The Godfather", 1972), parseMediaName("The Godfather (1972) [1080p].mp4"))
    }

    @Test
    fun yearAtStartIsPartOfTheTitle() {
        assertEquals(ParsedName("2001 A Space Odyssey", 1968), parseMediaName("2001.A.Space.Odyssey.1968.1080p.mkv"))
        assertEquals(ParsedName("Blade Runner 2049", 2017), parseMediaName("Blade.Runner.2049.2017.2160p.mkv"))
    }

    @Test
    fun movieWithoutYear() {
        assertEquals(ParsedName("Inception"), parseMediaName("Inception.1080p.BluRay.x264.mkv"))
        assertEquals(ParsedName("Home video"), parseMediaName("Home video.mp4"))
    }

    @Test
    fun episodeStyles() {
        assertEquals(ParsedName("The Bear", season = 2, episode = 5), parseMediaName("The.Bear.S02E05.1080p.WEB.h264.mkv"))
        assertEquals(ParsedName("Dark", season = 1, episode = 3), parseMediaName("Dark (2017) - 1x03 - Past and Present.mkv"))
        assertEquals(ParsedName("Severance", season = 1, episode = 2), parseMediaName("Severance s01 e02.mkv"))
    }

    @Test
    fun episodeTitleFromFolder() {
        assertEquals(
            ParsedName("Breaking Bad", season = 1, episode = 2),
            parseMediaName("Shows/Breaking Bad (2008)/Season 1/S01E02.mkv"),
        )
    }
}
