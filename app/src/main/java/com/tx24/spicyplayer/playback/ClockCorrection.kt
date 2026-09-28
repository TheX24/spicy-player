package com.tx24.spicyplayer.playback

import kotlin.math.abs

/**
 * How far to move the lyric clock when the player sends a new position report mid-play
 * (state changes, seeks and skips always take the report as is).
 *
 * A local player stamps its report with the moment it measured it, so the report is accurate:
 * anything past jitter is applied at once rather than eased in over several reports.
 *
 * A [mirrored] session relays another device (KDE Connect for a PC player). It stamps the report
 * when it arrives, so every report is late by the relay's lag, and never early. A report ahead of
 * the clock means the lyrics are behind and is applied; one behind it is only believed when it is
 * far enough back to be a real seek on the other device.
 */
internal object ClockCorrection {
    /** Smaller differences are report jitter, not worth a visible jump. */
    const val JITTER_MS = 150L
    /** A relayed report at least this far behind means the song really went back. */
    const val MIRROR_BACKWARD_JUMP_MS = 1_500L
    /** A pause report at least this far behind the clock means the song really went back. */
    const val STALE_PAUSE_MAX_MS = 5_000L

    /**
     * [earlierDriftMs]: what a report at least half a second before said, when it was not applied.
     * A relayed report behind the clock is taken for lag, unless an earlier one said the same:
     * then the clock itself is ahead. KDE Connect's first report after a resume or a new song runs
     * up to ~0.6 s ahead of the steady ones that follow, and would otherwise hold the clock there.
     */
    fun adjustmentMs(driftMs: Long, mirrored: Boolean, earlierDriftMs: Long? = null): Long = when {
        abs(driftMs) < JITTER_MS -> 0L
        mirrored && driftMs < 0L && driftMs > -MIRROR_BACKWARD_JUMP_MS &&
            (earlierDriftMs == null || earlierDriftMs >= 0L || abs(earlierDriftMs - driftMs) >= JITTER_MS) -> 0L
        else -> driftMs
    }

    /**
     * Players report a pause badly. The position they give is an old sample: YouTube Music's is up
     * to ~1.3 s behind where the audio stopped, KDE Connect's up to ~4 s. Reports while playing are
     * sound. So a pause report behind a clock that was in step is not believed: the clock keeps its
     * place and the gap becomes the bias returned here.
     *
     * From then on each report is read either as is or [biasMs] later, whichever agrees with the
     * clock: YouTube Music keeps counting from its stale sample after resuming, while the audio
     * carries on from where it really was; KDE Connect is right again on resume; and either may
     * correct itself later. A report that fits neither is a seek, and drops the bias.
     *
     * [pauseReport]: the first report of a pause after playing. [rawMs]: the report's position
     * brought to now. [predictedMs]: where the clock is.
     */
    fun reportBiasMs(rawMs: Long, predictedMs: Long, biasMs: Long, pauseReport: Boolean): Long {
        if (pauseReport) {
            val behind = predictedMs - rawMs
            // Position 0 is the player going back to the start, not an old sample.
            return if (rawMs > 0L && behind >= JITTER_MS && behind < STALE_PAUSE_MAX_MS) behind else 0L
        }
        if (biasMs == 0L) return 0L
        val asIs = abs(rawMs - predictedMs)
        val late = abs(rawMs + biasMs - predictedMs)
        return if (late < asIs && late < STALE_PAUSE_MAX_MS) biasMs else 0L
    }
}
