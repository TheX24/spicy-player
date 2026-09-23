package com.tx24.spicyplayer.latencytest

import android.content.Context
import com.google.gson.Gson
import com.tx24.spicyplayer.network.data.*
import com.tx24.spicyplayer.network.data.providers.*
import com.tx24.spicyplayer.network.data.spotify.AnonymousSpotifyCatalogSearch
import com.tx24.spicyplayer.network.data.spotify.SpotifyTrackResolver
import com.tx24.spicyplayer.network.service.LyricsService
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** External-playback wiring for the original player's provider boundary. */
internal class NextLyricsBackend(context: Context, clientKey: String) {
    private val preferences = context.getSharedPreferences("lyrics_sources", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val spotifyResolver = SpotifyTrackResolver(AnonymousSpotifyCatalogSearch(client, gson))
    private val lrclib = Retrofit.Builder()
        .baseUrl(LyricsService.BASE_URL)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
        .create(LyricsService::class.java)

    private val providers: Set<RemoteLyricsProvider> = setOf(
        SpicyLyricsProvider(client, gson, spotifyResolver, clientKey),
        AmllLyricsProvider(client, gson),
        UnisonLyricsProvider(client, gson),
        AppleMusicLyricsProvider(client, gson),
        RmmRevivalLyricsProvider(client, gson),
        BiniLyricsProvider(client, gson),
        KugouLyricsProvider(client, gson),
        QqMusicLyricsProvider(client, gson),
        KuwoLyricsProvider(client),
        NetEaseLyricsProvider(client, gson),
        LyricsSource(lrclib),
        MusixmatchLyricsProvider(client, gson),
        LrcMuxLyricsProvider(client, gson),
        MegaLobizLyricsProvider(client),
        GeniusLyricsProvider(client, gson),
        YouTubeTranscriptLyricsProvider(client, gson),
    )
    private val source = RemoteLyricsSource(providers, ProviderCooldownTracker())

    val descriptors: List<LyricsSourceDescriptor> = providers.map(RemoteLyricsProvider::descriptor)
        .sortedWith(compareBy<LyricsSourceDescriptor> { it.defaultPriority }.thenBy { it.id })

    suspend fun resolve(
        request: LyricsLookupRequest,
        onSourceStarted: (LyricsSourceDescriptor) -> Unit = {},
    ): RemoteLyricsResolution = source.resolveLyrics(request, policy(), onSourceStarted = onSourceStarted)

    fun policy(): RemoteLyricsPolicy {
        val order = preferences.getString("order", null)?.split(',')?.filter(String::isNotBlank)
        val disabled = preferences.getStringSet("disabled", emptySet()).orEmpty()
        val normalized = LyricsSourcePreferenceNormalizer.normalize(order, disabled, descriptors)
        return RemoteLyricsPolicy(normalized.order, normalized.disabledSourceIds)
    }

    fun setPolicy(order: List<String>, disabledSourceIds: Set<String>) {
        val normalized = LyricsSourcePreferenceNormalizer.normalize(order, disabledSourceIds, descriptors)
        preferences.edit()
            .putString("order", normalized.order.joinToString(","))
            .putStringSet("disabled", normalized.disabledSourceIds)
            .apply()
    }
}
