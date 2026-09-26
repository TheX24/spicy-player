package com.tx24.spicyplayer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NotificationsActive
import com.tx24.spicyplayer.ui.components.SpicyButtonStyle
import com.tx24.spicyplayer.ui.components.SpicyModal
import com.tx24.spicyplayer.ui.components.SpicyModalButton
import com.tx24.spicyplayer.ui.components.SpicyModalGap
import com.tx24.spicyplayer.ui.components.SpicyModalMessage
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.CubicBezierEasing
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
import androidx.compose.foundation.layout.size
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import com.tx24.spicyplayer.ui.components.LyricsSkeleton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import com.tx24.spicyplayer.lyrics.spicy.canvas.LyricsView
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
import com.tx24.spicyplayer.ui.nowplaying.TrackDirection
import androidx.compose.animation.core.spring
import com.tx24.spicyplayer.ui.settings.AppSettings
import com.tx24.spicyplayer.lyrics.spicy.canvas.LyricsLayoutCalculator
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
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
        hideSystemBars()
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

    // Dialogs and the keyboard can bring the bars back; hide them again once the window is ours.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** Fullscreen: a swipe from the edge shows the bars for a moment over the page. */
    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
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
    val settings = remember { AppSettings(context.getSharedPreferences("ui", Context.MODE_PRIVATE)) }
    val lowPerformance = settings.lowPerformance
    val romanizationAvailable = (state.lyrics as? LyricsState.Ready)?.let { ready ->
        ready.lines.any { line -> line.words.any { it.romanized != null } }
    } == true
    val romanize = settings.romanize && romanizationAvailable
    val systemFont = settings.systemFont
    SideEffect { LyricsLayoutCalculator.useSystemFont = systemFont }
    // Awake while the music plays, so the lyrics can be followed without touching the phone.
    val view = LocalView.current
    val keepAwake = settings.keepScreenOn && state.isPlaying
    DisposableEffect(view, keepAwake) {
        view.keepScreenOn = keepAwake
        onDispose { view.keepScreenOn = false }
    }
    // The big cover: the button (or a tap on the cover) asks for it; the setting brings it up on
    // its own while a song has no lyrics, until it's put away for that song.
    var userExpanded by remember { mutableStateOf(false) }
    var dismissedForSong by remember(state.title, state.artist) { mutableStateOf(false) }
    val autoExpanded = settings.expandWithoutLyrics && state.lyrics is LyricsState.Error && !dismissedForSong
    val headerExpanded = userExpanded || autoExpanded
    val toggleExpanded = {
        if (headerExpanded) {
            userExpanded = false
            if (autoExpanded) dismissedForSong = true
        } else {
            userExpanded = true
        }
    }
    // The header expands over 0.4 s with CSS's `ease`.
    val expansion by animateFloatAsState(
        if (headerExpanded) 1f else 0f,
        tween(400, easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)),
        label = "headerExpansion",
    )
    // Built fresh, not copied: the mode-dependent defaults are worked out in the constructor.
    val renderConfig = RenderConfig(
        simpleLyricsMode = settings.simpleLyricsMode,
        minimalLyricsMode = settings.minimalLyricsMode,
        simpleAnimationStyle = settings.simpleAnimationStyle,
        wordMotionBoost = settings.wordMotionBoost,
        distanceBlurEnabled = settings.distanceBlur && !lowPerformance,
        glowEnabled = settings.glow && !lowPerformance,
    )
    var showSettings by remember { mutableStateOf(false) }
    val backdrop = remember { HazeState() }

    // Any touch shows the controls, and they fade after a few seconds without one.
    var controlsVisible by remember { mutableStateOf(true) }
    var lastTouchMs by remember { mutableLongStateOf(0L) }
    // They stay while paused, and while the big cover is up (it has nothing else to show).
    LaunchedEffect(lastTouchMs, state.isPlaying, headerExpanded, settings.autoHideControls) {
        if (!state.isPlaying || headerExpanded || !settings.autoHideControls) {
            controlsVisible = true
            return@LaunchedEffect
        }
        delay(CONTROLS_IDLE_MS)
        controlsVisible = false
    }

    LaunchedEffect(Unit) { viewModel.refresh() }

    // The bars are hidden, so this is only the camera cutout (and the keyboard in settings).
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        Box(
            Modifier
                .fillMaxSize()
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
            // The background fills the screen; everything on it keeps clear of the cutout.
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // The header rises into the cutout's band (the camera is clear of the cover), so the
                // page starts at the top of the screen and only the bottom inset comes off.
                val topInsetPx = with(LocalDensity.current) { padding.calculateTopPadding().toPx() }
                val bottomInsetPx = with(LocalDensity.current) { padding.calculateBottomPadding().toPx() }
                val pageWidth = constraints.maxWidth.toFloat()
                val pageHeight = constraints.maxHeight.toFloat() - bottomInsetPx
                val density = LocalDensity.current.density
                val headerMetrics = remember(pageWidth, pageHeight, density, topInsetPx) {
                    CompactHeaderMetrics(
                        pageWidthPx = pageWidth,
                        pageHeightPx = pageHeight,
                        density = density,
                        lyricFontSizeSp = LyricsLayoutMetrics(pageWidth, density, LyricsType.Syllable, 1f).baseFontSizeSp,
                        topInsetPx = topInsetPx,
                    )
                }
                val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
                val belowTop = androidx.compose.foundation.layout.PaddingValues(
                    start = padding.calculateStartPadding(layoutDirection),
                    end = padding.calculateEndPadding(layoutDirection),
                    bottom = padding.calculateBottomPadding(),
                )
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
                        animate = !lowPerformance && !settings.staticBackground,
                        legacy = settings.legacyBackground,
                    )
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(belowTop)
                            // Expanding, the lyrics fade out under the cover.
                            .graphicsLayer { alpha = 1f - expansion },
                    ) {
                        Spacer(Modifier.height(with(LocalDensity.current) { headerMetrics.lyricsTopPx.toDp() }))
                        LyricsPanel(
                            lyrics = state.lyrics,
                            currentTimeMs = viewModel::currentLyricPositionMs,
                            onSeek = viewModel::seekTo,
                            romanize = romanize,
                            activeLineTopPx = headerMetrics.activeLineTopPx,
                            noticeBottomPx = { noticeBottomPx },
                            config = renderConfig,
                            fontSizeScale = settings.lyricsSize.scale,
                            modifier = Modifier.weight(1f),
                        )
                    }
                // Hidden lyrics take no touches (no seeking through the cover view).
                if (headerExpanded) {
                    Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } })
                }
                val expandedHeader = remember(headerMetrics, controlsHeightPx) {
                    headerMetrics.expanded(pageHeight - controlsHeightPx)
                }
                CompactNowPlayingHeader(
                    info = NowPlayingInfo(state.title, state.artist, state.artwork, state.artworkUri, state.trackDirection),
                    metrics = headerMetrics,
                    modifier = Modifier.padding(belowTop),
                    expansion = { expansion },
                    expanded = expandedHeader,
                    isPlaying = state.isPlaying,
                    onPlayPause = viewModel::playPause,
                    onSkip = { direction -> if (direction == TrackDirection.Forward) viewModel.skipNext() else viewModel.skipPrevious() },
                )
                // The header sits in what the glass and the shade blur, so the shade blurs it
                // rather than painting the blurred page over it.
                }
                // Out of the way under settings too: their glass would keep blurring behind it.
                val controlsTarget = controlsVisible && !showSettings
                // Quick to appear under the finger; a slow, soft fade when they time out.
                val controlsShown by animateFloatAsState(
                    if (controlsTarget) 1f else 0f,
                    if (controlsTarget) {
                        tween(CONTROLS_SHOW_MS, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
                    } else {
                        tween(CONTROLS_HIDE_MS, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f))
                    },
                    label = "controlsShown",
                )
                // Once faded out, the controls stop blurring: the shade's progressive blur and each
                // glass button would otherwise re-blur the whole page every frame, unseen.
                val controlsGone by remember { derivedStateOf { controlsShown == 0f } }
                CompositionLocalProvider(LocalBackdrop provides backdrop.takeUnless { controlsGone || lowPerformance }) {
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
                            onResync = viewModel::resync,
                        ),
                        romanizeAvailable = romanizationAvailable,
                        romanized = romanize,
                        onToggleRomanize = { settings.romanize = !settings.romanize },
                        onOpenSettings = { showSettings = true },
                        expanded = headerExpanded,
                        onToggleExpanded = toggleExpanded,
                        interactive = controlsVisible,
                        shown = { controlsShown },
                        // No lyrics behind them when expanded, so no shade over the cover.
                        shade = { 1f - expansion.coerceIn(0f, 1f) },
                        onControlsHeight = { controlsHeightPx = it },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(padding),
                    )
                }
            }
        }

        // Coming back from the system's settings refreshes the grant (onResume), which closes it.
        SpicyModal(
            visible = !state.accessGranted,
            onDismissRequest = null,
            backdrop = backdrop,
            modifier = Modifier.padding(padding),
        ) {
            NotificationAccessMessage(openNotificationAccess)
        }

        if (showSettings) {
            SettingsScreen(
                state = state,
                viewModel = viewModel,
                settings = settings,
                backdrop = backdrop,
                contentPadding = padding,
                onClosed = { showSettings = false },
            )
        }
    }
}

private const val CONTROLS_IDLE_MS = 3_000L
private const val CONTROLS_SHOW_MS = 350
private const val CONTROLS_HIDE_MS = 700
private val CssEaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)

/**
 * A notice in place of lyrics: centred in the lyrics area, 80% of its width, centre-aligned. The
 * message is semibold primary text at clamp(1.25rem, 3.5cqw, 2.5rem); the detail sits 1cqh
 * under it, regular secondary text at clamp(0.95rem, 3.5cqw, 1.45rem).
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

/** Asks for the notification-listener grant, which is how Android hands out media sessions. */
@Composable
private fun NotificationAccessMessage(openNotificationAccess: () -> Unit) {
    SpicyModalMessage(
        title = "Allow notification access",
        description = "Spicy Player reads the song playing in your music app, and controls it, through its media " +
            "notification. Android files that under notification access. You can turn it off again in system settings.",
        icon = {
            Icon(Icons.Rounded.NotificationsActive, null, Modifier.size(24.dp), tint = SpicyColors.TextPrimary)
        },
    )
    SpicyModalGap()
    SpicyModalButton("Open notification access", openNotificationAccess, style = SpicyButtonStyle.Primary, fill = true)
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
    fontSizeScale: Float,
    modifier: Modifier = Modifier,
) {
    // The loading skeleton: up as soon as the lookup starts, over whatever the panel
    // shows, fading in over 0.2s and out over 0.25s (ease-out).
    Box(modifier) {
        LyricsPanelContent(lyrics, currentTimeMs, onSeek, romanize, activeLineTopPx, noticeBottomPx, config, fontSizeScale, Modifier.fillMaxSize())
        AnimatedVisibility(
            visible = lyrics == LyricsState.Loading,
            enter = fadeIn(tween(200, easing = CssEaseOut)),
            exit = fadeOut(tween(250, easing = CssEaseOut)),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val density = LocalDensity.current.density
                val metrics = LyricsLayoutMetrics(constraints.maxWidth.toFloat(), density, LyricsType.Syllable, fontSizeScale)
                val column = metrics.contentSlot(hasDuet = false, isRtl = false, oppositeAligned = false)
                LyricsSkeleton(
                    lineSizeSp = metrics.baseFontSizeSp,
                    lineStartPx = column.startPx,
                    lineMaxWidthPx = column.widthPx,
                    topPx = activeLineTopPx ?: 0f,
                )
            }
        }
    }
}

@Composable
private fun LyricsPanelContent(
    lyrics: LyricsState,
    currentTimeMs: () -> Long,
    onSeek: (Long) -> Unit,
    romanize: Boolean,
    activeLineTopPx: Float?,
    noticeBottomPx: () -> Float,
    config: RenderConfig,
    fontSizeScale: Float,
    modifier: Modifier = Modifier,
) {
    when (lyrics) {
        LyricsState.Idle -> LyricsNotice("Waiting for a song", null, noticeBottomPx, modifier)
        LyricsState.Loading -> Unit
        is LyricsState.Error -> LyricsNotice(lyrics.message, lyrics.detail, noticeBottomPx, modifier)
        is LyricsState.Ready -> {
            val rendererLines = remember(lyrics.lines, config.isMinimal, config.isSimple) {
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
                }, minimalMode = config.isMinimal, holdThroughShortGaps = when (lyrics.lyricsType) {
                    LyricsType.Syllable -> config.isMinimal
                    LyricsType.Line -> config.isSimple
                    else -> false
                })
            }
            LyricsView(
                lines = rendererLines,
                documentId = remember(lyrics.lines) { lyrics.lines.hashCode().toString() },
                currentTimeMs = currentTimeMs,
                onSeekWord = onSeek,
                romanize = romanize,
                activeLineTopPx = activeLineTopPx,
                lyricsType = lyrics.lyricsType,
                config = config,
                fontSizeScale = fontSizeScale,
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

