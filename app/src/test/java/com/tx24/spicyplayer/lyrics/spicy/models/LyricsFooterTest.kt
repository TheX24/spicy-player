package com.tx24.spicyplayer.lyrics.spicy.models

import com.tx24.spicyplayer.latencytest.RemoteLyricsAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsFooterTest {
    private val spicy = LyricsProvenance("Spicy Lyrics", "Spicy Lyrics Community")

    @Test fun communityCreditsFollowSpicyLyricsWording() {
        val footer = LyricsFooter(songwriters = listOf("Ada", "Ben"), provenance = spicy, uploader = LyricsCredit("TX26"))

        assertEquals(
            listOf("Written by: Ada, Ben", "These lyrics have been provided by our community", "Made by @TX26"),
            footer.lines().map(FooterLine::text),
        )
    }

    @Test fun makerAndUploaderAreBothCredited() {
        val lines = LyricsFooter(provenance = spicy, maker = LyricsCredit("maker"), uploader = LyricsCredit("up")).lines()

        assertEquals(
            listOf("These lyrics have been provided by our community", "Made by @maker", "Uploaded by @up"),
            lines.map(FooterLine::text),
        )
    }

    @Test fun otherSourcesNameThemselvesThenTheirCredits() {
        val amll = LyricsFooter(provenance = LyricsProvenance("AMLL TTML DB"), maker = LyricsCredit("someone")).lines()
        val unison = LyricsFooter(provenance = LyricsProvenance("Unison"), uploader = LyricsCredit("sub")).lines()

        assertEquals(listOf("Lyrics: AMLL TTML DB", "Made by @someone"), amll.map(FooterLine::text))
        assertEquals(listOf("Lyrics: Unison", "Submitted by @sub"), unison.map(FooterLine::text))
    }

    @Test fun catalogueLyricsNameTheirSource() {
        val lines = LyricsFooter(provenance = LyricsProvenance("Spicy Lyrics", "Apple Music")).lines()

        assertEquals(listOf("Lyrics: Spicy Lyrics • Apple Music"), lines.map(FooterLine::text))
    }

    @Test fun onlyTrustedProfilesAreClickable() {
        fun url(profile: String) = LyricsFooter(maker = LyricsCredit("x", profile)).lines().single().profileUrl

        assertEquals("https://spicylyrics.org/uid/1", url("https://spicylyrics.org/uid/1"))
        assertEquals("https://github.com/someone", url("https://github.com/someone"))
        assertNull(url("http://spicylyrics.org/uid/1"))
        assertNull(url("https://evil.example/spicylyrics.org"))
    }

    @Test fun doubleEscapedSongwritersAreDecoded() {
        assertEquals("Tom & Jerry", RemoteLyricsAdapter.decodeEntities("Tom &amp; Jerry"))
    }
}
