package com.tx24.spicyplayer.lyrics

import android.content.Context
import com.google.gson.Gson
import com.tx24.spicyplayer.BuildConfig
import com.tx24.spicyplayer.network.data.*
import com.tx24.spicyplayer.network.data.providers.*
import com.tx24.spicyplayer.network.data.spotify.AnonymousSpotifyCatalogSearch
import com.tx24.spicyplayer.network.data.spotify.SpotifyTrackResolver
import com.tx24.spicyplayer.network.service.LyricsService
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
    private val providers: Set<RemoteLyricsProvider> = createProviders(client, gson, clientKey, context.cacheDir)
    private val source = RemoteLyricsSource(providers, ProviderCooldownTracker())

    val descriptors: List<LyricsSourceDescriptor> = providers.map(RemoteLyricsProvider::descriptor)
        .sortedWith(compareBy<LyricsSourceDescriptor> { it.defaultPriority }.thenBy { it.id })
    /** Blends are switched on and off apart from the ordered sources; their rank follows their donors. */
    val blendDescriptors: List<LyricsSourceDescriptor> = LyricsBlends.ALL.map(LyricsBlendDefinition::descriptor)

    /** Readies every source's tokens; failures are left for the real lookup to report. */
    suspend fun warmUp() = coroutineScope {
        providers.forEach { provider ->
            launch { runCatching { provider.warmUp() }.onFailure { if (it is CancellationException) throw it } }
        }
    }

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
        val source = (descriptors + blendDescriptors).firstOrNull { it.id == stored.sourceId } ?: return null
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

    /** Drops every stored pick; the next lookups ask the sources again. */
    fun clearCache() {
        diskCache.deleteRecursively()
    }

    private fun enabledOrder(): List<String> = policy().let { p ->
        p.sourceOrder.filter { it !in p.disabledSourceIds } + p.enabledBlendIds.sorted()
    }

    private fun cacheFile(request: LyricsLookupRequest): File {
        // Title + artist only: a queue entry warmed ahead has no album, length or Spotify ID, and
        // must hit the same entry the real load asks for once the song starts.
        val identity = listOf(request.title, request.artist)
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

    companion object {
        /** Every source the app asks, built on [client]. Also used by the live source check test. */
        fun createProviders(client: OkHttpClient, gson: Gson, clientKey: String, cacheDir: File? = null): Set<RemoteLyricsProvider> {
            val spotifyResolver = SpotifyTrackResolver(AnonymousSpotifyCatalogSearch(client, gson))
            val lrclib = Retrofit.Builder()
                .baseUrl(LyricsService.BASE_URL)
                // LRCLIB asks clients to identify themselves; its Cloudflare front answers OkHttp's
                // default user agent with HTTP 520.
                .client(client.newBuilder().addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("User-Agent", LRCLIB_USER_AGENT).build())
                }.build())
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
                .create(LyricsService::class.java)
            return setOf(
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
                MusixmatchLyricsProvider(client, gson, cacheDir?.let { File(it, "musixmatch-token.txt") }),
                LrcMuxLyricsProvider(client, gson),
                GeniusLyricsProvider(client, gson),
                YouTubeTranscriptLyricsProvider(client, gson),
            )
        }

        /** Bump when payload conversion changes, so stale conversions are refetched. */
        private const val CACHE_VERSION = 10
        /** Bump when the default source order or on/off set changes, to reset saved choices once. */
        private const val SOURCE_DEFAULTS_VERSION = 1
        private val LRCLIB_USER_AGENT = "Spicy Player Next ${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})"
        private const val CACHE_DAYS = 3
    }

    fun policy(): RemoteLyricsPolicy {
        // A new default order and set of sources replaces what was saved against the old one, once.
        if (preferences.getInt("defaults", 0) < SOURCE_DEFAULTS_VERSION) {
            preferences.edit().remove("order").remove("disabled").putInt("defaults", SOURCE_DEFAULTS_VERSION).apply()
        }
        val order = preferences.getString("order", null)?.split(',')?.filter(String::isNotBlank)
        val disabled = preferences.getStringSet("disabled", emptySet()).orEmpty()
        val normalized = LyricsSourcePreferenceNormalizer.normalize(order, disabled, descriptors)
        val blends = preferences.getStringSet("blends", emptySet()).orEmpty()
            .filterTo(mutableSetOf()) { LyricsBlends.byId(it) != null }
        return RemoteLyricsPolicy(normalized.order, normalized.disabledSourceIds, blends)
    }

    fun setBlendEnabled(id: String, enabled: Boolean) {
        val blends = policy().enabledBlendIds.toMutableSet()
        if (enabled) blends += id else blends -= id
        preferences.edit().putStringSet("blends", blends).apply()
    }

    fun setPolicy(order: List<String>, disabledSourceIds: Set<String>) {
        val normalized = LyricsSourcePreferenceNormalizer.normalize(order, disabledSourceIds, descriptors)
        preferences.edit()
            .putString("order", normalized.order.joinToString(","))
            .putStringSet("disabled", normalized.disabledSourceIds)
            .apply()
    }
}
