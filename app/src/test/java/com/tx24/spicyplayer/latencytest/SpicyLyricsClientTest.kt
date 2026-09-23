package com.tx24.spicyplayer.latencytest

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import com.tx24.spicyplayer.lyrics.spicy.models.LineRole
import org.junit.Test

class SpicyLyricsClientTest {
    @Test fun parsesLeadWordTiming() {
        val root = JsonParser.parseString(
            """{
                "Status": 200,
                "Body": {
                    "Content": [{
                        "Type": "Vocal",
                        "Lead": {
                            "StartTime": 1.0,
                            "EndTime": 2.5,
                            "Syllables": [
                                {"Text":"Hel","StartTime":1.0,"EndTime":1.4,"IsPartOfWord":true},
                                {"Text":"lo","StartTime":1.4,"EndTime":1.8,"IsPartOfWord":false}
                            ]
                        }
                    }]
                }
            }""".trimIndent(),
        ).asJsonObject

        val line = SpicyLyricsClient(apiKey = "test").parse(root).single()
        assertEquals(1_000L, line.startMs)
        assertEquals(2_500L, line.endMs)
        assertEquals("Hel", line.words[0].text)
        assertEquals(1_800L, line.words[1].endMs)
        assertEquals(true, line.words[1].attached)
    }

    @Test fun keepsBackgroundAndDuetGroups() {
        val root = JsonParser.parseString(
            """{"Status":200,"Body":{"Content":[
                {"Type":"Vocal","Agent":"lead","Lead":{"Syllables":[{"Text":"A","StartTime":1,"EndTime":2}]},"Background":[{"Syllables":[{"Text":"(oh)","StartTime":1.5,"EndTime":2.5}]}]},
                {"Type":"Vocal","Agent":"guest","OppositeAligned":true,"Lead":{"Syllables":[{"Text":"B","StartTime":3,"EndTime":4}]}}
            ]}}""",
        ).asJsonObject

        val lines = SpicyLyricsClient(apiKey = "test").parse(root)
        assertEquals(3, lines.size)
        assertEquals(LineRole.BACKGROUND, lines[1].role)
        assertEquals(lines[0].groupId, lines[1].groupId)
        assertEquals("guest", lines[2].agent)
        assertEquals(true, lines[2].oppositeAligned)
    }
}
