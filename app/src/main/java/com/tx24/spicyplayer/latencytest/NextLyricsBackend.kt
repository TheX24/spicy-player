package com.tx24.spicyplayer.latencytest

import android.content.Context
import com.google.gson.Gson
import com.tx24.spicyplayer.network.data.*
import com.tx24.spicyplayer.network.data.providers.*
import com.tx24.spicyplayer.network.data.spotify.AnonymousSpotifyCatalogSearch
import com.tx24.spicyplayer.network.data.spotify.SpotifyTrackResolver
import com.tx24.spicyplayer.network.service.LyricsService
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** External-playback wiring for the original player's provider boundary. */
internal class NextLyricsBackend(context: Context, clientKey: String) {
    private val diskCache = File(context.cacheDir, "lyrics")
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
        known: MutableMap<String, ProviderResult>,
        onUpdate: suspend (RemoteLyricsResolution) -> Unit = {},
    ): RemoteLyricsResolution = source.resolveLyrics(request, policy(), known = known, onUpdate = onUpdate)

    /**
     * The last final pick for [request], like Spicy Lyrics' LyricsStore: kept [CACHE_DAYS] days,
     * "no lyrics" included, errors never. Only reused while the enabled source order is the one it
     * was picked under (mild-lyrics' rule), since another order could pick differently.
     */
    fun cachedResolution(request: LyricsLookupRequest): RemoteLyricsResolution? {
        val stored = runCatching { gson.fromJson(cacheFile(request).readText(), StoredPick::class.java) }.getOrNull()
            ?: return null
        if (stored.version != CACHE_VERSION || stored.expiresAt < System.currentTimeMillis() || stored.order != enabledOrder()) return null
        val payload = stored.payload ?: return RemoteLyricsResolution.NotFound(emptyList())
        val source = descriptors.firstOrNull { it.id == stored.sourceId } ?: return null
        val quality = payload.measuredQuality()
        return RemoteLyricsResolution.Found(
            RemoteLyricsSelection(source, payload, quality),
            listOf(ProviderAttempt(source.id, ProviderAttemptOutcome.HIT, quality, message = "cached")),
        )
    }

    fun store(request: LyricsLookupRequest, resolution: RemoteLyricsResolution) {
        val found = resolution as? RemoteLyricsResolution.Found
        if (found == null && resolution !is RemoteLyricsResolution.NotFound) return
        val stored = StoredPick(
            version = CACHE_VERSION,
            expiresAt = System.currentTimeMillis() + CACHE_DAYS * 86_400_000L,
            order = enabledOrder(),
            sourceId = found?.selection?.source?.id,
            payload = found?.selection?.payload,
        )
        runCatching { diskCache.mkdirs(); cacheFile(request).writeText(gson.toJson(stored)) }
    }

    fun forget(request: LyricsLookupRequest) {
        cacheFile(request).delete()
    }

    private fun enabledOrder(): List<String> = policy().let { p -> p.sourceOrder.filter { it !in p.disabledSourceIds } }

    private fun cacheFile(request: LyricsLookupRequest): File {
        // Title + artist only: a queue entry warmed ahead has no album or length, and must hit
        // the same entry the real load asks for once the song starts.
        val identity = listOf(request.title, request.artist, request.spotifyTrackId.orEmpty())
            .joinToString("\u001f") { it.trim().lowercase() }
        val key = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(diskCache, "$key.json")
    }

    private data class StoredPick(
        val version: Int,
        val expiresAt: Long,
        val order: List<String>,
        val sourceId: String?,
        val payload: RemoteLyricsPayload?,
    )

    private companion object {
        /** Bump when payload conversion changes, so stale conversions are refetched. */
        const val CACHE_VERSION = 7
        const val CACHE_DAYS = 3
    }

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
