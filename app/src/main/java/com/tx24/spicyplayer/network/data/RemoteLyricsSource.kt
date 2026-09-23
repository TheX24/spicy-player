package com.tx24.spicyplayer.network.data

import com.tx24.spicyplayer.network.model.NetworkErrorException
import com.tx24.spicyplayer.network.model.NotFoundException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

enum class ProviderAttemptOutcome {
    HIT,
    MISS,
    NEEDS_MATCH,
    DISABLED,
    COOLING_DOWN,
    QUEUED,
    UNAVAILABLE,
    MALFORMED_HIT,
}

data class ProviderAttempt(
    val sourceId: String,
    val outcome: ProviderAttemptOutcome,
    val quality: RemoteLyricsQuality = RemoteLyricsQuality.NONE,
    val retryAt: Instant? = null,
    val failureCategory: ProviderFailureCategory? = null,
    val message: String? = null,
)

data class RemoteLyricsSelection(
    val source: LyricsSourceDescriptor,
    val payload: RemoteLyricsPayload,
    val quality: RemoteLyricsQuality,
)

sealed interface RemoteLyricsResolution {
    val attempts: List<ProviderAttempt>

    data class Found(
        val selection: RemoteLyricsSelection,
        override val attempts: List<ProviderAttempt>,
    ) : RemoteLyricsResolution

    data class NotFound(
        override val attempts: List<ProviderAttempt>,
    ) : RemoteLyricsResolution

    data class Unavailable(
        override val attempts: List<ProviderAttempt>,
        val earliestRetryAt: Instant? = null,
    ) : RemoteLyricsResolution
}

/**
 * Applies source policy and selects the best usable payload. TTML is parsed
 * before it can win, so malformed or empty XML never terminates the chain.
 */
@Singleton
class RemoteLyricsSource @Inject constructor(
    providers: Set<@JvmSuppressWildcards RemoteLyricsProvider>,
    private val cooldowns: ProviderCooldownTracker,
    private val diagnostics: LyricsDiagnostics = LyricsDiagnostics(),
) {
    private val providers = providers.toList()

    suspend fun resolveLyrics(
        request: LyricsLookupRequest,
        policy: RemoteLyricsPolicy = RemoteLyricsPolicy(),
        now: Instant = Instant.now(),
        onSourceStarted: (LyricsSourceDescriptor) -> Unit = {},
    ): RemoteLyricsResolution {
        val attempts = mutableListOf<ProviderAttempt>()
        var best: RemoteLyricsSelection? = null
        var hadUnavailableProvider = false
        var earliestRetryAt: Instant? = null

        for (provider in orderedProviders(policy)) {
            val source = provider.descriptor
            if (source.id in policy.disabledSourceIds ||
                (policy.sourceOrder.isEmpty() && !source.defaultEnabled)
            ) {
                attempts += ProviderAttempt(source.id, ProviderAttemptOutcome.DISABLED)
                continue
            }

            val activeCooldown = cooldowns.retryAt(source.id, now)
            if (activeCooldown != null) {
                attempts += ProviderAttempt(
                    sourceId = source.id,
                    outcome = ProviderAttemptOutcome.COOLING_DOWN,
                    retryAt = activeCooldown,
                )
                earliestRetryAt = earliest(earliestRetryAt, activeCooldown)
                hadUnavailableProvider = true
                continue
            }

            onSourceStarted(source)
            val result = try {
                provider.fetch(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: NotFoundException) {
                ProviderResult.Miss
            } catch (error: NetworkErrorException) {
                ProviderResult.Unavailable(
                    category = ProviderFailureCategory.NETWORK,
                    message = error.message,
                    retryable = true,
                )
            } catch (error: Exception) {
                ProviderResult.Unavailable(
                    category = ProviderFailureCategory.UNKNOWN,
                    message = error.message,
                )
            }

            when (result) {
                is ProviderResult.Hit -> {
                    val quality = result.payload.measuredQuality()
                    if (quality == RemoteLyricsQuality.NONE) {
                        attempts += ProviderAttempt(
                            source.id,
                            ProviderAttemptOutcome.MALFORMED_HIT,
                            failureCategory = ProviderFailureCategory.MALFORMED_RESPONSE,
                        )
                        hadUnavailableProvider = true
                        continue
                    }

                    attempts += ProviderAttempt(source.id, ProviderAttemptOutcome.HIT, quality)
                    if (best == null || quality.rank > best.quality.rank) {
                        best = RemoteLyricsSelection(source, result.payload, quality)
                    }

                    if (quality == RemoteLyricsQuality.WORD_SYNCED) {
                        return RemoteLyricsResolution.Found(requireNotNull(best), attempts)
                    }
                }

                ProviderResult.Miss ->
                    attempts += ProviderAttempt(source.id, ProviderAttemptOutcome.MISS)

                ProviderResult.NeedsMatch ->
                    attempts += ProviderAttempt(source.id, ProviderAttemptOutcome.NEEDS_MATCH)

                is ProviderResult.CoolingDown -> {
                    cooldowns.record(source.id, result.retryAt)
                    attempts += ProviderAttempt(
                        source.id,
                        ProviderAttemptOutcome.COOLING_DOWN,
                        retryAt = result.retryAt,
                    )
                    earliestRetryAt = earliest(earliestRetryAt, result.retryAt)
                    hadUnavailableProvider = true
                }

                is ProviderResult.Queued -> {
                    attempts += ProviderAttempt(
                        source.id,
                        ProviderAttemptOutcome.QUEUED,
                        retryAt = result.retryAt,
                    )
                    result.retryAt?.let { earliestRetryAt = earliest(earliestRetryAt, it) }
                    hadUnavailableProvider = true
                }

                is ProviderResult.Unavailable -> {
                    attempts += ProviderAttempt(
                        source.id,
                        ProviderAttemptOutcome.UNAVAILABLE,
                        failureCategory = result.category,
                        message = result.message,
                    )
                    hadUnavailableProvider = true
                }
            }
        }

        best?.let { return RemoteLyricsResolution.Found(it, attempts) }
        return if (hadUnavailableProvider) {
            RemoteLyricsResolution.Unavailable(attempts, earliestRetryAt)
        } else {
            RemoteLyricsResolution.NotFound(attempts)
        }
    }

    /** Compatibility boundary for the existing repository while it migrates to typed outcomes. */
    suspend fun getLyrics(
        request: LyricsLookupRequest,
        policy: RemoteLyricsPolicy = RemoteLyricsPolicy(),
    ): RemoteLyricsPayload {
        val started = System.nanoTime()
        val resolution = resolveLyrics(request, policy)
        diagnostics.record(LyricsLookupDiagnostic(
            title = request.title,
            recordedAt = Instant.now(),
            durationMs = (System.nanoTime() - started) / 1_000_000L,
            outcome = when (resolution) {
                is RemoteLyricsResolution.Found -> "Found: ${resolution.selection.source.id}"
                is RemoteLyricsResolution.NotFound -> "Not found"
                is RemoteLyricsResolution.Unavailable -> "Temporarily unavailable"
            },
            attempts = resolution.attempts,
        ))
        return when (resolution) {
        is RemoteLyricsResolution.Found -> resolution.selection.payload.copy(
            sourceId = resolution.selection.source.id,
            attribution = resolution.selection.payload.attribution ?: LyricsAttribution(
                providerName = resolution.selection.source.displayName,
            ),
        )
        is RemoteLyricsResolution.NotFound -> throw LyricsNotFoundException(resolution.attempts)
        is RemoteLyricsResolution.Unavailable -> throw LyricsSourcesUnavailableException(resolution.attempts, resolution.earliestRetryAt)
        }
    }

    class LyricsNotFoundException(val attempts: List<ProviderAttempt>) : NotFoundException("Lyrics not found")
    class LyricsSourcesUnavailableException(
        val attempts: List<ProviderAttempt>,
        val retryAt: Instant?,
    ) : NetworkErrorException("Lyrics sources are temporarily unavailable")

    private fun orderedProviders(policy: RemoteLyricsPolicy): List<RemoteLyricsProvider> {
        val explicitOrder = policy.sourceOrder
            .withIndex()
            .associate { (index, id) -> id to index }
        return providers.sortedWith(
            compareBy<RemoteLyricsProvider> {
                explicitOrder[it.descriptor.id] ?: Int.MAX_VALUE
            }.thenBy { it.descriptor.defaultPriority }
                .thenBy { it.descriptor.id }
        )
    }

    private fun earliest(current: Instant?, candidate: Instant): Instant = when {
        current == null -> candidate
        candidate.isBefore(current) -> candidate
        else -> current
    }
}
