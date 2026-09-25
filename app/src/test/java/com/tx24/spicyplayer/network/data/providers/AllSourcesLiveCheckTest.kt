package com.tx24.spicyplayer.network.data.providers

import com.google.gson.Gson
import com.tx24.spicyplayer.BuildConfig
import com.tx24.spicyplayer.lyrics.NextLyricsBackend
import com.tx24.spicyplayer.network.data.LyricsLookupRequest
import com.tx24.spicyplayer.network.data.ProviderResult
import com.tx24.spicyplayer.network.data.measuredQuality
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Asks every source the app uses for a few well-known songs in different languages and prints
 * what each answered. Fails when a source answered none of them. Live network, so it skips
 * unless `RUN_ALL_SOURCES_TEST=1`; run it with `--info` to see the table.
 */
class AllSourcesLiveCheckTest {
    @Test
    fun `every source answers at least one well known song`() = runBlocking {
        assumeTrue(System.getenv("RUN_ALL_SOURCES_TEST") == "1")
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
        val key = System.getenv("SPICY_LYRICS_CLIENT_KEY") ?: BuildConfig.SPICY_LYRICS_CLIENT_KEY
        val providers = NextLyricsBackend.createProviders(client, Gson(), key)

        val rows = providers.map { provider ->
            async {
                provider.descriptor.id to SONGS.map { song ->
                    val started = System.nanoTime()
                    val result = runCatching { withTimeoutOrNull(30_000) { provider.fetch(song) } }
                        .getOrElse { ProviderResult.Unavailable(com.tx24.spicyplayer.network.data.ProviderFailureCategory.UNKNOWN, it.toString()) }
                    val ms = (System.nanoTime() - started) / 1_000_000
                    result to ms
                }
            }
        }.awaitAll()

        val silent = mutableListOf<String>()
        rows.sortedBy { it.first }.forEach { (id, results) ->
            println("── $id")
            results.forEachIndexed { i, (result, ms) ->
                val text = when (result) {
                    is ProviderResult.Hit -> "HIT ${result.payload.measuredQuality()}"
                    null -> "TIMEOUT"
                    is ProviderResult.Unavailable -> "UNAVAILABLE ${result.category} ${result.message.orEmpty().take(120)}"
                    else -> result.toString()
                }
                println("   %-40s %6d ms  %s".format(SONGS[i].title.take(40), ms, text))
            }
            if (results.none { it.first is ProviderResult.Hit }) silent += id
        }
        assertTrue("No hits at all from: $silent", silent.isEmpty())
    }

    private companion object {
        val SONGS = listOf(
            LyricsLookupRequest("Rick Astley", "Never Gonna Give You Up", "Whenever You Need Somebody", 213),
            LyricsLookupRequest("The Weeknd", "Blinding Lights", "After Hours", 200),
            LyricsLookupRequest("周杰伦", "晴天", "叶惠美", 269),
            LyricsLookupRequest("米津玄師", "Lemon", "STRAY SHEEP", 255),
            LyricsLookupRequest("BTS", "Dynamite", "BE", 199),
            // Unison is a small community catalogue; this is one it has.
            LyricsLookupRequest("Michael Jackson", "Love Never Felt So Good", "XSCAPE", 246),
        )
    }
}
