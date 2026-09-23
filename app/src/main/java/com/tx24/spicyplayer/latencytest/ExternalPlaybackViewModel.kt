package com.tx24.spicyplayer.latencytest

import android.app.Application
import android.graphics.Bitmap
import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.tx24.spicyplayer.network.data.LyricsLookupRequest
import com.tx24.spicyplayer.network.data.RemoteLyricsResolution
import com.tx24.spicyplayer.network.data.LyricsSourceDescriptor
import com.tx24.spicyplayer.network.data.ProviderAttempt
import androidx.core.app.NotificationManagerCompat
import com.tx24.spicyplayer.BuildConfig
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

data class PlayerUiState(
    val accessGranted: Boolean = false,
    val sourcePackage: String? = null,
    val title: String = "Nothing playing",
    val artist: String = "Start playback in another app",
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val canSeek: Boolean = false,
    val outputLabel: String = "Detecting output",
    val lyricDelayMs: Int = 0,
    val clockDriftMs: Long? = null,
    val matchInfo: String? = null,
    val detectedSpotifyId: String? = null,
    val manualSpotifyId: String? = null,
    val lyrics: LyricsState = LyricsState.Idle,
    val lastCommandLatencyMs: Long? = null,
    val status: String? = null,
    val artwork: Bitmap? = null,
    val artworkUri: String? = null,
    val sourceDescriptors: List<LyricsSourceDescriptor> = emptyList(),
    val sourceOrder: List<String> = emptyList(),
    val disabledSourceIds: Set<String> = emptySet(),
    val providerAttempts: List<ProviderAttempt> = emptyList(),
    val lookupStatus: String? = null,
)

class ExternalPlaybackViewModel(application: Application) : AndroidViewModel(application) {
    private val sessionManager = application.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(application, SessionAccessService::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lyricsBackend = NextLyricsBackend(application, BuildConfig.SPICY_LYRICS_CLIENT_KEY)
    private val overrideStore = application.getSharedPreferences("spotify_id_overrides", 0)
    private val outputProfiles = AudioOutputProfiles(application)
    private var outputRoute = outputProfiles.currentRoute()
    private val mutableState = MutableStateFlow(PlayerUiState(
        outputLabel = outputRoute.label,
        lyricDelayMs = outputProfiles.delayMs(outputRoute),
        sourceDescriptors = lyricsBackend.descriptors,
        sourceOrder = lyricsBackend.policy().sourceOrder,
        disabledSourceIds = lyricsBackend.policy().disabledSourceIds,
    ))
    val state: StateFlow<PlayerUiState> = mutableState.asStateFlow()

    private var controller: MediaController? = null
    private val observedSessions = mutableMapOf<MediaSession.Token, MediaController>()
    private val observedCallbacks = mutableMapOf<MediaSession.Token, MediaController.Callback>()
    private var lyricsJob: Job? = null
    private var manualSpotifyId: String? = null
    private var runtimeApiKey: String = ""
    private var currentTrackIdentity: String? = null
    private var pendingCommand: PendingCommand? = null
    private var timeline = TimelineAnchor(0L, SystemClock.elapsedRealtime(), 0f, false)
    private var lastPeriodicCheckMs = 0L

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = updateFromController(metadataChanged = true)
        override fun onPlaybackStateChanged(playbackState: PlaybackState?) = updateFromController()
        override fun onSessionDestroyed() = refresh()
    }

    private val activeSessionsListener = MediaSessionManager.OnActiveSessionsChangedListener {
        attachBestSession(it.orEmpty())
    }

    init {
        startTicker()
        refresh()
    }

    fun refresh() {
        refreshOutputRoute()
        val granted = NotificationManagerCompat.getEnabledListenerPackages(getApplication())
            .contains(getApplication<Application>().packageName)
        mutableState.value = mutableState.value.copy(accessGranted = granted)
        if (!granted) {
            stopObservingSessions()
            detachController()
            mutableState.value = PlayerUiState(
                accessGranted = false,
                outputLabel = outputRoute.label,
                lyricDelayMs = outputProfiles.delayMs(outputRoute),
                sourceDescriptors = lyricsBackend.descriptors,
                sourceOrder = lyricsBackend.policy().sourceOrder,
                disabledSourceIds = lyricsBackend.policy().disabledSourceIds,
            )
            return
        }
        runCatching {
            sessionManager.removeOnActiveSessionsChangedListener(activeSessionsListener)
            sessionManager.addOnActiveSessionsChangedListener(activeSessionsListener, listenerComponent, mainHandler)
            attachBestSession(sessionManager.getActiveSessions(listenerComponent))
        }.onFailure { error ->
            mutableState.value = mutableState.value.copy(status = error.message ?: "Could not read media sessions")
        }
    }

    fun playPause() {
        val active = controller ?: return
        val expectPlaying = active.playbackState?.state != PlaybackState.STATE_PLAYING
        pendingCommand = PendingCommand.PlayState(expectPlaying, SystemClock.elapsedRealtime())
        timeline = TimelineAnchor(
            positionMs = currentPositionMs(),
            atElapsedMs = SystemClock.elapsedRealtime(),
            speed = active.playbackState?.playbackSpeed ?: 1f,
            isPlaying = expectPlaying,
        )
        if (expectPlaying) active.transportControls.play() else active.transportControls.pause()
        mutableState.value = mutableState.value.copy(isPlaying = expectPlaying)
    }

    fun skipNext() = commandForTrackChange { skipToNext() }
    fun skipPrevious() = commandForTrackChange { skipToPrevious() }

    fun seekBy(deltaMs: Long) {
        val target = (currentPositionMs() + deltaMs)
            .coerceIn(0L, mutableState.value.durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE)
        seekTo(target)
    }

    fun seekTo(targetMs: Long) {
        val active = controller ?: return
        if (((active.playbackState?.actions ?: 0L) and PlaybackState.ACTION_SEEK_TO) == 0L) {
            mutableState.value = mutableState.value.copy(
                status = "${active.packageName} does not expose seeking through MediaSession",
            )
            return
        }
        val target = targetMs.coerceIn(
            0L,
            mutableState.value.durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE,
        )
        pendingCommand = PendingCommand.Seek(target, SystemClock.elapsedRealtime())
        timeline = timeline.copy(positionMs = target, atElapsedMs = SystemClock.elapsedRealtime())
        mutableState.value = mutableState.value.copy(positionMs = target, status = null)
        active.transportControls.seekTo(target)
    }

    fun resync() {
        pendingCommand = null
        refresh()
        controller?.playbackState?.let { reconcileClock(it, force = true) }
        mutableState.value = mutableState.value.copy(
            lastCommandLatencyMs = null,
            status = if (controller != null) "Timeline resynced to the media session" else "No active media session to resync",
        )
    }

    /** Positive delay makes the lyrics appear later; negative delay advances them. */
    fun adjustLyricDelay(deltaMs: Int) {
        val newDelay = (mutableState.value.lyricDelayMs + deltaMs).coerceIn(-2_000, 2_000)
        outputProfiles.saveDelayMs(outputRoute, newDelay)
        mutableState.value = mutableState.value.copy(lyricDelayMs = newDelay)
    }

    fun resetLyricDelay() {
        outputProfiles.saveDelayMs(outputRoute, 0)
        mutableState.value = mutableState.value.copy(lyricDelayMs = 0)
    }

    fun currentLyricPositionMs(): Long =
        (currentPositionMs() - mutableState.value.lyricDelayMs).coerceAtLeast(0L)

    fun useApiKey(key: String) {
        runtimeApiKey = key.trim()
        lyricsBackend = NextLyricsBackend(getApplication(), runtimeApiKey.ifBlank { BuildConfig.SPICY_LYRICS_CLIENT_KEY })
        refreshSourcePolicy()
        loadLyrics()
    }

    fun setSourceEnabled(id: String, enabled: Boolean) {
        val disabled = mutableState.value.disabledSourceIds.toMutableSet()
        if (enabled) disabled.remove(id) else disabled.add(id)
        lyricsBackend.setPolicy(mutableState.value.sourceOrder, disabled)
        refreshSourcePolicy()
        loadLyrics()
    }

    fun moveSource(id: String, direction: Int) {
        val order = mutableState.value.sourceOrder.toMutableList()
        val from = order.indexOf(id)
        val to = from + direction
        if (from !in order.indices || to !in order.indices) return
        java.util.Collections.swap(order, from, to)
        lyricsBackend.setPolicy(order, mutableState.value.disabledSourceIds)
        refreshSourcePolicy()
        loadLyrics()
    }

    private fun refreshSourcePolicy() {
        val policy = lyricsBackend.policy()
        mutableState.value = mutableState.value.copy(
            sourceDescriptors = lyricsBackend.descriptors,
            sourceOrder = policy.sourceOrder,
            disabledSourceIds = policy.disabledSourceIds,
        )
    }

    fun overrideSpotifyId(input: String) {
        val id = input.spotifyTrackId()
        if (id == null) {
            mutableState.value = mutableState.value.copy(
                status = "Enter a valid Spotify track ID or URL",
            )
            return
        }
        manualSpotifyId = id
        controller?.metadata.overrideKey()?.let { overrideStore.edit().putString(it, id).apply() }
        mutableState.value = mutableState.value.copy(
            detectedSpotifyId = id,
            manualSpotifyId = id,
            matchInfo = "Manual Spotify ID: $id",
            status = null,
        )
        loadLyrics()
    }

    fun clearSpotifyIdOverride() {
        manualSpotifyId = null
        controller?.metadata.overrideKey()?.let { overrideStore.edit().remove(it).apply() }
        lyricsJob?.cancel()
        mutableState.value = mutableState.value.copy(
            detectedSpotifyId = null,
            manualSpotifyId = null,
            lyrics = LyricsState.Idle,
            matchInfo = "Matching Spotify track…",
            status = null,
        )
        updateFromController()
        loadLyrics()
    }

    fun loadLyrics(spotifyIdInput: String? = null) {
        val metadata = controller?.metadata ?: return
        val identity = metadata.trackIdentity()
        val request = LyricsLookupRequest(
            artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty(),
            title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            durationSeconds = (metadata.getLong(MediaMetadata.METADATA_KEY_DURATION) / 1_000L).coerceAtLeast(0L).toInt(),
            spotifyTrackId = spotifyIdInput?.spotifyTrackId()
                ?: manualSpotifyId
                ?: mutableState.value.detectedSpotifyId,
        )
        if (request.title.isBlank() || request.artist.isBlank()) {
            mutableState.value = mutableState.value.copy(lyrics = LyricsState.Error("Track metadata is insufficient for a lyric lookup"))
            return
        }
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(lyrics = LyricsState.Loading, providerAttempts = emptyList(), lookupStatus = "Starting lyric lookup…")
            runCatching { withContext(Dispatchers.IO) {
                val result = lyricsBackend.resolve(request) { source ->
                    val detail = if (source.id == "spicy_lyrics" && request.spotifyTrackId == null) " · matching Spotify track" else ""
                    mutableState.value = mutableState.value.copy(lookupStatus = "Checking ${source.displayName}$detail…")
                }
                // Rendered here: it includes on-device romanization, which is too heavy for main.
                val rendered = (result as? RemoteLyricsResolution.Found)?.let { found ->
                    runCatching { RemoteLyricsAdapter.render(found.selection, request.durationSeconds * 1_000L) }
                        .getOrElse { LyricsState.Error(it.message ?: "Lyrics could not be displayed") }
                }
                result to rendered
            } }
                .onSuccess { (result, rendered) ->
                    if (currentTrackIdentity != identity) return@onSuccess
                    Log.d("LyricsProviders", result.attempts.joinToString { "${it.sourceId}:${it.outcome}:${it.failureCategory ?: ""}:${it.message ?: ""}" })
                    mutableState.value = mutableState.value.copy(
                        providerAttempts = result.attempts,
                        lookupStatus = when (result) {
                            is RemoteLyricsResolution.Found -> "Showing lyrics from ${result.selection.source.displayName}"
                            is RemoteLyricsResolution.NotFound -> "No enabled source found lyrics"
                            is RemoteLyricsResolution.Unavailable -> "Lookup finished; some sources were unavailable"
                        },
                        lyrics = when (result) {
                        is RemoteLyricsResolution.Found -> requireNotNull(rendered)
                        is RemoteLyricsResolution.NotFound -> LyricsState.Error("No enabled lyric source found this track")
                        is RemoteLyricsResolution.Unavailable -> LyricsState.Error("Lyrics sources are temporarily unavailable")
                    })
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    mutableState.value = mutableState.value.copy(
                        lyrics = LyricsState.Error(error.message ?: "Lyrics request failed"),
                        lookupStatus = "Lyric lookup failed",
                    )
                }
        }
    }

    private fun commandForTrackChange(command: MediaController.TransportControls.() -> Unit) {
        val active = controller ?: return
        pendingCommand = PendingCommand.Track(active.metadata.trackIdentity(), SystemClock.elapsedRealtime())
        active.transportControls.command()
    }

    private fun attachBestSession(sessions: List<MediaController>) {
        val activeTokens = sessions.map { it.sessionToken }.toSet()
        (observedSessions.keys - activeTokens).forEach { token ->
            observedSessions.remove(token)?.let { old ->
                observedCallbacks.remove(token)?.let(old::unregisterCallback)
            }
        }
        sessions.forEach { session ->
            val token = session.sessionToken
            if (token !in observedSessions) {
                observedSessions[token] = session
                val callback = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) = refreshSessionSelection()
                    override fun onSessionDestroyed() = refreshSessionSelection()
                }
                observedCallbacks[token] = callback
                session.registerCallback(callback, mainHandler)
            }
        }
        val best = sessions.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING &&
                it.playbackInfo?.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL
        } ?: sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull { it.sessionToken == controller?.sessionToken }
            ?: sessions.firstOrNull {
                it.playbackInfo?.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL
            } ?: sessions.firstOrNull()
        if (best?.sessionToken == controller?.sessionToken) {
            updateFromController()
            return
        }
        detachController()
        controller = best
        refreshOutputRoute()
        best?.registerCallback(controllerCallback, mainHandler)
        updateFromController(metadataChanged = true)
    }

    private fun refreshSessionSelection() {
        runCatching { sessionManager.getActiveSessions(listenerComponent) }
            .onSuccess(::attachBestSession)
    }

    private fun detachController() {
        controller?.unregisterCallback(controllerCallback)
        controller = null
        currentTrackIdentity = null
        manualSpotifyId = null
        lyricsJob?.cancel()
        timeline = TimelineAnchor(0L, SystemClock.elapsedRealtime(), 0f, false)
    }

    private fun updateFromController(metadataChanged: Boolean = false, preserveStatus: Boolean = false) {
        val active = controller
        if (active == null) {
            mutableState.value = PlayerUiState(
                accessGranted = mutableState.value.accessGranted,
                outputLabel = outputRoute.label,
                lyricDelayMs = outputProfiles.delayMs(outputRoute),
                sourceDescriptors = lyricsBackend.descriptors,
                sourceOrder = lyricsBackend.policy().sourceOrder,
                disabledSourceIds = lyricsBackend.policy().disabledSourceIds,
            )
            return
        }
        val metadata = active.metadata
        val playback = active.playbackState
        val trackIdentity = metadata.trackIdentity()
        val trackChanged = trackIdentity != currentTrackIdentity
        val refreshArtwork = metadataChanged || trackChanged
        val waitingForSeek = pendingCommand is PendingCommand.Seek &&
            pendingCommand?.acknowledged(playback, trackIdentity) != true &&
            SystemClock.elapsedRealtime() - pendingCommand!!.issuedAtMs < 1_500L
        if (!waitingForSeek && playback != null) reconcileClock(playback, force = trackChanged)
        val mediaId = metadata?.description?.mediaId
        val spotifyId = sequenceOf(
            mediaId,
            metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
            metadata?.description?.mediaUri?.toString(),
        ).mapNotNull { it.spotifyTrackId() }.firstOrNull()

        val latency = pendingCommand?.takeIf { it.acknowledged(playback, trackIdentity) }
            ?.let { SystemClock.elapsedRealtime() - it.issuedAtMs }
        if (latency != null) pendingCommand = null

        if (trackChanged) {
            currentTrackIdentity = trackIdentity
            manualSpotifyId = metadata.overrideKey()?.let { overrideStore.getString(it, null) }
            lyricsJob?.cancel()
        }
        mutableState.value = mutableState.value.copy(
            accessGranted = true,
            sourcePackage = active.packageName,
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown track",
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: "Unknown artist",
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L,
            positionMs = currentPositionMs(),
            isPlaying = playback?.state == PlaybackState.STATE_PLAYING,
            canSeek = playback?.actions?.and(PlaybackState.ACTION_SEEK_TO) != 0L && playback != null,
            detectedSpotifyId = manualSpotifyId ?: spotifyId,
            manualSpotifyId = manualSpotifyId,
            matchInfo = if (trackChanged) {
                if (manualSpotifyId != null) "Manual Spotify ID"
                else if (spotifyId != null) "Spotify ID from media session"
                else "Matching through enabled lyric sources…"
            } else mutableState.value.matchInfo,
            lyrics = if (trackChanged) LyricsState.Idle else mutableState.value.lyrics,
            providerAttempts = if (trackChanged) emptyList() else mutableState.value.providerAttempts,
            lookupStatus = if (trackChanged) null else mutableState.value.lookupStatus,
            lastCommandLatencyMs = latency ?: mutableState.value.lastCommandLatencyMs,
            status = if (waitingForSeek || preserveStatus) mutableState.value.status else null,
            artwork = if (refreshArtwork) {
                metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                    ?: metadata?.description?.iconBitmap
            } else mutableState.value.artwork,
            artworkUri = if (refreshArtwork) {
                metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                    ?: metadata?.getString(MediaMetadata.METADATA_KEY_ART_URI)
                    ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
                    ?: metadata?.description?.iconUri?.toString()
            } else mutableState.value.artworkUri,
        )
        if (trackChanged) loadLyrics()
    }

    private fun startTicker() = viewModelScope.launch {
        while (isActive) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastPeriodicCheckMs >= 2_000L) {
                lastPeriodicCheckMs = now
                refreshOutputRoute()
                if (pendingCommand !is PendingCommand.Seek) {
                    updateFromController(preserveStatus = true)
                }
            }
            if (controller != null) {
                val seek = pendingCommand as? PendingCommand.Seek
                if (seek != null && SystemClock.elapsedRealtime() - seek.issuedAtMs >= 1_500L) {
                    pendingCommand = null
                    updateFromController()
                    mutableState.value = mutableState.value.copy(
                        status = "Seek not confirmed by ${controller?.packageName ?: "player"}; timeline restored",
                    )
                }
                mutableState.value = mutableState.value.copy(positionMs = currentPositionMs())
            }
            delay(100L)
        }
    }

    private fun refreshOutputRoute() {
        val active = controller
        val route = if (active?.playbackInfo?.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE) {
            AudioOutputRoute("remote:${active.packageName}", "Remote playback (device unknown)")
        } else outputProfiles.currentRoute()
        if (route.key != outputRoute.key) {
            outputRoute = route
            mutableState.value = mutableState.value.copy(
                outputLabel = route.label,
                lyricDelayMs = outputProfiles.delayMs(route),
            )
        }
    }

    private fun reconcileClock(playback: PlaybackState, force: Boolean = false) {
        if (playback.position < 0L) return
        val now = SystemClock.elapsedRealtime()
        val playing = playback.state == PlaybackState.STATE_PLAYING
        val elapsed = if (playing) (now - playback.lastPositionUpdateTime).coerceAtLeast(0L) else 0L
        val reported = (playback.position + elapsed * playback.playbackSpeed).toLong().coerceAtLeast(0L)
        val predicted = currentPositionMs()
        val drift = reported - predicted
        val changedState = timeline.isPlaying != playing || timeline.speed != playback.playbackSpeed
        val adjustment = if (force || changedState) drift else ClockCorrection.adjustmentMs(drift)
        timeline = TimelineAnchor(
            positionMs = (predicted + adjustment).coerceAtLeast(0L),
            atElapsedMs = now,
            speed = playback.playbackSpeed,
            isPlaying = playing,
        )
        mutableState.value = mutableState.value.copy(clockDriftMs = drift)
    }

    fun currentPositionMs(): Long {
        val elapsed = if (timeline.isPlaying) {
            (SystemClock.elapsedRealtime() - timeline.atElapsedMs).coerceAtLeast(0L)
        } else 0L
        val position = (timeline.positionMs + elapsed * timeline.speed).toLong().coerceAtLeast(0L)
        return mutableState.value.durationMs.takeIf { it > 0L }?.let(position::coerceAtMost) ?: position
    }

    private data class TimelineAnchor(
        val positionMs: Long,
        val atElapsedMs: Long,
        val speed: Float,
        val isPlaying: Boolean,
    )

    override fun onCleared() {
        runCatching { sessionManager.removeOnActiveSessionsChangedListener(activeSessionsListener) }
        stopObservingSessions()
        detachController()
    }

    private fun stopObservingSessions() {
        observedSessions.forEach { (token, session) ->
            observedCallbacks[token]?.let(session::unregisterCallback)
        }
        observedSessions.clear()
        observedCallbacks.clear()
    }

    private sealed interface PendingCommand {
        val issuedAtMs: Long
        fun acknowledged(playback: PlaybackState?, trackIdentity: String?): Boolean

        data class PlayState(val playing: Boolean, override val issuedAtMs: Long) : PendingCommand {
            override fun acknowledged(playback: PlaybackState?, trackIdentity: String?) =
                playback != null && (playback.state == PlaybackState.STATE_PLAYING) == playing
        }

        data class Seek(val targetMs: Long, override val issuedAtMs: Long) : PendingCommand {
            override fun acknowledged(playback: PlaybackState?, trackIdentity: String?) =
                playback != null && kotlin.math.abs(playback.position - targetMs) < 750L
        }

        data class Track(val previousIdentity: String?, override val issuedAtMs: Long) : PendingCommand {
            override fun acknowledged(playback: PlaybackState?, trackIdentity: String?) =
                trackIdentity != previousIdentity
        }
    }
}

private fun MediaMetadata?.trackIdentity(): String? = this?.let {
    trackIdentity(
        mediaId = description.mediaId ?: getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
        title = getString(MediaMetadata.METADATA_KEY_TITLE),
        artist = getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
        album = getString(MediaMetadata.METADATA_KEY_ALBUM),
        durationMs = getLong(MediaMetadata.METADATA_KEY_DURATION),
    )
}

private fun MediaMetadata?.overrideKey(): String? = this?.let {
    val title = getString(MediaMetadata.METADATA_KEY_TITLE)?.trim()?.lowercase().orEmpty()
    val artist = (getString(MediaMetadata.METADATA_KEY_ARTIST)
        ?: getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))?.trim()?.lowercase().orEmpty()
    if (title.isBlank() || artist.isBlank()) return null
    val identity = "$title\u001f$artist\u001f${getLong(MediaMetadata.METADATA_KEY_DURATION)}"
    MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

internal fun trackIdentity(
    mediaId: String?,
    title: String?,
    artist: String?,
    album: String?,
    durationMs: Long,
): String? {
    if (mediaId.isNullOrBlank() && title.isNullOrBlank() && artist.isNullOrBlank()) return null
    return listOf(mediaId.orEmpty(), title.orEmpty(), artist.orEmpty(), album.orEmpty(), durationMs.toString())
        .joinToString("\u001f")
}

internal fun String?.spotifyTrackId(): String? {
    val value = this?.trim().orEmpty()
    val candidate = when {
        value.startsWith("spotify:track:") -> value.substringAfterLast(':')
        "/track/" in value -> value.substringAfter("/track/").substringBefore('?').substringBefore('/')
        else -> value
    }
    return candidate.takeIf { it.length == 22 && it.all(Char::isLetterOrDigit) }
}
