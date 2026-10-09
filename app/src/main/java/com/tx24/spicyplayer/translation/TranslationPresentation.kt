package com.tx24.spicyplayer.translation

import com.tx24.spicyplayer.lyrics.spicy.romanization.ScriptDetector
import com.tx24.spicyplayer.lyrics.spicy.models.Line
import com.tx24.spicyplayer.lyrics.spicy.models.Word
import com.tx24.spicyplayer.lyrics.spicy.romanization.RomanizationMode

/** Supplemental rows are independent: romanization first, then translation. */
internal fun presentationSupplements(original: Line, romanize: Boolean, romanizationMode: RomanizationMode,
    translation: String?, translationMode: TranslationMode?): List<String> {
    if (original.isInterlude) return emptyList()
    val romanized = if (romanize && romanizationMode == RomanizationMode.UnderLine && original.words.any { it.romanizedText != null }) {
        // Chinese runs its characters together, but each one's pinyin is a syllable of its own.
        val chinese = original.words.any { word -> word.text.any(ScriptDetector::hasHan) } && original.words.none { word -> word.text.any(ScriptDetector::hasKana) }
        buildString {
            original.words.forEach { word ->
                if (isNotEmpty() && (!word.isPartOfWord || chinese)) append(' ')
                append(word.romanizedText ?: word.text)
            }
        }.takeIf(String::isNotBlank)
    } else null
    return listOfNotNull(romanized, translation?.takeIf { translationMode == TranslationMode.UnderLine && it.isNotBlank() })
}

data class TranslationPresentation(val texts: List<String?>, val mode: TranslationMode) {
    companion object {
        fun forTimeline(originals: List<Line>, timeline: List<Line>, result: TranslationResult, mode: TranslationMode): TranslationPresentation? {
            if (result.texts.size != originals.size) return null
            // Timeline copies retain their words; synthetic interludes have none.
            val indices = java.util.IdentityHashMap<List<Word>, Int>()
            originals.forEachIndexed { index, line -> indices[line.words] = index }
            return TranslationPresentation(timeline.map { line ->
                if (line.isInterlude) null else indices[line.words]?.let(result.texts::get)
                    ?.let { plainTranslation(line.words.joinToString("") { word -> word.text }, it) }
            }, mode)
        }
    }

    fun displayLines(lines: List<Line>): List<Line> {
        if (texts.size != lines.size || mode != TranslationMode.Replace) return lines
        return lines.mapIndexed { index, line ->
            texts[index]?.takeIf(String::isNotBlank)?.let { text ->
                line.copy(words = listOf(Word(text, line.startMs, line.endMs)), translationReplaces = true)
            } ?: line
        }
    }
}


/** Translators mark titles and stress as *emphasis*; shown as is, the asterisks are just noise. */
internal fun plainTranslation(original: String, translation: String): String =
    if ('*' in original) translation else translation.replace(EMPHASIS, "$1").replace("*", "").trim()

private val EMPHASIS = Regex("""\*+([^*]+)\*+""")
