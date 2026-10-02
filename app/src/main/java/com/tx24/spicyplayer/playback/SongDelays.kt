package com.tx24.spicyplayer.playback

import android.content.Context

/**
 * Lyric delays saved per song, on top of the output's own ([AudioOutputProfiles]): for lyrics that
 * are timed a little off rather than an output that lags. Keyed like the Lyrics Manager's songs.
 */
internal class SongDelays(context: Context) {
    private val preferences = context.getSharedPreferences("song_delays", Context.MODE_PRIVATE)

    fun delayMs(songKey: String?): Int = songKey?.let { preferences.getInt(it, 0) } ?: 0

    /** Saves [delayMs] for [songKey]; 0 forgets the song, so the file only holds songs that need one. */
    fun save(songKey: String, delayMs: Int) {
        val clamped = clampDelay(delayMs)
        preferences.edit().apply { if (clamped == 0) remove(songKey) else putInt(songKey, clamped) }.apply()
    }
}

/** How far either delay goes, each way: enough for lyrics synced to a different cut of the song. */
const val MAX_DELAY_MS = 5_000

internal fun clampDelay(delayMs: Int): Int = delayMs.coerceIn(-MAX_DELAY_MS, MAX_DELAY_MS)

/** Where the lyrics are: the player's position held back by both delays, never before the start. */
internal fun lyricPositionMs(playerPositionMs: Long, outputDelayMs: Int, songDelayMs: Int): Long =
    (playerPositionMs - outputDelayMs - songDelayMs).coerceAtLeast(0L)
