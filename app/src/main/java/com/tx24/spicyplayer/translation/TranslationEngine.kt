package com.tx24.spicyplayer.translation

/** Keeps request positions separate from display positions, including split long lines. */
data class TranslationPiece(val index: Int, val part: Int, val text: String)

object TranslationBatching {
    fun batches(document: TranslationDocument, provider: TranslationProvider): List<List<TranslationPiece>> {
        val verses = mutableListOf<MutableList<TranslationPiece>>()
        var verse = mutableListOf<TranslationPiece>()
        document.lines.forEach { line ->
            if (line.boundary || line.verseStart) {
                if (verse.isNotEmpty()) verses.add(verse)
                verse = mutableListOf()
            }
            if (!line.boundary) {
                val texts = if (provider == TranslationProvider.Unison) splitLongLine(line.text) else listOf(line.text)
                texts.forEachIndexed { part, text -> verse.add(TranslationPiece(line.index, part, text)) }
            }
        }
        if (verse.isNotEmpty()) verses.add(verse)
        if (provider == TranslationProvider.DeepL) return verses.flatten().chunked(50)
        val batches = mutableListOf<List<TranslationPiece>>()
        var batch = mutableListOf<TranslationPiece>()
        verses.forEach { block ->
            if (block.size > 200) throw TranslationFailure("A verse exceeds Unison's limit. Try DeepL.")
            if (batch.size + block.size > 200) {
                batches.add(batch)
                batch = mutableListOf()
            }
            batch.addAll(block)
        }
        if (batch.isNotEmpty()) batches.add(batch)
        return batches
    }

    private fun splitLongLine(text: String): List<String> {
        val parts = mutableListOf<String>()
        var remaining = text
        while (remaining.length > 500) {
            var end = remaining.lastIndexOf(' ', 500).takeIf { it > 0 } ?: 500
            if (remaining[end - 1].isHighSurrogate()) end--
            parts.add(remaining.substring(0, end))
            remaining = remaining.substring(end).trimStart()
        }
        if (remaining.isNotBlank()) parts.add(remaining)
        return parts
    }

    /** A positional response of the wrong size cannot reveal where a missing item was. */
    fun align(batch: List<TranslationPiece>, response: TranslatorResponse): Map<Pair<Int, Int>, String?> {
        if (response.lines.size != batch.size) return batch.associate { (it.index to it.part) to null }
        return batch.mapIndexed { position, piece ->
            val result = response.lines[position]
            (piece.index to piece.part) to result?.text?.takeIf { result.needsTranslation && it.isNotBlank() }
        }.toMap()
    }
}

interface TranslationCache {
    fun read(key: TranslationKey, lineCount: Int): TranslationResult?
    fun write(result: TranslationResult)
}

sealed interface TranslationOutcome {
    data class Translated(val result: TranslationResult) : TranslationOutcome
    data class Skipped(val language: String?) : TranslationOutcome
}

class TranslationEngine(private val detector: SongLanguageDetector, private val cache: TranslationCache) {
    private val languages = object : LinkedHashMap<String, String?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>): Boolean = size > 32
    }

    fun language(document: TranslationDocument, override: String?): String? = languageCode(override) ?: synchronized(languages) {
        if (!languages.containsKey(document.hash)) languages[document.hash] = languageCode(detector.detect(document.context))
        languages[document.hash]
    }

    fun key(document: TranslationDocument, preferences: TranslationPreferences, source: String?): TranslationKey =
        TranslationKey(document.hash, languageCode(preferences.targetLanguage) ?: "en", preferences.provider, languageCode(source))

    suspend fun translate(
        document: TranslationDocument,
        preferences: TranslationPreferences,
        source: String?,
        translator: Translator,
    ): TranslationOutcome {
        if (skipTranslation(source, preferences.targetLanguage, preferences.excludedLanguages)) return TranslationOutcome.Skipped(source)
        require(translator.provider == preferences.provider)
        val key = key(document, preferences, source)
        cache.read(key, document.lines.size)?.let {
            return if (skipTranslation(it.detectedLanguage, preferences.targetLanguage, preferences.excludedLanguages))
                TranslationOutcome.Skipped(it.detectedLanguage) else TranslationOutcome.Translated(it)
        }
        val batches = TranslationBatching.batches(document, translator.provider)
        if (batches.isEmpty()) return TranslationOutcome.Skipped(source)
        if (source == null && translator.provider == TranslationProvider.Unison && batches.size > 1)
            throw TranslationFailure("Choose this song's lyrics language in Quick settings first.")
        val aligned = mutableMapOf<Pair<Int, Int>, String?>()
        var detected = source
        for (batch in batches) {
            val response = translator.translate(TranslatorRequest(batch.map { it.text }, key.targetLanguage, source, document.context))
            if (response.lines.size != batch.size) throw TranslationFailure("Translation returned an incomplete response. Try again later.")
            detected = detected ?: languageCode(response.detectedLanguage)
            aligned.putAll(TranslationBatching.align(batch, response))
        }
        if (skipTranslation(detected, preferences.targetLanguage, preferences.excludedLanguages)) return TranslationOutcome.Skipped(detected)
        val pieces = batches.flatten().groupBy { it.index }
        val texts = document.lines.map { line ->
            val parts = pieces[line.index].orEmpty()
            // A missing chunk leaves the entire original line intact.
            if (parts.isEmpty() || parts.any { aligned[it.index to it.part] == null }) null
            else parts.joinToString(" ") { aligned.getValue(it.index to it.part).orEmpty() }
        }
        val result = TranslationResult(key, texts, detected)
        cache.write(result)
        return TranslationOutcome.Translated(result)
    }
}
