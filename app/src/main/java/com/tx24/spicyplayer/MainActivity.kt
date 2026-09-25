package com.tx24.spicyplayer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import com.tx24.spicyplayer.lyrics.spicy.RenderConfig
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.width
import com.tx24.spicyplayer.lyrics.LyricsState
import com.tx24.spicyplayer.lyrics.spicy.canvas.LyricsLayoutMetrics
import com.tx24.spicyplayer.lyrics.spicy.canvas.SpicyLyricsView
import com.tx24.spicyplayer.lyrics.spicy.models.Line
import com.tx24.spicyplayer.lyrics.spicy.models.LyricsCredit
import com.tx24.spicyplayer.lyrics.spicy.models.LyricsFooter
import com.tx24.spicyplayer.lyrics.spicy.models.LyricsProvenance
import com.tx24.spicyplayer.lyrics.spicy.models.LyricsType
import com.tx24.spicyplayer.lyrics.spicy.models.Word
import com.tx24.spicyplayer.lyrics.spicy.models.buildDisplayTimeline
import com.tx24.spicyplayer.playback.ExternalPlaybackViewModel
import com.tx24.spicyplayer.playback.PlayerUiState
import com.tx24.spicyplayer.ui.background.SpicySessionBackground
import com.tx24.spicyplayer.ui.components.LocalBackdrop
import com.tx24.spicyplayer.ui.controls.LyricsControls
import com.tx24.spicyplayer.ui.controls.PlaybackControlsState
import com.tx24.spicyplayer.ui.nowplaying.CompactHeaderMetrics
import com.tx24.spicyplayer.ui.nowplaying.CompactNowPlayingHeader
import com.tx24.spicyplayer.ui.nowplaying.NowPlayingInfo
import com.tx24.spicyplayer.ui.settings.LyricsPreferences
import com.tx24.spicyplayer.ui.settings.SettingsScreen
import com.tx24.spicyplayer.ui.theme.SpicyColors
import com.tx24.spicyplayer.ui.theme.SpicyMotion
import com.tx24.spicyplayer.ui.theme.SpicyType
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val playbackViewModel: ExternalPlaybackViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                LyricsApp(
                    viewModel = playbackViewModel,
                    openNotificationAccess = {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        playbackViewModel.refresh()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LyricsApp(
    openNotificationAccess: () -> Unit,
    viewModel: ExternalPlaybackViewModel,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    // Global and persisted, like the reference's "romanization" setting.
    val uiPrefs = remember { context.getSharedPreferences("ui", Context.MODE_PRIVATE) }
    var romanizePreferred by remember { mutableStateOf(uiPrefs.getBoolean("romanize", false)) }
    val setRomanize = { on: Boolean ->
        romanizePreferred = on
        uiPrefs.edit().putBoolean("romanize", on).apply()
    }
    val romanizationAvailable = (state.lyrics as? LyricsState.Ready)?.let { ready ->
        ready.lines.any { line -> line.words.any { it.romanized != null } }
    } == true
    val romanize = romanizePreferred && romanizationAvailable
    var originalWordMotion by remember { mutableStateOf(uiPrefs.getBoolean("originalWordMotion", false)) }
    val setOriginalWordMotion = { on: Boolean ->
        originalWordMotion = on
        uiPrefs.edit().putBoolean("originalWordMotion", on).apply()
    }
    val renderConfig = remember(originalWordMotion) {
        RenderConfig.FULL.copy(wordMotionBoost = if (originalWordMotion) 1f else WORD_MOTION_BOOST)
    }
    var showSettings by remember { mutableStateOf(false) }
    val backdrop = remember { HazeState() }

    // SL shows its controls while the pointer is over the page. On a phone: any touch shows them,
    // and they fade after a few seconds without one. They stay while paused.
    var controlsVisible by remember { mutableStateOf(true) }
    var lastTouchMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(lastTouchMs, state.isPlaying) {
        if (!state.isPlaying) {
            controlsVisible = true
            return@LaunchedEffect
        }
        delay(CONTROLS_IDLE_MS)
        controlsVisible = false
    }

    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold { padding ->
        if (!state.accessGranted) {
            PermissionScreen(
                modifier = Modifier.padding(padding),
                openNotificationAccess = openNotificationAccess,
                refresh = viewModel::refresh,
            )
            return@Scaffold
        }

        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .pointerInput(Unit) {
                    // Sees every touch on its way down without taking it from what's underneath.
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial)
                            controlsVisible = true
                            lastTouchMs = SystemClock.uptimeMillis()
                        }
                    }
                },
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val pageWidth = constraints.maxWidth.toFloat()
                val pageHeight = constraints.maxHeight.toFloat()
                val density = LocalDensity.current.density
                val headerMetrics = remember(pageWidth, pageHeight, density) {
                    CompactHeaderMetrics(
                        pageWidthPx = pageWidth,
                        pageHeightPx = pageHeight,
                        density = density,
                        lyricFontSizeSp = LyricsLayoutMetrics(pageWidth, density, LyricsType.Syllable, 1f).baseFontSizeSp,
                    )
                }
                var controlsHeightPx by remember { mutableIntStateOf(0) }
                // While the controls show, a notice centres in the space they leave above them.
                val noticeBottomPx by animateFloatAsState(
                    if (controlsVisible) controlsHeightPx.toFloat() else 0f,
                    tween(SpicyMotion.CONTROLS_FADE_MS),
                    label = "noticeBottom",
                )
                // Everything the glass controls blur.
                Box(Modifier.fillMaxSize().hazeSource(backdrop)) {
                    SpicySessionBackground(
                        artwork = state.artwork,
                        artworkUri = state.artworkUri,
                        isPlaying = state.isPlaying,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Column(Modifier.fillMaxSize()) {
                        Spacer(Modifier.height(with(LocalDensity.current) { headerMetrics.lyricsTopPx.toDp() }))
                        LyricsPanel(
                            lyrics = state.lyrics,
                            currentTimeMs = viewModel::currentLyricPositionMs,
                            onSeek = viewModel::seekTo,
                            romanize = romanize,
                            activeLineTopPx = headerMetrics.activeLineTopPx,
                            noticeBottomPx = { noticeBottomPx },
                            config = renderConfig,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                CompactNowPlayingHeader(
                    info = NowPlayingInfo(state.title, state.artist, state.artwork, state.artworkUri, state.trackDirection),
                    metrics = headerMetrics,
                )
                // Out of the way under settings too: their glass would keep blurring behind it.
                val controlsAlpha by animateFloatAsState(
                    if (controlsVisible && !showSettings) 1f else 0f,
                    tween(SpicyMotion.CONTROLS_FADE_MS),
                    label = "controlsAlpha",
                )
                CompositionLocalProvider(LocalBackdrop provides backdrop) {
                    LyricsControls(
                        controls = PlaybackControlsState(
                            isPlaying = state.isPlaying,
                            canSeek = state.canSeek,
                            durationMs = state.durationMs,
                            positionMs = viewModel::currentPositionMs,
                            onPlayPause = viewModel::playPause,
                            onPrevious = viewModel::skipPrevious,
                            onNext = viewModel::skipNext,
                            onSeek = viewModel::seekTo,
                            customActions = state.customActions,
                            onCustomAction = viewModel::sendCustomAction,
                        ),
                        romanizeAvailable = romanizationAvailable,
                        romanized = romanize,
                        onToggleRomanize = { setRomanize(!romanizePreferred) },
                        onOpenSettings = { showSettings = true },
                        interactive = controlsVisible,
                        onControlsHeight = { controlsHeightPx = it },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .graphicsLayer { alpha = controlsAlpha },
                    )
                }
            }
        }

        if (showSettings) {
            SettingsScreen(
                state = state,
                viewModel = viewModel,
                prefs = LyricsPreferences(
                    originalWordMotion = originalWordMotion,
                    onOriginalWordMotionChange = setOriginalWordMotion,
                    wordMotionBoost = WORD_MOTION_BOOST,
                ),
                backdrop = backdrop,
                contentPadding = padding,
                onClosed = { showSettings = false },
            )
        }
    }
}

private const val CONTROLS_IDLE_MS = 3_000L
private const val SPINNER_DELAY_MS = 500L

/** Default word-motion boost over Spicy Lyrics' own (RenderConfig.wordMotionBoost). */
private const val WORD_MOTION_BOOST = 1.25f

/**
 * Spicy Lyrics' `.LyricsNotice` (default.scss): centred in the lyrics area, 80% of its width,
 * centre-aligned. `.notice-descriptor` is semibold primary text at clamp(1.25rem, 3.5cqw, 2.5rem);
 * `.notice-footer` sits 1cqh under it, regular secondary text at clamp(0.95rem, 3.5cqw, 1.45rem).
 * [bottomPx] is how much of the panel's bottom the controls cover right now.
 */
@Composable
private fun LyricsNotice(message: String, detail: String?, bottomPx: () -> Float, modifier: Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val cqw = maxWidth.value / 100f
        val cqh = maxHeight / 100f
        Column(
            Modifier
                .align(Alignment.Center)
                .width(maxWidth * 0.8f)
                .graphicsLayer { translationY = -bottomPx() / 2f },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val base = SpicyType.Body.copy(letterSpacing = (-0.01f).em, textAlign = TextAlign.Center)
            Text(message, style = base.copy(fontSize = (cqw * 3.5f).coerceIn(20f, 40f).sp, fontWeight = FontWeight.SemiBold, lineHeight = 1.25.em))
            if (detail != null) {
                Text(
                    detail,
                    modifier = Modifier.padding(top = cqh),
                    style = base.copy(
                        fontSize = (cqw * 3.5f).coerceIn(15.2f, 23.2f).sp,
                        fontWeight = FontWeight.Normal,
                        letterSpacing = 0.sp,
                        color = SpicyColors.TextSecondary,
                    ),
                )
            }
        }
    }
}

@Composable
private fun PermissionScreen(
    modifier: Modifier,
    openNotificationAccess: () -> Unit,
    refresh: () -> Unit,
) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("MediaSession access required", style = MaterialTheme.typography.titleLarge)
                Text("Allow notification access so Spicy Player can find the music app currently playing and use its playback controls. Android groups media-session access under this permission. You can revoke it in system settings.")
                Button(onClick = openNotificationAccess) { Text("Open notification access") }
                Button(onClick = refresh) { Text("Refresh") }
            }
        }
    }
}

@Composable
private fun LyricsPanel(
    lyrics: LyricsState,
    currentTimeMs: () -> Long,
    onSeek: (Long) -> Unit,
    romanize: Boolean,
    activeLineTopPx: Float?,
    noticeBottomPx: () -> Float,
    config: RenderConfig,
    modifier: Modifier = Modifier,
) {
    when (lyrics) {
        LyricsState.Idle -> LyricsNotice("Waiting for a song", null, noticeBottomPx, modifier)
        LyricsState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // Cached lyrics arrive within a few frames; a spinner flashing up for those would
            // only be noise, so it waits until the lookup actually takes a while.
            var showSpinner by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                delay(SPINNER_DELAY_MS)
                showSpinner = true
            }
            if (showSpinner) CircularProgressIndicator(color = SpicyColors.TextSecondary)
        }
        is LyricsState.Error -> LyricsNotice(lyrics.message, lyrics.detail, noticeBottomPx, modifier)
        is LyricsState.Ready -> {
            val rendererLines = remember(lyrics.lines) {
                buildDisplayTimeline(lyrics.lines.map { line ->
                    Line(
                        words = line.words.map { word ->
                            Word(word.text, word.startMs, word.endMs, isPartOfWord = word.attached, romanizedText = word.romanized)
                        },
                        startMs = line.startMs,
                        endMs = line.endMs,
                        agent = line.agent,
                        role = line.role,
                        groupId = line.groupId,
                        oppositeAligned = line.oppositeAligned,
                    )
                }, minimalMode = false)
            }
            SpicyLyricsView(
                lines = rendererLines,
                documentId = remember(lyrics.lines) { lyrics.lines.hashCode().toString() },
                currentTimeMs = currentTimeMs,
                onSeekWord = onSeek,
                romanize = romanize,
                activeLineTopPx = activeLineTopPx,
                lyricsType = lyrics.lyricsType,
                config = config,
                footer = remember(lyrics) {
                    LyricsFooter(
                        songwriters = lyrics.songwriters,
                        // "Lyrics: Spicy Lyrics • Apple Music" when a provider syndicates another catalogue.
                        provenance = LyricsProvenance(lyrics.provider, lyrics.source?.takeIf { it != lyrics.provider }),
                        maker = lyrics.maker?.let { LyricsCredit(it.username, it.profileUrl, it.avatarUrl) },
                        uploader = lyrics.uploader?.let { LyricsCredit(it.username, it.profileUrl, it.avatarUrl) },
                    )
                },
                modifier = modifier,
            )
        }
    }
}

