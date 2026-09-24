package com.tx24.spicyplayer.network.data

import com.tx24.spicyplayer.network.model.LrclibSearchEntry
import com.tx24.spicyplayer.network.model.SongLyricsNetwork
import com.tx24.spicyplayer.network.service.LyricsService
import org.junit.Assert.assertEquals
import org.junit.Test

class LrclibFallbackSelectionTest {
    private val source = LyricsSource(object : LyricsService {
        override suspend fun getSongLyrics(artistName: String, trackName: String, albumName: String?, durationSeconds: Int?): SongLyricsNetwork = error("unused")
        override suspend fun searchSongLyrics(artistName: String, trackName: String): List<LrclibSearchEntry> = error("unused")
    })

    @Test fun prefersMatchingAlbumAndRejectsWrongRecording() {
        val request = LyricsLookupRequest("Creepy Nuts", "Bling-Bang-Bang-Born", "LEGION", 169)
        val otherAlbum = entry("Bling-Bang-Bang-Born", "Creepy Nuts", "Single", 169.0)
        val matchingAlbum = entry("Bling‐Bang‐Bang‐Born", "Creepy Nuts", "LEGION", 170.0)
        val wrongVersion = entry("Bling-Bang-Bang-Born (Live)", "Creepy Nuts", "LEGION", 169.0)

        assertEquals(matchingAlbum, source.chooseSearchResult(request, listOf(otherAlbum, wrongVersion, matchingAlbum)))
    }

    private fun entry(title: String, artist: String, album: String, duration: Double) =
        LrclibSearchEntry(title, artist, album, duration, "lyrics", null)
}
