package com.tx24.spicyplayer.network.data.providers

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.tx24.spicyplayer.network.data.*
import com.tx24.spicyplayer.network.data.spotify.SpotifyTrackMatcher
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class RmmRevivalLyricsProvider @Inject constructor(private val client: OkHttpClient, private val gson: Gson) : RemoteLyricsProvider {
    override val descriptor = LyricsSourceDescriptor(
        "rmm_revival", "RMM Revival", 40,
        setOf(LyricsCapability.WORD_SYNC, LyricsCapability.LINE_SYNC, LyricsCapability.PLAIN_TEXT),
        upstreamFamily = "apple_music", releaseChannel = SourceReleaseChannel.EXTENDED, defaultEnabled = false,
    )

    override suspend fun fetch(request: LyricsLookupRequest): ProviderResult = guarded {
        val search = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", "${request.artist} ${request.title}")
            .addQueryParameter("entity", "song").addQueryParameter("limit", "8").build()
        val appleId = client.json(search, gson).getAsJsonArray("results")
            ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
            ?.filter { it.matches(request, "trackName", "artistName") }
            ?.minByOrNull { item -> kotlin.math.abs((item.get("trackTimeMillis")?.asLong ?: 0L) / 1000 - request.durationSeconds) }
            ?.get("trackId")?.asString ?: return@guarded ProviderResult.Miss
        val url = "https://lyrics.rmmreviv.al/lyrics".toHttpUrl().newBuilder().addQueryParameter("id", appleId).build()
        val data = client.json(url, gson)
        payloadOf(data) ?: ProviderResult.Miss
    }
}

@Singleton
class BiniLyricsProvider @Inject constructor(private val client: OkHttpClient, private val gson: Gson) : RemoteLyricsProvider {
    override val descriptor = LyricsSourceDescriptor(
        "bini_lyrics", "BiniLyrics", 50,
        setOf(LyricsCapability.WORD_SYNC, LyricsCapability.LINE_SYNC, LyricsCapability.PLAIN_TEXT),
        upstreamFamily = "apple_music", releaseChannel = SourceReleaseChannel.EXTENDED, defaultEnabled = false,
    )

    override suspend fun fetch(request: LyricsLookupRequest): ProviderResult = guarded {
        val url = "https://lyrics-api.binimum.org/getLyrics".toHttpUrl().newBuilder()
            .addQueryParameter("track", request.title).addQueryParameter("artist", request.artist)
            .addQueryParameter("duration", request.durationSeconds.toString()).build()
        val root = client.json(url, gson)
        val candidates = root.getAsJsonArray("results")
        if (candidates != null) {
            for (element in candidates) {
                val item = element.asJsonObject
                if (!item.matches(request, "track_name", "artist_name")) continue
                payloadOf(item)?.let { return@guarded it }
                val remote = sequenceOf("lyricsUrl", "ttmlUrl", "url").mapNotNull { item.get(it)?.asString }.firstOrNull()
                if (remote != null) {
                    val text = client.text(remote)
                    contentPayload(text)?.let { return@guarded it }
                }
            }
        }
        payloadOf(root) ?: ProviderResult.Miss
    }
}

private suspend fun <T : ProviderResult> guarded(block: suspend () -> T): ProviderResult = try { block() }
catch (cancelled: CancellationException) { throw cancelled }
catch (error: HttpStatusException) {
    when (error.code) {
        404 -> ProviderResult.Miss
        429 -> ProviderResult.CoolingDown(RetryAfterParser.deadline(error.retryAfter))
        else -> ProviderResult.Unavailable(if (error.code in 400..499) ProviderFailureCategory.CLIENT_REQUEST else ProviderFailureCategory.SERVER, error.message, error.code >= 500)
    }
} catch (error: IOException) { ProviderResult.Unavailable(ProviderFailureCategory.NETWORK, error.message, true) }
catch (error: Exception) { ProviderResult.Unavailable(ProviderFailureCategory.MALFORMED_RESPONSE, error.message) }

private class HttpStatusException(val code: Int, val retryAfter: String?) : IOException("HTTP $code")

private suspend fun OkHttpClient.text(url: Any): String {
    val requestUrl = when (url) { is okhttp3.HttpUrl -> url; else -> url.toString().toHttpUrl() }
    return newCall(Request.Builder().url(requestUrl).get().build()).awaitResponse().use { response ->
        if (!response.isSuccessful) throw HttpStatusException(response.code, response.header("Retry-After"))
        response.body?.string().orEmpty()
    }
}

private suspend fun OkHttpClient.json(url: Any, gson: Gson): JsonObject = gson.fromJson(text(url), JsonObject::class.java)

private fun JsonObject.matches(request: LyricsLookupRequest, titleKey: String, artistKey: String): Boolean {
    val title = get(titleKey)?.asString ?: get("title")?.asString ?: get("name")?.asString ?: return false
    val artist = get(artistKey)?.asString ?: get("artist")?.asString ?: return false
    val wantTitle = SpotifyTrackMatcher.normalize(request.title)
    val wantArtist = SpotifyTrackMatcher.normalize(request.artist)
    val gotArtist = SpotifyTrackMatcher.normalize(artist)
    return SpotifyTrackMatcher.normalize(title) == wantTitle && (gotArtist == wantArtist || gotArtist in wantArtist || wantArtist in gotArtist)
}

private fun payloadOf(data: JsonObject): ProviderResult? {
    val text = sequenceOf("ttml", "content", "syncedLyrics", "lyrics")
        .mapNotNull { data.get(it)?.takeIf { e -> e.isJsonPrimitive }?.asString?.takeIf(String::isNotBlank) }
        .firstOrNull() ?: return null
    return contentPayload(text)
}

private fun contentPayload(text: String): ProviderResult? = when {
    text.contains("<tt", true) -> ProviderResult.Hit(RemoteLyricsPayload(ttmlLyrics = text))
    Regex("\\[\\d{1,3}:\\d{2}").containsMatchIn(text) -> ProviderResult.Hit(RemoteLyricsPayload(syncedLyrics = text))
    text.isNotBlank() -> ProviderResult.Hit(RemoteLyricsPayload(plainLyrics = text))
    else -> null
}
