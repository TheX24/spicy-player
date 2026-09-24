package com.tx24.spicyplayer.lyrics.spicy.canvas

import com.tx24.spicyplayer.lyrics.spicy.animation.LyricsAnimator
import com.tx24.spicyplayer.lyrics.spicy.models.Line
import com.tx24.spicyplayer.lyrics.spicy.models.LineRole
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

internal enum class ScrollMotion { NONE, SNAP, SMOOTH }

internal data class ScrollDecision(val targetIndex: Int?, val motion: ScrollMotion)

internal class ScrollPolicyController {
    private var initialized = false
    private var lastTimeMs = 0L
    private var lastTargetIndex: Int? = null

    fun reset() {
        initialized = false
        lastTimeMs = 0L
        lastTargetIndex = null
    }

    fun decide(lines: List<Line>, timeMs: Long, explicitSeek: Boolean = false): ScrollDecision {
        val target = selectTargetIndex(lines, timeMs)
        val replayToZero = initialized && timeMs <= 100L && lastTimeMs > 1_000L
        val largeSeek = initialized && abs(timeMs - lastTimeMs) > 1_000L
        val motion = when {
            target == null -> ScrollMotion.NONE
            !initialized || explicitSeek || replayToZero || largeSeek -> ScrollMotion.SNAP
            target != lastTargetIndex -> ScrollMotion.SMOOTH
            else -> ScrollMotion.NONE
        }
        initialized = true
        lastTimeMs = timeMs
        lastTargetIndex = target
        return ScrollDecision(target, motion)
    }

    companion object {
        fun selectTargetIndex(lines: List<Line>, timeMs: Long): Int? {
            if (lines.isEmpty()) return null
            val active = lines.indices.filter { timeMs in lines[it].startMs..lines[it].endMs }
            val activeInterlude = active.firstOrNull { lines[it].role == LineRole.INTERLUDE }
            if (activeInterlude != null) {
                // Once the dots start closing, aim at the line after them, so the scroll and the
                // closing gap move together in one glide instead of chasing the shrinking dots.
                val interlude = lines[activeInterlude]
                if (timeMs <= interlude.endMs - LyricsAnimator.PRE_HIDDEN_DOT_LINE_MS) return activeInterlude
                val next = (activeInterlude + 1 until lines.size).firstOrNull { lines[it].role == LineRole.LEAD }
                if (next != null) return next
            }

            val leadIndices = active.mapNotNull { index ->
                val line = lines[index]
                when (line.role) {
                    LineRole.LEAD -> index
                    LineRole.BACKGROUND -> lines.indexOfFirst {
                        it.role == LineRole.LEAD && it.groupId == line.groupId
                    }.takeIf { it >= 0 }
                    LineRole.INTERLUDE -> null
                }
            }.distinct().sorted()

            return when {
                leadIndices.isEmpty() -> lines.indexOfLast {
                    it.role == LineRole.LEAD && it.startMs <= timeMs
                }.takeIf { it >= 0 } ?: lines.indexOfFirst { it.role != LineRole.BACKGROUND }.takeIf { it >= 0 }
                leadIndices.size == 1 -> leadIndices.first()
                else -> {
                    val first = leadIndices.first()
                    val last = leadIndices.last()
                    val lookahead = lines.asSequence().drop(last + 1)
                        .filter { it.role == LineRole.LEAD }.take(2).lastOrNull()
                    if (lookahead != null && lines[last].endMs <= lookahead.startMs) last
                    else if (last - first <= 1) first else last
                }
            }
        }

        fun flingDecayMultiplier(deltaTimeSeconds: Float): Float =
            exp(ln(0.95f) * deltaTimeSeconds.coerceAtLeast(0f) * 60f)

        fun anchorY(viewportHeightPx: Float, focusFraction: Float): Float =
            viewportHeightPx * focusFraction.coerceIn(0f, 1f)
    }
}
