package com.tx24.spicyplayer.network.data.spotify

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

data class LocalTrackMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

data class SpotifyTrackCandidate(
    val id: String,
    val title: String,
    val artists: List<String>,
    val album: String,
    val durationMs: Long,
)

data class ScoredSpotifyTrack(
    val candidate: SpotifyTrackCandidate,
    val score: Int,
    val titleSimilarity: Double,
    val artistSimilarity: Double,
    val durationDeltaMs: Long,
    val hasVersionConflict: Boolean,
)

sealed interface SpotifyTrackResolution {
    /**
     * [alternates]: other IDs for the same recording (single, album, re-release), best first.
     * Lyrics are uploaded per ID, so a lookup that misses on [track] should try these.
     */
    data class Matched(
        val track: ScoredSpotifyTrack,
        val alternates: List<ScoredSpotifyTrack> = emptyList(),
    ) : SpotifyTrackResolution
    data class Ambiguous(val candidates: List<ScoredSpotifyTrack>) : SpotifyTrackResolution
    data object NotFound : SpotifyTrackResolution
}

/**
 * Ranks Spotify search results against local audio metadata.
 *
 * The 55/30/15 title/artist/duration weighting comes from SpotMatch, while the
 * version-conflict and winner-margin checks are intentionally stricter: showing
 * no lyrics is better than attaching lyrics for a live, remix, or instrumental
 * recording to the studio track.
 */
object SpotifyTrackMatcher {
    private const val MINIMUM_SCORE = 82
    private const val MINIMUM_TITLE_SIMILARITY = 0.78
    private const val MINIMUM_ARTIST_SIMILARITY = 0.55
    private const val MAXIMUM_DURATION_DELTA_MS = 8_000L
    private const val MINIMUM_WINNER_MARGIN = 4
    private const val DURATION_SCORE_WINDOW_MS = 15_000.0
    private const val SAME_RECORDING_DURATION_MS = 2_000L
    private const val MAXIMUM_ALTERNATES = 3

    private val versionTerms = setOf(
        "acoustic",
        "demo",
        "extended",
        "instrumental",
        "karaoke",
        "live",
        "mix",
        "remaster",
        "remastered",
        "remix",
        "slowed",
        "sped",
    )

    fun resolve(
        source: LocalTrackMetadata,
        candidates: Collection<SpotifyTrackCandidate>,
    ): SpotifyTrackResolution {
        val ranked = candidates
            .asSequence()
            .distinctBy(SpotifyTrackCandidate::id)
            .map { score(source, it) }
            .filter(::isPlausible)
            .sortedWith(
                compareByDescending<ScoredSpotifyTrack> { it.score }
                    .thenBy { it.durationDeltaMs }
                    .thenBy { it.candidate.id }
            )
            .toList()

        var winner = ranked.firstOrNull() ?: return SpotifyTrackResolution.NotFound
        val runnerUp = ranked.getOrNull(1)
        if (runnerUp != null && winner.score - runnerUp.score < MINIMUM_WINNER_MARGIN) {
            // The same recording is often listed as a single, an album track, and a
            // compilation. A unique exact album match picks which to ask first.
            val tied = ranked.takeWhile { winner.score - it.score < MINIMUM_WINNER_MARGIN }
            val sourceAlbum = normalize(source.album)
            val albumMatch = tied.filter { sourceAlbum.isNotBlank() && normalize(it.candidate.album) == sourceAlbum }
                .singleOrNull()
            if (albumMatch != null) winner = albumMatch
            // A tie between different songs (same title, other artist) stays unresolved.
            else if (!tied.all { sameRecording(winner.candidate, it.candidate) }) {
                return SpotifyTrackResolution.Ambiguous(ranked.take(5))
            }
        }

        // Like mild-lyrics' NetEase alternates: only a copy that ties on title, byline and
        // length is another pressing of this song; anything less is a different song.
        val alternates = ranked
            .filter { it !== winner && sameRecording(winner.candidate, it.candidate) }
            .take(MAXIMUM_ALTERNATES)
        return SpotifyTrackResolution.Matched(winner, alternates)
    }

    private fun sameRecording(a: SpotifyTrackCandidate, b: SpotifyTrackCandidate): Boolean =
        normalize(a.title) == normalize(b.title) &&
            a.artists.map(::normalize).toSet() == b.artists.map(::normalize).toSet() &&
            abs(a.durationMs - b.durationMs) <= SAME_RECORDING_DURATION_MS

    fun score(source: LocalTrackMetadata, candidate: SpotifyTrackCandidate): ScoredSpotifyTrack {
        val titleSimilarity = similarity(source.title, candidate.title)
        val artistSimilarity = artistSimilarity(source.artist, candidate.artists)
        val durationDelta = abs(source.durationMs - candidate.durationMs)
        val durationScore = max(0.0, 1.0 - durationDelta / DURATION_SCORE_WINDOW_MS)
        val versionConflict = versionTerms(source.title) != versionTerms(candidate.title)
        val weightedScore = 100 * (
            titleSimilarity * 0.55 +
                artistSimilarity * 0.30 +
                durationScore * 0.15
            )
        val score = (weightedScore - if (versionConflict) 18 else 0).roundToInt()

        return ScoredSpotifyTrack(
            candidate = candidate,
            score = score.coerceIn(0, 100),
            titleSimilarity = titleSimilarity,
            artistSimilarity = artistSimilarity,
            durationDeltaMs = durationDelta,
            hasVersionConflict = versionConflict,
        )
    }

    internal fun normalize(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace("&", " and ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun similarity(left: String, right: String): Double {
        val normalizedLeft = normalize(left)
        val normalizedRight = normalize(right)
        if (normalizedLeft == normalizedRight) return 1.0
        if (normalizedLeft.isEmpty() || normalizedRight.isEmpty()) return 0.0

        // Sørensen-Dice over character bigrams is compact, deterministic, and
        // cheap enough to rank a normal Spotify search result page on-device.
        val leftBigrams = normalizedLeft.bigrams()
        val rightBigrams = normalizedRight.bigrams().toMutableList()
        var intersection = 0
        for (bigram in leftBigrams) {
            val index = rightBigrams.indexOf(bigram)
            if (index >= 0) {
                intersection += 1
                rightBigrams.removeAt(index)
            }
        }
        return 2.0 * intersection / (leftBigrams.size + normalizedRight.bigrams().size)
    }

    private fun artistSimilarity(localArtist: String, spotifyArtists: List<String>): Double {
        val localParts = splitArtists(localArtist)
        val candidateParts = spotifyArtists.flatMap(::splitArtists)
        if (localParts.isEmpty() || candidateParts.isEmpty()) return 0.0

        return localParts.maxOf { local ->
            candidateParts.maxOf { candidate -> similarity(local, candidate) }
        }
    }

    private fun splitArtists(value: String): List<String> = value
        .split(Regex("(?i)\\s+(?:feat(?:uring)?|ft|with|x)\\.?\\s+|[,;/]") )
        .map(::normalize)
        .filter(String::isNotEmpty)

    private fun versionTerms(value: String): Set<String> {
        val tokens = normalize(value).split(' ').toSet()
        return tokens.intersect(versionTerms)
    }

    private fun isPlausible(track: ScoredSpotifyTrack): Boolean =
        track.score >= MINIMUM_SCORE &&
            track.titleSimilarity >= MINIMUM_TITLE_SIMILARITY &&
            track.artistSimilarity >= MINIMUM_ARTIST_SIMILARITY &&
            track.durationDeltaMs <= MAXIMUM_DURATION_DELTA_MS &&
            !track.hasVersionConflict

    private fun String.bigrams(): List<String> = when (length) {
        0 -> emptyList()
        1 -> listOf(this)
        else -> windowed(size = 2, step = 1)
    }
}
