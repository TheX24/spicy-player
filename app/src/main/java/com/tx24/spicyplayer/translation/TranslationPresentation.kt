package com.tx24.spicyplayer.translation

import com.tx24.spicyplayer.lyrics.spicy.models.Line
import com.tx24.spicyplayer.lyrics.spicy.models.Word

data class TranslationPresentation(val texts: List<String?>, val mode: TranslationMode) {
    companion object {
        fun forTimeline(originals: List<Line>, timeline: List<Line>, result: TranslationResult, mode: TranslationMode): TranslationPresentation? {
            if (result.texts.size != originals.size) return null
            // Timeline copies retain their words; synthetic interludes have none.
            val indices = java.util.IdentityHashMap<List<Word>, Int>()
            originals.forEachIndexed { index, line -> indices[line.words] = index }
            return TranslationPresentation(timeline.map { line ->
                if (line.isInterlude) null else indices[line.words]?.let(result.texts::get)
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
