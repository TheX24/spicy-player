package com.tx24.spicyplayer.network.data.providers

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.tx24.spicyplayer.network.data.*
import com.tx24.spicyplayer.network.data.spotify.SpotifyTrackMatcher
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class NetEaseLyricsProvider @Inject constructor(private val client: OkHttpClient, private val gson: Gson) : RemoteLyricsProvider {
    override val descriptor = LyricsSourceDescriptor(
        "netease", "NetEase", 60,
        setOf(LyricsCapability.WORD_SYNC, LyricsCapability.LINE_SYNC, LyricsCapability.TRANSLITERATION),
        releaseChannel = SourceReleaseChannel.EXTENDED, defaultEnabled = false,
    )

    override suspend fun fetch(request: LyricsLookupRequest): ProviderResult = try {
        val searchUrl = "$BASE/cloudsearch/pc".toHttpUrl().newBuilder()
            .addQueryParameter("s", "${request.artist} ${request.title}").addQueryParameter("type", "1")
            .addQueryParameter("offset", "0").addQueryParameter("limit", "8").build()
        val search = getJson(searchUrl)
        val candidates = search.getAsJsonObject("result")?.getAsJsonArray("songs") ?: return ProviderResult.Miss
        val song = candidates.map { it.asJsonObject }.firstOrNull { item ->
            val name = item.get("name")?.asString.orEmpty()
            val artists = (item.getAsJsonArray("ar") ?: item.getAsJsonArray("artists"))?.joinToString(" ") { it.asJsonObject.get("name").asString }.orEmpty()
            val duration = (item.get("dt") ?: item.get("duration"))?.asLong?.div(1000) ?: 0
            SpotifyTrackMatcher.normalize(name) == SpotifyTrackMatcher.normalize(request.title) &&
                SpotifyTrackMatcher.normalize(artists).contains(SpotifyTrackMatcher.normalize(request.artist)) &&
                (duration == 0L || kotlin.math.abs(duration - request.durationSeconds) <= 8)
        } ?: return ProviderResult.Miss
        val id = song.get("id").asString
        val lyricUrl = "$BASE/song/lyric".toHttpUrl().newBuilder()
            .addQueryParameter("os", "pc").addQueryParameter("id", id)
            .addQueryParameter("lv", "-1").addQueryParameter("kv", "-1")
            .addQueryParameter("tv", "-1").addQueryParameter("yv", "-1").addQueryParameter("rv", "-1").build()
        val data = getJson(lyricUrl)
        val yrc = data.getAsJsonObject("yrc")?.get("lyric")?.asString
        if (!yrc.isNullOrBlank() && !yrc.contains("纯音乐，请欣赏")) {
            YrcToTtml.convert(yrc)?.let { return ProviderResult.Hit(RemoteLyricsPayload(ttmlLyrics = it)) }
        }
        val lrc = data.getAsJsonObject("lrc")?.get("lyric")?.asString
        if (!lrc.isNullOrBlank()) ProviderResult.Hit(RemoteLyricsPayload(syncedLyrics = lrc)) else ProviderResult.Miss
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (error: IOException) { ProviderResult.Unavailable(ProviderFailureCategory.NETWORK, error.message, true) }
      catch (error: Exception) { ProviderResult.Unavailable(ProviderFailureCategory.MALFORMED_RESPONSE, error.message) }

    private suspend fun getJson(url: okhttp3.HttpUrl): JsonObject {
        val request = Request.Builder().url(url).header("Referer", "https://music.163.com").get().build()
        return client.newCall(request).awaitResponse().use { response ->
            if (response.code == 404) return@use JsonObject()
            if (!response.isSuccessful) throw IOException("NetEase HTTP ${response.code}")
            gson.fromJson(response.body?.string(), JsonObject::class.java)
        }
    }

    private companion object { const val BASE = "https://music.163.com/api" }
}

internal object YrcToTtml {
    private val line = Regex("^\\[(\\d+),(\\d+)](.*)$")
    private val word = Regex("\\((\\d+),(\\d+),[^)]*\\)([^()]*)")
    fun convert(yrc: String): String? {
        val paragraphs = yrc.lineSequence().mapNotNull { raw ->
            val match = line.matchEntire(raw.trim()) ?: return@mapNotNull null
            val start = match.groupValues[1].toLong(); val duration = match.groupValues[2].toLong()
            val spans = word.findAll(match.groupValues[3]).mapNotNull { token ->
                val offset = token.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val length = token.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                val text = token.groupValues[3]; if (text.isEmpty()) return@mapNotNull null
                "<span begin=\"${sec(start + offset)}\" end=\"${sec(start + offset + length)}\">${xml(text)}</span>"
            }.toList()
            if (spans.isEmpty()) null else "<p begin=\"${sec(start)}\" end=\"${sec(start + duration)}\">${spans.joinToString("")}</p>"
        }.toList()
        if (paragraphs.isEmpty()) return null
        return """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="word"><body><div>${paragraphs.joinToString("")}</div></body></tt>"""
    }
    private fun sec(ms: Long) = "${"%.3f".format(Locale.ROOT, ms / 1000.0)}s"
    private fun xml(s: String) = s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
}
