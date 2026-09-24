package com.tx24.spicyplayer.lyrics.spicy.parser

import com.tx24.spicyplayer.lyrics.spicy.models.LineRole
import com.tx24.spicyplayer.lyrics.spicy.models.LyricsType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TtmlLyricsParserTest {
    private fun doc(body: String, timing: String? = "Word", head: String = "") =
        """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal"${timing?.let { " itunes:timing=\"$it\"" }.orEmpty()}><head><metadata>$head</metadata></head><body>$body</body></tt>"""

    private fun parse(xml: String) = TtmlLyricsParser.parse(xml.byteInputStream())

    @Test fun aSpanIsOneSyllableWithItsOwnTiming() {
        val words = parse(doc("""<div><p begin="1s" end="3s"><span begin="1s" end="2s">two words</span> <span begin="2s" end="3s">well-known</span></p></div>""")).lines.single().words
        assertEquals(listOf("two words", "well-known"), words.map { it.text })
        assertEquals(listOf(1_000L, 2_000L), words.map { it.startMs })
    }

    @Test fun syllablesJoinOnlyWhenTheirTagsTouch() {
        val p = """<p begin="1" end="5"><span begin="1" end="2">Ma</span><span begin="2" end="3">ma,</span><span begin="3" end="4">mi</span> <span begin="4" end="5">a</span></p>"""
        val words = parse(doc("<div>$p</div>")).lines.single().words
        // "ma" glues to "Ma"; a comma ends the word; whitespace between tags ends it too.
        assertEquals(listOf(false, true, false, false), words.map { it.isPartOfWord })
    }

    @Test fun aBackgroundSpanInBetweenEndsTheWord() {
        val p = """<p begin="1" end="3"><span begin="1" end="2">Ma</span><span ttm:role="x-bg"><span begin="1" end="2">(oh)</span></span><span begin="2" end="3">ma</span></p>"""
        val lines = parse(doc("<div>$p</div>")).lines
        assertEquals(listOf(false, false), lines[0].words.map { it.isPartOfWord })
        assertEquals("oh", lines[1].words.single().text)
    }

    @Test fun backgroundVocalsLoseEveryParenthesis() {
        val p = """<p begin="1" end="3"><span begin="1" end="2">A</span><span ttm:role="x-bg"><span begin="1" end="2">(ooh (yeah))</span></span></p>"""
        assertEquals("ooh yeah", parse(doc("<div>$p</div>")).lines[1].words.single().text)
    }

    @Test fun onlyDeclaredV2AndV2000SingFromTheOtherSide() {
        val head = """<ttm:agent type="person" xml:id="v1"/><ttm:agent type="person" xml:id="v2"/><ttm:agent type="person" xml:id="v3"/>"""
        val body = listOf("v1", "v2", "v3").joinToString("") { agent ->
            """<p begin="1" end="2" ttm:agent="$agent"><span begin="1" end="2">x</span></p>"""
        }
        assertEquals(listOf(false, true, false), parse(doc("<div>$body</div>", head = head)).lines.map { it.oppositeAligned })
    }

    @Test fun instrumentalSectionsAndEmptyLinesAreSkipped() {
        val body = """<div itunes:songPart="Instrumental"><p begin="0" end="1"><span begin="0" end="1">skip</span></p></div>""" +
            """<div><p begin="1" end="2"><span begin="1" end="2"> </span></p><p begin="2" end="3"><span begin="2" end="3">keep</span></p></div>"""
        assertEquals(listOf("keep"), parse(doc(body)).lines.map { it.words.single().text })
    }

    @Test fun readsEveryTimeFormat() {
        assertEquals(0.3, TtmlLyricsParser.convertTimeToSeconds("300ms")!!, 1e-9)
        assertEquals(90.0, TtmlLyricsParser.convertTimeToSeconds("1.5m")!!, 1e-9)
        assertEquals(3723.5, TtmlLyricsParser.convertTimeToSeconds("01:02:03.5")!!, 1e-9)
        assertEquals(63.25, TtmlLyricsParser.convertTimeToSeconds("1:03.25")!!, 1e-9)
        assertEquals(12.5, TtmlLyricsParser.convertTimeToSeconds("12.5s")!!, 1e-9)
        assertNull(TtmlLyricsParser.convertTimeToSeconds("10f"))
    }

    @Test fun transliterationsAreMatchedByTiming() {
        val head = """<iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal"><transliterations><transliteration><text for="L1">""" +
            """<span begin="00:00:02.000" end="00:00:03.000">de</span><span begin="1s" end="2s">nan</span></text></transliteration></transliterations></iTunesMetadata>"""
        val p = """<p begin="1s" end="3s" itunes:key="L1"><span begin="1s" end="2s">なん</span><span begin="2s" end="3s">で</span></p>"""
        assertEquals(listOf("nan", "de"), parse(doc("<div>$p</div>", head = head)).lines.single().words.map { it.romanizedText })
    }

    @Test fun oneTimedSpanPerLineWithoutADeclaredTimingIsLineSynced() {
        val parsed = parse(doc("""<div><p begin="1s" end="2s"><span begin="1s" end="2s">hello there</span></p></div>""", timing = null))
        assertEquals(LyricsType.Line, parsed.type)
        assertEquals("hello there", parsed.lines.single().words.single().text)
    }

    @Test fun aLineWithoutTimingStartsWithItsFirstSyllable() {
        val lines = parse(doc("""<div begin="0s"><p><span begin="4s" end="5s">late</span><span begin="5s" end="6s">r</span></p></div>""")).lines
        assertEquals(4_000L, lines.single().startMs)
        assertEquals(6_000L, lines.single().endMs)
        assertEquals(LineRole.LEAD, lines.single().role)
    }
}
