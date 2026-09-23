package com.tx24.spicyplayer.lyrics.spicy.romanization

import com.atilika.kuromoji.ipadic.Tokenizer

/**
 * Dictionary-backed Japanese romanizer using Kuromoji (IPADIC). Tokenizes text, reads each
 * token's katakana reading (covering kanji), and converts it to Hepburn romaji via
 * [KanaRomanizer]. Falls back to kana-only conversion on any failure.
 *
 * The tokenizer loads a bundled dictionary lazily on first use (off the main thread).
 */
object JapaneseRomanizer : Romanizer {
    override val script = Script.JAPANESE

    private val tokenizer: Tokenizer? by lazy {
        try { Tokenizer() } catch (t: Throwable) { null }
    }

    override fun isAvailable(): Boolean = tokenizer != null

    override fun romanize(text: String): String {
        val tk = tokenizer ?: return KanaRomanizer.romanize(text)
        return try {
            val sb = StringBuilder(text.length * 2)
            for (token in tk.tokenize(text)) {
                val reading = token.reading
                val source = if (reading != null && reading != "*") reading else token.surface
                // Spaced between tokens, like the reference's kuroshiro "spaced" mode.
                sb.append(KanaRomanizer.romanize(source)).append(' ')
            }
            sb.toString().trim().replace(Regex(" +"), " ")
        } catch (t: Throwable) {
            KanaRomanizer.romanize(text)
        }
    }

    /**
     * Romanizes one line's [syllables] with the whole line as tokenizer context, returning one
     * entry per syllable. Per-syllable tokenizing loses readings when a word is split across
     * syllables (段|々 → "dan" + "々", 堕|ち → "堕" + "chi"); here 段々 reads だんだん and the
     * reading is shared back out to the syllables it spans.
     */
    fun romanizeLine(syllables: List<String>): List<String> {
        val tk = tokenizer ?: return syllables.map(KanaRomanizer::romanize)
        val owner = syllables.flatMapIndexed { index, text -> List(text.length) { index } }
        val pieces = List(syllables.size) { mutableListOf<String>() }
        return try {
            for (token in tk.tokenize(syllables.joinToString(""))) {
                val readings = charReadings(token.surface, token.reading?.takeIf { it != "*" })
                readings.indices.groupBy { owner[token.position + it] }.forEach { (syllable, chars) ->
                    val piece = KanaRomanizer.romanize(chars.joinToString("") { readings[it] }).trim()
                    if (piece.isNotEmpty()) pieces[syllable] += piece
                }
            }
            pieces.map { it.joinToString(" ") }
        } catch (t: Throwable) {
            syllables.map(KanaRomanizer::romanize)
        }
    }

    /** One kana reading per surface character: kana at the ends read as themselves, the kanji middle shares the rest by mora. */
    internal fun charReadings(surface: String, reading: String?): List<String> {
        if (reading == null) return surface.map(Char::toString)
        val kata = surface.map(::toKatakana)
        var prefix = 0
        while (prefix < surface.length && prefix < reading.length && isKana(kata[prefix]) && kata[prefix] == reading[prefix]) prefix++
        var suffix = 0
        while (suffix < surface.length - prefix && suffix < reading.length - prefix &&
            isKana(kata[surface.length - 1 - suffix]) && kata[surface.length - 1 - suffix] == reading[reading.length - 1 - suffix]
        ) suffix++
        val middleChars = surface.length - prefix - suffix
        val morae = morae(reading.substring(prefix, reading.length - suffix))
        val middle = List(middleChars) { i ->
            // ponytail: even mora split across kanji, per-kanji readings need a dictionary we don't bundle
            morae.subList(i * morae.size / middleChars, (i + 1) * morae.size / middleChars).joinToString("")
        }
        return kata.take(prefix).map(Char::toString) + middle + kata.takeLast(suffix).map(Char::toString)
    }

    private fun morae(reading: String): List<String> = buildList {
        for (c in reading) {
            if (isEmpty() || c !in "ャュョァィゥェォヮー") add(c.toString()) else this[lastIndex] += c
        }
    }

    private fun isKana(c: Char) = c.code in 0x30A1..0x30FC
    private fun toKatakana(c: Char) = if (c.code in 0x3041..0x3096) (c.code + 0x60).toChar() else c
}
