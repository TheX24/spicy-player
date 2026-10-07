package com.tx24.spicyplayer.playback

/**
 * The last few things the lyric clock did, for the debug report. Release builds drop the sync log,
 * and a desync on someone else's phone can't be read any other way.
 *
 * Plain Kotlin, no Android: the times are `elapsedRealtime`, passed in.
 */
internal object SyncTrace {
    private const val MAX_LINES = 40
    private val lines = ArrayDeque<Pair<Long, String>>()

    fun add(nowMs: Long, line: String) = synchronized(lines) {
        if (lines.size >= MAX_LINES) lines.removeFirst()
        lines.addLast(nowMs to line)
    }

    /** Oldest first, each stamped with how long before [nowMs] it happened. */
    fun lines(nowMs: Long): List<String> = synchronized(lines) {
        lines.map { (at, line) -> "-%.1fs %s".format(java.util.Locale.ROOT, (nowMs - at) / 1000.0, line) }
    }
}
