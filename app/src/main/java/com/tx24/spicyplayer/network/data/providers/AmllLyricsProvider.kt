package com.tx24.spicyplayer.network.data.providers

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.tx24.spicyplayer.network.data.LyricsCapability
import com.tx24.spicyplayer.network.data.LyricsLookupRequest
import com.tx24.spicyplayer.network.data.LyricsSourceDescriptor
import com.tx24.spicyplayer.network.data.ProviderFailureCategory
import com.tx24.spicyplayer.network.data.ProviderResult
import com.tx24.spicyplayer.network.data.RemoteLyricsPayload
import com.tx24.spicyplayer.network.data.RemoteLyricsProvider
import com.tx24.spicyplayer.network.data.SourceReleaseChannel
import com.tx24.spicyplayer.network.data.awaitResponse
import com.tx24.spicyplayer.network.data.spotify.SpotifyTrackMatcher
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class AmllLyricsProvider @Inject constructor(
    private val client: OkHttpClient,
    private val gson: Gson,
) : RemoteLyricsProvider {
    override val descriptor = LyricsSourceDescriptor(
        id = "amll_ttml_db",
        displayName = "AMLL TTML DB",
        defaultPriority = 20,
        capabilities = setOf(
            LyricsCapability.WORD_SYNC,
            LyricsCapability.LINE_SYNC,
            LyricsCapability.TRANSLATION,
            LyricsCapability.TRANSLITERATION,
            LyricsCapability.CONTRIBUTOR_CREDITS,
        ),
        releaseChannel = SourceReleaseChannel.RECOMMENDED,
    )

    override suspend fun fetch(request: LyricsLookupRequest): ProviderResult = try {
        val searchUrl = "$BASE/v1/lyrics/search".toHttpUrl().newBuilder()
            .addQueryParameter("trackName", request.title)
            .addQueryParameter("artistName", request.artist)
            .build()
        client.newCall(Request.Builder().url(searchUrl).get().build()).awaitResponse().use { response ->
            if (response.code == 404) return ProviderResult.Miss
            if (!response.isSuccessful) return httpFailure(response.code)
            val root = gson.fromJson(response.body?.string(), JsonObject::class.java)
            val items = root.getAsJsonObject("data")?.getAsJsonArray("items") ?: return ProviderResult.Miss
            val candidate = items
                .mapNotNull { it.takeIf { value -> value.isJsonObject }?.asJsonObject }
                .firstOrNull { item -> item.matches(request) }
                ?: return ProviderResult.Miss
            val id = candidate.get("id")?.asString ?: return ProviderResult.Miss
            fetchById(id)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: IOException) {
        ProviderResult.Unavailable(ProviderFailureCategory.NETWORK, error.message, retryable = true)
    } catch (error: Exception) {
        ProviderResult.Unavailable(ProviderFailureCategory.MALFORMED_RESPONSE, error.message)
    }

    private suspend fun fetchById(id: String): ProviderResult {
        val url = "$BASE/v1/lyrics/get".toHttpUrl().newBuilder()
            .addQueryParameter("id", id)
            .build()
        return client.newCall(Request.Builder().url(url).get().build()).awaitResponse().use { response ->
            if (response.code == 404) return@use ProviderResult.Miss
            if (!response.isSuccessful) return@use httpFailure(response.code)
            val root = gson.fromJson(response.body?.string(), JsonObject::class.java)
            val data = root.getAsJsonObject("data") ?: root
            val ttml = sequenceOf("lyrics", "ttml", "content")
                .mapNotNull { key -> data.get(key)?.takeIf { it.isJsonPrimitive }?.asString }
                .firstOrNull { it.contains("<tt", ignoreCase = true) }
                ?: return@use ProviderResult.Unavailable(ProviderFailureCategory.MALFORMED_RESPONSE)
            ProviderResult.Hit(RemoteLyricsPayload(ttmlLyrics = ttml))
        }
    }

    private fun JsonObject.matches(request: LyricsLookupRequest): Boolean {
        val titles = getAsJsonArray("musicNames")?.map { it.asString }.orEmpty()
        val artists = getAsJsonArray("artistNames")?.map { it.asString }.orEmpty()
        val title = SpotifyTrackMatcher.normalize(request.title)
        val artist = SpotifyTrackMatcher.normalize(request.artist)
        return titles.any { SpotifyTrackMatcher.normalize(it) == title } &&
            artists.any { candidate ->
                val normalized = SpotifyTrackMatcher.normalize(candidate)
                normalized == artist || normalized in artist || artist in normalized
            }
    }

    private fun httpFailure(code: Int) = ProviderResult.Unavailable(
        if (code in 400..499) ProviderFailureCategory.CLIENT_REQUEST else ProviderFailureCategory.SERVER,
        "AMLL returned HTTP $code",
        retryable = code >= 500,
    )

    private companion object {
        const val BASE = "https://api.amll.dev"
    }
}
