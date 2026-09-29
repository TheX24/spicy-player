package com.tx24.spicyplayer.lyrics.spicy.parser

import com.tx24.spicyplayer.lyrics.spicy.RenderConfig
import com.tx24.spicyplayer.lyrics.spicy.SyllableMerge
import com.tx24.spicyplayer.lyrics.spicy.models.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LetterSynthesizerTest {
    // "to|night " (short, 600 ms) then "for|ev|er" (held, 1500 ms).
    private val words = listOf(
        Word("to", 0, 200),
        Word("night ", 200, 600, isPartOfWord = true),
        Word("for", 1000, 1200),
        Word("ev", 1200, 1400, isPartOfWord = true, romanizedText = "EV"),
        Word("er", 1400, 2500, isPartOfWord = true),
    )

    private fun merge(mode: SyllableMerge, romanized: Boolean = false) =
        LetterSynthesizer.mergeSyllables(words, RenderConfig.FULL.copy(syllableMerge = mode), romanized)

    @Test
    fun `off keeps the syllables`() {
        assertEquals(words, merge(SyllableMerge.Off))
    }

    @Test
    fun `full merges every split word into one spanning its syllables`() {
        val merged = merge(SyllableMerge.Full)
        assertEquals(listOf("tonight ", "forever"), merged.map { it.text })
        assertEquals(listOf(0L to 600L, 1000L to 2500L), merged.map { it.startMs to it.endMs })
        assertEquals(listOf(false, false), merged.map { it.isPartOfWord })
        assertNull(merged[0].romanizedText)
        assertEquals("forEVer", merged[1].romanizedText)
    }

    @Test
    fun `held merges only words long enough for the letter effect`() {
        val merged = merge(SyllableMerge.Held)
        assertEquals(listOf("to", "night ", "forever"), merged.map { it.text })
    }

    private fun mergeTexts(vararg syllables: String): List<String> {
        val glued = syllables.mapIndexed { i, s -> Word(s, i * 300L, i * 300L + 300, isPartOfWord = i > 0) }
        return LetterSynthesizer.mergeSyllables(glued, RenderConfig.FULL.copy(syllableMerge = SyllableMerge.Full), false)
            .map { it.text }
    }

    @Test
    fun `hyphens and dashes keep glued words apart`() {
        assertEquals(listOf("well-", "known"), mergeTexts("well-", "known"))
        assertEquals(listOf("yeah", "—", "no"), mergeTexts("yeah", "—", "no"))
        assertEquals(listOf("to", "-night"), mergeTexts("to", "-night"))
        assertEquals(listOf("don't,"), mergeTexts("don", "'t", ","))
    }

    @Test
    fun `scripts without word spaces never merge`() {
        assertEquals(listOf("愛", "し", "て", "る"), mergeTexts("愛", "し", "て", "る"))
        assertEquals(listOf("ラー", "メン"), mergeTexts("ラー", "メン"))
        assertEquals(listOf("รัก", "เธอ"), mergeTexts("รัก", "เธอ"))
        assertEquals(listOf("I", "愛"), mergeTexts("I", "愛"))
    }

    @Test
    fun `korean spaces its words so its syllables merge`() {
        assertEquals(listOf("사랑해"), mergeTexts("사", "랑", "해"))
    }

    @Test
    fun `a split-off word keeps its glue to the one before`() {
        val merged = LetterSynthesizer.mergeSyllables(
            listOf(Word("well-", 0, 300), Word("kn", 300, 600, isPartOfWord = true), Word("own", 600, 900, isPartOfWord = true)),
            RenderConfig.FULL.copy(syllableMerge = SyllableMerge.Full), false,
        )
        assertEquals(listOf("well-", "known"), merged.map { it.text })
        assertEquals(listOf(false, true), merged.map { it.isPartOfWord })
    }

    @Test
    fun `a merged held word gets letters across the whole word`() {
        val config = RenderConfig.FULL.copy(syllableMerge = SyllableMerge.Held)
        val line = com.tx24.spicyplayer.lyrics.spicy.models.Line(words, 0)
        val forever = LetterSynthesizer.apply(listOf(line), config, romanized = false).single().words.last()
        assertEquals(true, forever.isLetterGroup)
        assertEquals("forever", forever.letters.joinToString("") { it.char })
    }
}
