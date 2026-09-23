package com.tx24.spicyplayer.lyrics.spicy.romanization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RomanizerTest {
    @Test fun koreanRevisedRomanization() {
        assertEquals("annyeonghaseyo", KoreanRomanizer.romanize("안녕하세요"))
    }

    @Test fun cyrillicAndGreek() {
        assertEquals("privet", CyrillicRomanizer.romanize("привет"))
        assertEquals("kalimera", GreekRomanizer.romanize("καλημέρα"))
    }

    @Test fun kanaHandlesYouonAndSokuon() {
        assertEquals("kya", KanaRomanizer.romanize("きゃ"))
        assertEquals("kitto", KanaRomanizer.romanize("きっと"))
    }

    @Test fun kanjiOnlyWordInJapaneseSongIsNotPinyin() {
        // "心" alone reads as Chinese; the song's kana makes it Japanese.
        val out = RomanizationService.romanize(listOf("君と", "心"))
        assertTrue(out[1] != null && out[1] != "xin")
    }

    @Test fun hanOnlySongIsPinyin() {
        assertEquals(listOf("xin"), RomanizationService.romanize(listOf("心")))
    }

    @Test fun latinIsLeftAlone() {
        assertNull(RomanizationService.romanize(listOf("hello")).single())
    }

    @Test fun composesEveryPresentScript() {
        val out = RomanizationService.romanize(listOf("Привет κόσμος")).single()
        assertTrue(out != null && out.none { it in 'Ͱ'..'ԯ' })
    }
}
