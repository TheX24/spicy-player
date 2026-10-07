package com.tx24.spicyplayer.network.data

import com.google.gson.Gson
import com.tx24.spicyplayer.lyrics.NextLyricsBackend
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceDefaultsTest {
    private val descriptors = NextLyricsBackend.createProviders(OkHttpClient(), Gson(), clientKey = "")
        .map(RemoteLyricsProvider::descriptor)

    @Test
    fun `a fresh install asks Spicy Lyrics and the lyrics APIs open to any app`() {
        val prefs = LyricsSourcePreferenceNormalizer.normalize(null, emptySet(), descriptors)
        val asked = descriptors.filter { !it.rankOnly && it.id !in prefs.disabledSourceIds }.map { it.id }.toSet()
        assertEquals(DEFAULT_ON, asked)
    }

    @Test
    fun `Musixmatch is gone`() {
        assertTrue(descriptors.none { it.id == "musixmatch" })
    }

    @Test
    fun `relayed Apple Music and Spotify lyrics keep a place in the order`() {
        val slots = descriptors.filter { it.rankOnly }.map { it.id }.toSet()
        assertEquals(setOf(RemoteLyricsSource.APPLE_MUSIC_ID, RemoteLyricsSource.SPOTIFY_ID), slots)
    }

    @Test
    fun `every other source says what it sends before it's switched on`() {
        for (source in descriptors.filter { !it.rankOnly && it.id !in DEFAULT_ON }) {
            // A named entry, not the generic fallback.
            assertNotNull(source.id, SourceDisclosures.forId(source.id))
            assertEquals(source.id, SourceDisclosures.forSource(source)?.id)
        }
    }

    @Test
    fun `default sources and the rank-only slots need no disclosure`() {
        for (source in descriptors.filter { it.rankOnly || it.id in DEFAULT_ON }) {
            assertNull(source.id, SourceDisclosures.forSource(source))
        }
    }

    @Test
    fun `the user's own sources need no disclosure`() {
        val custom = LyricsSourceDescriptor("custom_1", "Mine", 1_000, emptySet(), upstreamFamily = "custom")
        assertNull(SourceDisclosures.forSource(custom))
    }

    @Test
    fun `a disclosure names where the song goes and what's sent`() {
        val text = SourceDisclosures.forId("kugou")!!.description
        assertTrue(text, text.contains("Kugou's servers in China"))
        assertTrue(text, text.contains("title, artist and length"))
        assertFalse(text, text.contains("  "))
    }

    private companion object {
        /** Spicy Lyrics, and the lyrics APIs that say any app may use them. */
        val DEFAULT_ON = setOf(RemoteLyricsSource.SPICY_ID, "lrclib", "amll_ttml_db", "lrcmux", "lrc_red", "bini_lyrics")
    }
}
