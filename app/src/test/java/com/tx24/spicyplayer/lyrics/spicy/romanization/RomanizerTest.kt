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
        val out = RomanizationService.romanize(listOf(listOf("君と"), listOf("心"))).map { it.single() }
        assertTrue(out[1] != null && out[1] != "xin")
    }

    @Test fun hanOnlySongIsPinyin() {
        assertEquals(listOf(listOf("xin")), RomanizationService.romanize(listOf(listOf("心"))))
    }

    @Test fun latinIsLeftAlone() {
        assertNull(RomanizationService.romanize(listOf(listOf("hello"))).single().single())
    }

    @Test fun composesEveryPresentScript() {
        val out = RomanizationService.romanize(listOf(listOf("Привет κόσμος"))).single().single()
        assertTrue(out != null && out.none { it in 'Ͱ'..'ԯ' })
    }

    @Test fun syllableSplitJapaneseUsesLineContext() {
        // SL splits 段々堕ちてく one character per syllable; read alone, 々 and 堕 stay unromanized.
        val syllables = listOf("段", "々", "堕", "ち", "て", "く", "I", "Q", "ク", "ソ", "ワ", "ロ")
        val out = RomanizationService.romanize(listOf(syllables)).single()
        assertEquals(listOf("dan", "dan", "o", "chi", "te", "ku"), out.take(6))
        assertTrue(out.none { it != null && it.any { c -> c in '぀'..'鿿' } })
    }
}
