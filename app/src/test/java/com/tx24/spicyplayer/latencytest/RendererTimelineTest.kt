package com.tx24.spicyplayer.latencytest

import com.tx24.spicyplayer.lyrics.spicy.models.Line
import com.tx24.spicyplayer.lyrics.spicy.models.LineRole
import com.tx24.spicyplayer.lyrics.spicy.models.Word
import com.tx24.spicyplayer.lyrics.spicy.models.buildDisplayTimeline
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
}
