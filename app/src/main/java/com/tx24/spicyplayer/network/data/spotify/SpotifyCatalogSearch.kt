package com.tx24.spicyplayer.network.data.spotify

/**
 * Transport boundary for resolving local metadata to Spotify candidates.
 *
 * The first implementation may use Spotify's anonymous web search behavior,
 * but callers and matching logic must not depend on that unofficial transport.
 */
interface SpotifyCatalogSearch {
    suspend fun search(track: LocalTrackMetadata): List<SpotifyTrackCandidate>
}
