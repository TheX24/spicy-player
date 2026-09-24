package com.tx24.spicyplayer.lyrics.spicy.models

private const val NORMAL_INTERLUDE_THRESHOLD_MS = 3_000L
private const val MINIMAL_INTERLUDE_THRESHOLD_MS = 5_000L

/**
 * Builds the presentation timeline from normalized vocal lines. Background vocals remain next
 * to their lead group but never participate in gap detection.
 */
fun buildDisplayTimeline(lines: List<Line>, minimalMode: Boolean): List<Line> {
    if (lines.isEmpty()) return emptyList()

    val threshold = if (minimalMode) MINIMAL_INTERLUDE_THRESHOLD_MS else NORMAL_INTERLUDE_THRESHOLD_MS
    val ordered = lines.sortedWith(compareBy<Line> { it.startMs }.thenBy { it.role.ordinal })
    val leads = ordered.filter { it.role == LineRole.LEAD }
    if (leads.isEmpty()) return ordered

    val interludes = ArrayList<Line>()
    val firstLead = leads.first()
    if (firstLead.startMs >= threshold) {
        interludes += Line(
            words = emptyList(),
            startMs = 0L,
            endMs = firstLead.startMs,
            role = LineRole.INTERLUDE,
        )
    }

    for (index in 0 until leads.lastIndex) {
        val current = leads[index]
        val next = leads[index + 1]
        if (next.startMs - current.endMs >= threshold) {
            interludes += Line(
                words = emptyList(),
                startMs = current.endMs,
                endMs = next.startMs,
                role = LineRole.INTERLUDE,
            )
        }
    }

    return (ordered + interludes).sortedWith(
        compareBy<Line> { it.startMs }.thenBy { if (it.role == LineRole.INTERLUDE) 0 else 1 }
    )
}

/** spicy-lyrics' getInterludeTimePadding(): (preHiddenDotLineMs + 50) * -1. */
private const val INTERLUDE_TIME_PADDING_MS = -550.0

/**
 * The three interlude dots' [start, end) times, ported from spicy-lyrics' Syllable/Line
 * applyers: each fills a third of the gap, shifted earlier so the last dot finishes 550ms
 * before the next line (the dot line itself hides 500ms before it).
 */
fun interludeDotTimes(startMs: Long, endMs: Long): List<Pair<Long, Long>> {
    val total = (endMs - startMs).toDouble()
    val base = total / 3.0
    val padding = INTERLUDE_TIME_PADDING_MS / 3.0
    val dot1 = maxOf(startMs.toDouble(), startMs + base + padding)
    val dot2 = maxOf(dot1, startMs + base * 2 + padding * 2)
    val dot3 = maxOf(dot2, startMs + total + INTERLUDE_TIME_PADDING_MS)
    val ends = listOf(dot1, dot2, dot3).map { Math.round(it) }
    return listOf(startMs to ends[0], ends[0] to ends[1], ends[1] to ends[2])
}
