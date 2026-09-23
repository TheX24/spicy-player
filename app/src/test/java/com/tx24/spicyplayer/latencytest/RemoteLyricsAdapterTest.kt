package com.tx24.spicyplayer.latencytest

import com.tx24.spicyplayer.network.data.LyricsAttribution
import com.tx24.spicyplayer.network.data.LyricsSourceDescriptor
import com.tx24.spicyplayer.network.data.RemoteLyricsPayload
import com.tx24.spicyplayer.network.data.RemoteLyricsQuality
import com.tx24.spicyplayer.network.data.RemoteLyricsSelection
import com.tx24.spicyplayer.lyrics.spicy.models.LyricsType
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteLyricsAdapterTest {
    @Test fun keepsProviderAndTtmlSongwriterCredits() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml"><head><metadata><songwriters><songwriter>Ada</songwriter></songwriters></metadata></head><body><div><p begin="0s" end="2s">Hello</p></div></body></tt>"""
        val selection = RemoteLyricsSelection(
            LyricsSourceDescriptor("example", "Example", 1, emptySet()),
            RemoteLyricsPayload(
                ttmlLyrics = ttml,
                attribution = LyricsAttribution(providerName = "Example", songwriters = listOf("Ada", "Ben")),
            ),
            RemoteLyricsQuality.LINE_SYNCED,
        )

        val ready = RemoteLyricsAdapter.render(selection, 2_000)

        assertEquals("Example", ready.provider)
        assertEquals(listOf("Ada", "Ben"), ready.songwriters)
        assertEquals(LyricsType.Line, ready.lyricsType)
    }
}
