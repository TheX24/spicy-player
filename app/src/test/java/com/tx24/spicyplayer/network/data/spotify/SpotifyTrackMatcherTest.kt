package com.tx24.spicyplayer.network.data.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyTrackMatcherTest {
    private val source = LocalTrackMetadata(
        title = "Beyoncé & Jay-Z - Déjà Vu",
        artist = "Beyoncé feat. Jay-Z",
        album = "B'Day",
        durationMs = 239_000,
    )

    @Test
    fun `normalizes unicode punctuation and featuring artists`() {
        assertEquals("beyonce and jay z deja vu", SpotifyTrackMatcher.normalize(source.title))
        val result = SpotifyTrackMatcher.resolve(
            source.copy(title = "Déjà Vu"),
            listOf(candidate(id = "correct", title = "Deja Vu")),
        )

        assertTrue(result is SpotifyTrackResolution.Matched)
        assertEquals("correct", (result as SpotifyTrackResolution.Matched).track.candidate.id)
    }

    @Test
    fun `rejects a live version for a studio source`() {
        val result = SpotifyTrackMatcher.resolve(
            source.copy(title = "Deja Vu"),
            listOf(candidate(id = "live", title = "Deja Vu - Live", durationMs = 240_000)),
        )

        assertEquals(SpotifyTrackResolution.NotFound, result)
    }

    @Test
    fun `rejects a candidate with materially different duration`() {
        val result = SpotifyTrackMatcher.resolve(
            source.copy(title = "Deja Vu"),
            listOf(candidate(id = "short", title = "Deja Vu", durationMs = 210_000)),
        )

        assertEquals(SpotifyTrackResolution.NotFound, result)
    }

    @Test
    fun `tied copies of the same recording become alternates`() {
        val result = SpotifyTrackMatcher.resolve(
            source.copy(title = "Deja Vu", album = ""),
            listOf(
                candidate(id = "a", title = "Deja Vu", durationMs = 239_000),
                candidate(id = "b", title = "Deja Vu", durationMs = 239_200),
            ),
        ) as SpotifyTrackResolution.Matched

        assertEquals("a", result.track.candidate.id)
        assertEquals(listOf("b"), result.alternates.map { it.candidate.id })
    }

    @Test
    fun `tie between different artists stays ambiguous`() {
        val result = SpotifyTrackMatcher.resolve(
            source.copy(title = "Deja Vu", artist = "Beyoncé", album = ""),
            listOf(
                candidate(id = "a", title = "Deja Vu", durationMs = 239_000),
                candidate(id = "b", title = "Deja Vu", durationMs = 239_200).copy(artists = listOf("Beyoncé", "Someone Else")),
            ),
        )

        assertTrue(result is SpotifyTrackResolution.Ambiguous)
    }

    @Test
    fun `deduplicates spotify ids before deciding ambiguity`() {
        val duplicate = candidate(id = "same", title = "Deja Vu")
        val result = SpotifyTrackMatcher.resolve(
            source.copy(title = "Deja Vu"),
            listOf(duplicate, duplicate),
        )

        assertTrue(result is SpotifyTrackResolution.Matched)
    }

    @Test
    fun `uses unique exact album match to resolve duplicate releases`() {
        val result = SpotifyTrackMatcher.resolve(
            source.copy(title = "Deja Vu"),
            listOf(
                candidate(id = "single", title = "Deja Vu").copy(album = "Deja Vu"),
                candidate(id = "album", title = "Deja Vu", durationMs = 239_200),
                candidate(id = "compilation", title = "Deja Vu", durationMs = 238_900).copy(album = "Hits"),
            ),
        )

        assertEquals("album", (result as SpotifyTrackResolution.Matched).track.candidate.id)
    }

    private fun candidate(
        id: String,
        title: String,
        durationMs: Long = 239_000,
    ) = SpotifyTrackCandidate(
        id = id,
        title = title,
        artists = listOf("Beyoncé", "Jay-Z"),
        album = "B'Day",
        durationMs = durationMs,
    )
}
