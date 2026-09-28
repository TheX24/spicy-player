package com.tx24.spicyplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LyricClockTest {
    private val playing = 3
    private val paused = 2

    /** A clock playing from [positionMs] at t = 0, in step with its player. */
    private fun clockAt(positionMs: Long) = LyricClock(0L).apply {
        onReport(LyricClock.Report(playing, true, positionMs, 0L, 1f), 0L, snap = true)
    }

    /** A report from a relayed session (KDE Connect). */
    private fun LyricClock.report(atMs: Long, positionMs: Long, state: Int = playing) =
        onReport(LyricClock.Report(state, state == playing, positionMs, atMs, 1f, relayed = true), atMs)

    /** A report from a player on this device. */
    private fun LyricClock.local(atMs: Long, positionMs: Long, state: Int = playing) =
        onReport(LyricClock.Report(state, state == playing, positionMs, atMs, 1f), atMs)

    private fun assertNear(expected: Long, actual: Long, slack: Long = 5L) =
        assertTrue("expected ~$expected, was $actual", abs(expected - actual) <= slack)

    @Test fun jitterIsIgnored() {
        val clock = clockAt(10_000L)
        assertEquals(0L, clock.report(1_000L, 11_020L).appliedMs)
        assertEquals(0L, clock.report(2_000L, 11_980L).appliedMs)
        assertNear(12_000L, clock.positionAt(2_000L))
    }

    @Test fun aSmallSteadyOffsetIsCorrected() {
        // 60 ms is well within what players report precisely: two reports saying so move the clock.
        val clock = clockAt(10_000L)
        assertEquals(0L, clock.report(1_000L, 11_060L).appliedMs)
        assertEquals(60L, clock.report(2_000L, 12_060L).appliedMs)
    }

    @Test fun reportsThatDisagreeWithEachOtherDontMoveIt() {
        // A relayed player's stray reports scatter; none agrees with another.
        val clock = clockAt(10_000L)
        assertEquals(0L, clock.report(500L, 10_585L).appliedMs)
        assertEquals(0L, clock.report(1_000L, 10_910L).appliedMs)
        assertEquals(0L, clock.report(1_500L, 11_680L).appliedMs)
        assertNear(11_500L, clock.positionAt(1_500L))
    }

    @Test fun aLoneStrayReportIsOutvoted() {
        // KDE Connect's first report after a seek, ~450 ms ahead of the steady ones around it.
        val clock = clockAt(62_000L)
        clock.report(200L, 62_200L)
        assertEquals(0L, clock.report(400L, 62_850L).appliedMs)
        assertEquals(0L, clock.report(700L, 62_700L).appliedMs)
        assertEquals(0L, clock.report(1_000L, 63_000L).appliedMs)
        assertNear(63_000L, clock.positionAt(1_000L))
    }

    @Test fun reportsThatAgreeMoveTheClock() {
        // The clock ran 444 ms ahead; the reports keep saying so, a few hundred ms apart.
        val clock = clockAt(62_684L)
        assertEquals(0L, clock.report(220L, 62_460L).appliedMs)
        // A burst of the same report doesn't count twice.
        assertEquals(0L, clock.report(223L, 62_463L).appliedMs)
        val moved = clock.report(600L, 62_840L)
        assertNear(-444L, moved.appliedMs, slack = 10L)
        assertNear(62_840L, clock.positionAt(600L), slack = 10L)
    }

    @Test fun aJumpIsTakenAtOnce() {
        val clock = clockAt(30_000L)
        val seek = clock.report(1_000L, 90_000L)
        assertEquals(59_000L, seek.appliedMs)
        // A new song back at the start.
        assertEquals(-90_950L, clock.report(2_000L, 50L).appliedMs)
    }

    @Test fun aSongsStartIsTakenAsItComes() {
        // A relayed player going back to 0 ~0.65 s into a song.
        val clock = clockAt(0L)
        clock.report(130L, 130L)
        assertEquals(-779L, clock.report(781L, 2L).appliedMs)
        assertNear(2L, clock.positionAt(781L))
    }

    @Test fun aResumeCarriesOnFromWhereTheClockStopped() {
        // KDE Connect: paused at 64 271, its first report on resume 663 ms ahead of where it stopped.
        val clock = clockAt(64_000L)
        clock.report(271L, 64_271L, paused)
        assertEquals(0L, clock.report(740L, 64_934L).appliedMs)
        assertNear(64_271L, clock.positionAt(740L))
        // The steady reports after it are about 150 ms ahead of the clock, and agree: they win.
        clock.report(907L, 64_588L)
        val moved = clock.report(1_300L, 64_985L)
        assertTrue(moved.appliedMs in 100L..200L)
    }

    @Test fun youTubeMusicCountingOnFromAStalePause() {
        // Paused at 12 892 while the audio had reached 13 649, then resumed counting from 12 892.
        val clock = clockAt(13_000L)
        val pause = clock.local(649L, 12_892L, paused)
        assertEquals(757L, pause.biasMs)
        assertNear(13_649L, clock.positionAt(649L))
        // Every report after the resume is 757 ms short; read with the bias they agree with the clock.
        clock.local(5_000L, 12_892L)
        clock.local(5_500L, 13_392L)
        clock.local(6_000L, 13_892L)
        assertNear(13_649L + 1_000L, clock.positionAt(6_000L))
    }

    @Test fun ourSeekClearsTheVotes() {
        val clock = clockAt(10_000L)
        clock.report(100L, 10_400L)  // a stray vote
        clock.seekTo(40_000L, 200L)
        // The player confirms near the target; nothing from before the seek counts.
        assertEquals(0L, clock.report(300L, 40_100L).appliedMs)
        assertNear(40_100L, clock.positionAt(300L))
    }

    @Test fun aLocalPlayerIsTakenAtOnce() {
        // Spotify paused at 87 788, then resumed reporting 88 699: its real position.
        val clock = clockAt(87_000L)
        clock.local(788L, 87_788L, paused)
        assertEquals(911L, clock.local(35_000L, 88_699L).appliedMs)
        // YouTube Music 116 ms behind, in one burst, then quiet for over a second.
        assertEquals(-116L, clock.local(36_000L, 89_583L).appliedMs)
        // Under a frame is left alone.
        assertEquals(0L, clock.local(37_000L, 90_593L).appliedMs)
    }

    @Test fun aPlayerThatResumesAtItsTruePositionDropsTheBias() {
        // Spotify "paused" 1 011 ms behind the clock, then resumed at 35 441: 428 ms past its
        // pause report, so not counting on from it. Its resume is the truth.
        val clock = clockAt(35_000L)
        assertEquals(1_011L, clock.local(1_024L, 35_013L, paused).biasMs)
        val resume = clock.local(1_420L, 35_441L)
        assertEquals(0L, resume.biasMs)
        assertNear(35_441L, clock.positionAt(1_420L))
    }

    @Test fun snapTakesTheReportOutright() {
        val clock = clockAt(10_000L)
        assertEquals(400L, clock.onReport(LyricClock.Report(playing, true, 10_500L, 100L, 1f), 100L, snap = true).appliedMs)
    }
}
