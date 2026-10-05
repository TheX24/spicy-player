package com.tx24.spicyplayer.network.data.providers

import com.tx24.spicyplayer.network.data.LyricsLookupRequest
import com.tx24.spicyplayer.network.data.RemoteLyricsQuality
import com.tx24.spicyplayer.network.data.measuredQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomLyricsSourceTest {
    private val request = LyricsLookupRequest("Kenshi Yonezu & Friend", "Lemon / Remix?", "STRAY SHEEP", 255)

    @Test fun fillsInTheSong() {
        assertEquals(
            "https://x.dev/l?t=Lemon%20%2F%20Remix%3F&a=Kenshi%20Yonezu%20%26%20Friend&d=255&ms=255000",
            CustomLyricsSource.expand("https://x.dev/l?t={title}&a={artist}&d={duration}&ms={durationMs}", request, null),
        )
        assertEquals("https://x.dev/abc", CustomLyricsSource.expand("https://x.dev/{spotifyId}", request, "abc"))
        // Needs a Spotify ID it hasn't got: not asked.
        assertNull(CustomLyricsSource.expand("https://x.dev/{spotifyId}", request, null))
    }

    @Test fun checksTheAddress() {
        assertNull(CustomLyricsSource.problem("https://x.dev/l?t={title}"))
        assertNotNull(CustomLyricsSource.problem("http://x.dev/l?t={title}"))
        assertTrue(CustomLyricsSource.problem("https://x.dev/l?t={name}")!!.contains("{name}"))
        assertNotNull(CustomLyricsSource.problem("https://"))
    }

    @Test fun readsRawAnswers() {
        assertEquals("<tt>x</tt>", CustomLyricsSource.payloadOf("\uFEFF<tt>x</tt>", "")?.ttmlLyrics)
        assertEquals("[00:01.00]Hi", CustomLyricsSource.payloadOf("[00:01.00]Hi", "")?.syncedLyrics)
        assertEquals("Just words", CustomLyricsSource.payloadOf("Just words", "")?.plainLyrics)
        assertNull(CustomLyricsSource.payloadOf("   ", ""))
        // Enhanced LRC keeps its word timing, as TTML.
        val enhanced = CustomLyricsSource.payloadOf("[00:01.00]<00:01.00>Hi <00:01.50>there<00:02.00>", "")!!
        assertEquals(RemoteLyricsQuality.WORD_SYNCED, enhanced.measuredQuality())
    }

    @Test fun findsLyricsInJson() {
        // LRCLIB-like: synced beats plain.
        val lrclib = """{"id":1,"plainLyrics":"Hi","syncedLyrics":"[00:01.00]Hi"}"""
        assertEquals("[00:01.00]Hi", CustomLyricsSource.payloadOf(lrclib, "")?.syncedLyrics)
        // Under a wrapper, in a list.
        assertEquals("<tt/>", CustomLyricsSource.payloadOf("""{"data":[{"ttml":"<tt/>"}]}""", "")?.ttmlLyrics)
        // A path, with a list of lines at the end of it.
        val nested = """{"result":{"tracks":[{"lines":["[00:01.00]a","[00:02.00]b"]}]}}"""
        assertEquals("[00:01.00]a\n[00:02.00]b", CustomLyricsSource.payloadOf(nested, "$.result.tracks[0].lines")?.syncedLyrics)
        assertNull(CustomLyricsSource.payloadOf(nested, "result.tracks[3].lines"))
        assertNull(CustomLyricsSource.payloadOf("""{"error":"not found"}""", ""))
    }

    @Test fun sharingLeavesHeaderValuesOut() {
        val source = CustomLyricsSource("custom_1", "Mine", "https://x.dev/{title}", "data.ttml", listOf("Authorization" to "Bearer secret"))
        val shared = CustomLyricsSource.share(source)
        assertFalse(shared.contains("secret"))
        val pasted = CustomLyricsSource.fromShare(shared)!!
        assertEquals(listOf("Authorization" to ""), pasted.headers)
        assertEquals(source.url, pasted.url)
        assertEquals("data.ttml", pasted.path)
        assertTrue(CustomLyricsSource.isCustom(pasted.id) && pasted.id != source.id)
        assertNull(CustomLyricsSource.fromShare("""{"name":"x","url":"https://x.dev"}"""))
        assertEquals(listOf("A" to "b: c", "X-Key" to ""), CustomLyricsSource.parseHeaders("A: b: c\nnot a header\nX-Key:"))
    }
}
