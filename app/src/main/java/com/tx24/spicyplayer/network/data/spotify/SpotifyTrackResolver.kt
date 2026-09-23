package com.tx24.spicyplayer.network.data.spotify

import android.util.Log

/** Cautious Spotify matching for externally played tracks. */
class SpotifyTrackResolver(private val catalogSearch: SpotifyCatalogSearch) {
    private var cached: Pair<LocalTrackMetadata, SpotifyTrackResolution.Matched>? = null

    suspend fun resolve(track: LocalTrackMetadata): SpotifyTrackResolution {
        cached?.takeIf { it.first == track }?.let { return it.second }
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val candidates = catalogSearch.search(track)
        val result = SpotifyTrackMatcher.resolve(track, candidates)
        if (result is SpotifyTrackResolution.Matched) cached = track to result
        Log.d("SpotifyMatch", "lookupMs=${android.os.SystemClock.elapsedRealtime() - startedAt} duration=${track.durationMs} candidates=${candidates.size} result=${result.javaClass.simpleName} top=" +
            candidates.map { SpotifyTrackMatcher.score(track, it) }
                .sortedByDescending { it.score }.take(4)
                .joinToString { "${it.candidate.id}:${it.score}:${it.durationDeltaMs}:${it.hasVersionConflict}" })
        return result
    }
}
