package com.tx24.spicyplayer.network.data

import com.tx24.spicyplayer.lyrics.spicy.models.LineRole
import com.tx24.spicyplayer.lyrics.spicy.parser.TtmlLyricsParser
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsBlendsTest {
    private val request = LyricsLookupRequest("Nobody", "Made Up", "", 60)

    // Invented lyrics. The donor spells some words its own way and runs on a clock 0.12s later.
    private val lines = listOf(
        "Walking down the river road",
        "Counting every stone I know",
        "Hey, hey, the water's cold",
        "I'll carry you, I'll carry you home",
        "Walking down the river road",
        "Counting every stone I know",
        "Somewhere the lanterns glow",
        "I'll carry you, I'll carry you home",
    )

    private val baseLrc = lines.mapIndexed { i, text -> "[00:${"%02d".format(Locale.ROOT, 10 + i * 5)}.00]$text" }.joinToString("\n")

    /** Word-timed TTML as our NetEase converter writes it: one span per word, spaces between. */
    private val donorTtml = buildString {
        append("""<tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="word"><body><div>""")
        lines.forEachIndexed { i, text ->
            val start = 10.12 + i * 5
            val spelled = text.replace("I'll", "Ill").replace("water's", "waters")
            val words = spelled.split(' ')
            val step = 3.6 / words.size
            append("<p begin=\"${t(start)}\" end=\"${t(start + 3.6)}\">")
            words.forEachIndexed { w, word ->
                append("<span begin=\"${t(start + w * step)}\" end=\"${t(start + (w + 1) * step)}\">$word</span>")
                if (w < words.lastIndex) append(' ')
            }
            append("</p>")
        }
        append("</div></body></tt>")
    }

    @Test
    fun `a blend sits just above its highest-ranked donor`() {
        val order = listOf("spicy", "netease", "kugou", "qq_music", "lrclib")
        val chain = LyricsBlends.chain(order, LyricsBlends.live(order.toSet(), LyricsBlends.ALL.map { it.id }.toSet()))
        assertEquals(
            listOf("spicy", "blend_netease_kugou", "blend_netease_qq", "blend_netease", "netease",
                "blend_kugou", "kugou", "blend_qq", "qq_music", "lrclib"),
            chain,
        )
        // The three-ways go home to whichever donor ranks first, and need both donors switched on.
        val onlyNetEase = LyricsBlends.live(setOf("spicy", "netease"), LyricsBlends.ALL.map { it.id }.toSet())
        assertEquals(listOf("blend_netease"), onlyNetEase.map { it.id })
    }

    @Test
    fun `a line-synced base gets the donor's word timing in its own words`() = runBlocking {
        val result = source().resolveLyrics(request, RemoteLyricsPolicy(enabledBlendIds = setOf("blend_netease")))
            as RemoteLyricsResolution.Found
        assertEquals("blend_netease", result.selection.source.id)
        assertEquals(RemoteLyricsQuality.WORD_SYNCED, result.selection.quality)
        assertEquals("Spicy + NetEase", result.selection.payload.attribution?.originName)

        val parsed = TtmlLyricsParser.parse(result.selection.payload.ttmlLyrics!!.byteInputStream())
        val leads = parsed.lines.filter { it.role == LineRole.LEAD }
        assertEquals(lines.size, leads.size)
        // The base's spelling, the donor's clock.
        val carry = leads[3]
        assertEquals("I'll carry you, I'll carry you home", carry.words.joinToString(" ") { it.text })
        assertEquals(25_120L, carry.startMs)
        assertEquals(25_120L, carry.words.first().startMs)
        assertTrue(carry.words.zipWithNext().all { (a, b) -> a.startMs < b.startMs })
    }

    @Test
    fun `without the blend the donor wins on timing alone`() = runBlocking {
        val result = source().resolveLyrics(request, RemoteLyricsPolicy()) as RemoteLyricsResolution.Found
        assertEquals("netease", result.selection.source.id)
    }

    @Test
    fun `a word-synced answer ranked above stands the blend down`() = runBlocking {
        val result = source(baseWordSynced = true)
            .resolveLyrics(request, RemoteLyricsPolicy(enabledBlendIds = setOf("blend_netease"))) as RemoteLyricsResolution.Found
        assertEquals("spicy", result.selection.source.id)
        assertEquals(ProviderAttemptOutcome.SKIPPED, result.attempts.first { it.sourceId == "blend_netease" }.outcome)
    }

    private fun source(baseWordSynced: Boolean = false) = RemoteLyricsSource(
        setOf(
            provider("spicy", 1, "Spicy", ProviderResult.Hit(
                if (baseWordSynced) RemoteLyricsPayload(ttmlLyrics = donorTtml) else RemoteLyricsPayload(syncedLyrics = baseLrc),
            )),
            provider("netease", 60, "NetEase", ProviderResult.Hit(RemoteLyricsPayload(ttmlLyrics = donorTtml))),
            provider("lrclib", 100, "LRCLIB", ProviderResult.Miss),
        ),
        ProviderCooldownTracker(),
    )

    private fun provider(id: String, priority: Int, name: String, result: ProviderResult) = object : RemoteLyricsProvider {
        override val descriptor = LyricsSourceDescriptor(id, name, priority, setOf(LyricsCapability.WORD_SYNC))
        override suspend fun fetch(request: LyricsLookupRequest) = result
    }

    private fun t(seconds: Double) = "%.3fs".format(Locale.ROOT, seconds)
}
