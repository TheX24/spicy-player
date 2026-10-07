package com.tx24.spicyplayer.network.data

import com.tx24.spicyplayer.network.model.NetworkErrorException
import com.tx24.spicyplayer.network.model.NotFoundException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RemoteLyricsSourceTest {
    private val request = LyricsLookupRequest("Artist", "Title", "Album", 180)

    @Test
    fun `uses default priority order`() = runBlocking {
        val calls = mutableListOf<String>()
        val source = source(
            provider("fallback", 100, calls, ProviderResult.Hit(plain("fallback"))),
            provider("preferred", 10, calls, ProviderResult.Hit(wordTtml("preferred"))),
        )

        val result = source.resolveLyrics(request) as RemoteLyricsResolution.Found

        assertEquals("preferred", result.selection.source.id)
        assertEquals(listOf("preferred"), calls)
        assertEquals(RemoteLyricsQuality.WORD_SYNCED, result.selection.quality)
    }

    @Test
    fun `Spicy Lyrics relaying Apple Music ranks in Apple Music's place`() = runBlocking {
        val relayed = wordTtml("relayed").copy(attribution = LyricsAttribution("Spicy Lyrics", originName = "Apple Music"))
        val source = source(
            provider("spicy_lyrics", 1, result = ProviderResult.Hit(relayed)),
            provider("amll_ttml_db", 2, result = ProviderResult.Hit(wordTtml("amll"))),
            provider("apple_music", 3, result = ProviderResult.Hit(wordTtml("apple"))),
        )

        val result = source.resolveLyrics(request) as RemoteLyricsResolution.Found

        assertEquals("amll_ttml_db", result.selection.source.id)
    }

    @Test
    fun `Spicy Lyrics relaying Spotify ranks in the Spotify slot's place`() = runBlocking {
        val relayed = wordTtml("relayed").copy(attribution = LyricsAttribution("Spicy Lyrics", originName = "Spotify"))
        val source = source(
            provider("spicy_lyrics", 1, result = ProviderResult.Hit(relayed)),
            provider("amll_ttml_db", 2, result = ProviderResult.Hit(wordTtml("amll"))),
            slot("spotify", 3),
        )

        val result = source.resolveLyrics(request) as RemoteLyricsResolution.Found

        assertEquals("amll_ttml_db", result.selection.source.id)
    }

    @Test
    fun `relayed Spotify lyrics beat a source ranked below the Spotify slot`() = runBlocking {
        val relayed = wordTtml("relayed").copy(attribution = LyricsAttribution("Spicy Lyrics", originName = "Spotify"))
        val source = source(
            provider("spicy_lyrics", 1, result = ProviderResult.Hit(relayed)),
            slot("spotify", 2),
            provider("amll_ttml_db", 3, result = ProviderResult.Hit(wordTtml("amll"))),
        )

        val result = source.resolveLyrics(request) as RemoteLyricsResolution.Found

        assertEquals("spicy_lyrics", result.selection.source.id)
    }

    @Test
    fun `LRCMux relaying KuGou ranks in Kugou's place`() = runBlocking {
        val relayed = wordTtml("relayed").copy(attribution = LyricsAttribution("LRCMux", originName = "KuGou"))
        val source = source(
            provider("kugou", 1, result = ProviderResult.Miss),
            provider("amll_ttml_db", 2, result = ProviderResult.Hit(wordTtml("amll"))),
            provider("lrcmux", 3, result = ProviderResult.Hit(relayed)),
        )

        val result = source.resolveLyrics(request) as RemoteLyricsResolution.Found

        assertEquals("lrcmux", result.selection.source.id)
    }

    @Test
    fun `LRCMux relaying a switched-off source ranks after every source`() = runBlocking {
        val relayed = wordTtml("relayed").copy(attribution = LyricsAttribution("LRCMux", originName = "KuGou"))
        val source = source(
            provider("lrcmux", 1, result = ProviderResult.Hit(relayed)),
            provider("kugou", 2, result = ProviderResult.Miss),
            provider("amll_ttml_db", 3, result = ProviderResult.Hit(wordTtml("amll"))),
        )

        val result = source.resolveLyrics(request, RemoteLyricsPolicy(disabledSourceIds = setOf("kugou"))) as RemoteLyricsResolution.Found

        assertEquals("amll_ttml_db", result.selection.source.id)
    }

    @Test
    fun `LRCMux relaying a source it doesn't name keeps its own place`() = runBlocking {
        val relayed = wordTtml("relayed").copy(attribution = LyricsAttribution("LRCMux", originName = "Somewhere new"))
        val source = source(
            provider("lrcmux", 1, result = ProviderResult.Hit(relayed)),
            provider("amll_ttml_db", 2, result = ProviderResult.Hit(wordTtml("amll"))),
        )

        val result = source.resolveLyrics(request) as RemoteLyricsResolution.Found

        assertEquals("lrcmux", result.selection.source.id)
    }

    @Test
    fun `a slot is never asked and stays out of the attempts`() = runBlocking {
        val source = source(
            slot("apple_music", 1),
            provider("amll_ttml_db", 2, result = ProviderResult.Hit(wordTtml("amll"))),
        )

        val result = source.resolveLyrics(request) as RemoteLyricsResolution.Found

        assertEquals("amll_ttml_db", result.selection.source.id)
        assertEquals(listOf("amll_ttml_db"), result.attempts.map { it.sourceId })
    }

    @Test
    fun `a switched off slot sends its relayed lyrics after every source`() = runBlocking {
        val relayed = wordTtml("relayed").copy(attribution = LyricsAttribution("Spicy Lyrics", originName = "Apple Music"))
        val source = source(
            slot("apple_music", 1),
            provider("spicy_lyrics", 2, result = ProviderResult.Hit(relayed)),
            provider("lrclib", 3, result = ProviderResult.Hit(wordTtml("lrclib"))),
        )

        val result = source.resolveLyrics(
            request,
            RemoteLyricsPolicy(disabledSourceIds = setOf("apple_music")),
        ) as RemoteLyricsResolution.Found

        assertEquals("lrclib", result.selection.source.id)
    }

    @Test
    fun `Spicy Lyrics community syncs keep its place`() = runBlocking {
        val community = wordTtml("community").copy(attribution = LyricsAttribution("Spicy Lyrics", originName = "Spicy Lyrics Community"))
        val source = source(
            provider("spicy_lyrics", 1, result = ProviderResult.Hit(community)),
            provider("amll_ttml_db", 2, result = ProviderResult.Hit(wordTtml("amll"))),
        )

        val result = source.resolveLyrics(request) as RemoteLyricsResolution.Found

        assertEquals("spicy_lyrics", result.selection.source.id)
    }

    @Test
    fun `RMM Revival's Apple Music copy ranks with Apple Music, behind Spicy Lyrics' copy`() = runBlocking {
        fun apple(by: String) = wordTtml(by).copy(attribution = LyricsAttribution(by, originName = "Apple Music"))
        // RMM Revival ordered above Apple Music: its copy must still not jump Spicy Lyrics' own.
        val source = source(
            provider("spicy_lyrics", 1, result = ProviderResult.Hit(apple("Spicy Lyrics"))),
            provider("amll_ttml_db", 2, result = ProviderResult.Miss),
            provider("rmm_revival", 3, result = ProviderResult.Hit(apple("RMM Revival"))),
            provider("apple_music", 4, result = ProviderResult.Miss),
        )

        val result = source.resolveLyrics(request) as RemoteLyricsResolution.Found

        assertEquals("spicy_lyrics", result.selection.source.id)
    }

    @Test
    fun `RMM Revival's copy of a Spicy Lyrics sync ranks in Spicy Lyrics' place`() = runBlocking {
        val community = wordTtml("community").copy(attribution = LyricsAttribution("RMM Revival", originName = "Spicy Lyrics Community"))
        val slowRmm = object : RemoteLyricsProvider {
            override val descriptor = descriptor("rmm_revival", 3)
            override suspend fun fetch(request: LyricsLookupRequest): ProviderResult {
                kotlinx.coroutines.delay(100)
                return ProviderResult.Hit(community)
            }
        }
        // No Spotify match for Spicy Lyrics; AMLL answers first, but the relay is waited for.
        val source = source(
            provider("spicy_lyrics", 1, result = ProviderResult.NeedsMatch),
            provider("amll_ttml_db", 2, result = ProviderResult.Hit(wordTtml("amll"))),
            slowRmm,
        )

        val result = source.resolveLyrics(request) as RemoteLyricsResolution.Found

        assertEquals("rmm_revival", result.selection.source.id)
    }

    @Test
    fun `a relay is not waited for once Spicy Lyrics has answered`() = runBlocking {
        val relayed = wordTtml("relayed").copy(attribution = LyricsAttribution("Spicy Lyrics", originName = "Apple Music"))
        val source = source(
            provider("spicy_lyrics", 1, result = ProviderResult.Hit(relayed)),
            provider("amll_ttml_db", 2, result = ProviderResult.Hit(wordTtml("amll"))),
            hanging("rmm_revival", 3),
            provider("apple_music", 4, result = ProviderResult.Miss),
        )

        val result = withTimeout(2_000) { source.resolveLyrics(request) } as RemoteLyricsResolution.Found

        assertEquals("amll_ttml_db", result.selection.source.id)
    }

    @Test
    fun `explicit source order overrides defaults`() = runBlocking {
        val calls = mutableListOf<String>()
        val source = source(
            provider("a", 10, calls, ProviderResult.Hit(wordTtml("a"))),
            provider("b", 100, calls, ProviderResult.Hit(wordTtml("b"))),
        )

        val result = source.resolveLyrics(
            request,
            RemoteLyricsPolicy(sourceOrder = listOf("b", "a")),
        ) as RemoteLyricsResolution.Found

        assertEquals("b", result.selection.source.id)
        assertEquals(listOf("b"), calls)
    }

    @Test
    fun `disabled provider is recorded and never called`() = runBlocking {
        val calls = mutableListOf<String>()
        val source = source(
            provider("disabled", 1, calls, ProviderResult.Hit(wordTtml("bad"))),
            provider("enabled", 2, calls, ProviderResult.Hit(wordTtml("good"))),
        )

        val result = source.resolveLyrics(
            request,
            RemoteLyricsPolicy(disabledSourceIds = setOf("disabled")),
        ) as RemoteLyricsResolution.Found

        assertEquals("enabled", result.selection.source.id)
        assertEquals(listOf("enabled"), calls)
        assertEquals(ProviderAttemptOutcome.DISABLED, result.attempts.first().outcome)
    }

    @Test
    fun `falls back after provider miss`() = runBlocking {
        val source = source(
            provider("missing", 10, result = ProviderResult.Miss),
            provider("found", 100, result = ProviderResult.Hit(synced("line"))),
        )

        val result = source.resolveLyrics(request) as RemoteLyricsResolution.Found

        assertEquals("found", result.selection.source.id)
        assertEquals(
            listOf(ProviderAttemptOutcome.MISS, ProviderAttemptOutcome.HIT),
            result.attempts.map(ProviderAttempt::outcome),
        )
    }

    @Test
    fun `later richer result replaces earlier plain result`() = runBlocking {
        val source = source(
            provider("plain", 10, result = ProviderResult.Hit(plain("words"))),
            provider("line", 20, result = ProviderResult.Hit(synced("line"))),
            provider("word", 30, result = ProviderResult.Hit(wordTtml("ttml"))),
        )

        val result = source.resolveLyrics(request) as RemoteLyricsResolution.Found

        assertEquals("word", result.selection.source.id)
        assertEquals(RemoteLyricsQuality.WORD_SYNCED, result.selection.quality)
        assertEquals(3, result.attempts.size)
    }

    @Test
    fun `equal quality keeps earlier source`() = runBlocking {
        val source = source(
            provider("first", 10, result = ProviderResult.Hit(synced("first"))),
            provider("second", 20, result = ProviderResult.Hit(synced("second"))),
        )

        val result = source.resolveLyrics(request) as RemoteLyricsResolution.Found

        assertEquals("first", result.selection.source.id)
        assertEquals("[00:01.00]first", result.selection.payload.syncedLyrics)
    }

    @Test
    fun `word synced result stops later requests`() = runBlocking {
        val calls = mutableListOf<String>()
        val source = source(
            provider("word", 10, calls, ProviderResult.Hit(wordTtml("word"))),
            provider("unused", 20, calls, ProviderResult.Hit(wordTtml("unused"))),
        )

        source.resolveLyrics(request)

        assertEquals(listOf("word"), calls)
    }

    @Test
    fun `empty hit is a miss and fallback continues`() = runBlocking {
        val source = source(
            provider("broken", 10, result = ProviderResult.Hit(RemoteLyricsPayload())),
            provider("found", 20, result = ProviderResult.Hit(plain("lyrics"))),
        )

        val result = source.resolveLyrics(request) as RemoteLyricsResolution.Found

        assertEquals("found", result.selection.source.id)
        assertEquals(ProviderAttemptOutcome.MISS, result.attempts.first().outcome)
    }

    @Test
    fun `malformed ttml falls back to valid synced representation in same hit`() = runBlocking {
        val source = source(
            provider(
                "mixed",
                10,
                result = ProviderResult.Hit(
                    RemoteLyricsPayload(
                        ttmlLyrics = "not xml",
                        syncedLyrics = "[00:01.00]usable line",
                    )
                ),
            )
        )

        val result = source.resolveLyrics(request) as RemoteLyricsResolution.Found

        assertEquals("mixed", result.selection.source.id)
        assertEquals(RemoteLyricsQuality.LINE_SYNCED, result.selection.quality)
    }

    @Test
    fun `static ttml is measured as plain and does not block richer fallback`() = runBlocking {
        val source = source(
            provider("static", 10, result = ProviderResult.Hit(staticTtml("plain"))),
            provider("line", 20, result = ProviderResult.Hit(synced("[00:01.00]line"))),
        )

        val result = source.resolveLyrics(request) as RemoteLyricsResolution.Found

        assertEquals("line", result.selection.source.id)
        assertEquals(RemoteLyricsQuality.LINE_SYNCED, result.selection.quality)
    }

    @Test
    fun `empty ttml is rejected instead of stopping source chain`() = runBlocking {
        val source = source(
            provider("empty-ttml", 10, result = ProviderResult.Hit(ttml("<tt><body><div /></body></tt>"))),
            provider("plain", 20, result = ProviderResult.Hit(plain("fallback"))),
        )

        val result = source.resolveLyrics(request) as RemoteLyricsResolution.Found

        assertEquals("plain", result.selection.source.id)
        assertEquals(ProviderAttemptOutcome.MALFORMED_HIT, result.attempts.first().outcome)
    }

    @Test
    fun `network failure does not hide later success`() = runBlocking {
        val source = source(
            provider(
                "offline",
                10,
                result = ProviderResult.Unavailable(ProviderFailureCategory.NETWORK),
            ),
            provider("found", 20, result = ProviderResult.Hit(plain("lyrics"))),
        )

        val result = source.resolveLyrics(request) as RemoteLyricsResolution.Found

        assertEquals("found", result.selection.source.id)
    }

    @Test
    fun `instrumental notes and empty records resolve not found, not unavailable`() = runBlocking {
        val result = source(
            provider("note", 10, result = ProviderResult.Hit(RemoteLyricsPayload(syncedLyrics = "[00:00.00] ♪ Instrumental ♪"))),
            provider("bracket", 20, result = ProviderResult.Hit(RemoteLyricsPayload(syncedLyrics = "[00:01.00][Instrumental]\n[01:00.00]♪"))),
            provider("empty", 30, result = ProviderResult.Hit(RemoteLyricsPayload())),
        ).resolveLyrics(request)

        assertTrue(result is RemoteLyricsResolution.NotFound)
    }

    @Test
    fun `all authoritative misses resolve not found`() = runBlocking {
        val result = source(
            provider("one", 10, result = ProviderResult.Miss),
            provider("two", 20, result = ProviderResult.Miss),
        ).resolveLyrics(request)

        assertTrue(result is RemoteLyricsResolution.NotFound)
    }

    @Test
    fun `unreachable source without hit resolves unavailable`() = runBlocking {
        val result = source(
            provider("missing", 10, result = ProviderResult.Miss),
            provider(
                "offline",
                20,
                result = ProviderResult.Unavailable(ProviderFailureCategory.NETWORK),
            ),
        ).resolveLyrics(request)

        assertTrue(result is RemoteLyricsResolution.Unavailable)
    }

    @Test
    fun `cooldown is remembered and suppresses later call`() = runBlocking {
        val now = Instant.parse("2026-09-21T12:00:00Z")
        val retryAt = now.plusSeconds(120)
        val calls = mutableListOf<String>()
        val cooling = provider(
            "limited",
            10,
            calls,
            ProviderResult.CoolingDown(retryAt, "HTTP 429: window exhausted"),
        )
        val source = source(cooling)

        val first = source.resolveLyrics(request, now = now)
        val second = source.resolveLyrics(request, now = now.plusSeconds(30))

        assertTrue(first is RemoteLyricsResolution.Unavailable)
        assertTrue(second is RemoteLyricsResolution.Unavailable)
        assertEquals(listOf("limited"), calls)
        assertEquals(
            ProviderAttemptOutcome.COOLING_DOWN,
            second.attempts.single().outcome,
        )
        // Why it rests is said both when refused and while it rests, not asked.
        assertEquals("HTTP 429: window exhausted", first.attempts.single().message)
        assertEquals("HTTP 429: window exhausted", second.attempts.single().message)
    }

    @Test
    fun `expired cooldown permits another call`() = runBlocking {
        val now = Instant.parse("2026-09-21T12:00:00Z")
        val calls = mutableListOf<String>()
        val tracker = ProviderCooldownTracker()
        tracker.record("source", now.plusSeconds(10))
        val source = RemoteLyricsSource(
            setOf(provider("source", 10, calls, ProviderResult.Miss)),
            tracker,
        )

        val result = source.resolveLyrics(request, now = now.plusSeconds(11))

        assertTrue(result is RemoteLyricsResolution.NotFound)
        assertEquals(listOf("source"), calls)
    }

    @Test
    fun `cancellation is never converted into provider failure`() = runBlocking {
        val cancelling = object : RemoteLyricsProvider {
            override val descriptor = descriptor("cancel", 10)

            override suspend fun fetch(request: LyricsLookupRequest): ProviderResult {
                throw CancellationException("track changed")
            }
        }

        try {
            source(cancelling).resolveLyrics(request)
            fail("Expected cancellation")
        } catch (expected: CancellationException) {
            assertEquals("track changed", expected.message)
        }
    }

    @Test
    fun `legacy not found exception remains isolated during migration`() = runBlocking {
        val legacy = throwingProvider("legacy", 10, NotFoundException("missing"))
        val result = source(
            legacy,
            provider("found", 20, result = ProviderResult.Hit(plain("lyrics"))),
        ).resolveLyrics(request)

        assertTrue(result is RemoteLyricsResolution.Found)
    }

    @Test
    fun `slow lead does not hold back the rest`() = runBlocking {
        // The lead only answers once "word" has been asked, so this deadlocks unless the rest
        // fan out while the lead is still out.
        val wordAsked = CompletableDeferred<Unit>()
        val source = source(
            object : RemoteLyricsProvider {
                override val descriptor = descriptor("lead", 10)
                override suspend fun fetch(request: LyricsLookupRequest): ProviderResult {
                    wordAsked.await()
                    return ProviderResult.Miss
                }
            },
            object : RemoteLyricsProvider {
                override val descriptor = descriptor("word", 20)
                override suspend fun fetch(request: LyricsLookupRequest): ProviderResult {
                    wordAsked.complete(Unit)
                    return ProviderResult.Hit(wordTtml("word"))
                }
            },
        )

        val result = withTimeout(5_000) { source.resolveLyrics(request) } as RemoteLyricsResolution.Found

        assertEquals("word", result.selection.source.id)
    }

    @Test
    fun `best answer so far is reported as each source lands`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val source = source(
            provider("miss", 10, result = ProviderResult.Miss),
            provider("line", 20, result = ProviderResult.Hit(synced("[00:01.00]line"))),
            object : RemoteLyricsProvider {
                override val descriptor = descriptor("word", 30)
                override suspend fun fetch(request: LyricsLookupRequest): ProviderResult {
                    gate.await()
                    return ProviderResult.Hit(wordTtml("word"))
                }
            },
        )
        val shown = mutableListOf<String>()

        val result = source.resolveLyrics(request) { update ->
            (update as? RemoteLyricsResolution.Found)?.selection?.source?.id?.let { id ->
                if (shown.lastOrNull() != id) shown += id
            }
            gate.complete(Unit)
        } as RemoteLyricsResolution.Found

        assertEquals(listOf("line", "word"), shown)
        assertEquals("word", result.selection.source.id)
    }

    @Test
    fun `known results are reused instead of asked again`() = runBlocking {
        val calls = mutableListOf<String>()
        val source = source(
            provider("one", 10, calls, ProviderResult.Miss),
            provider("two", 20, calls, ProviderResult.Hit(synced("[00:01.00]line"))),
        )
        val known = mutableMapOf<String, ProviderResult>()

        source.resolveLyrics(request, known = known)
        val reordered = source.resolveLyrics(request, RemoteLyricsPolicy(sourceOrder = listOf("two", "one")), known = known)

        assertEquals(listOf("one", "two"), calls)
        assertEquals("two", (reordered as RemoteLyricsResolution.Found).selection.source.id)
    }

    @Test
    fun `word hit cancels sources ranked below it`() = runBlocking {
        val source = source(
            provider("miss", 10, result = ProviderResult.Miss),
            provider("word", 20, result = ProviderResult.Hit(wordTtml("word"))),
            hanging("slow", 30),
        )

        val result = withTimeout(5_000) { source.resolveLyrics(request) } as RemoteLyricsResolution.Found

        assertEquals("word", result.selection.source.id)
        assertEquals(ProviderAttemptOutcome.SKIPPED, result.attempts.last().outcome)
    }

    @Test(expected = NetworkErrorException::class)
    fun `compatibility getLyrics reports unavailable`() = runBlocking {
        source(
            provider(
                "offline",
                10,
                result = ProviderResult.Unavailable(ProviderFailureCategory.NETWORK),
            )
        ).getLyrics(request)
        Unit
    }

    @Test(expected = NotFoundException::class)
    fun `compatibility getLyrics reports not found`() = runBlocking {
        source(provider("missing", 10, result = ProviderResult.Miss)).getLyrics(request)
        Unit
    }

    private fun source(vararg providers: RemoteLyricsProvider) = RemoteLyricsSource(
        providers.toSet(),
        ProviderCooldownTracker(),
    )

    private fun provider(
        id: String,
        priority: Int,
        calls: MutableList<String>? = null,
        result: ProviderResult,
    ) = object : RemoteLyricsProvider {
        override val descriptor = descriptor(id, priority)

        override suspend fun fetch(request: LyricsLookupRequest): ProviderResult {
            calls?.add(id)
            return result
        }
    }

    private fun slot(id: String, priority: Int) =
        com.tx24.spicyplayer.network.data.providers.RelayedOriginSlot(descriptor(id, priority).copy(rankOnly = true))

    private fun hanging(id: String, priority: Int) = object : RemoteLyricsProvider {
        override val descriptor = descriptor(id, priority)
        override suspend fun fetch(request: LyricsLookupRequest): ProviderResult = awaitCancellation()
    }

    private fun throwingProvider(id: String, priority: Int, error: Exception) =
        object : RemoteLyricsProvider {
            override val descriptor = descriptor(id, priority)

            override suspend fun fetch(request: LyricsLookupRequest): ProviderResult = throw error
        }

    private fun descriptor(id: String, priority: Int) = LyricsSourceDescriptor(
        id = id,
        displayName = id,
        defaultPriority = priority,
        capabilities = setOf(LyricsCapability.PLAIN_TEXT),
    )

    private fun plain(value: String) = RemoteLyricsPayload(plainLyrics = value)
    private fun synced(value: String) = RemoteLyricsPayload(syncedLyrics = "[00:01.00]$value")
    private fun ttml(value: String) = RemoteLyricsPayload(ttmlLyrics = value)
    private fun wordTtml(value: String) = ttml(
        """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word"><body><div><p begin="1s" end="3s"><span begin="1s" end="2s">$value</span></p></div></body></tt>"""
    )
    private fun staticTtml(value: String) = ttml(
        """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p>$value</p></div></body></tt>"""
    )
}
