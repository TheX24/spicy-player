package com.tx24.spicyplayer.lyrics.spicy.canvas

import org.junit.Assert.assertEquals
import org.junit.Test

class LineWrapperTest {
    private fun word(w: Float, text: String = "a") = LineWrapper.Piece(w, text, gluedToPrevious = false)
    private fun glued(w: Float, text: String = "a") = LineWrapper.Piece(w, text, gluedToPrevious = true)

    /** A word written without spaces: its first piece starts a group, the rest are glued on. */
    private fun group(text: String, charWidth: Float = 20f) =
        text.mapIndexed { i, c -> LineWrapper.Piece(charWidth, c.toString(), gluedToPrevious = i > 0) }

    private fun syllable(pieces: List<LineWrapper.Piece>, max: Float, trailing: Float = 5f) =
        LineWrapper.breaks(pieces, max, wordGap = 5f, trailingGap = trailing, textMode = false)

    private fun text(pieces: List<LineWrapper.Piece>, max: Float) =
        LineWrapper.breaks(pieces, max, wordGap = 5f, trailingGap = 0f, textMode = true)

    @Test fun aWordGroupMovesToTheNextRowWhole() {
        // Spicy Lyrics: 君の一番 / かわいいところな～んだ？ (not split inside the second group).
        val pieces = group("君の一番") + group("かわいいところな～んだ？")
        assertEquals(listOf(0, 4, 16), syllable(pieces, max = 300f))
    }

    @Test fun aGroupWiderThanARowTakesItsOwnRowsAndSoDoesWhatFollows() {
        val pieces = listOf(word(40f)) + group("abcdef", charWidth = 40f) + listOf(word(20f))
        // "a" | group from a fresh row, broken where it fills | the next word on a fresh row.
        assertEquals(listOf(0, 1, 3, 5, 7, 8), syllable(pieces, max = 100f))
    }

    @Test fun leftAlignedWordsNeedRoomForTheirTrailingSpace() {
        val pieces = listOf(word(45f), word(45f), word(10f))
        // 45 + 5 + 45 = 95 fits, but the second word's own trailing 0.32ch makes it 100.
        assertEquals(listOf(0, 1, 3), syllable(pieces, max = 95f))
    }

    @Test fun theLastWordNeedsNoTrailingSpace() {
        assertEquals(listOf(0, 2), syllable(listOf(word(45f), word(45f)), max = 95f))
    }

    @Test fun oppositeAlignedLinesOnlyCountGapsBetweenWords() {
        val pieces = listOf(word(45f), word(45f), word(10f))
        assertEquals(listOf(0, 2, 3), syllable(pieces, max = 95f, trailing = 0f))
    }

    @Test fun plainTextBreaksBetweenCjkCharacters() {
        assertEquals(listOf(0, 3, 5), text(group("あいうえお"), max = 70f))
    }

    @Test fun plainTextNeverStartsARowWithClosingPunctuation() {
        // あいう fills the row, but 、 can't start the next one, so う moves down with it.
        assertEquals(listOf(0, 2, 5), text(group("あいう、え"), max = 70f))
    }

    @Test fun plainTextNeverEndsARowWithAnOpeningBracket() {
        assertEquals(listOf(0, 2, 5), text(group("あい「うえ"), max = 70f))
    }

    @Test fun plainTextKeepsLatinWordsWhole() {
        val pieces = listOf(word(40f, "hello"), word(40f, "there"), word(40f, "you"))
        assertEquals(listOf(0, 2, 3), text(pieces, max = 90f))
    }

    @Test fun anOverlongLatinWordStillBreaksRatherThanOverflow() {
        val pieces = listOf(word(40f, "a")) + listOf(glued(40f, "b"), glued(40f, "c"), glued(40f, "d"))
        assertEquals(listOf(0, 2, 4), text(pieces, max = 90f))
    }
}
