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

    fun adjustmentMs(driftMs: Long, mirrored: Boolean): Long = when {
        abs(driftMs) < JITTER_MS -> 0L
        mirrored && driftMs < 0L && driftMs > -MIRROR_BACKWARD_JUMP_MS -> 0L
        else -> driftMs
    }
}
