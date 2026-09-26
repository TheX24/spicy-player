package com.tx24.spicyplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class ClockCorrectionTest {
    @Test fun ignoresTinySessionJitter() {
        for (mirrored in listOf(false, true)) {
            assertEquals(0L, ClockCorrection.adjustmentMs(149L, mirrored))
            assertEquals(0L, ClockCorrection.adjustmentMs(-149L, mirrored))
        }
    }

    @Test fun localPlayerDriftIsAppliedAtOnce() {
        assertEquals(200L, ClockCorrection.adjustmentMs(200L, mirrored = false))
        assertEquals(-200L, ClockCorrection.adjustmentMs(-200L, mirrored = false))
    }

    @Test fun mirroredReportsOnlyPullBackOnARealSeek() {
        assertEquals(400L, ClockCorrection.adjustmentMs(400L, mirrored = true))
        assertEquals(0L, ClockCorrection.adjustmentMs(-400L, mirrored = true))
        assertEquals(0L, ClockCorrection.adjustmentMs(-1_499L, mirrored = true))
        assertEquals(-1_500L, ClockCorrection.adjustmentMs(-1_500L, mirrored = true))
    }
}
