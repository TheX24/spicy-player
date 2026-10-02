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
import com.tx24.spicyplayer.haptics.MusicHaptic
import com.tx24.spicyplayer.haptics.MusicHapticScore
import com.tx24.spicyplayer.haptics.MusicHapticsStyle
import com.tx24.spicyplayer.lyrics.LocalLyricsStore
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
import com.tx24.spicyplayer.network.data.LyricsCapability
import com.tx24.spicyplayer.network.data.RemoteLyricsPayload
import com.tx24.spicyplayer.network.data.measuredQuality
import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import com.tx24.spicyplayer.network.data.ItunesReleaseYear
import com.tx24.spicyplayer.network.data.spotify.AudioAnalysis
import com.tx24.spicyplayer.network.data.spotify.LocalTrackMetadata
import com.tx24.spicyplayer.network.data.spotify.SharedSpotify
import com.tx24.spicyplayer.network.data.spotify.SpotifyTrackCandidate
import com.tx24.spicyplayer.lyrics.spicy.canvas.kawarp.BackgroundSpeed
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * While skipping (track changes closer together than this), how long a track must stay current
 * before its lyrics are fetched, so skipping past it costs no requests.
 */
private const val TRACK_SETTLE_MS = 700L

/** Beats Spotify is less sure of than this aren't tapped to. */
private const val CALIBRATION_BEAT_CONFIDENCE = 0.2f

/** Upcoming queue entries whose lyrics are fetched ahead. */
private const val FETCH_AHEAD = 3

/** How long after our own skip a track change is still taken to be its result. */
private const val SKIP_DIRECTION_WINDOW_MS = 3_000L
private const val SYNC_TAG = "SpicySync"
private const val SPOTIFY_PACKAGE = "com.spotify.music"
/** How long a skip may take before Spotify is taken to have refused it. */
private const val SKIP_REFUSED_AFTER_MS = 2_500L
/** How long after a track change a pause is taken for the player loading the song. */
private const val LOADING_PAUSE_GRACE_MS = 3_000L
/**
 * A report this recent when the song changes may already be the new song's: players often send
 * the new position a moment before the new title. An older one is the previous song's.
 */
private const val TRACK_REPORT_FRESH_MS = 1_000L
/** Lyrics saved in the Lyrics Manager, and lyrics uploaded for one song "just once". */
internal val LOCAL_SOURCE = LyricsSourceDescriptor("local", "Local Lyrics DB", 0, setOf(LyricsCapability.WORD_SYNC))
internal val UPLOADED_SOURCE = LyricsSourceDescriptor("uploaded", "Uploaded TTML", 0, setOf(LyricsCapability.WORD_SYNC))
/** How wide a saved song's cover is kept for the Lyrics Manager's list. */
private const val COVER_THUMB_PX = 128
private val IN_BETWEEN_STATES = setOf(
    PlaybackState.STATE_BUFFERING,
    PlaybackState.STATE_CONNECTING,
    PlaybackState.STATE_SKIPPING_TO_NEXT,
    PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
    PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
)

/**
 * Something Spotify Free doesn't allow, noticed when Spotify leaves a command out of its session
 * or ignores it. Android can't tell which plan an account is on, so this is how it shows.
 */
enum class PlayerLimit { Seek, Previous, Skips }

data class PlayerUiState(
    val accessGranted: Boolean = false,
    val sourcePackage: String? = null,
    val title: String = "Nothing playing",
    val artist: String = "Start playback in another app",
    val album: String = "",
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val canSeek: Boolean = false,
    val outputLabel: String = "Detecting output",
    val lyricDelayMs: Int = 0,
    /** This song's own delay, on top of [lyricDelayMs] ([SongDelays]). */
    val songDelayMs: Int = 0,
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
    val humanRomanizations: Boolean = true,
    val ignoreMusixmatchWordSync: Boolean = true,
    /** A Spotify Free limit to explain, until dismissed. */
    val limitNotice: PlayerLimit? = null,
    /** The song's release year, when asked for ([ExternalPlaybackViewModel.setTrackExtrasWanted]) and found. */
    val releaseYear: String? = null,
    /** Still looking the year up: the header keeps room for it meanwhile. */
    val releaseYearPending: Boolean = false,
    /** The lead artist's Spotify header image, when asked for and they have one. */
    val artistHeaderUrl: String? = null,
    val artistHeaderPending: Boolean = false,
    /** The Lyrics Manager's saved songs, newest first. */
    val localLyrics: List<LocalLyricsStore.Entry> = emptyList(),
    /** The playing song's key in the Lyrics Manager, saved there or not. */
    val localLyricsKey: String? = null,
    /** The output delay is being found by tapping along ([TapCalibration]); the music haptics rest. */
    val calibratingDelay: Boolean = false,
)

/** The lookups beyond the lyrics that the screen currently shows. */
data class TrackExtrasWanted(
    val releaseYear: Boolean = false,
    val artistHeader: Boolean = false,
    val beats: Boolean = false,
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
    private val songDelays = SongDelays(application)
    private var outputRoute = outputProfiles.currentRoute()
    private val mutableState = MutableStateFlow(PlayerUiState(
        outputLabel = outputRoute.label,
        lyricDelayMs = outputProfiles.delayMs(outputRoute),
        sourceDescriptors = lyricsBackend.descriptors + lyricsBackend.blendDescriptors,
        sourceOrder = lyricsBackend.policy().sourceOrder,
        disabledSourceIds = lyricsBackend.policy().disabledSourceIds,
        blendDescriptors = lyricsBackend.blendDescriptors,
        enabledBlendIds = lyricsBackend.policy().enabledBlendIds,
        humanRomanizations = lyricsBackend.humanRomanizations,
        ignoreMusixmatchWordSync = lyricsBackend.ignoreMusixmatchWordSync,
    ))
    val state: StateFlow<PlayerUiState> = mutableState.asStateFlow()
    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Short notes on what an action did ("Removed from Local DB."), shown as toasts. */
    val messages: SharedFlow<String> = mutableMessages

    private val localLyrics = LocalLyricsStore(File(application.filesDir, "local-lyrics"))
    /** A TTML applied to one song "just once" (by lyrics key): it holds until the song changes or is reset. */
    private var temporaryLyrics: Pair<String, RemoteLyricsSelection>? = null

    private var controller: MediaController? = null
    // Which way our own skip buttons last moved, so the next track change can say so.
    private var pendingSkip: Pair<TrackDirection, Long>? = null
    private var lastQueueIndex = -1
    private val actionIcons = HashMap<Pair<String, Int>, Bitmap?>()
    private val observedSessions = mutableMapOf<MediaSession.Token, MediaController>()
    private val observedCallbacks = mutableMapOf<MediaSession.Token, MediaController.Callback>()
    private var lyricsJob: Job? = null
    /** When the last track change arrived, to tell skipping from a song handing over. */
    private var lastTrackChangeAt = Long.MIN_VALUE / 2
    private var warmJob: Job? = null
    private var shownSelection: RemoteLyricsSelection? = null
    private var shownRequest: LyricsLookupRequest? = null
    /** What the last lookup asked for, to notice the player filling in the song's length later. */
    private var lookedUp: LyricsLookupRequest? = null
    private var humanJob: Job? = null
    // Lyrics with Genius's romanization laid over, per pick, so a replay doesn't wait for it again.
    private val humanCache = object : LinkedHashMap<RemoteLyricsSelection, LyricsState.Ready>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RemoteLyricsSelection, LyricsState.Ready>) = size > 12
    }
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
    private val clock = LyricClock(SystemClock.elapsedRealtime())
    private var lastPeriodicCheckMs = 0L
    /** Whether the lyrics screen is started. The ticker only polls while it is. */
    private val uiStarted = MutableStateFlow(true)
    /** When the last fresh report arrived. */
    private var lastReportAt = Long.MIN_VALUE / 2
    /** The song changed before its first report: that report re-anchors the clock outright. */
    private var awaitingTrackReport = false
    /** Limits already explained this session: each is said once, not on every refused tap. */
    private val explainedLimits = mutableSetOf<PlayerLimit>()
    private var skipCheckJob: Job? = null
    /** When the track last changed, for a pause some players report while they load the next one. */
    private var trackChangedAt = Long.MIN_VALUE / 2
    /** Resync on a player that can't seek: the next fresh report re-anchors the clock outright. */
    private var resyncOnNextReport = false
    /** The player that last ignored a seek it lists (Spotify Free): its resyncs go by its reports. */
    private var seekRefusedBy: String? = null

    private val spotifyExtras = SharedSpotify.extras(application.cacheDir)
    private val itunesYear = ItunesReleaseYear(okhttp3.OkHttpClient())
    private var extrasWanted = TrackExtrasWanted()
    /** What the screen asks for; [extrasWanted] adds the beats while calibrating. */
    private var screenExtrasWanted = TrackExtrasWanted()
    private var extrasJob: Job? = null
    /** The playing song's audio analysis, for the beat-reactive background. */
    @Volatile private var audioAnalysis: AudioAnalysis? = null
    /** The last music haptics built: from which analysis, in which style. */
    @Volatile private var musicHaptics: Triple<AudioAnalysis, MusicHapticsStyle, List<MusicHaptic>>? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = updateFromController(metadataChanged = true)
        override fun onPlaybackStateChanged(playbackState: PlaybackState?) = updateFromController()
        override fun onSessionDestroyed() = refresh()
    }

    private val activeSessionsListener = MediaSessionManager.OnActiveSessionsChangedListener {
        attachBestSession(it.orEmpty())
    }

    init {
        // The Spotify token that matching needs is fetched now, not by the first song.
        viewModelScope.launch(Dispatchers.IO) { lyricsBackend.warmUp() }
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
        // Only the button changes now. The clock waits for the player's report: the song goes on
        // for a moment after a pause is sent (and starts a moment after a play), and stopping the
        // lyrics on the tap lost that moment on every pause.
        if (expectPlaying) active.transportControls.play() else active.transportControls.pause()
        mutableState.value = mutableState.value.copy(isPlaying = expectPlaying)
    }

    fun skipNext() {
        pendingSkip = TrackDirection.Forward to SystemClock.elapsedRealtime()
        watchForRefusedSkip(PlayerLimit.Skips, PlaybackState.ACTION_SKIP_TO_NEXT)
        commandForTrackChange { skipToNext() }
    }

    fun skipPrevious() {
        pendingSkip = TrackDirection.Backward to SystemClock.elapsedRealtime()
        watchForRefusedSkip(PlayerLimit.Previous, PlaybackState.ACTION_SKIP_TO_PREVIOUS)
        commandForTrackChange { skipToPrevious() }
    }

    fun dismissLimitNotice() {
        mutableState.value = mutableState.value.copy(limitNotice = null)
    }

    private fun explainLimit(limit: PlayerLimit) {
        if (controller?.packageName != SPOTIFY_PACKAGE || !explainedLimits.add(limit)) return
        mutableState.value = mutableState.value.copy(limitNotice = limit)
    }

    /**
     * Spotify Free drops skip actions from its session once the hourly skips run out, or keeps
     * them and ignores the press. Either way the song stays: a previous that restarts it counts.
     */
    private fun watchForRefusedSkip(limit: PlayerLimit, action: Long) {
        val active = controller ?: return
        if (active.packageName != SPOTIFY_PACKAGE) return
        if (((active.playbackState?.actions ?: 0L) and action) == 0L) {
            explainLimit(limit)
            return
        }
        val before = active.metadata.trackIdentity()
        val positionBefore = currentPositionMs()
        skipCheckJob?.cancel()
        skipCheckJob = viewModelScope.launch {
            delay(SKIP_REFUSED_AFTER_MS)
            if (controller !== active || currentTrackIdentity != before) return@launch
            val restarted = limit == PlayerLimit.Previous && currentPositionMs() < positionBefore
            if (!restarted) explainLimit(limit)
        }
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

    fun seekTo(targetMs: Long) = seekTo(targetMs, resync = false)

    private fun seekTo(targetMs: Long, resync: Boolean) {
        val active = controller ?: return
        if (((active.playbackState?.actions ?: 0L) and PlaybackState.ACTION_SEEK_TO) == 0L) {
            mutableState.value = mutableState.value.copy(
                status = "${active.packageName} does not expose seeking through MediaSession",
            )
            explainLimit(PlayerLimit.Seek)
            return
        }
        val target = targetMs.coerceIn(
            0L,
            mutableState.value.durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE,
        )
        pendingCommand = PendingCommand.Seek(target, SystemClock.elapsedRealtime(), resync)
        clock.seekTo(target, SystemClock.elapsedRealtime())
        mutableState.value = mutableState.value.copy(status = null)
        active.transportControls.seekTo(target)
    }

    /**
     * Android can't ask a player where it is, only take its last report. So this seeks the player
     * to where the lyrics think it is: it must answer with a fresh report, which the clock takes
     * outright. A player that can't seek, or lists seeking but ignores it (Spotify Free, found out
     * on the first try), snaps to its last report now and to its next one outright.
     */
    fun resync() {
        pendingCommand = null
        refresh()
        val active = controller
        val seekable = ((active?.playbackState?.actions ?: 0L) and PlaybackState.ACTION_SEEK_TO) != 0L
        if (active != null && seekable && active.packageName != seekRefusedBy) {
            seekTo(currentPositionMs(), resync = true)
            return
        }
        resyncFromReports()
    }

    private fun resyncFromReports() {
        val active = controller
        active?.playbackState?.let { reconcileClock(it, force = true, snap = it.state == PlaybackState.STATE_PLAYING) }
        resyncOnNextReport = active != null
        mutableState.value = mutableState.value.copy(
            lastCommandLatencyMs = null,
            status = if (controller != null) "Timeline resynced to the media session" else "No active media session to resync",
        )
    }

    /** Positive delay makes the lyrics appear later; negative delay advances them. */
    fun adjustLyricDelay(deltaMs: Int) {
        val newDelay = clampDelay(mutableState.value.lyricDelayMs + deltaMs)
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

    /** Sets this song's own delay; does nothing while no song is identified. */
    fun setSongDelay(delayMs: Int) {
        val key = mutableState.value.localLyricsKey ?: return
        val newDelay = clampDelay(delayMs)
        songDelays.save(key, newDelay)
        mutableState.value = mutableState.value.copy(songDelayMs = newDelay)
    }

    fun adjustSongDelay(deltaMs: Int) = setSongDelay(mutableState.value.songDelayMs + deltaMs)

    fun currentLyricPositionMs(): Long = mutableState.value.let {
        lyricPositionMs(currentPositionMs(), it.lyricDelayMs, it.songDelayMs)
    }

    fun useApiKey(key: String) {
        runtimeApiKey = key.trim()
        keyStore.edit().putString("key", runtimeApiKey).apply()
        lyricsBackend = NextLyricsBackend(getApplication(), runtimeApiKey.ifBlank { BuildConfig.SPICY_LYRICS_CLIENT_KEY })
        lyricsBackend.let { backend -> viewModelScope.launch(Dispatchers.IO) { backend.warmUp() } }
        lookupCache.clear()  // Spicy Lyrics answers depend on the key
        refreshSourcePolicy()
        loadLyrics()
    }

    /** Forgets this song's lyrics everywhere they are kept (bar the Lyrics Manager) and asks the sources again. */
    fun clearCurrentSongCaches() {
        val request = currentRequest() ?: return noTrack()
        forgetShown(request)
        lyricsBackend.forgetRomanization(request.title, request.artist)
        loadLyrics(force = true)
        mutableMessages.tryEmit("Lyrics for the current song have been removed from all caches.")
    }

    /** Deletes every stored pick. The lyrics on screen stay: they are held in memory. */
    fun clearStoredLyricsCache() {
        val backend = lyricsBackend
        viewModelScope.launch(Dispatchers.IO) {
            backend.clearCache()
            mutableMessages.tryEmit("The lyrics cache has been cleared.")
        }
    }

    /** Drops this song's lyrics from memory only, so they are read back from the cache or asked for again. */
    fun clearCurrentSongFromMemory() {
        val request = currentRequest() ?: return noTrack()
        forgetShown(request)
        loadLyrics()
        mutableMessages.tryEmit("Lyrics for the current song have been removed from memory.")
    }

    /** Shows [message] as a toast. */
    fun showMessage(message: String) {
        mutableMessages.tryEmit(message)
    }

    private fun noTrack() = showMessage("No track is currently playing.")

    /**
     * Drops a just-once upload and makes the next load pick this song's lyrics afresh.
     * [dropResults] also forgets the sources' answers and the rendered lyrics, so they are asked
     * for and rendered again; without it what is already in is re-picked at once.
     */
    private fun forgetShown(request: LyricsLookupRequest, dropResults: Boolean = true) {
        temporaryLyrics = null
        if (dropResults) lookupCache.remove(request)
        shownSelection?.takeIf { dropResults }?.let { shown ->
            synchronized(renderedCache) { renderedCache.remove(shown) }
            synchronized(humanCache) { humanCache.remove(shown) }
        }
        shownSelection = null
    }

    /** The playing song as the sources are asked for it. */
    private fun currentRequest(): LyricsLookupRequest? {
        val metadata = controller?.metadata ?: return null
        return lookupRequest(metadata, manualSpotifyId ?: mutableState.value.detectedSpotifyId)
            .takeIf { it.title.isNotBlank() && it.artist.isNotBlank() }
    }

    /** Re-reads the Lyrics Manager's list. */
    fun refreshLocalLyrics() {
        viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) { localLyrics.entries() }
            mutableState.value = mutableState.value.copy(localLyrics = entries)
        }
    }

    /**
     * Shows [ttml] for the playing song: saved in the Lyrics Manager when [persistent] (it then
     * beats every source), else for now only. False, with the reason said, when it can't be used.
     */
    suspend fun importTtml(ttml: String, persistent: Boolean): Boolean {
        val request = currentRequest() ?: return false.also { noTrack() }
        val selection = localSelection(ttml, persistent)
        val usable = withContext(Dispatchers.Default) {
            runCatching { RemoteLyricsAdapter.render(selection, request.durationSeconds * 1_000L) }
                .getOrNull()?.lines?.isNotEmpty() == true
        }
        if (!usable) {
            mutableMessages.tryEmit("Failed to parse TTML.")
            return false
        }
        forgetShown(request, dropResults = false)
        if (persistent) {
            val state = mutableState.value
            val artwork = state.artwork
            withContext(Dispatchers.IO) {
                val entry = localLyrics.put(request.title, request.artist, state.title, state.artist, state.album, ttml)
                artwork?.let { saveCover(it, localLyrics.coverFile(entry.key)) }
            }
            refreshLocalLyrics()
            mutableMessages.tryEmit("TTML saved to Local DB!")
        } else {
            temporaryLyrics = currentLyricsKey.orEmpty() to selection
            mutableMessages.tryEmit("Lyrics parsed and applied!")
        }
        loadLyrics()
        return true
    }

    /** Deletes a saved song; if it's the one playing, its lyrics are looked up again. */
    fun removeLocalLyrics(key: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { localLyrics.remove(key) }
            refreshLocalLyrics()
            mutableMessages.tryEmit("Removed from Local DB.")
            val request = currentRequest() ?: return@launch
            if (LocalLyricsStore.keyOf(request.title, request.artist) == key) {
                forgetShown(request, dropResults = false)
                loadLyrics()
            }
        }
    }

    /** The TTML saved under [key], for exporting. */
    suspend fun localLyricsText(key: String): String? = withContext(Dispatchers.IO) { localLyrics.raw(key) }

    /** Where a saved song's cover thumbnail is, if it has one. */
    fun localLyricsCover(key: String): File = localLyrics.coverFile(key)

    /**
     * Takes down lyrics applied just once, back to the ones found online. A saved song's stay (they
     * beat the sources until deleted), which is said rather than seeming to do nothing.
     */
    fun resetTtml() {
        val request = currentRequest() ?: return noTrack()
        val uploaded = temporaryLyrics?.first == currentLyricsKey.orEmpty()
        forgetShown(request, dropResults = false)
        loadLyrics()
        val saved = localLyrics.contains(request.title, request.artist)
        showMessage(
            when {
                uploaded && saved -> "TTML has been reset. This song's saved lyrics are showing; delete them to use the online ones."
                saved -> "This song's lyrics are saved in the Local DB. Delete them there to go back to the online ones."
                uploaded -> "TTML has been reset."
                else -> "No uploaded TTML to reset."
            },
        )
    }

    private fun localSelection(ttml: String, persistent: Boolean): RemoteLyricsSelection {
        val payload = RemoteLyricsPayload(ttmlLyrics = ttml, sourceId = LOCAL_SOURCE.id)
        return RemoteLyricsSelection(if (persistent) LOCAL_SOURCE else UPLOADED_SOURCE, payload, payload.measuredQuality())
    }

    /** The playing song's lyrics from the Lyrics Manager: a just-once upload, else a saved song. */
    private fun localLyricsFor(identity: String?, request: LyricsLookupRequest): RemoteLyricsResolution? {
        val selection = temporaryLyrics?.takeIf { it.first == identity.orEmpty() }?.second
            ?: localLyrics.get(request.title, request.artist)?.let { localSelection(it, persistent = true) }
            ?: return null
        val message = if (selection.source == UPLOADED_SOURCE) "applied once" else "saved"
        return RemoteLyricsResolution.Found(
            selection,
            listOf(ProviderAttempt(selection.source.id, ProviderAttemptOutcome.HIT, selection.quality, message = message)),
        )
    }

    private fun saveCover(artwork: Bitmap, file: File) {
        val height = (COVER_THUMB_PX * artwork.height / artwork.width.coerceAtLeast(1)).coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(artwork, COVER_THUMB_PX, height, true)
        runCatching { file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) } }
        if (scaled !== artwork) scaled.recycle()
    }

    fun setSourceEnabled(id: String, enabled: Boolean) {
        val disabled = mutableState.value.disabledSourceIds.toMutableSet()
        if (enabled) disabled.remove(id) else disabled.add(id)
        lyricsBackend.setPolicy(mutableState.value.sourceOrder, disabled)
        refreshSourcePolicy()
        loadLyrics()
    }

    fun setIgnoreMusixmatchWordSync(enabled: Boolean) {
        lyricsBackend.ignoreMusixmatchWordSync = enabled
        mutableState.value = mutableState.value.copy(ignoreMusixmatchWordSync = enabled)
        lookupCache.clear()  // the Musixmatch sources answer differently
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

    /** Picks up a settings restore: sources, delays and Spotify links are re-read from storage. */
    fun reloadSavedSettings() {
        refreshSourcePolicy()
        mutableState.value = mutableState.value.copy(
            lyricDelayMs = outputProfiles.delayMs(outputRoute),
            songDelayMs = songDelays.delayMs(mutableState.value.localLyricsKey),
            humanRomanizations = lyricsBackend.humanRomanizations,
            ignoreMusixmatchWordSync = lyricsBackend.ignoreMusixmatchWordSync,
        )
        // The playing song's Spotify link may have come or gone: look it up again if so.
        val restoredId = controller?.metadata.overrideKey()?.let { overrideStore.getString(it, null) }
        if (restoredId != manualSpotifyId) {
            if (restoredId != null) overrideSpotifyId(restoredId) else clearSpotifyIdOverride()
        }
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

    /**
     * Spotify's results for the playing song: the automatic match's own when [query] is null,
     * else for [query]. Null when there's no song or the search failed.
     */
    suspend fun searchSpotify(query: String?): List<SpotifyTrackCandidate>? {
        val request = currentRequest() ?: return null
        return runCatching {
            withContext(Dispatchers.IO) {
                if (query == null) {
                    SharedSpotify.resolver.candidates(
                        LocalTrackMetadata(request.title, request.artist, request.album, request.durationSeconds * 1_000L),
                    )
                } else {
                    SharedSpotify.resolver.search(query)
                }
            }
        }.onFailure { if (it is CancellationException) throw it }.getOrNull()
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
     * [settle]: a track change that follows another within [TRACK_SETTLE_MS] waits that long before
     * any request, so skipping through tracks costs nothing (the wait is cancelled by the next
     * change). A song that simply ends and hands over is asked for at once. [force] drops this
     * track's cached source results, e.g. for Retry.
     */
    fun loadLyrics(spotifyIdInput: String? = null, settle: Boolean = false, force: Boolean = false) {
        val metadata = controller?.metadata ?: return
        val identity = metadata.lyricsKey()
        val request = lookupRequest(
            metadata,
            spotifyIdInput?.spotifyTrackId() ?: manualSpotifyId ?: mutableState.value.detectedSpotifyId,
        )
        lookedUp = request
        if (request.title.isBlank() || request.artist.isBlank()) {
            mutableState.value = mutableState.value.copy(lyrics = LyricsNotices.missingMetadata)
            return
        }
        val now = SystemClock.elapsedRealtime()
        val skipping = settle && now - lastTrackChangeAt < TRACK_SETTLE_MS
        if (settle) lastTrackChangeAt = now
        lyricsJob?.cancel()
        warmJob?.cancel()  // the song on screen goes first
        if (force) lookupCache.remove(request)
        // Results per source for this request: a policy change re-picks from these instead of refetching.
        val known = lookupCache.getOrPut(request) { ConcurrentHashMap() }
        lyricsJob = viewModelScope.launch {
            if (mutableState.value.lyrics !is LyricsState.Ready) {
                mutableState.value = mutableState.value.copy(lyrics = LyricsState.Loading, lookupStatus = "Starting lyric lookup…")
            }
            // The Lyrics Manager's lyrics beat every source and the cache.
            withContext(Dispatchers.IO) { localLyricsFor(identity, request) }?.let { local ->
                publish(local, identity, request, final = true)
                return@launch
            }
            // The disk cache is keyed on title + artist; a manual Spotify ID asks the sources afresh.
            val freshLookup = known.isEmpty()
            if (known.isEmpty() && manualSpotifyId == null) {
                val cached = withContext(Dispatchers.IO) {
                    if (force) lyricsBackend.forget(request)
                    lyricsBackend.cachedResolution(request)
                }
                if (cached != null) {
                    known.putAll(cached.answers)
                    if (cached.settled) {
                        publish(cached.resolution, identity, request, final = true)
                        warmAhead()
                        return@launch
                    }
                    // Picked without a real answer from a source ranked above it (often a queue
                    // entry fetched ahead, which has no length for the Spotify match): the pick
                    // shows now, and only those sources are asked again below.
                    publish(cached.resolution, identity, request, final = false)
                }
            }
            if (skipping && freshLookup) delay(TRACK_SETTLE_MS)
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

    /** What the sources are asked for [metadata]: its names cleaned up for lookup. */
    private fun lookupRequest(metadata: MediaMetadata, spotifyTrackId: String?): LyricsLookupRequest {
        val names = TrackNameCleaner.clean(
            title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty(),
            artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty(),
        )
        return LyricsLookupRequest(
            artist = names.artist,
            title = names.title,
            album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            durationSeconds = (metadata.getLong(MediaMetadata.METADATA_KEY_DURATION) / 1_000L).coerceAtLeast(0L).toInt(),
            spotifyTrackId = spotifyTrackId,
        )
    }

    /**
     * Fetches the next [FETCH_AHEAD] queue entries into the disk cache, one at a time: started only once the current song is settled and cancelled by
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
                if (localLyrics.contains(request.title, request.artist)) continue
                val resolution = backend.cachedResolution(request)?.resolution
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
            lyricsBackend.humanRomanizations && synchronized(humanCache) { humanCache[selection] } != null ->
                synchronized(humanCache) { humanCache.getValue(selection) }
            // Rendering includes on-device romanization, which is too heavy for main.
            else -> withContext(Dispatchers.Default) {
                runCatching { rendered(selection, request.durationSeconds * 1_000L) }
                    .getOrElse { LyricsNotices.renderFailed(selection.source.displayName, it) }
            }
        }
        if (currentLyricsKey != identity) return
        val newPick = selection != shownSelection
        shownSelection = selection
        shownRequest = request
        if (newPick && selection != null && lyrics is LyricsState.Ready &&
            synchronized(humanCache) { humanCache[selection] } == null
        ) humanize(selection, lyrics, identity, request)
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

    /**
     * Looks up Genius's human romanization for the lyrics on screen and swaps it in when it
     * lines up, a moment after the on-device one shows. Off the main thread; dropped if the song
     * or the pick changes first.
     */
    private fun humanize(selection: RemoteLyricsSelection, lyrics: LyricsState.Ready, identity: String?, request: LyricsLookupRequest) {
        humanJob?.cancel()
        if (!lyricsBackend.humanRomanizations || !RemoteLyricsAdapter.wantsHumanRomanization(lyrics)) return
        val backend = lyricsBackend
        humanJob = viewModelScope.launch {
            val genius = runCatching { withContext(Dispatchers.IO) { backend.humanRomanization(request.title, request.artist) } }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull() ?: return@launch
            val human = withContext(Dispatchers.Default) {
                runCatching { RemoteLyricsAdapter.withHumanRomanization(lyrics, genius) }.getOrNull()
            } ?: return@launch
            synchronized(humanCache) { humanCache[selection] = human }
            if (currentLyricsKey != identity || shownSelection != selection) return@launch
            Log.d("LyricsProviders", "Genius romanization laid over ${human.lines.count { it !in lyrics.lines }} lines")
            mutableState.value = mutableState.value.copy(lyrics = human)
        }
    }

    fun setHumanRomanizations(enabled: Boolean) {
        lyricsBackend.humanRomanizations = enabled
        mutableState.value = mutableState.value.copy(humanRomanizations = enabled)
        val selection = shownSelection ?: return
        val request = shownRequest ?: return
        val machine = runCatching { rendered(selection, request.durationSeconds * 1_000L) }.getOrNull() ?: return
        mutableState.value = mutableState.value.copy(lyrics = machine)
        humanize(selection, machine, currentLyricsKey, request)
    }

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
        clock.reset(SystemClock.elapsedRealtime())
        awaitingTrackReport = false
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
        // A command the player has just confirmed (play, pause, seek) re-anchors on its report.
        val confirmed = pendingCommand?.acknowledged(playback, trackIdentity) == true
        val resyncConfirmed = confirmed && (pendingCommand as? PendingCommand.Seek)?.resync == true
        if (confirmed && pendingCommand is PendingCommand.Seek) seekRefusedBy = null
        if (!waitingForSeek && playback != null) {
            reconcileClock(playback, force = trackChanged || confirmed, trackChanged = trackChanged, snap = trackChanged || resyncConfirmed)
        }
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
        // Not cleared on the change: players often send a new song in steps (title, then artist or
        // cover), and each step must still see the button that caused it. It lapses on its own.
        if (queueIndex >= 0) lastQueueIndex = queueIndex
        if (trackChanged) {
            currentTrackIdentity = trackIdentity
            trackChangedAt = SystemClock.elapsedRealtime()
        }
        // Buffering, connecting and skipping are in-between states: Spotify reports them (with no
        // custom actions) while it moves between songs, then a pause for a moment. Showing each
        // would flash the play button and the floating buttons, so they keep what was shown.
        // Spotify Free answers a refused skip with an error state (no position, no actions) while
        // the song plays on, and is back to playing a second later: that is in-between too.
        val previous = mutableState.value
        val inBetween = playback?.state in IN_BETWEEN_STATES || playback?.state == PlaybackState.STATE_ERROR
        if (playback?.state == PlaybackState.STATE_ERROR) {
            pendingSkip?.takeIf { SystemClock.elapsedRealtime() - it.second < SKIP_REFUSED_AFTER_MS }?.let { (direction, _) ->
                skipCheckJob?.cancel()
                explainLimit(if (direction == TrackDirection.Forward) PlayerLimit.Skips else PlayerLimit.Previous)
            }
        }
        val loadingPause = playback?.state == PlaybackState.STATE_PAUSED && previous.isPlaying &&
            SystemClock.elapsedRealtime() - trackChangedAt < LOADING_PAUSE_GRACE_MS &&
            pendingCommand !is PendingCommand.PlayState
        val isPlaying = if (inBetween || loadingPause) previous.isPlaying else playback?.state == PlaybackState.STATE_PLAYING
        val customActions = playback?.customActions.orEmpty().map { custom ->
            SessionCustomAction(custom.action, custom.name.toString(), actionIcon(active.packageName, custom.icon))
        }.ifEmpty { if (inBetween) previous.customActions else emptyList() }
        val lyricsKey = metadata.lyricsKey()
        val lyricsChanged = lyricsKey != currentLyricsKey
        if (lyricsChanged) {
            currentLyricsKey = lyricsKey
            shownSelection = null
            temporaryLyrics = null
            manualSpotifyId = metadata.overrideKey()?.let { overrideStore.getString(it, null) }
            lyricsJob?.cancel()
        }
        val localKey = if (lyricsChanged) {
            metadata?.let { lookupRequest(it, null) }?.let { LocalLyricsStore.keyOf(it.title, it.artist) }
        } else mutableState.value.localLyricsKey
        mutableState.value = mutableState.value.copy(
            accessGranted = true,
            sourcePackage = active.packageName,
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown track",
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: "Unknown artist",
            album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L,
            isPlaying = isPlaying,
            canSeek = if (inBetween) previous.canSeek else playback?.actions?.and(PlaybackState.ACTION_SEEK_TO) != 0L && playback != null,
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
            localLyricsKey = localKey,
            songDelayMs = if (lyricsChanged) songDelays.delayMs(localKey) else mutableState.value.songDelayMs,
            lookupStatus = if (lyricsChanged) null else mutableState.value.lookupStatus,
            lastCommandLatencyMs = latency ?: mutableState.value.lastCommandLatencyMs,
            status = if (waitingForSeek || preserveStatus) mutableState.value.status else null,
            trackDirection = direction,
            customActions = customActions,
            artwork = if (refreshArtwork) {
                val fresh = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                    ?: metadata?.description?.iconBitmap
                // Players re-send the same cover with every metadata update, each time as a new
                // bitmap. Keeping the one already shown spares the header and background redoing it.
                val shown = mutableState.value.artwork
                if (!trackChanged && fresh != null && shown != null && fresh.sameAs(shown)) shown else fresh
            } else mutableState.value.artwork,
            artworkUri = if (refreshArtwork) {
                metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                    ?: metadata?.getString(MediaMetadata.METADATA_KEY_ART_URI)
                    ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
                    ?: metadata?.description?.iconUri?.toString()
            } else mutableState.value.artworkUri,
        )
        if (lyricsChanged) {
            loadExtras()
            loadLyrics(settle = true)
        } else if (metadataChanged && metadata != null) {
            rematchWithLength(metadata)
        }
    }

    /**
     * YouTube Music reports the song's length (and album) a moment after its title, so the lookup
     * started then matched Spotify without them, and a song with several releases of different
     * lengths came out ambiguous. When the length arrives and a source still wants a match, or the
     * lookup is still out, it's asked again with it; answers that didn't need it are kept.
     */
    private fun rematchWithLength(metadata: MediaMetadata) {
        val before = lookedUp ?: return
        if (before.durationSeconds > 0) return
        val now = lookupRequest(metadata, before.spotifyTrackId)
        if (now.durationSeconds <= 0) return
        val known = lookupCache[before].orEmpty()
        if (lyricsJob?.isActive != true && known.values.none { it == ProviderResult.NeedsMatch }) return
        lookupCache[now] = ConcurrentHashMap(known.filterValues { it != ProviderResult.NeedsMatch })
        loadLyrics()
    }

    /**
     * What the screen currently shows that needs a lookup beyond the lyrics: the release year,
     * the artist's header image, the beats for the background. Asking for something new looks it
     * up for the song playing; nothing is fetched that isn't asked for.
     */
    fun setTrackExtrasWanted(wanted: TrackExtrasWanted) {
        screenExtrasWanted = wanted
        applyExtrasWanted()
    }

    private fun applyExtrasWanted() {
        val wanted = screenExtrasWanted.let { if (mutableState.value.calibratingDelay) it.copy(beats = true) else it }
        val added = (wanted.releaseYear && !extrasWanted.releaseYear) ||
            (wanted.artistHeader && !extrasWanted.artistHeader) ||
            (wanted.beats && !extrasWanted.beats)
        extrasWanted = wanted
        if (added) loadExtras()
    }

    /** Starts or ends tapping along to find the output delay: it needs the song's beats. */
    fun setCalibratingDelay(calibrating: Boolean) {
        mutableState.value = mutableState.value.copy(calibratingDelay = calibrating)
        applyExtrasWanted()
    }

    /** The playing song's beats in ms, for [TapCalibration], or null while there are none. */
    fun calibrationBeatsMs(): LongArray? = audioAnalysis?.beats
        ?.filter { it.confidence >= CALIBRATION_BEAT_CONFIDENCE }
        ?.map { (it.start * 1000f).toLong() }
        ?.takeIf { it.size >= 2 }
        ?.toLongArray()

    /** The player's own position at [elapsedRealtimeMs], with no delay taken off. */
    fun playerPositionAt(elapsedRealtimeMs: Long): Long = clock.positionAt(elapsedRealtimeMs)

    /**
     * How fast the background should move right now by the song's beats and loudness, or null when
     * there is no analysis for it (the background then keeps its normal speed).
     */
    fun backgroundSpeed(): Float? {
        val analysis = audioAnalysis ?: return null
        return BackgroundSpeed.at(currentPositionMs() / 1000f, analysis)
    }

    /** The vibrations that go with the song in [style], or null when there is no analysis for it. */
    fun musicHaptics(style: MusicHapticsStyle): List<MusicHaptic>? {
        val analysis = audioAnalysis ?: return null
        musicHaptics?.takeIf { it.first === analysis && it.second == style }?.let { return it.third }
        return MusicHapticScore.build(analysis, style).also { musicHaptics = Triple(analysis, style, it) }
    }

    private fun loadExtras() {
        extrasJob?.cancel()
        audioAnalysis = null
        val metadata = controller?.metadata
        val wanted = extrasWanted
        val sessionYear = metadata?.sessionYear()
        val request = metadata?.let { lookupRequest(it, manualSpotifyId ?: mutableState.value.detectedSpotifyId) }
            ?.takeIf { it.title.isNotBlank() && it.artist.isNotBlank() }
        mutableState.value = mutableState.value.copy(
            releaseYear = sessionYear,
            releaseYearPending = wanted.releaseYear && sessionYear == null && request != null,
            artistHeaderUrl = null,
            artistHeaderPending = wanted.artistHeader && request != null,
        )
        val needYear = wanted.releaseYear && sessionYear == null
        if (request == null || !(needYear || wanted.artistHeader || wanted.beats)) return
        // Skipping through songs, only the one that stays is looked up.
        val skipping = SystemClock.elapsedRealtime() - lastTrackChangeAt < TRACK_SETTLE_MS
        extrasJob = viewModelScope.launch {
            if (skipping) delay(TRACK_SETTLE_MS)
            val track = LocalTrackMetadata(request.title, request.artist, request.album, request.durationSeconds * 1_000L)
            val trackIds = withContext(Dispatchers.IO) { spotifyExtras.trackIds(track, request.spotifyTrackId) }
            val trackId = trackIds.firstOrNull()
            if (wanted.beats && trackId != null) launch {
                audioAnalysis = withContext(Dispatchers.IO) { spotifyExtras.audioAnalysis(trackId) }
            }
            if (wanted.artistHeader) launch {
                val artistId = trackId?.let { withContext(Dispatchers.IO) { spotifyExtras.details(it) } }?.artistIds?.firstOrNull()
                val url = if (trackId != null && artistId != null) {
                    withContext(Dispatchers.IO) { spotifyExtras.artistHeaderUrl(artistId, trackId) }
                } else null
                mutableState.value = mutableState.value.copy(artistHeaderUrl = url, artistHeaderPending = false)
            }
            if (needYear) {
                val year = withContext(Dispatchers.IO) {
                    spotifyExtras.releaseYear(trackIds)
                        ?: itunesYear.find(request.title, request.artist, request.album, request.durationSeconds * 1_000L)
                }
                mutableState.value = mutableState.value.copy(releaseYear = year, releaseYearPending = false)
            }
        }
    }

    /** The year the player itself gives for the song, if any. */
    private fun MediaMetadata.sessionYear(): String? {
        getLong(MediaMetadata.METADATA_KEY_YEAR).takeIf { it in 1000L..9999L }?.let { return it.toString() }
        return getString(MediaMetadata.METADATA_KEY_DATE)?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }
    }

    fun setUiStarted(started: Boolean) {
        uiStarted.value = started
    }

    private fun startTicker() = viewModelScope.launch {
        while (isActive) {
            if (!uiStarted.value) {
                // Nothing is shown: park instead of waking ten times a second. The session's
                // callbacks still arrive, and the first pass back runs the periodic check at once.
                uiStarted.first { it }
                lastPeriodicCheckMs = 0L
            }
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
                    seekRefusedBy = controller?.packageName
                    updateFromController()
                    // Spotify Free lists seeking but ignores it: resync from its reports instead.
                    if (seek.resync) resyncFromReports()
                    mutableState.value = mutableState.value.copy(
                        status = "Seek not confirmed by ${controller?.packageName ?: "player"}; timeline restored",
                    )
                    explainLimit(PlayerLimit.Seek)
                }
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

    /**
     * [force]: take the report even if it isn't new. [snap]: move the clock to it outright rather
     * than count it as a vote ([LyricClock]).
     */
    private fun reconcileClock(playback: PlaybackState, force: Boolean = false, trackChanged: Boolean = false, snap: Boolean = trackChanged) {
        if (playback.position < 0L) return
        val playing = playback.state == PlaybackState.STATE_PLAYING
        val relayed = controller?.playbackInfo?.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE
        val report = LyricClock.Report(playback.state, playing, playback.position, playback.lastPositionUpdateTime, playback.playbackSpeed, relayed)
        val fresh = clock.isFresh(report)
        val now = SystemClock.elapsedRealtime()
        if (trackChanged && !fresh && now - lastReportAt > TRACK_REPORT_FRESH_MS) {
            // No report for the new song yet: the one we have is the previous song's position.
            // Seeding the clock from it jumps the new lyrics ahead and forces a scroll, so the
            // song starts from 0 until the player reports.
            Log.d(SYNC_TAG, "track changed before its report; clock starts at 0")
            clock.startTrack(now, playback.playbackSpeed, playing)
            awaitingTrackReport = true
            return
        }
        if (!force && !fresh) return
        // A pause report is judged as ever ([ClockCorrection.reportBiasMs]): it is the one most often stale.
        val resync = resyncOnNextReport && fresh && playing
        if (resync) resyncOnNextReport = false
        val firstOfTrack = awaitingTrackReport && fresh
        if (firstOfTrack) awaitingTrackReport = false
        if (fresh) lastReportAt = now
        val outcome = clock.onReport(report, now, snap = snap || resync || firstOfTrack)
        Log.d(
            SYNC_TAG,
            "report ${controller?.packageName} state=${playback.state} pos=${playback.position} " +
                "age=${now - playback.lastPositionUpdateTime}ms speed=${playback.playbackSpeed} " +
                "drift=${outcome.driftMs}ms applied=${outcome.appliedMs}ms bias=${outcome.biasMs}ms force=$force snap=${snap || resync || firstOfTrack} relayed=$relayed",
        )
        mutableState.value = mutableState.value.copy(clockDriftMs = outcome.driftMs)
    }

    fun currentPositionMs(): Long {
        val position = clock.positionAt(SystemClock.elapsedRealtime())
        return mutableState.value.durationMs.takeIf { it > 0L }?.let(position::coerceAtMost) ?: position
    }

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

        /** [resync]: sent by [resync] to draw a fresh report, which the clock then takes outright. */
        data class Seek(val targetMs: Long, override val issuedAtMs: Long, val resync: Boolean = false) : PendingCommand {
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
