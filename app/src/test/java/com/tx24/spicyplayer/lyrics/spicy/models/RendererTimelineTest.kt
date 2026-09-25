package com.tx24.spicyplayer.lyrics.spicy.models

import com.tx24.spicyplayer.lyrics.spicy.models.Line
import com.tx24.spicyplayer.lyrics.spicy.models.LineRole
import com.tx24.spicyplayer.lyrics.spicy.models.Word
import com.tx24.spicyplayer.lyrics.spicy.models.buildDisplayTimeline
import com.tx24.spicyplayer.lyrics.spicy.models.interludeDotTimes
import org.junit.Assert.assertEquals
import org.junit.Test

class RendererTimelineTest {
    @Test fun insertsInterludeDotsForVocalGaps() {
        val lines = listOf(
            Line(listOf(Word("one", 0L, 1_000L)), 0L, 1_000L),
            Line(listOf(Word("two", 5_000L, 6_000L)), 5_000L, 6_000L),
        )

        val timeline = buildDisplayTimeline(lines, minimalMode = false)
        assertEquals(3, timeline.size)
        assertEquals(LineRole.INTERLUDE, timeline[1].role)
        assertEquals(1_000L, timeline[1].startMs)
        assertEquals(5_000L, timeline[1].endMs)
    }

    @Test fun interludeDotsFollowSpicyLyricsTiming() {
        // 9s gap from 10s: thirds of 3000ms, each shifted by -550/3, last ending 550ms early.
        assertEquals(
            listOf(10_000L to 12_817L, 12_817L to 15_633L, 15_633L to 18_450L),
            interludeDotTimes(10_000L, 19_000L),
        )
    }
}
