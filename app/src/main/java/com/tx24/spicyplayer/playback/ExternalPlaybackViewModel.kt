package com.tx24.spicyplayer.playback

import android.app.Application
import android.content.ComponentName
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tx24.spicyplayer.BuildConfig
import com.tx24.spicyplayer.lyrics.LyricsNotices
import com.tx24.spicyplayer.lyrics.LyricsState
import com.tx24.spicyplayer.lyrics.NextLyricsBackend
import com.tx24.spicyplayer.lyrics.RemoteLyricsAdapter
import com.tx24.spicyplayer.network.data.LyricsLookupRequest
import com.tx24.spicyplayer.network.data.LyricsSourceDescriptor
import com.tx24.spicyplayer.network.data.ProviderAttempt
import com.tx24.spicyplayer.network.data.ProviderAttemptOutcome
import com.tx24.spicyplayer.network.data.ProviderResult
import com.tx24.spicyplayer.network.data.RemoteLyricsResolution
import com.tx24.spicyplayer.network.data.RemoteLyricsSelection
import com.tx24.spicyplayer.network.data.TrackNameCleaner
import com.tx24.spicyplayer.ui.nowplaying.SessionCustomAction
import com.tx24.spicyplayer.ui.nowplaying.TrackDirection
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How long a track must stay current before its lyrics are fetched; skipping past it costs no requests. */
private const val TRACK_SETTLE_MS = 700L

/** Upcoming queue entries whose lyrics are fetched ahead, like mild-lyrics' default. */
private const val FETCH_AHEAD = 3

/** How long after our own skip a track change is still taken to be its result. */
private const val SKIP_DIRECTION_WINDOW_MS = 3_000L

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
    val trackDirection: TrackDirection = TrackDirection.Forward,
    val customActions: List<SessionCustomAction> = emptyList(),
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
    val blendDescriptors: List<LyricsSourceDescriptor> = emptyList(),
    val enabledBlendIds: Set<String> = emptySet(),
    val providerAttempts: List<ProviderAttempt> = emptyList(),
    val lookupStatus: String? = null,
)

class ExternalPlaybackViewModel(application: Application) : AndroidViewModel(application) {
    private val sessionManager = application.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(application, SessionAccessService::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    // Optional user-supplied Spicy Lyrics key overriding the shipped one; kept on device (backups are disabled).
    private val keyStore = application.getSharedPreferences("spicy_lyrics_key", 0)
    private var runtimeApiKey: String = keyStore.getString("key", null).orEmpty()
    private var lyricsBackend = NextLyricsBackend(application, runtimeApiKey.ifBlank { BuildConfig.SPICY_LYRICS_CLIENT_KEY })
    private val overrideStore = application.getSharedPreferences("spotify_id_overrides", 0)
    private val outputProfiles = AudioOutputProfiles(application)
    private var outputRoute = outputProfiles.currentRoute()
    private val mutableState = MutableStateFlow(PlayerUiState(
        outputLabel = outputRoute.label,
        lyricDelayMs = outputProfiles.delayMs(outputRoute),
        sourceDescriptors = lyricsBackend.descriptors + lyricsBackend.blendDescriptors,
        sourceOrder = lyricsBackend.policy().sourceOrder,
        disabledSourceIds = lyricsBackend.policy().disabledSourceIds,
        blendDescriptors = lyricsBackend.blendDescriptors,
        enabledBlendIds = lyricsBackend.policy().enabledBlendIds,
    ))
    val state: StateFlow<PlayerUiState> = mutableState.asStateFlow()

    private var controller: MediaController? = null
    // Which way our own skip buttons last moved, so the next track change can say so.
    private var pendingSkip: Pair<TrackDirection, Long>? = null
    private var lastQueueIndex = -1
    private val actionIcons = HashMap<Pair<String, Int>, Bitmap?>()
    private val observedSessions = mutableMapOf<MediaSession.Token, MediaController>()
    private val observedCallbacks = mutableMapOf<MediaSession.Token, MediaController.Callback>()
    private var lyricsJob: Job? = null
    private var warmJob: Job? = null
    private var shownSelection: RemoteLyricsSelection? = null
    // Per-source results for recent requests, so a policy change re-picks without refetching.
    private val lookupCache = object : LinkedHashMap<LyricsLookupRequest, MutableMap<String, ProviderResult>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LyricsLookupRequest, MutableMap<String, ProviderResult>>) = size > 30
    }
    // Rendered lyrics for recent picks. Rendering (on-device romanization above all) is the slow
    // step of showing cached lyrics, so a song played again, or warmed ahead, skips it.
    private val renderedCache = object : LinkedHashMap<RemoteLyricsSelection, RenderedLyrics>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RemoteLyricsSelection, RenderedLyrics>) = size > 12
    }
    private var manualSpotifyId: String? = null
    private var currentTrackIdentity: String? = null
    // Players fill metadata in over several updates (YouTube Music adds length and album after
    // the title), so lyrics follow title + artist only; the clock follows the full identity.
    private var currentLyricsKey: String? = null
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
        // The grant is per listener class, not per app: after the class moves, the package can
        // still be listed while this component is not, and every session read is refused.
        val granted = Settings.Secure.getString(getApplication<Application>().contentResolver, "enabled_notification_listeners")
            .orEmpty().split(':')
            .any { ComponentName.unflattenFromString(it) == listenerComponent }
        mutableState.value = mutableState.value.copy(accessGranted = granted)
        if (!granted) {
            stopObservingSessions()
            detachController()
            mutableState.value = PlayerUiState(
                accessGranted = false,
                outputLabel = outputRoute.label,
                lyricDelayMs = outputProfiles.delayMs(outputRoute),
                sourceDescriptors = lyricsBackend.descriptors + lyricsBackend.blendDescriptors,
                sourceOrder = lyricsBackend.policy().sourceOrder,
                disabledSourceIds = lyricsBackend.policy().disabledSourceIds,
                blendDescriptors = lyricsBackend.blendDescriptors,
                enabledBlendIds = lyricsBackend.policy().enabledBlendIds,
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

    fun skipNext() {
        pendingSkip = TrackDirection.Forward to SystemClock.elapsedRealtime()
        commandForTrackChange { skipToNext() }
    }

    fun skipPrevious() {
        pendingSkip = TrackDirection.Backward to SystemClock.elapsedRealtime()
        commandForTrackChange { skipToPrevious() }
    }

    /** Presses one of the player's own buttons (see [PlayerUiState.customActions]). */
    fun sendCustomAction(action: String) {
        val active = controller ?: return
        val extras = active.playbackState?.customActions?.firstOrNull { it.action == action }?.extras
        active.transportControls.sendCustomAction(action, extras)
    }

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

    fun setLyricDelay(delayMs: Int) {
        adjustLyricDelay(delayMs - mutableState.value.lyricDelayMs)
    }

    fun resetLyricDelay() {
        outputProfiles.saveDelayMs(outputRoute, 0)
        mutableState.value = mutableState.value.copy(lyricDelayMs = 0)
    }

    fun currentLyricPositionMs(): Long =
        (currentPositionMs() - mutableState.value.lyricDelayMs).coerceAtLeast(0L)

    fun useApiKey(key: String) {
        runtimeApiKey = key.trim()
        keyStore.edit().putString("key", runtimeApiKey).apply()
        lyricsBackend = NextLyricsBackend(getApplication(), runtimeApiKey.ifBlank { BuildConfig.SPICY_LYRICS_CLIENT_KEY })
        lookupCache.clear()  // Spicy Lyrics answers depend on the key
        refreshSourcePolicy()
        loadLyrics()
    }

    /** Forgets every lyric answer, on disk and in memory, and looks the current song up again. */
    fun clearLyricsCache() {
        lookupCache.clear()
        synchronized(renderedCache) { renderedCache.clear() }
        lyricsBackend.clearCache()
        loadLyrics(force = true)
    }

    fun setSourceEnabled(id: String, enabled: Boolean) {
        val disabled = mutableState.value.disabledSourceIds.toMutableSet()
        if (enabled) disabled.remove(id) else disabled.add(id)
        lyricsBackend.setPolicy(mutableState.value.sourceOrder, disabled)
        refreshSourcePolicy()
        loadLyrics()
    }

    fun setBlendEnabled(id: String, enabled: Boolean) {
        lyricsBackend.setBlendEnabled(id, enabled)
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
            sourceDescriptors = lyricsBackend.descriptors + lyricsBackend.blendDescriptors,
            sourceOrder = policy.sourceOrder,
            disabledSourceIds = policy.disabledSourceIds,
            blendDescriptors = lyricsBackend.blendDescriptors,
            enabledBlendIds = policy.enabledBlendIds,
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

    /**
     * [settle]: a track change waits [TRACK_SETTLE_MS] before any request, so skipping through
     * tracks costs nothing (the wait is cancelled by the next change). [force] drops this
     * track's cached source results, e.g. for Retry.
     */
    fun loadLyrics(spotifyIdInput: String? = null, settle: Boolean = false, force: Boolean = false) {
        val metadata = controller?.metadata ?: return
        val identity = metadata.lyricsKey()
        val names = TrackNameCleaner.clean(
            title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty(),
            artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty(),
        )
        val request = LyricsLookupRequest(
            artist = names.artist,
            title = names.title,
            album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            durationSeconds = (metadata.getLong(MediaMetadata.METADATA_KEY_DURATION) / 1_000L).coerceAtLeast(0L).toInt(),
            spotifyTrackId = spotifyIdInput?.spotifyTrackId()
                ?: manualSpotifyId
                ?: mutableState.value.detectedSpotifyId,
        )
        if (request.title.isBlank() || request.artist.isBlank()) {
            mutableState.value = mutableState.value.copy(lyrics = LyricsNotices.missingMetadata)
            return
        }
        lyricsJob?.cancel()
        warmJob?.cancel()  // the song on screen goes first
        if (force) lookupCache.remove(request)
        // Results per source for this request: a policy change re-picks from these instead of refetching.
        val known = lookupCache.getOrPut(request) { ConcurrentHashMap() }
        lyricsJob = viewModelScope.launch {
            if (mutableState.value.lyrics !is LyricsState.Ready) {
                mutableState.value = mutableState.value.copy(lyrics = LyricsState.Loading, lookupStatus = "Starting lyric lookup…")
            }
            // The disk cache is keyed on title + artist; a manual Spotify ID asks the sources afresh.
            if (known.isEmpty() && manualSpotifyId == null) {
                val cached = withContext(Dispatchers.IO) {
                    if (force) lyricsBackend.forget(request)
                    lyricsBackend.cachedResolution(request)
                }
                if (cached != null) {
                    (cached as? RemoteLyricsResolution.Found)?.let { known[it.selection.source.id] = ProviderResult.Hit(it.selection.payload) }
                    publish(cached, identity, request, final = true)
                    warmAhead()
                    return@launch
                }
            }
            if (settle && known.isEmpty()) delay(TRACK_SETTLE_MS)
            runCatching {
                withContext(Dispatchers.IO) {
                    lyricsBackend.resolve(request, known) { update ->
                        withContext(Dispatchers.Main) { publish(update, identity, request, final = false) }
                    }
                }
            }
                .onSuccess { resolution ->
                    withContext(Dispatchers.IO) { lyricsBackend.store(request, resolution) }
                    publish(resolution, identity, request, final = true)
                    warmAhead()
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    mutableState.value = mutableState.value.copy(
                        lyrics = LyricsNotices.lookupFailed(error),
                        lookupStatus = "Lyric lookup failed",
                    )
                }
        }
    }

    /**
     * Fetches the next [FETCH_AHEAD] queue entries into the disk cache, one at a time, like
     * mild-lyrics' look-ahead: started only once the current song is settled and cancelled by
     * the next lookup, so it never competes with the song on screen. Cached entries cost nothing.
     */
    private fun warmAhead() {
        val session = controller ?: return
        val queue = session.queue.orEmpty()
        val at = queue.indexOfFirst { it.queueId == session.playbackState?.activeQueueItemId }
        if (at < 0) return
        val upcoming = queue.drop(at + 1).take(FETCH_AHEAD).mapNotNull { item ->
            val d = item.description
            val names = TrackNameCleaner.clean(d.title?.toString().orEmpty(), d.subtitle?.toString().orEmpty())
            if (names.title.isBlank() || names.artist.isBlank()) return@mapNotNull null
            LyricsLookupRequest(
                artist = names.artist,
                title = names.title,
                album = "",
                durationSeconds = ((d.extras?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L) / 1_000L).toInt(),
            )
        }
        val backend = lyricsBackend
        warmJob = viewModelScope.launch(Dispatchers.IO) {
            for (request in upcoming) {
                val resolution = backend.cachedResolution(request)
                    // Failures are not reported: nobody is looking at this song yet.
                    ?: runCatching { backend.resolve(request, ConcurrentHashMap()).also { backend.store(request, it) } }
                        .onFailure { if (it is CancellationException) throw it }
                        .getOrNull()
                // Rendered too, so the song's lyrics are up the moment it starts.
                (resolution as? RemoteLyricsResolution.Found)?.selection?.let { selection ->
                    withContext(Dispatchers.Default) { runCatching { rendered(selection, request.durationSeconds * 1_000L) } }
                }
            }
        }
    }

    /** Shows the best answer so far; re-renders only when the pick itself changes. */
    private suspend fun publish(
        resolution: RemoteLyricsResolution,
        identity: String?,
        request: LyricsLookupRequest,
        final: Boolean,
    ) {
        if (currentLyricsKey != identity) return
        val selection = (resolution as? RemoteLyricsResolution.Found)?.selection
        val lyrics = when {
            // Nothing picked yet: whatever is up stays up until the lookup ends.
            selection == null && !final -> mutableState.value.lyrics
            selection == null -> LyricsNotices.noLyrics(resolution.attempts) { id ->
                lyricsBackend.descriptors.firstOrNull { it.id == id }?.displayName ?: id
            }
            selection == shownSelection -> mutableState.value.lyrics
            // Rendering includes on-device romanization, which is too heavy for main.
            else -> withContext(Dispatchers.Default) {
                runCatching { rendered(selection, request.durationSeconds * 1_000L) }
                    .getOrElse { LyricsNotices.renderFailed(selection.source.displayName, it) }
            }
        }
        if (currentLyricsKey != identity) return
        shownSelection = selection
        val pending = resolution.attempts.filter { it.outcome == ProviderAttemptOutcome.PENDING }
        val names = pending.joinToString { attempt ->
            lyricsBackend.descriptors.firstOrNull { it.id == attempt.sourceId }?.displayName ?: attempt.sourceId
        }
        if (final) Log.d("LyricsProviders", resolution.attempts.joinToString { "${it.sourceId}:${it.outcome}:${it.failureCategory ?: ""}:${it.message ?: ""}" })
        mutableState.value = mutableState.value.copy(
            providerAttempts = resolution.attempts,
            lyrics = lyrics,
            lookupStatus = when {
                selection != null && pending.isNotEmpty() -> "Showing ${selection.source.displayName} · still checking $names…"
                selection != null -> "Showing lyrics from ${selection.source.displayName}"
                pending.isNotEmpty() -> "Checking $names…"
                resolution is RemoteLyricsResolution.NotFound -> "No enabled source found lyrics"
                else -> "Lookup finished; some sources were unavailable"
            },
        )
    }

    /** [selection] rendered for the renderer, from [renderedCache] when it holds it. */
    private fun rendered(selection: RemoteLyricsSelection, durationMs: Long): LyricsState.Ready {
        val payload = selection.payload
        // Only LRC depends on the length (its last line ends with the song).
        val usesDuration = payload.ttmlLyrics.isNullOrBlank() && !payload.syncedLyrics.isNullOrBlank()
        synchronized(renderedCache) { renderedCache[selection] }
            ?.takeIf { !usesDuration || it.durationMs == durationMs }
            ?.let { return it.lyrics }
        val lyrics = RemoteLyricsAdapter.render(selection, durationMs)
        synchronized(renderedCache) { renderedCache[selection] = RenderedLyrics(durationMs, lyrics) }
        return lyrics
    }

    private class RenderedLyrics(val durationMs: Long, val lyrics: LyricsState.Ready)

    /** A custom action's icon, which lives in the player's own resources. */
    private fun actionIcon(packageName: String, iconRes: Int): Bitmap? = actionIcons.getOrPut(packageName to iconRes) {
        try {
            val resources = getApplication<Application>().packageManager.getResourcesForApplication(packageName)
            ResourcesCompat.getDrawable(resources, iconRes, null)?.toBitmap()
        } catch (_: Exception) {
            null
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
        currentLyricsKey = null
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
                sourceDescriptors = lyricsBackend.descriptors + lyricsBackend.blendDescriptors,
                sourceOrder = lyricsBackend.policy().sourceOrder,
                disabledSourceIds = lyricsBackend.policy().disabledSourceIds,
                blendDescriptors = lyricsBackend.blendDescriptors,
                enabledBlendIds = lyricsBackend.policy().enabledBlendIds,
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

        val queueIndex = active.queue.orEmpty().indexOfFirst { it.queueId == playback?.activeQueueItemId }
        val direction = if (trackChanged) {
            // Our own skip says it outright; otherwise a lower queue position means we went back.
            pendingSkip?.takeIf { SystemClock.elapsedRealtime() - it.second < SKIP_DIRECTION_WINDOW_MS }?.first
                ?: if (queueIndex >= 0 && lastQueueIndex >= 0 && queueIndex < lastQueueIndex) {
                    TrackDirection.Backward
                } else TrackDirection.Forward
        } else mutableState.value.trackDirection
        if (trackChanged) pendingSkip = null
        if (queueIndex >= 0) lastQueueIndex = queueIndex
        if (trackChanged) currentTrackIdentity = trackIdentity
        val lyricsKey = metadata.lyricsKey()
        val lyricsChanged = lyricsKey != currentLyricsKey
        if (lyricsChanged) {
            currentLyricsKey = lyricsKey
            shownSelection = null
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
            matchInfo = if (lyricsChanged) {
                if (manualSpotifyId != null) "Manual Spotify ID"
                else if (spotifyId != null) "Spotify ID from media session"
                else "Matching through enabled lyric sources…"
            } else mutableState.value.matchInfo,
            // A new song: Loading (which shows nothing at first), not Idle's "Waiting for a song".
            lyrics = when {
                !lyricsChanged -> mutableState.value.lyrics
                lyricsKey == null -> LyricsState.Idle
                else -> LyricsState.Loading
            },
            providerAttempts = if (lyricsChanged) emptyList() else mutableState.value.providerAttempts,
            lookupStatus = if (lyricsChanged) null else mutableState.value.lookupStatus,
            lastCommandLatencyMs = latency ?: mutableState.value.lastCommandLatencyMs,
            status = if (waitingForSeek || preserveStatus) mutableState.value.status else null,
            trackDirection = direction,
            customActions = playback?.customActions.orEmpty().map { custom ->
                SessionCustomAction(custom.action, custom.name.toString(), actionIcon(active.packageName, custom.icon))
            },
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
        if (lyricsChanged) loadLyrics(settle = true)
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

private fun MediaMetadata?.lyricsKey(): String? = this?.let {
    val title = (getString(MediaMetadata.METADATA_KEY_TITLE) ?: getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))
        ?.trim()?.lowercase().orEmpty()
    val artist = (getString(MediaMetadata.METADATA_KEY_ARTIST)
        ?: getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))?.trim()?.lowercase().orEmpty()
    if (title.isBlank() && artist.isBlank()) null else "$title\u001f$artist"
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
