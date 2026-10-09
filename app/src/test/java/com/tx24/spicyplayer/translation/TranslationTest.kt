package com.tx24.spicyplayer.translation

import com.tx24.spicyplayer.lyrics.spicy.romanization.RomanizationMode
import com.tx24.spicyplayer.lyrics.TimedLine
import com.tx24.spicyplayer.lyrics.TimedWord
import com.tx24.spicyplayer.lyrics.spicy.models.Line
import com.tx24.spicyplayer.lyrics.spicy.models.LineRole
import com.tx24.spicyplayer.lyrics.spicy.models.Word
import com.tx24.spicyplayer.lyrics.spicy.models.buildDisplayTimeline
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TranslationTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun line(text: String, role: LineRole = LineRole.LEAD, group: Int? = null) =
        TimedLine(1_000, 2_000, listOf(TimedWord(text, 1_000, 2_000, false, "display only")), role, group)
    private fun document(vararg texts: String) = TranslationDocument.from("song", "source", texts.map { line(it) })
    private val preferences = TranslationPreferences(targetLanguage = "en")
    private class MemoryCache : TranslationCache {
        var result: TranslationResult? = null
        override fun read(key: TranslationKey, lineCount: Int) = result?.takeIf { it.key == key && it.texts.size == lineCount }
        override fun write(result: TranslationResult) { this.result = result }
    }
    private class FakeTranslator(
        override val provider: TranslationProvider = TranslationProvider.Google,
        val answer: suspend (TranslatorRequest) -> TranslatorResponse = { request ->
            TranslatorResponse(request.lines.map { TranslationEntry("translated:$it") }, "it")
        },
    ) : Translator {
        val requests = mutableListOf<TranslatorRequest>()
        override suspend fun translate(request: TranslatorRequest): TranslatorResponse {
            requests.add(request)
            return answer(request)
        }
    }

    @Test fun `short and long positional responses never shift lines`() {
        val batch = TranslationBatching.batches(document("uno", "due", "tre"), TranslationProvider.Google).single()
        for (size in listOf(0, 1, 2, 4, 10)) {
            val result = TranslationBatching.align(batch, TranslatorResponse(List(size) { TranslationEntry("x$it") }))
            assertEquals(setOf(0 to 0, 1 to 0, 2 to 0), result.keys)
            assertTrue(result.values.all { it == null })
        }
    }

    @Test fun `missing middle entry stays empty and no-op lines have no overlay`() {
        val batch = TranslationBatching.batches(document("uno", "due", "tre"), TranslationProvider.Google).single()
        val result = TranslationBatching.align(batch, TranslatorResponse(listOf(TranslationEntry("one"), null, TranslationEntry("three"))))
        assertEquals(mapOf((0 to 0) to "one", (1 to 0) to null, (2 to 0) to "three"), result)
        assertNull(TranslationBatching.align(batch, TranslatorResponse(listOf(TranslationEntry("one"), TranslationEntry("two", false), TranslationEntry("three"))))[1 to 0])
    }

    @Test fun `a mismatched verse falls back to lines without moving a missing line`() = runBlocking {
        val texts = List(9) { "riga $it" }
        val translator = FakeTranslator { request ->
            if ("riga 5" in request.lines) TranslatorResponse(emptyList(), "it")
            else TranslatorResponse(request.lines.map { TranslationEntry("translated:$it") }, "it")
        }
        val waits = mutableListOf<Long>()
        val result = TranslationEngine(SongLanguageDetector { "it" }, MemoryCache(), pause = { waits.add(it) })
            .translate(document(*texts.toTypedArray()), preferences, "it", translator) as TranslationOutcome.Translated
        assertEquals(texts.map { if (it == "riga 5") null else "translated:$it" }, result.result.texts)
        assertEquals(10, translator.requests.size)
        assertEquals(List(9) { 300L }, waits)
    }

    @Test fun `a busy provider is waited out`() = runBlocking {
        var calls = 0
        val waits = mutableListOf<Long>()
        val translator = FakeTranslator { request ->
            if (calls++ == 0) throw ProviderBusy(retryAfterMs = 4_000L)
            TranslatorResponse(request.lines.map { TranslationEntry("translated:$it") }, "it")
        }
        val result = TranslationEngine(SongLanguageDetector { null }, MemoryCache(), pause = { waits.add(it) })
            .translate(document("uno"), preferences, "it", translator) as TranslationOutcome.Translated
        assertEquals(listOf("translated:uno"), result.result.texts)
        assertEquals(listOf(4_000L), waits)
    }

    @Test fun `a provider that stays busy says so and nothing is cached`() = runBlocking {
        val cache = MemoryCache()
        val translator = FakeTranslator { throw ProviderBusy() }
        val error = runCatching {
            TranslationEngine(SongLanguageDetector { null }, cache, pause = {}).translate(document("uno"), preferences, "it", translator)
        }.exceptionOrNull()
        assertEquals("Translation is busy right now. Try again in a minute.", error?.message)
        assertEquals(3, translator.requests.size)
        assertNull(cache.result)
    }

    @Test fun `a song mismatched everywhere is a failure and is not cached`() = runBlocking {
        val cache = MemoryCache()
        val translator = FakeTranslator { TranslatorResponse(emptyList()) }
        val error = runCatching {
            TranslationEngine(SongLanguageDetector { "it" }, cache, pause = {}).translate(document(*Array(40) { "riga $it" }), preferences, "it", translator)
        }.exceptionOrNull()
        assertTrue(error is TranslationFailure)
        assertNull(cache.result)
        assertEquals(41, translator.requests.size)
    }

    @Test fun `blank and musical markers keep their positions without being sent`() = runBlocking {
        val input = document("uno", "", "♪", "♫", "...", "due")
        val translator = FakeTranslator()
        val result = TranslationEngine(SongLanguageDetector { "it" }, MemoryCache())
            .translate(input, preferences, "it", translator) as TranslationOutcome.Translated
        assertEquals(listOf("uno", "due"), translator.requests.single().lines)
        assertEquals(listOf("translated:uno", null, null, null, null, "translated:due"), result.result.texts)
    }

    @Test fun `background voices are separate indexed entries in the same context`() = runBlocking {
        val input = TranslationDocument.from("song", "ttml", listOf(
            line("lead", group = 4), line("back one", LineRole.BACKGROUND, 4), line("back two", LineRole.BACKGROUND, 4), line("next", group = 5),
        ))
        assertEquals(listOf(LyricAddress(0), LyricAddress(0, 0), LyricAddress(0, 1), LyricAddress(3)), input.lines.map { it.address })
        val translator = FakeTranslator()
        val result = TranslationEngine(SongLanguageDetector { "it" }, MemoryCache()).translate(input, preferences, "it", translator) as TranslationOutcome.Translated
        assertEquals(4, result.result.texts.size)
        assertEquals("translated:back two", result.result.texts[2])
        assertEquals(input.context, translator.requests.single().context)
    }

    @Test fun `detection and requests use all original text and ignore romanization`() = runBlocking {
        val input = TranslationDocument.from("song", "source", listOf(
            TimedLine(0, 2_000, listOf(TimedWord("こん", 0, 1_000, false, "kon"), TimedWord("にちは", 1_000, 2_000, true, "nichiwa"))),
            line("世界"),
        ))
        var detectedText = ""
        var detections = 0
        val engine = TranslationEngine(SongLanguageDetector { detectedText = it; detections++; "ja" }, MemoryCache())
        val source = engine.language(input, null)
        engine.language(input, null)
        assertEquals(1, detections)
        assertEquals("こんにちは\n世界", detectedText)
        val translator = FakeTranslator()
        engine.translate(input, preferences, source, translator)
        assertEquals(listOf("こんにちは", "世界"), translator.requests.single().lines)
        assertEquals("ru", engine.language(input, "ru"))
        assertEquals(1, detections)
        engine.language(input.copy(song = "different"), null)
        assertEquals(2, detections)
    }

    @Test fun `same or excluded song language makes zero provider calls`() = runBlocking {
        val translator = FakeTranslator()
        val engine = TranslationEngine(SongLanguageDetector { "en" }, MemoryCache())
        assertTrue(engine.translate(document("original"), preferences, "EN-us", translator) is TranslationOutcome.Skipped)
        assertTrue(engine.translate(document("original"), preferences.copy(excludedLanguages = setOf("ru")), "ru", translator) is TranslationOutcome.Skipped)
        assertTrue(translator.requests.isEmpty())
        assertFalse(skipTranslation(null, "en", setOf("ru")))
        assertTrue(skipTranslation("nb", "no", emptySet()))
    }

    @Test fun `an uncancellable old result is dropped after song source or target changes`() = runBlocking {
        val input = document("uno")
        val engine = TranslationEngine(SongLanguageDetector { "it" }, MemoryCache())
        val session = TranslationSession()
        val ready = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val translator = FakeTranslator(answer = { request -> ready.complete(Unit); release.await(); TranslatorResponse(request.lines.map { TranslationEntry("one") }) })
        val old = session.begin(engine.key(input, preferences, "it"))
        var displayed: TranslationResult? = null
        val work = async {
            val result = engine.translate(input, preferences, "it", translator) as TranslationOutcome.Translated
            if (session.accepts(old)) displayed = result.result
        }
        ready.await()
        session.begin(engine.key(input.copy(song = "new", source = "other"), preferences.copy(targetLanguage = "de"), "it"))
        release.complete(Unit)
        work.await()
        assertNull(displayed)
    }

    @Test fun `off and on rejects old tickets even for identical lyrics`() {
        val session = TranslationSession()
        val key = TranslationKey(document("uno").hash, "en", TranslationProvider.Google, "it")
        val old = session.begin(key)
        session.invalidate()
        assertFalse(session.accepts(old))
        val current = session.begin(key)
        assertFalse(session.accepts(old))
        assertTrue(session.accepts(current))
    }

    @Test fun `disk hit returns exactly the fresh presentation and wrong count refetches`() = runBlocking {
        val input = document("uno", "due")
        val folder = temporary.newFolder()
        val first = TranslationEngine(SongLanguageDetector { "it" }, DiskTranslationCache(folder))
        val fresh = (first.translate(input, preferences, "it", FakeTranslator()) as TranslationOutcome.Translated).result
        val translator = FakeTranslator()
        val next = TranslationEngine(SongLanguageDetector { "it" }, DiskTranslationCache(folder))
        val hit = (next.translate(input, preferences, "it", translator) as TranslationOutcome.Translated).result
        assertEquals(fresh, hit)
        assertEquals(TranslationPresentation(fresh.texts, TranslationMode.UnderLine), TranslationPresentation(hit.texts, TranslationMode.UnderLine))
        assertTrue(translator.requests.isEmpty())
        DiskTranslationCache(folder).write(fresh.copy(texts = listOf("wrong count")))
        val refetched = (next.translate(input, preferences, "it", translator) as TranslationOutcome.Translated).result
        assertEquals(fresh, refetched)
        assertEquals(1, translator.requests.size)
    }

    @Test fun `cache identity covers song source original script address target provider and version`() {
        val input = document("a", "bc")
        val key = TranslationKey(input.hash, "en", TranslationProvider.Google, "it")
        assertNotEquals(input.hash, input.copy(song = "other").hash)
        assertNotEquals(input.hash, input.copy(source = "other").hash)
        assertNotEquals(input.hash, document("ab", "c").hash)
        assertNotEquals(input.hash, input.copy(lines = input.lines.reversed()).hash)
        assertNotEquals(key.fileName(), key.copy(targetLanguage = "de").fileName())
        assertNotEquals(key.fileName(), key.copy(provider = TranslationProvider.DeepL).fileName())
        assertNotEquals(key.fileName(), key.copy(sourceLanguage = "ru").fileName())
        assertNotEquals(key.fileName(), key.fileName(TRANSLATION_CACHE_VERSION + 1))
        assertEquals(2, TRANSLATION_CACHE_VERSION)
    }

    @Test fun `wrong cache version and malformed entries are ignored`() {
        val directory = temporary.newFolder()
        val key = TranslationKey(document("uno").hash, "en", TranslationProvider.Google, "it")
        val cache = DiskTranslationCache(directory)
        cache.write(TranslationResult(key, listOf("one"), "it"))
        val file = java.io.File(directory, key.fileName())
        file.writeText(file.readText().replace("\"version\":2", "\"version\":999"))
        assertNull(cache.read(key, 1))
        file.writeText("{broken")
        assertNull(cache.read(key, 1))
    }

    @Test fun `Google sends a whole song and splits oversized songs at verse boundaries`() {
        assertEquals(1, TranslationBatching.batches(document(*Array(250) { "line $it" }), TranslationProvider.Google).size)
        val input = document(*(List(150) { "first $it " + "a".repeat(16) } + "" + List(90) { "second $it " + "b".repeat(16) }).toTypedArray())
        val batches = TranslationBatching.batches(input, TranslationProvider.Google)
        assertEquals(listOf(150, 90), batches.map { it.size })
        assertEquals(151, batches[1].first().index)
        assertTrue(batches.all { it.joinToString("\n") { piece -> piece.text }.length < 5_000 })
        val oversizedVerse = TranslationBatching.batches(document(*Array(201) { "verse " + "v".repeat(100) }), TranslationProvider.Google)
        assertTrue(oversizedVerse.size > 1)
        assertTrue(oversizedVerse.all { it.joinToString("\n") { piece -> piece.text }.length < 5_000 })
    }

    @Test fun `long lines are split within the request limits and recombined at their index`() = runBlocking {
        val input = document("a ".repeat(3_000), "next")
        val translator = FakeTranslator()
        val result = TranslationEngine(SongLanguageDetector { "it" }, MemoryCache(), pause = {}).translate(input, preferences, "it", translator) as TranslationOutcome.Translated
        assertTrue(translator.requests.all { it.lines.joinToString("\n").length < 5_000 })
        assertEquals(2, result.result.texts.size)
        assertEquals("translated:next", result.result.texts[1])
        assertTrue(result.result.texts[0]!!.count { it == ':' } > 1)
    }

    @Test fun `DeepL gets the whole song as one tagged document`() = runBlocking {
        val input = document(*Array(120) { "line $it" })
        val translator = FakeTranslator(TranslationProvider.DeepL)
        TranslationEngine(SongLanguageDetector { "ru" }, MemoryCache()).translate(input, preferences.copy(provider = TranslationProvider.DeepL), "ru", translator)
        assertEquals(listOf(120), translator.requests.map { it.lines.size })
        assertEquals("ru", translator.requests.single().source)
        val request = deepLRequest(translator.requests.single(), "0123456789abcdef:fx")
        val body = request.body as FormBody
        val fields = (0 until body.size).groupBy({ body.name(it) }, { body.value(it) })
        assertEquals(listOf(deepLDocument(translator.requests.single().lines)), fields["text"])
        assertNull(fields["context"])
        assertEquals(listOf("xml"), fields["tag_handling"])
        assertEquals(listOf("nonewlines"), fields["split_sentences"])
        assertEquals(listOf("RU"), fields["source_lang"])
    }

    @Test fun `DeepL lines come back by tag, never running into each other`() {
        val lines = listOf("マジクソ笑えるわ", "a < b & c", "止まらない")
        val sent = deepLDocument(lines)
        assertEquals(lines, deepLLines(sent, 3))
        // A tag DeepL dropped leaves only that line empty; the others stay where they were.
        val answer = "<l i=\"0\">This is hilarious</l>\n<l i=\"2\">Can't stop</l>"
        assertEquals(listOf("This is hilarious", null, "Can't stop"), deepLLines(answer, 3))
    }

    @Test fun `DeepL free and paid keys select the right host without key in URL`() {
        assertEquals("https://api-free.deepl.com", DeepLKey.host("0123456789abcdef:fx"))
        assertEquals("https://api.deepl.com", DeepLKey.host("0123456789abcdef"))
        assertTrue(DeepLKey.valid(" 0123456789abcdef:fx "))
        assertFalse(DeepLKey.valid("not a key"))
        val request = deepLRequest(TranslatorRequest(listOf("text"), "en", null, "context"), "0123456789abcdef:fx")
        assertFalse(request.url.toString().contains("0123456789abcdef"))
        val body = request.body as FormBody
        assertFalse((0 until body.size).any { body.name(it) == "source_lang" })
    }

    @Test fun `replace keeps line span and background role and leaves missing originals intact`() {
        val lead = Line(listOf(Word("a", 100, 200), Word("b", 300, 400)), 50, 500, groupId = 4)
        val background = lead.copy(role = LineRole.BACKGROUND)
        val lines = listOf(lead, background, lead.copy(startMs = 600, endMs = 900))
        val result = TranslationPresentation(listOf("translated lead", "translated bg", null), TranslationMode.Replace).displayLines(lines)
        assertEquals(50, result[0].words.single().startMs)
        assertEquals(500, result[0].words.single().endMs)
        assertTrue(result[0].translationReplaces)
        assertEquals(LineRole.BACKGROUND, result[1].role)
        assertEquals(4, result[1].groupId)
        assertSame(lines[2], result[2])
        assertSame(lines, TranslationPresentation(listOf("x"), TranslationMode.Replace).displayLines(lines))
        assertSame(lines, TranslationPresentation(listOf("x", "y", null), TranslationMode.UnderLine).displayLines(lines))
        assertEquals(listOf("a", "b"), lead.words.map { it.text })
    }

    @Test fun `display interludes and held line copies do not move translated background voices`() {
        val lead = Line(listOf(Word("first", 5_000, 6_000)), 5_000, 6_000, groupId = 1)
        val background = Line(listOf(Word("voice", 5_100, 5_900)), 5_100, 5_900, role = LineRole.BACKGROUND, groupId = 1)
        val next = Line(listOf(Word("next", 7_000, 8_000)), 7_000, 8_000, groupId = 2)
        val last = Line(listOf(Word("last", 13_000, 14_000)), 13_000, 14_000, groupId = 3)
        val originals = listOf(lead, background, next, last)
        val timeline = buildDisplayTimeline(originals, minimalMode = false, holdThroughShortGaps = true)
        val result = TranslationResult(TranslationKey("hash", "en", TranslationProvider.Google, "it"), listOf("one", "back", "two", "three"), "it")
        val presentation = TranslationPresentation.forTimeline(originals, timeline, result, TranslationMode.Replace)!!
        assertEquals(listOf(null, "one", "back", "two", null, "three"), presentation.texts)
        assertEquals(7_000L, timeline[1].endMs)
        assertEquals("back", presentation.displayLines(timeline)[2].words.single().text)
        assertEquals(LineRole.BACKGROUND, presentation.displayLines(timeline)[2].role)
        assertFalse(presentation.displayLines(timeline)[0].translationReplaces)
        assertNull(TranslationPresentation.forTimeline(originals, timeline, result.copy(texts = listOf("wrong count")), TranslationMode.Replace))
    }

    @Test fun `bad provider count is not persisted as a successful cache entry`() = runBlocking {
        val cache = MemoryCache()
        val translator = FakeTranslator(answer = { TranslatorResponse(emptyList()) })
        try {
            TranslationEngine(SongLanguageDetector { "it" }, cache).translate(document("uno"), preferences, "it", translator)
            fail("Malformed alignment must fail safely")
        } catch (_: TranslationFailure) { }
        assertNull(cache.result)
    }

    @Test fun `timed verse boundaries keep lead and background together`() {
        val first = List(150) { line("verse one $it " + "v".repeat(15), group = it) }
        val second = List(100) { line("verse two $it " + "v".repeat(15), group = it + 150).copy(startMs = 10_000, endMs = 11_000) }
        val input = TranslationDocument.from("song", "ttml", first + second)
        assertEquals(listOf(150, 100), TranslationBatching.batches(input, TranslationProvider.Google).map { it.size })
        assertTrue(input.lines[150].verseStart)
        assertFalse(input.lines[151].verseStart)
    }

    @Test fun `cached detection also obeys newly excluded languages with no provider call`() = runBlocking {
        val input = document("uno")
        val cache = MemoryCache()
        val engine = TranslationEngine(SongLanguageDetector { null }, cache)
        engine.translate(input, preferences, null, FakeTranslator())
        val translator = FakeTranslator()
        val result = engine.translate(input, preferences.copy(excludedLanguages = setOf("it")), null, translator)
        assertTrue(result is TranslationOutcome.Skipped)
        assertTrue(translator.requests.isEmpty())
    }

    @Test fun `a song found to be in the target language is asked about once`() = runBlocking {
        val cache = MemoryCache()
        val engine = TranslationEngine(SongLanguageDetector { null }, cache)
        val translator = FakeTranslator { request -> TranslatorResponse(request.lines.map { TranslationEntry(null, false) }, "en") }
        val song = document("I want your love", "and I want your revenge")
        assertEquals(TranslationOutcome.Skipped("en"), engine.translate(song, preferences, null, translator))
        assertEquals(TranslationOutcome.Skipped("en"), engine.translate(song, preferences, null, translator))
        assertEquals(1, translator.requests.size)
    }

    @Test fun `later batches carry the language the first answer found`() = runBlocking {
        val texts = (0 until 260).map { if (it % 100 == 99) "" else "riga $it " + "a".repeat(30) }
        val translator = FakeTranslator()
        TranslationEngine(SongLanguageDetector { null }, MemoryCache()).translate(document(*texts.toTypedArray()), preferences, null, translator)
        assertTrue(translator.requests.size > 1)
        assertNull(translator.requests.first().source)
        assertTrue(translator.requests.drop(1).all { it.source == "it" })
    }

    @Test fun `translation asterisks go unless the line has its own`() {
        assertEquals("If you don't, it's like Strange Tales from a Chinese Studio",
            plainTranslation("你不出手說聊齋", "If you don't, it's like *Strange Tales from a Chinese Studio*"))
        assertEquals("*sigh*", plainTranslation("*ため息*", "*sigh*"))
    }

    @Test fun `chinese pinyin under the line is spaced by syllable, japanese keeps its words`() {
        fun syllable(text: String, roman: String) = Word(text, 0, 1, isPartOfWord = true, romanizedText = roman)
        val chinese = Line(listOf(Word("你", 0, 1, romanizedText = "nǐ"), syllable("不", "bù"), syllable("出", "chū")), 0)
        assertEquals("nǐ bù chū", presentationSupplements(chinese, true, RomanizationMode.UnderLine, null, null).single())
        val japanese = Line(listOf(Word("ちょう", 0, 1, romanizedText = "chou"), syllable("だい", "dai"), Word("ビーム", 0, 1, romanizedText = "biimu")), 0)
        assertEquals("choudai biimu", presentationSupplements(japanese, true, RomanizationMode.UnderLine, null, null).single())
    }
}
