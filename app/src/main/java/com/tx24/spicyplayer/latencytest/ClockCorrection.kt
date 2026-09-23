package com.tx24.spicyplayer.latencytest

import kotlin.math.abs
import kotlin.math.roundToLong

/** Ignore harmless jitter, ease ordinary drift, snap after a real position jump. */
internal object ClockCorrection {
    fun adjustmentMs(driftMs: Long): Long = when {
        abs(driftMs) < 80L -> 0L
        abs(driftMs) < 600L -> (driftMs * 0.35).roundToLong()
        else -> driftMs
    }
}
