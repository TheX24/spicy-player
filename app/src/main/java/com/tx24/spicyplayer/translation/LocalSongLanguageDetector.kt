package com.tx24.spicyplayer.translation

import com.optimaize.langdetect.LanguageDetectorBuilder
import com.optimaize.langdetect.ngram.NgramExtractors
import com.optimaize.langdetect.profiles.LanguageProfileReader

/** Offline detection over the complete original song, with no per-line guesses or telemetry. */
class LocalSongLanguageDetector : SongLanguageDetector {
    private val detector by lazy {
        LanguageDetectorBuilder.create(NgramExtractors.standard())
            .withProfiles(LanguageProfileReader().readAllBuiltIn()).seed(0).build()
    }
    override fun detect(originalLyrics: String): String? {
        if (!isTranslationContent(originalLyrics)) return null
        val language = detector.detect(originalLyrics)
        return if (language.isPresent) language.get().language else null
    }
}
