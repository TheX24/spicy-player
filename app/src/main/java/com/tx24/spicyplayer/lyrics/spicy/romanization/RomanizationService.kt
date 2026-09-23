package com.tx24.spicyplayer.lyrics.spicy.romanization

/**
 * Resolves the best available [Romanizer] for a script. Japanese prefers the dictionary-backed
 * [JapaneseRomanizer] (Kuromoji) when present, falling back to kana-only conversion.
 */
object Romanizers {
    fun forScript(script: Script): Romanizer? = when (script) {
        Script.JAPANESE -> JapaneseRomanizerProvider.get()
        Script.CHINESE -> PinyinRomanizer
        Script.KOREAN -> KoreanRomanizer
        Script.CYRILLIC -> CyrillicRomanizer
        Script.GREEK -> GreekRomanizer
        Script.LATIN -> null
    }
}

/**
 * On-device romanization, mirroring `spicy-lyrics/src/utils/Lyrics/ProcessLyrics.ts`: the
 * scripts present are detected across the whole song first (so a kanji-only syllable in a
 * Japanese song is read as Japanese, never pinyin), then each text is run through every
 * present-script romanizer whose characters it contains, in priority order.
 */
object RomanizationService {

    /** One entry per input text: its romanization, or null when nothing changed. */
    fun romanize(texts: List<String>): List<String?> {
        val songScripts = ScriptDetector.detect(texts.joinToString("\n"))
        if (songScripts.isEmpty()) return texts.map { null }
        return texts.map { text ->
            var romanized = text
            for (script in songScripts) {
                if (!ScriptDetector.contains(script, romanized)) continue
                val romanizer = Romanizers.forScript(script) ?: continue
                if (romanizer.isAvailable()) romanized = romanizer.romanize(romanized)
            }
            romanized.takeIf { it != text && it.isNotBlank() }
        }
    }
}
