package com.tx24.spicyplayer.network.data.providers

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.tx24.spicyplayer.network.data.*
import com.tx24.spicyplayer.network.data.spotify.SpotifyTrackMatcher
import java.io.IOException
import java.net.URLDecoder
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Apple Music TTML exposed through AMLL's unauthenticated Apple lookup bridge. */
@Singleton
class AppleMusicLyricsProvider @Inject constructor(
    private val client: OkHttpClient,
    private val gson: Gson,
) : RemoteLyricsProvider {
    override val descriptor = LyricsSourceDescriptor(
        "apple_music", "Apple Music", 30,
        setOf(LyricsCapability.WORD_SYNC, LyricsCapability.LINE_SYNC),
        upstreamFamily = "apple_music",
        releaseChannel = SourceReleaseChannel.EXTENDED,
    )

    override suspend fun fetch(request: LyricsLookupRequest): ProviderResult {
        return try {
            val search = "$BASE/v1/lyrics/search".toHttpUrl().newBuilder()
                .addQueryParameter("trackName", request.title)
                .addQueryParameter("artistName", request.artist).build()
            val root = json(search.toString())
            val items = when {
                root.has("data") && root.get("data").isJsonObject -> root.getAsJsonObject("data").getAsJsonArray("items")
                else -> root.getAsJsonArray("items")
            } ?: return ProviderResult.Miss
            val item = items.mapNotNull { it.takeIf { value -> value.isJsonObject }?.asJsonObject }
                .firstOrNull { candidate -> matches(candidate, request) } ?: return ProviderResult.Miss
            val id = item.get("id")?.asString ?: return ProviderResult.Miss
            val response = json("$BASE/v1/lyrics/get".toHttpUrl().newBuilder().addQueryParameter("id", id).build().toString())
            val data = response.getAsJsonObject("data") ?: response
            val ttml = listOf("lyrics", "ttml", "content").firstNotNullOfOrNull { key ->
                data.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.contains("<tt", true) }
            } ?: return ProviderResult.Miss
            ProviderResult.Hit(RemoteLyricsPayload(ttmlLyrics = ttml))
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (error: IOException) { ProviderResult.Unavailable(ProviderFailureCategory.NETWORK, error.message, true) }
          catch (error: Exception) { ProviderResult.Unavailable(ProviderFailureCategory.MALFORMED_RESPONSE, error.message) }
    }

    private fun matches(item: JsonObject, request: LyricsLookupRequest): Boolean {
        val titles = item.getAsJsonArray("musicNames")?.map { it.asString }
            ?: listOfNotNull(item.get("trackName")?.asString)
        val artists = item.getAsJsonArray("artistNames")?.map { it.asString }
            ?: listOfNotNull(item.get("artistName")?.asString)
        val title = SpotifyTrackMatcher.normalize(request.title)
        val artist = SpotifyTrackMatcher.normalize(request.artist)
        return titles.any { SpotifyTrackMatcher.normalize(it) == title } && artists.any {
            val value = SpotifyTrackMatcher.normalize(it); value == artist || value in artist || artist in value
        }
    }

    private suspend fun json(url: String): JsonObject = client.newCall(Request.Builder().url(url).get().build()).awaitResponse().use {
        if (!it.isSuccessful) throw IOException("Apple bridge HTTP ${it.code}")
        gson.fromJson(it.body?.string(), JsonObject::class.java)
    }

    private companion object { const val BASE = "https://api.amll.dev" }
}

@Singleton
class YouTubeTranscriptLyricsProvider @Inject constructor(
    private val client: OkHttpClient,
    private val gson: Gson,
) : RemoteLyricsProvider {
    override val descriptor = LyricsSourceDescriptor(
        "youtube_transcript", "YouTube transcripts", 140,
        setOf(LyricsCapability.LINE_SYNC, LyricsCapability.PLAIN_TEXT),
        releaseChannel = SourceReleaseChannel.EXPERIMENTAL, defaultEnabled = false,
    )

    override suspend fun fetch(request: LyricsLookupRequest): ProviderResult {
        return try {
            val search = client.newCall(Request.Builder().url("https://www.youtube.com/results".toHttpUrl().newBuilder()
                .addQueryParameter("search_query", "${request.artist} ${request.title} official audio").build()).get().build())
                .awaitResponse().use { if (!it.isSuccessful) throw IOException("YouTube search HTTP ${it.code}"); it.body?.string().orEmpty() }
            val ids = Regex("\\\"videoId\\\":\\\"([A-Za-z0-9_-]{11})\\\"").findAll(search).map { it.groupValues[1] }.distinct().take(8).toList()
            for (id in ids) {
                for ((name, version, ua) in CLIENTS) {
                    val player = player(id, name, version, ua) ?: continue
                    val details = player.getAsJsonObject("videoDetails") ?: continue
                    val duration = details.get("lengthSeconds")?.asInt ?: 0
                    val title = details.get("title")?.asString.orEmpty()
                    val author = details.get("author")?.asString.orEmpty()
                    val titleMatch = SpotifyTrackMatcher.normalize(title).contains(SpotifyTrackMatcher.normalize(request.title))
                    val artistMatch = SpotifyTrackMatcher.normalize("$title $author").contains(SpotifyTrackMatcher.normalize(request.artist))
                    if (!titleMatch || !artistMatch || (duration > 0 && kotlin.math.abs(duration - request.durationSeconds) > 12)) continue
                    val tracks = player.getAsJsonObject("captions")?.getAsJsonObject("playerCaptionsTracklistRenderer")?.getAsJsonArray("captionTracks") ?: continue
                    val track = tracks.map { it.asJsonObject }.sortedBy { if (it.get("kind")?.asString == "asr") 1 else 0 }.firstOrNull() ?: continue
                    val baseUrl = track.get("baseUrl")?.asString ?: continue
                    val captions = captionLrc(baseUrl) ?: continue
                    return ProviderResult.Hit(RemoteLyricsPayload(syncedLyrics = captions))
                }
            }
            ProviderResult.Miss
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (error: IOException) { ProviderResult.Unavailable(ProviderFailureCategory.NETWORK, error.message, true) }
          catch (error: Exception) { ProviderResult.Unavailable(ProviderFailureCategory.MALFORMED_RESPONSE, error.message) }
    }

    private suspend fun player(id: String, clientName: String, version: String, ua: String): JsonObject? {
        val body = """{"context":{"client":{"clientName":"$clientName","clientVersion":"$version","hl":"en","gl":"US"}},"videoId":"$id"}"""
        return client.newCall(Request.Builder().url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("User-Agent", ua).post(body.toRequestBody("application/json".toMediaType())).build()).awaitResponse().use {
            if (!it.isSuccessful) return@use null; gson.fromJson(it.body?.string(), JsonObject::class.java)
        }
    }

    private suspend fun captionLrc(baseUrl: String): String? {
        val url = baseUrl.toHttpUrl().newBuilder().setQueryParameter("fmt", "json3").build()
        val root = client.newCall(Request.Builder().url(url).get().build()).awaitResponse().use {
            if (!it.isSuccessful) return null; gson.fromJson(it.body?.string(), JsonObject::class.java)
        }
        val lines = root.getAsJsonArray("events")?.mapNotNull { raw ->
            val event = raw.asJsonObject; val start = event.get("tStartMs")?.asLong ?: return@mapNotNull null
            val text = event.getAsJsonArray("segs")?.joinToString("") { it.asJsonObject.get("utf8")?.asString.orEmpty() }
                ?.replace("\n", " ")?.trim().orEmpty()
            if (text.isBlank()) null else "[${lrcTime(start)}]$text"
        }.orEmpty()
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun lrcTime(ms: Long): String {
        val totalSeconds = ms / 1000; val minutes = totalSeconds / 60; val seconds = totalSeconds % 60; val hundredths = (ms % 1000) / 10
        return String.format(Locale.ROOT, "%02d:%02d.%02d", minutes, seconds, hundredths)
    }

    private companion object {
        val CLIENTS = listOf(
            Triple("ANDROID", "20.10.38", "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip"),
            Triple("IOS", "20.10.4", "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_0 like Mac OS X)"),
        )
    }
}
