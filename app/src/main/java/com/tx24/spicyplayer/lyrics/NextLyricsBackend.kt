package com.tx24.spicyplayer.lyrics

import android.content.Context
import com.google.gson.Gson
import com.tx24.spicyplayer.BuildConfig
import com.tx24.spicyplayer.network.data.*
import com.tx24.spicyplayer.network.data.providers.*
import com.tx24.spicyplayer.network.data.spotify.AnonymousSpotifyCatalogSearch
import com.tx24.spicyplayer.network.data.spotify.SharedSpotify
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
    private val providers: Set<RemoteLyricsProvider> =
        createProviders(client, gson, clientKey, context.cacheDir, SharedSpotify.resolver) { ignoreMusixmatchWordSync }
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
     * A stored pick. [settled] is false when a source ranked above it had no real answer (an error,
     * or no Spotify match for a request without a length): the pick shows at once, but those
     * sources are asked again. [answers] are what may be reused without asking.
     */
    class CachedPick(val resolution: RemoteLyricsResolution, val settled: Boolean) {
        val answers: Map<String, ProviderResult> get() = buildMap {
            resolution.attempts.filter { it.outcome == ProviderAttemptOutcome.MISS }.forEach { put(it.sourceId, ProviderResult.Miss) }
            // A blend is rebuilt from its donors, never taken as an answer.
            (resolution as? RemoteLyricsResolution.Found)?.selection
                ?.takeIf { LyricsBlends.byId(it.source.id) == null }
                ?.let { put(it.source.id, ProviderResult.Hit(it.payload)) }
        }
    }

    /**
     * The last final pick for [request]: kept [CACHE_DAYS] days,
     * "no lyrics" included, errors never. Only reused while the enabled source order is the one it
     * was picked under, since another order could pick differently.
     */
    fun cachedResolution(request: LyricsLookupRequest): CachedPick? {
        val stored = runCatching { gson.fromJson(cacheFile(request).readText(), StoredPick::class.java) }.getOrNull()
            ?: return null
        if (stored.version != CACHE_VERSION || stored.expiresAt < System.currentTimeMillis() || stored.order != enabledOrder()) return null
        val attempts = stored.attempts.orEmpty().mapNotNull(StoredAttempt::restore)
        val payload = stored.payload ?: return CachedPick(RemoteLyricsResolution.NotFound(attempts), stored.settled)
        val source = (descriptors + blendDescriptors).firstOrNull { it.id == stored.sourceId } ?: return null
        val quality = payload.measuredQuality()
        return CachedPick(
            RemoteLyricsResolution.Found(
                RemoteLyricsSelection(source, payload, quality),
                attempts.map { if (it.sourceId == source.id) it.copy(message = "cached") else it },
            ),
            stored.settled,
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
            attempts = resolution.attempts.map(StoredAttempt::of),
            settled = isSettled(request, resolution),
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

    /** Also keyed on the Musixmatch word-sync switch, which changes what those sources answer. */
    private fun enabledOrder(): List<String> = policy().let { p ->
        p.sourceOrder.filter { it !in p.disabledSourceIds } + p.enabledBlendIds.sorted() +
            listOfNotNull(MUSIXMATCH_LINES_ONLY.takeIf { ignoreMusixmatchWordSync })
    }

    private fun cacheFile(request: LyricsLookupRequest): File {
        // Title + artist only: a queue entry warmed ahead has no album, length or Spotify ID, and
        // must hit the same entry the real load asks for once the song starts.
        return File(diskCache, cacheKey(request.title, request.artist) + ".json")
    }

    private fun cacheKey(title: String, artist: String): String {
        val identity = listOf(title, artist).joinToString("\u001f") { it.trim().lowercase() }
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private data class StoredPick(
        val version: Int,
        val expiresAt: Long,
        val order: List<String>,
        val sourceId: String?,
        val payload: RemoteLyricsPayload?,
        val attempts: List<StoredAttempt>?,
        val settled: Boolean,
    )

    /** An attempt by names, so a renamed or dropped enum value only loses that one line. */
    private data class StoredAttempt(
        val sourceId: String,
        val outcome: String,
        val quality: String,
        val category: String?,
        val message: String?,
    ) {
        fun restore(): ProviderAttempt? = runCatching {
            ProviderAttempt(
                sourceId,
                ProviderAttemptOutcome.valueOf(outcome),
                RemoteLyricsQuality.valueOf(quality),
                failureCategory = category?.let(ProviderFailureCategory::valueOf),
                message = message,
            )
        }.getOrNull()

        companion object {
            fun of(attempt: ProviderAttempt) = StoredAttempt(
                attempt.sourceId, attempt.outcome.name, attempt.quality.name, attempt.failureCategory?.name, attempt.message,
            )
        }
    }

    companion object {
        /** Whether every source ranked above the pick (every source, for "no lyrics") really answered. */
        internal fun isSettled(request: LyricsLookupRequest, resolution: RemoteLyricsResolution): Boolean {
            val pick = (resolution as? RemoteLyricsResolution.Found)?.selection?.source?.id
            val above = resolution.attempts.takeWhile { it.sourceId != pick }
            return above.none {
                it.outcome in UNANSWERED ||
                    // Matched on title and artist alone (a queue entry, or a player that sends the
                    // length later): with the length, the match may well be found.
                    (it.outcome == ProviderAttemptOutcome.NEEDS_MATCH && request.durationSeconds <= 0)
            }
        }

        /** Every source the app asks, built on [client]. Also used by the live source check test. */
        fun createProviders(
            client: OkHttpClient,
            gson: Gson,
            clientKey: String,
            cacheDir: File? = null,
            spotifyResolver: SpotifyTrackResolver = SpotifyTrackResolver(AnonymousSpotifyCatalogSearch(client, gson)),
            ignoreMusixmatchWordSync: () -> Boolean = { false },
        ): Set<RemoteLyricsProvider> {
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
                RelayedOriginSlot.APPLE_MUSIC,
                RmmRevivalLyricsProvider(client, gson),
                BiniLyricsProvider(client, gson),
                KugouLyricsProvider(client, gson),
                QqMusicLyricsProvider(client, gson),
                KuwoLyricsProvider(client),
                NetEaseLyricsProvider(client, gson),
                LyricsSource(lrclib),
                MusixmatchLyricsProvider(client, gson, cacheDir?.let { File(it, "musixmatch-token.txt") }, ignoreMusixmatchWordSync),
                LrcMuxLyricsProvider(client, gson, ignoreMusixmatchWordSync),
                GeniusLyricsProvider(client, gson),
                YouTubeTranscriptLyricsProvider(client, gson),
            )
        }

        /** Bump when payload conversion changes, so stale conversions are refetched. */
        private const val CACHE_VERSION = 16
        /** Outcomes that are no answer at all: a later lookup may get one. */
        private val UNANSWERED = setOf(
            ProviderAttemptOutcome.UNAVAILABLE,
            ProviderAttemptOutcome.COOLING_DOWN,
            ProviderAttemptOutcome.QUEUED,
            ProviderAttemptOutcome.PENDING,
        )
        /** Bump when the default source order or on/off set changes, to reset saved choices once. */
        private const val SOURCE_DEFAULTS_VERSION = 1
        private val LRCLIB_USER_AGENT = "Spicy Player ${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})"
        private const val CACHE_DAYS = 3
        private const val MUSIXMATCH_LINES_ONLY = "musixmatch-lines-only"
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

    /** Human-written romanizations from Genius over the on-device ones (on by default). */
    var humanRomanizations: Boolean
        get() = preferences.getBoolean("humanRomanizations", true)
        set(value) = preferences.edit().putBoolean("humanRomanizations", value).apply()

    /** Line timing over word timing from the Musixmatch sources (on by default: their word syncs are poor). */
    var ignoreMusixmatchWordSync: Boolean
        get() = preferences.getBoolean("ignoreMusixmatchWordSync", true)
        set(value) = preferences.edit().putBoolean("ignoreMusixmatchWordSync", value).apply()

    private val geniusRomanization = GeniusRomanizationSource(client, gson)
    private val romanCache = File(context.cacheDir, "genius-roman")

    /**
     * Genius's romanization of the song as lyric lines, or null when it has none. Kept on disk a
     * week when found and a day when not, so a replayed song asks once.
     */
    suspend fun humanRomanization(title: String, artist: String): List<String>? {
        val file = File(romanCache, cacheKey(title, artist) + ".json")
        runCatching { gson.fromJson(file.readText(), StoredRoman::class.java) }.getOrNull()
            ?.takeIf { it.expiresAt > System.currentTimeMillis() }
            ?.let { return it.lines }
        val lines = geniusRomanization.find(title, artist)
        val keep = TimeUnit.DAYS.toMillis(if (lines != null) 7 else 1)
        runCatching {
            romanCache.mkdirs()
            file.writeText(gson.toJson(StoredRoman(System.currentTimeMillis() + keep, lines)))
        }
        return lines
    }

    fun forgetRomanization(title: String, artist: String) {
        File(romanCache, cacheKey(title, artist) + ".json").delete()
    }

    private data class StoredRoman(val expiresAt: Long, val lines: List<String>?)

    fun setPolicy(order: List<String>, disabledSourceIds: Set<String>) {
        val normalized = LyricsSourcePreferenceNormalizer.normalize(order, disabledSourceIds, descriptors)
        preferences.edit()
            .putString("order", normalized.order.joinToString(","))
            .putStringSet("disabled", normalized.disabledSourceIds)
            .apply()
    }
}
