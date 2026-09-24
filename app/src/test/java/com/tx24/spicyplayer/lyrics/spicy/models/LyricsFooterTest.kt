package com.tx24.spicyplayer.lyrics.spicy.models

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsFooterTest {
    @Test fun communityCreditsFollowSpicyLyricsWording() {
        val footer = LyricsFooter(
            songwriters = listOf("Ada", "Ben"),
            provenance = LyricsProvenance("Spicy Lyrics", "Spicy Lyrics Community"),
            uploader = "TX26",
        )

        assertEquals(
            listOf("Written by: Ada, Ben", "These lyrics have been provided by our community", "Made by @TX26"),
            footer.lines().map(FooterLine::text),
        )
    }

    @Test fun makerAndUploaderAreBothCredited() {
        val lines = LyricsFooter(maker = "maker", uploader = "up").lines().map(FooterLine::text)

        assertEquals(listOf("These lyrics have been provided by our community", "Made by @maker", "Uploaded by @up"), lines)
    }

    @Test fun catalogueLyricsNameTheirSource() {
        val lines = LyricsFooter(provenance = LyricsProvenance("Spicy Lyrics", "Apple Music")).lines().map(FooterLine::text)

        assertEquals(listOf("Lyrics: Spicy Lyrics • Apple Music"), lines)
    }
}
