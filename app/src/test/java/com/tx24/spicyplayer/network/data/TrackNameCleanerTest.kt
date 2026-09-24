package com.tx24.spicyplayer.network.data

import com.tx24.spicyplayer.network.data.TrackNameCleaner.Names
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackNameCleanerTest {
    @Test fun taggedTrackPassesThrough() {
        assertEquals(Names("7 rings", "Ariana Grande"), TrackNameCleaner.clean("7 rings", "Ariana Grande"))
        assertEquals(Names("Song - Remix (Official Video)", "A"), TrackNameCleaner.clean("Song - Remix (Official Video)", "A"))
    }

    @Test fun fileNameWithoutArtistIsSplit() {
        assertEquals(Names("INDUSTRY BABY", "Lil Nas X"),
            TrackNameCleaner.clean("03 - Lil Nas X - INDUSTRY BABY (Official Video).mp3", "<unknown>"))
        assertEquals(Names("Blinding Lights", "The Weeknd"), TrackNameCleaner.clean("The_Weeknd_-_Blinding_Lights.flac", ""))
        assertEquals(Names("Song (Remix)", "Artist"), TrackNameCleaner.clean("01 Artist - Song (Remix) [Lyrics].m4a", "Unknown artist"))
    }

    @Test fun fileNameWithKnownArtistKeepsArtist() {
        assertEquals(Names("Levitating", "Dua Lipa"), TrackNameCleaner.clean("05. Levitating.opus", "Dua Lipa"))
        assertEquals(Names("7 rings", "Ariana Grande"), TrackNameCleaner.clean("7 rings.mp3", "Ariana Grande"))
    }

    @Test fun titleWithoutSeparatorStaysArtistless() {
        assertEquals(Names("Some Song", ""), TrackNameCleaner.clean("Some Song.mp3", ""))
    }
}
