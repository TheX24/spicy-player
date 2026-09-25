package com.tx24.spicyplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class ClockCorrectionTest {
    @Test fun ignoresTinySessionJitter() {
        assertEquals(0L, ClockCorrection.adjustmentMs(79L))
        assertEquals(0L, ClockCorrection.adjustmentMs(-79L))
    }

    @Test fun easesModerateDriftAndSnapsLargeJumps() {
        assertEquals(70L, ClockCorrection.adjustmentMs(200L))
        assertEquals(-70L, ClockCorrection.adjustmentMs(-200L))
        assertEquals(700L, ClockCorrection.adjustmentMs(700L))
    }
}
