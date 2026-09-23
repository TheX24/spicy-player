package com.tx24.spicyplayer.network.data.providers

import com.google.gson.JsonParser
import com.tx24.spicyplayer.lyrics.spicy.parser.TtmlLyricsParser
import com.tx24.spicyplayer.lyrics.spicy.models.LineRole
import org.junit.Assert.assertEquals
import org.junit.Test

class SpicyLyricsTtmlConverterTest {
    @Test fun preservesDuetSingerBackgroundAndExplicitAlignment() {
        val body = JsonParser.parseString("""{"Content":[
            {"Agent":"lead","Lead":{"Syllables":[{"Text":"A","StartTime":1,"EndTime":2}]},"Background":[{"Syllables":[{"Text":"oh","StartTime":1.5,"EndTime":2.5}]}]},
            {"Agent":"guest","OppositeAligned":false,"Lead":{"Syllables":[{"Text":"B","StartTime":3,"EndTime":4}]}},
            {"Agent":"lead","OppositeAligned":true,"Lead":{"Syllables":[{"Text":"C","StartTime":5,"EndTime":6}]}}
        ]}""").asJsonObject
        val ttml = requireNotNull(SpicyLyricsTtmlConverter.convert(body, body.getAsJsonArray("Content")))

        val lines = TtmlLyricsParser.parse(ttml.byteInputStream()).lines

        assertEquals(4, lines.size)
        assertEquals(LineRole.BACKGROUND, lines[1].role)
        assertEquals(lines[0].groupId, lines[1].groupId)
        assertEquals("lead", lines[0].agent)
        assertEquals("guest", lines[2].agent)
        assertEquals(false, lines[2].oppositeAligned)
        assertEquals(true, lines[3].oppositeAligned)
    }

    @Test fun preservesSyllableAttachments() {
        val body = JsonParser.parseString("""{"Content":[{"Lead":{"Syllables":[
            {"Text":"Hel","StartTime":1,"EndTime":1.4,"IsPartOfWord":true},
            {"Text":"lo","StartTime":1.4,"EndTime":1.8,"IsPartOfWord":false},
            {"Text":"world","StartTime":2,"EndTime":2.4,"IsPartOfWord":false}
        ]}}]}""").asJsonObject
        val ttml = requireNotNull(SpicyLyricsTtmlConverter.convert(body, body.getAsJsonArray("Content")))

        val words = TtmlLyricsParser.parse(ttml.byteInputStream()).lines.single().words

        assertEquals(listOf("Hel", "lo", "world"), words.map { it.text.trim() })
        assertEquals(listOf(false, true, false), words.map { it.isPartOfWord })
    }

    @Test fun carriesSpicyTransliterationsPerSyllable() {
        val body = JsonParser.parseString("""{"Content":[
            {"Lead":{"Syllables":[
                {"Text":"なん","TransliteratedText":"nan","StartTime":1,"EndTime":1.2,"IsPartOfWord":true},
                {"Text":"で","TransliteratedText":"de","StartTime":1.2,"EndTime":1.4,"IsPartOfWord":false}
            ]}},
            {"Lead":{"Syllables":[{"Text":"no","StartTime":2,"EndTime":2.4}]}}
        ]}""").asJsonObject
        val ttml = requireNotNull(SpicyLyricsTtmlConverter.convert(body, body.getAsJsonArray("Content")))

        val lines = TtmlLyricsParser.parse(ttml.byteInputStream()).lines

        assertEquals(listOf("nan", "de"), lines[0].words.map { it.romanizedText })
        assertEquals(listOf<String?>(null), lines[1].words.map { it.romanizedText })
    }
}
