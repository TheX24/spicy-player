package com.tx24.spicyplayer.translation

import com.google.gson.JsonParser
import com.tx24.spicyplayer.lyrics.TimedLine
import com.tx24.spicyplayer.lyrics.TimedWord
import com.tx24.spicyplayer.lyrics.spicy.models.Line
import com.tx24.spicyplayer.lyrics.spicy.models.LineRole
import com.tx24.spicyplayer.lyrics.spicy.models.LyricsFooter
import com.tx24.spicyplayer.lyrics.spicy.models.Word
import com.tx24.spicyplayer.lyrics.spicy.romanization.RomanizationMode
import com.tx24.spicyplayer.network.data.SourceDisclosures
import com.tx24.spicyplayer.network.data.providers.geniusLyricsText
import com.tx24.spicyplayer.network.data.providers.linkedTranslation
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TranslationSourcesTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun document(vararg text: String) = TranslationDocument.from("song", "source", text.map {
        TimedLine(0, 1_000, listOf(TimedWord(it, 0, 1_000, false)))
    })
    private val preferences = TranslationPreferences(targetLanguage = "en")
    private class Cache : TranslationCache {
        var result: TranslationResult? = null
        override fun read(key: TranslationKey, lineCount: Int) = result?.takeIf { it.key == key && it.texts.size == lineCount }
        override fun write(result: TranslationResult) { this.result = result }
    }
    private class Machine : Translator {
        override val provider = TranslationProvider.Google
        val requests = mutableListOf<TranslatorRequest>()
        override suspend fun translate(request: TranslatorRequest): TranslatorResponse {
            requests += request
            return TranslatorResponse(request.lines.map { TranslationEntry("machine:$it") }, "ja")
        }
    }

    @Test fun `Google posts original whole-song text in q and languages in the query`() {
        val request = googleRequest(TranslatorRequest(listOf("こんにちは", "世界 & friends"), "it", null, "ignored"))
        assertEquals("POST", request.method)
        assertEquals("translate.googleapis.com", request.url.host)
        assertEquals("/translate_a/single", request.url.encodedPath)
        assertEquals("gtx", request.url.queryParameter("client"))
        assertEquals("t", request.url.queryParameter("dt"))
        assertEquals("1", request.url.queryParameter("dj"))
        assertEquals("auto", request.url.queryParameter("sl"))
        assertEquals("it", request.url.queryParameter("tl"))
        val body = request.body as FormBody
        assertEquals(1, body.size)
        assertEquals("q", body.name(0))
        assertEquals("こんにちは\n世界 & friends", body.value(0))
        assertFalse(request.url.toString().contains("friends"))
        assertEquals("ja", googleRequest(TranslatorRequest(listOf("世界"), "en", "ja", "")).url.queryParameter("sl"))
    }

    @Test fun `Google sentences concatenate before splitting and preserve empty positions`() {
        val response = googleResponse("""{"sentences":[{"trans":"Hello\n","orig":"こんにちは\n"},{"trans":"\nWorld"}],"src":"ja"}""")
        assertEquals("ja", response.detectedLanguage)
        assertEquals(listOf("Hello", null, "World"), response.lines.map { it?.text })
        assertEquals(listOf("Hello world"), googleResponse("""{"sentences":[{"trans":"Hello "},{"trans":"world"}],"src":"ja"}""").lines.map { it?.text })
        assertTrue(runCatching { googleResponse("""{"sentences":[{"orig":"missing translation"}]}""") }.isFailure)
    }

    @Test fun `Google HTTP rate limits and unavailable answers use provider backoff`() = runBlocking {
        for (code in listOf(429, 503)) {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code)
                    .message("Busy").header("Retry-After", "4").body("".toResponseBody()).build()
            }.build()
            try {
                GoogleTranslator(client).translate(TranslatorRequest(listOf("ciao"), "en", "it", "ciao"))
                fail("A busy response must request backoff")
            } catch (busy: ProviderBusy) {
                assertEquals(4_000L, busy.retryAfterMs)
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
    }

    @Test fun `Google retries the whole mismatched song at verse boundaries then individual lines`() = runBlocking {
        val input = document("一行目", "二行目", "", "三行目", "四行目")
        val requests = mutableListOf<List<String>>()
        val translator = object : Translator {
            override val provider = TranslationProvider.Google
            override suspend fun translate(request: TranslatorRequest): TranslatorResponse {
                requests += request.lines
                return if (request.lines.size > 2 || "三行目" in request.lines) TranslatorResponse(emptyList(), "ja")
                else TranslatorResponse(request.lines.map { TranslationEntry("t:$it") }, "ja")
            }
        }
        val result = TranslationEngine(SongLanguageDetector { null }, Cache(), pause = {})
            .translate(input, preferences, null, translator) as TranslationOutcome.Translated
        assertEquals(listOf(listOf("一行目", "二行目", "三行目", "四行目"), listOf("一行目", "二行目"),
            listOf("三行目", "四行目"), listOf("三行目"), listOf("四行目")), requests)
        assertEquals(listOf("t:一行目", "t:二行目", null, null, "t:四行目"), result.result.texts)
    }

    @Test fun `old provider names migrate but Unison consent never grants Google or Genius consent`() {
        for (stored in listOf(null, "Unison", "unknown", "")) assertEquals(TranslationProvider.Google, TranslationProvider.stored(stored))
        assertEquals(TranslationProvider.DeepL, TranslationProvider.stored("DeepL"))
        assertFalse(TranslationConsent.covered(setOf("Unison", "translation_unison"), TranslationProvider.Google, false))
        assertFalse(TranslationConsent.covered(setOf("translation_google"), TranslationProvider.Google, true))
        assertFalse(TranslationConsent.covered(setOf("DeepL"), TranslationProvider.DeepL, true))
        assertTrue(TranslationConsent.covered(setOf("translation_google_genius"), TranslationProvider.Google, false))
        val notice = SourceDisclosures.translation(TranslationProvider.Google, true)
        assertTrue(notice.description.contains("free web service, not a paid API"))
        assertTrue(notice.description.contains("Genius"))
        assertFalse(SourceDisclosures.translation(TranslationProvider.Google, false).description.contains("Genius"))
    }

    @Test fun `Genius page pairs strip headers equally and refuse unequal counts`() {
        val original = geniusLyricsText("""<div data-lyrics-container="true">[Verse 1]<br/>夜が来る<br/><br/>星が光る</div>""")
        val translated = geniusLyricsText("""<div data-lyrics-container="true">[Verse]<br/>Night comes<br/>Stars shine<br/>Extra line</div>""")
        assertNull(GeniusTranslationPair.fromText(original, translated))
        val pair = GeniusTranslationPair.fromText(original, "[Verse]\nNight comes\n\nStars shine", "ja")!!
        assertEquals(listOf("夜が来る", "星が光る"), pair.originals)
        assertEquals(listOf("Night comes", "Stars shine"), pair.translations)
    }

    @Test fun `Genius linked language selects the target without guessing from the title`() {
        val song = JsonParser.parseString("""{"translation_songs":[
            {"language":"romanization","url":"roman"},{"language":"it","url":"italian"},
            {"language":"en","url":"english"},{"title":"English Translation","url":"no-language"}]}""").asJsonObject
        assertEquals("english", linkedTranslation(song, "en-US")?.get("url")?.asString)
        assertEquals("italian", linkedTranslation(song, "it")?.get("url")?.asString)
        assertNull(linkedTranslation(song, "fr"))
    }

    @Test fun `original-script alignment survives skips and a collapsed repeated chorus`() {
        val input = document("夜が来る", "星が光る", "私だけの行", "歩き続ける", "夜が来る", "星が光る", "朝が来る")
        val pair = GeniusTranslationPair.fromText("[Chorus]\n夜が来る\n星が光る\n[Verse]\n歩き続ける\n朝が来る",
            "[Chorus]\nNight comes\nStars shine\n[Verse]\nKeep walking\nMorning comes", "ja")!!
        assertEquals(listOf("Night comes", "Stars shine", null, "Keep walking", "Night comes", "Stars shine", "Morning comes"), HumanTranslation.align(input, pair))
        val ambiguous = GeniusTranslationPair(listOf("夜が来る", "夜が来る"), listOf("Night comes", "Darkness falls"), "ja")
        assertEquals(listOf(null), HumanTranslation.align(document("夜が来る"), ambiguous))
    }

    @Test fun `Genius background runs stay separate and use machine fallback`() = runBlocking {
        val input = TranslationDocument.from("song", "source", listOf(
            TimedLine(0, 1_000, listOf(TimedWord("夜が来る", 0, 1_000, false)), groupId = 2),
            TimedLine(0, 1_000, listOf(TimedWord("声が響く", 0, 1_000, false)), role = LineRole.BACKGROUND, groupId = 2),
        ))
        val pair = GeniusTranslationPair.fromText("夜が来る (声が響く)", "Night comes (Voices echo)", "ja")!!
        val machine = Machine()
        val result = (TranslationEngine(SongLanguageDetector { null }, Cache(), pause = {}).translate(input, preferences, null, machine) { pair } as TranslationOutcome.Translated).result
        assertEquals(listOf("Night comes", "machine:声が響く"), result.texts)
        assertEquals(listOf(TranslationOrigin.Genius, TranslationOrigin.Google), result.origins)
        assertEquals(input.lines.map { it.text }, machine.requests.single().lines)
        assertEquals("Translation: Genius + Google", result.geniusCredit())
        assertEquals("Translation: Genius + Google", LyricsFooter(translationCredit = result.geniusCredit()).lines().single().text)
    }

    @Test fun `complete human coverage makes zero machine calls and disk hits retain origins`() = runBlocking {
        val input = document("夜が来る", "", "星が光る")
        val pair = GeniusTranslationPair.fromText("夜が来る\n星が光る", "Night comes\nStars shine", "ja")!!
        val cache = DiskTranslationCache(temporary.newFolder())
        val machine = Machine()
        val engine = TranslationEngine(SongLanguageDetector { null }, cache, pause = {})
        val result = (engine.translate(input, preferences, null, machine) { pair } as TranslationOutcome.Translated).result
        assertEquals(listOf("Night comes", null, "Stars shine"), result.texts)
        assertEquals(listOf(TranslationOrigin.Genius, null, TranslationOrigin.Genius), result.origins)
        assertEquals("Translation: Genius", result.geniusCredit())
        assertEquals(result, (engine.translate(input, preferences, null, machine) { error("Cache should avoid Genius too") } as TranslationOutcome.Translated).result)
        assertTrue(machine.requests.isEmpty())
    }

    @Test fun `a slow human lookup shows the machine translation first, then upgrades it`() = runBlocking {
        val input = document("夜が来る", "違う歌詞", "星が光る")
        val pair = GeniusTranslationPair.fromText("夜が来る\n星が光る", "Night comes\nStars shine", "ja")!!
        val partials = mutableListOf<List<String?>>()
        val result = (TranslationEngine(SongLanguageDetector { null }, Cache(), pause = {})
            .translate(input, preferences, null, Machine(), onPartial = { partials.add(it.texts) }) {
                kotlinx.coroutines.delay(600)
                pair
            } as TranslationOutcome.Translated).result
        assertEquals(listOf(listOf("machine:夜が来る", "machine:違う歌詞", "machine:星が光る")), partials)
        assertEquals(listOf("Night comes", "machine:違う歌詞", "Stars shine"), result.texts)
    }

    @Test fun `mixed combination preserves gaps and disabling human translations has its own cache key`() = runBlocking {
        val input = document("夜が来る", "違う歌詞", "星が光る")
        val pair = GeniusTranslationPair.fromText("夜が来る\n星が光る", "Night comes\nStars shine", "ja")!!
        val engine = TranslationEngine(SongLanguageDetector { null }, Cache(), pause = {})
        val machine = Machine()
        val result = (engine.translate(input, preferences, null, machine) { pair } as TranslationOutcome.Translated).result
        assertEquals(listOf("Night comes", "machine:違う歌詞", "Stars shine"), result.texts)
        assertEquals(listOf(TranslationOrigin.Genius, TranslationOrigin.Google, TranslationOrigin.Genius), result.origins)
        assertNotEquals(engine.key(input, preferences, null).fileName(), engine.key(input, preferences.copy(humanTranslations = false), null).fileName())
        val disabled = (engine.translate(input, preferences.copy(humanTranslations = false), null, machine) { error("Disabled") } as TranslationOutcome.Translated).result
        assertEquals(listOf("machine:夜が来る", "machine:違う歌詞", "machine:星が光る"), disabled.texts)
    }

    @Test fun `Genius disk cache remembers found and missing pairs by song and language`() = runBlocking {
        val folder = temporary.newFolder()
        var now = 1_000L
        val cache = DiskGeniusTranslationCache(folder) { now }
        var calls = 0
        val pair = GeniusTranslationPair.fromText("夜が来る", "Night comes", "ja")!!
        assertEquals(pair, cache.find("song", "artist", "en") { calls++; pair })
        assertEquals(pair, DiskGeniusTranslationCache(folder) { now }.find("song", "artist", "en") { error("Disk hit") })
        assertNull(cache.find("song", "artist", "it") { calls++; null })
        assertNull(DiskGeniusTranslationCache(folder) { now }.find("song", "artist", "it") { error("Negative disk hit") })
        assertEquals(2, calls)
        now += 86_400_001L
        cache.find("song", "artist", "it") { calls++; null }
        assertEquals(3, calls)
        cache.forget("song", "artist")
        cache.find("song", "artist", "en") { calls++; pair }
        assertEquals(4, calls)
    }

    @Test fun `romanization and translation independently choose all four presentations`() {
        val original = Line(listOf(Word("こん", 0, 500, romanizedText = "kon"),
            Word("にちは", 500, 1_000, isPartOfWord = true, romanizedText = "nichiwa")), 0, 1_000)
        for (romanization in RomanizationMode.entries) {
            for (translation in TranslationMode.entries) {
                val display = TranslationPresentation(listOf("Hello"), translation).displayLines(listOf(original)).single()
                val supplements = presentationSupplements(original, true, romanization, "Hello", translation)
                val expected = (if (romanization == RomanizationMode.UnderLine) listOf("konnichiwa") else emptyList()) +
                    (if (translation == TranslationMode.UnderLine) listOf("Hello") else emptyList())
                assertEquals(expected, supplements)
                assertEquals(translation == TranslationMode.Replace, display.translationReplaces)
                if (translation == TranslationMode.UnderLine) assertSame(original, display)
                else assertEquals("Hello", display.words.single().text)
            }
        }
        assertEquals(listOf("konnichiwa"), presentationSupplements(original, true, RomanizationMode.UnderLine, null, null))
        assertTrue(presentationSupplements(original, true, RomanizationMode.Replace, null, null).isEmpty())
        assertEquals(listOf("Hello"), presentationSupplements(original, false, RomanizationMode.UnderLine, "Hello", TranslationMode.UnderLine))
        assertSame(original, TranslationPresentation(listOf(null), TranslationMode.Replace).displayLines(listOf(original)).single())
    }
}
