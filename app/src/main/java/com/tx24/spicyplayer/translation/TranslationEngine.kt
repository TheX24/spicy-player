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
        // One document, so DeepL reads the whole song together; its size limit is checked on the request.
        if (provider == TranslationProvider.DeepL) return listOf(verses.flatten())
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

class TranslationEngine(
    private val detector: SongLanguageDetector,
    private val cache: TranslationCache,
    /** Waits between requests and before a retry; tests pass one that doesn't. */
    private val pause: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {
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
        val aligned = mutableMapOf<Pair<Int, Int>, String?>()
        var detected = source
        var requests = 0
        var answered = false
        // A refused batch is halved until the refused lines stand alone; they stay untranslated and
        // the rest keep as much of their verse around them as the provider takes.
        suspend fun send(batch: List<TranslationPiece>) {
            if (requests++ >= MAX_REQUESTS) {
                batch.forEach { aligned[it.index to it.part] = null }
                return
            }
            // Spaced out, so singling out a refused line doesn't trip the provider's rate limit.
            if (requests > 1) pause(PACE_MS)
            val response = try {
                // Later batches go out with the language the first answer found.
                ask(translator, TranslatorRequest(batch.map { it.text }, key.targetLanguage, detected, document.context))
            } catch (refused: LinesRejected) {
                if (batch.size == 1) {
                    aligned[batch[0].index to batch[0].part] = null
                } else {
                    send(batch.subList(0, batch.size / 2))
                    send(batch.subList(batch.size / 2, batch.size))
                }
                return
            }
            if (response.lines.size != batch.size) throw TranslationFailure("Translation returned an incomplete response. Try again later.")
            answered = true
            detected = detected ?: languageCode(response.detectedLanguage)
            aligned.putAll(TranslationBatching.align(batch, response))
        }
        batches.forEach { send(it) }
        if (!answered) throw TranslationFailure("Couldn't translate these lyrics. Try again later.")
        if (skipTranslation(detected, preferences.targetLanguage, preferences.excludedLanguages)) {
            // Kept, untranslated, so the song is known to need nothing the next time it plays.
            cache.write(TranslationResult(key, List(document.lines.size) { null }, detected))
            return TranslationOutcome.Skipped(detected)
        }
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

    /** [Translator.translate], waiting out a busy provider a couple of times before giving up. */
    private suspend fun ask(translator: Translator, request: TranslatorRequest): TranslatorResponse {
        repeat(BUSY_RETRIES) { attempt ->
            try {
                return translator.translate(request)
            } catch (busy: ProviderBusy) {
                pause(busy.retryAfterMs?.coerceIn(1_000L, 10_000L) ?: (BUSY_WAIT_MS * (attempt + 1)))
            }
        }
        return try {
            translator.translate(request)
        } catch (busy: ProviderBusy) {
            throw TranslationFailure("Translation is busy right now. Try again in a minute.")
        }
    }

    private companion object {
        const val PACE_MS = 300L
        const val BUSY_RETRIES = 2
        const val BUSY_WAIT_MS = 2_000L
        /** Requests one song may take while singling out refused lines, so a broken one can't flood the provider. */
        const val MAX_REQUESTS = 24
    }
}
