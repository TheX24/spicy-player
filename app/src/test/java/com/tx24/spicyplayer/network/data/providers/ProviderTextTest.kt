package com.tx24.spicyplayer.network.data.providers

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.tx24.spicyplayer.network.data.LyricsAttribution
import com.tx24.spicyplayer.network.data.ProviderResult
import com.tx24.spicyplayer.network.data.RemoteLyricsPayload
import com.tx24.spicyplayer.network.data.RemoteLyricsQuality
import com.tx24.spicyplayer.network.data.isNoWordsNote
import com.tx24.spicyplayer.network.data.measuredQuality
import com.tx24.spicyplayer.lyrics.spicy.parser.TtmlLyricsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderTextTest {
    @Test fun instrumentalNoteIsNotLyrics() {
        assertTrue(RemoteLyricsPayload(syncedLyrics = "[00:00.00]作词 : 无\n[00:01.00]纯音乐，请欣赏").isNoWordsNote())
        assertTrue(RemoteLyricsPayload(plainLyrics = "此歌曲为没有填词的纯音乐，请您欣赏").isNoWordsNote())
    }

    @Test fun realLyricsMentioningTheWordsAreKept() {
        val song = (1..6).joinToString("\n") { "[00:0$it.00]line $it" } + "\n[00:07.00]纯音乐"
        assertFalse(RemoteLyricsPayload(syncedLyrics = song).isNoWordsNote())
    }

    @Test fun lrcMuxServesWordTimingAndFallsBackToLines() {
        fun parse(json: String) = LrcMuxLyricsProvider(okhttp3.OkHttpClient(), Gson())
            .parse(Gson().fromJson(json, JsonObject::class.java)) as ProviderResult.Hit
        val words = parse("""{"lines":[{"text":"Hello there","start":1.5,"end":3.0,"words":[{"text":"Hello","start":1.5,"end":2.0},{"text":"there","start":2.0,"end":3.0}]}]}""")
        assertTrue(words.payload.ttmlLyrics!!.contains("itunes:timing=\"word\""))
        assertEquals(null, words.payload.attribution?.originName)
        val kugou = parse("""{"meta":{"source":{"id":"kugou","name":"KuGou"}},"lines":[{"text":"Hi","start":1.0,"end":2.0}]}""")
        assertEquals(LyricsAttribution("LRCMux", originName = "KuGou"), kugou.payload.attribution)
        // A line-level answer (LRCMux relays LRCLIB's) has no words.
        val lines = parse("""{"lines":[{"text":"Hello there","start":1.5,"end":3.0}]}""")
        assertEquals(null, lines.payload.ttmlLyrics)
        assertEquals("[00:01.50]Hello there", lines.payload.syncedLyrics)
    }

    @Test fun rmmSaysWhereItsLyricsComeFrom() {
        fun rmm(json: String) = (rmmPayload(Gson().fromJson(json, JsonObject::class.java)) as ProviderResult.Hit).payload.attribution!!
        val relayed = rmm("""{"ttml":"<tt/>","lyricsSource":"spicylyrics","lyricsProviderSource":"apple_music","uploadAttribution":null,"songWriters":["A","B"]}""")
        assertEquals("Apple Music", relayed.originName)
        assertEquals(listOf("A", "B"), relayed.songwriters)
        assertEquals(null, relayed.maker)

        val community = rmm("""{"ttml":"<tt/>","lyricsProviderSource":"spicy_lyrics","uploadAttribution":{"Maker":{"username":"maker","url":"https://spicylyrics.org/uid/1"},"Uploader":{"username":"uploader"}}}""")
        assertEquals("Spicy Lyrics Community", community.originName)
        assertEquals("maker", community.maker?.username)
        assertEquals("https://spicylyrics.org/uid/1", community.maker?.profileUrl)
        assertEquals("uploader", community.uploader?.username)

        // Unlabelled: nothing to go by, so it keeps RMM Revival's own place.
        assertEquals(null, rmm("""{"ttml":"<tt/>"}""").originName)

        // Labelled "spicy_lyrics" with no uploader: Apple Music's lyrics passed on through Spicy Lyrics.
        assertEquals("Apple Music", rmm("""{"ttml":"<tt/>","lyricsSource":"spicylyrics","lyricsProviderSource":"spicy_lyrics","uploadAttribution":null}""").originName)
    }

    @Test fun lrcRedTtmlReadsAsApples() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:lrc="http://lrc.red/lyric-ttml-internal" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" lrc:timing="Word" xml:lang="ja">""" +
            """<head><metadata><sourceMetadata xmlns="http://lrc.red/lyric-ttml-internal"><songwriters><songwriter>Ayase</songwriter></songwriters>""" +
            """<transliterations><transliteration xml:lang="ja-Latn"><text for="L1"><span begin="0.5" end="1.0" xmlns="http://www.w3.org/ns/ttml">ka</span><span begin="1.0" end="1.5" xmlns="http://www.w3.org/ns/ttml">sa</span></text></transliteration></transliterations>""" +
            """</sourceMetadata></metadata></head><body><div lrc:songPart="Verse"><p begin="0.5" end="1.5" lrc:key="L1" ttm:agent="v1">""" +
            """<span begin="0.5" end="1.0">傘</span><span begin="1.0" end="1.5">さ</span></p></div></body></tt>"""
        val apple = LrcRedLyricsProvider.appleTtml(ttml)
        assertTrue(apple.contains("""itunes:timing="Word""""))
        assertTrue(apple.contains("""itunes:key="L1""""))
        assertTrue(apple.contains("<iTunesMetadata") && apple.contains("</iTunesMetadata>"))
        assertEquals(RemoteLyricsQuality.WORD_SYNCED, RemoteLyricsPayload(ttmlLyrics = apple).measuredQuality())
        val line = TtmlLyricsParser.parse(apple.byteInputStream()).lines.single()
        assertEquals(listOf("ka", "sa"), line.words.map { it.romanizedText })
    }

    @Test fun spicyLyricsApiErrorsSayWhy() {
        assertEquals("Rate limit exceeded", apiErrorMessage("""{"Body":{"error":"rate_limited","message":"Rate limit exceeded"},"Status":429,"Type":"object"}"""))
        assertEquals("rate_limited", apiErrorMessage("""{"Body":{"error":"rate_limited"},"Status":429}"""))
        assertEquals(null, apiErrorMessage("<html>Bad gateway</html>"))
        assertEquals(null, apiErrorMessage(""))
    }

    @Test fun geniusSkipsNestedPageFurniture() {
        val html = """<div data-lyrics-container="true" class="x"><div data-exclude-from-selection="true"><div><span>Translations</span></div></div>""" +
            """[Verse 1]<br/>We're no <a href="#"><span>strangers</span></a> to love<br>You know the rules</div><div>footer</div>"""
        assertEquals("[Verse 1]\nWe're no strangers to love\nYou know the rules", geniusLyricsText(html))
    }
}
