package com.tx24.spicyplayer.network.data

import com.tx24.spicyplayer.lyrics.spicy.models.LyricsType
import com.tx24.spicyplayer.lyrics.spicy.parser.TtmlLyricsParser
import java.time.Instant

/** Metadata available when looking up lyrics for a local track. */
data class LyricsLookupRequest(
    val artist: String,
    val title: String,
    val album: String,
    val durationSeconds: Int,
    /** Manual/cache override; bypasses metadata matching when present. */
    val spotifyTrackId: String? = null,
)

enum class LyricsCapability {
    WORD_SYNC,
    LINE_SYNC,
    PLAIN_TEXT,
    TRANSLATION,
    TRANSLITERATION,
    CONTRIBUTOR_CREDITS,
}

enum class SourceReleaseChannel {
    RECOMMENDED,
    EXTENDED,
    EXPERIMENTAL,
}

enum class TransportRequirement {
    DIRECT_OK,
    DIRECT_OR_BROKER,
    BROKER_ONLY,
}

/** Stable metadata used for ordering, settings, diagnostics, and future migrations. */
data class LyricsSourceDescriptor(
    val id: String,
    val displayName: String,
    val defaultPriority: Int,
    val capabilities: Set<LyricsCapability>,
    val upstreamFamily: String = id,
    val transportRequirement: TransportRequirement = TransportRequirement.DIRECT_OK,
    val releaseChannel: SourceReleaseChannel = SourceReleaseChannel.RECOMMENDED,
    val defaultEnabled: Boolean = true,
)

/**
 * Provider-neutral payload returned by an online lyrics service.
 *
 * TTML is first-class so word timing is never flattened to LRC. Attribution is
 * intentionally added only after the released Spicy API contract is mapped.
 */
data class RemoteLyricsPayload(
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
    val ttmlLyrics: String? = null,
    val sourceId: String? = null,
    val attribution: LyricsAttribution? = null,
) {
    fun isEmpty(): Boolean =
        plainLyrics.isNullOrBlank() && syncedLyrics.isNullOrBlank() && ttmlLyrics.isNullOrBlank()
}

data class LyricsContributor(
    val username: String,
    val profileUrl: String? = null,
    val avatarUrl: String? = null,
)

data class LyricsAttribution(
    val providerName: String,
    /** Original catalogue/community source when the provider syndicates several origins. */
    val originName: String? = null,
    val songwriters: List<String> = emptyList(),
    val maker: LyricsContributor? = null,
    val uploader: LyricsContributor? = null,
)

enum class ProviderFailureCategory {
    NETWORK,
    TIMEOUT,
    AUTHENTICATION,
    CLIENT_REQUEST,
    SERVER,
    MALFORMED_RESPONSE,
    UNKNOWN,
}

/** A provider miss is deliberately distinct from an unreachable provider. */
sealed interface ProviderResult {
    data class Hit(val payload: RemoteLyricsPayload) : ProviderResult
    data object Miss : ProviderResult
    data object NeedsMatch : ProviderResult
    data class CoolingDown(val retryAt: Instant) : ProviderResult
    data class Queued(val retryAt: Instant? = null) : ProviderResult
    data class Unavailable(
        val category: ProviderFailureCategory,
        val message: String? = null,
        val retryable: Boolean = false,
    ) : ProviderResult
}

/** A single online source. Ordering is policy, not part of fetch behavior. */
interface RemoteLyricsProvider {
    val descriptor: LyricsSourceDescriptor

    suspend fun fetch(request: LyricsLookupRequest): ProviderResult
}

/** Snapshot of source preferences for one lookup. */
data class RemoteLyricsPolicy(
    val sourceOrder: List<String> = emptyList(),
    val disabledSourceIds: Set<String> = emptySet(),
    /** Blends are off unless switched on, as in mild-lyrics; see [LyricsBlends]. */
    val enabledBlendIds: Set<String> = emptySet(),
)

enum class RemoteLyricsQuality(val rank: Int) {
    NONE(0),
    PLAIN(1),
    LINE_SYNCED(2),
    WORD_SYNCED(3),
}

/**
 * Measures the best usable representation in a provider response.
 *
 * TTML is parsed here instead of being trusted by file extension. A malformed or
 * empty TTML body can therefore fall back to a valid LRC/plain representation,
 * and cannot stop the provider chain as a fake word-synced hit.
 */
fun RemoteLyricsPayload.measuredQuality(): RemoteLyricsQuality {
    val ttmlQuality = parsedTtmlQuality()
    return when {
        ttmlQuality != RemoteLyricsQuality.NONE -> ttmlQuality
        !syncedLyrics.isNullOrBlank() -> RemoteLyricsQuality.LINE_SYNCED
        !plainLyrics.isNullOrBlank() -> RemoteLyricsQuality.PLAIN
        else -> RemoteLyricsQuality.NONE
    }
}

private fun RemoteLyricsPayload.parsedTtmlQuality(): RemoteLyricsQuality {
    val ttml = ttmlLyrics?.takeIf(String::isNotBlank) ?: return RemoteLyricsQuality.NONE
    val document = TtmlLyricsParser.parse(ttml.byteInputStream())
    val hasText = document.lines.any { line ->
        line.words.any { word -> word.text.isNotBlank() }
    }
    if (!hasText) return RemoteLyricsQuality.NONE

    return when (document.type) {
        LyricsType.Syllable -> RemoteLyricsQuality.WORD_SYNCED
        LyricsType.Line -> RemoteLyricsQuality.LINE_SYNCED
        LyricsType.Static -> RemoteLyricsQuality.PLAIN
    }
}
