package com.tx24.spicyplayer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NotificationsActive
import com.tx24.spicyplayer.ui.components.SlButtonStyle
import com.tx24.spicyplayer.ui.components.SlModal
import com.tx24.spicyplayer.ui.components.SlModalButton
import com.tx24.spicyplayer.ui.components.SlModalGap
import com.tx24.spicyplayer.ui.components.SlModalMessage
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
import androidx.compose.material3.CircularProgressIndicator
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
import com.tx24.spicyplayer.lyrics.spicy.SimpleAnimationStyle
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
    // Drops the costliest effects: the moving background, glass blur, and lyric blur and glow.
    var lowPerformance by remember { mutableStateOf(uiPrefs.getBoolean("lowPerformance", false)) }
    val setLowPerformance = { on: Boolean ->
        lowPerformance = on
        uiPrefs.edit().putBoolean("lowPerformance", on).apply()
    }
    // Spicy Lyrics' Simple and Minimal Lyrics Modes.
    var simpleLyricsMode by remember { mutableStateOf(uiPrefs.getBoolean("simpleLyricsMode", false)) }
    var simpleAnimationStyle by remember {
        mutableStateOf(runCatching { SimpleAnimationStyle.valueOf(uiPrefs.getString("simpleAnimationStyle", null)!!) }
            .getOrDefault(SimpleAnimationStyle.CALCULATE))
    }
    var minimalLyricsMode by remember { mutableStateOf(uiPrefs.getBoolean("minimalLyricsMode", false)) }
    val renderConfig = remember(originalWordMotion, lowPerformance, simpleLyricsMode, simpleAnimationStyle, minimalLyricsMode) {
        // Built fresh, not copied: the mode-dependent defaults are worked out in the constructor.
        RenderConfig(
            simpleLyricsMode = simpleLyricsMode,
            minimalLyricsMode = minimalLyricsMode,
            simpleAnimationStyle = simpleAnimationStyle,
            wordMotionBoost = if (originalWordMotion) 1f else WORD_MOTION_BOOST,
            distanceBlurEnabled = !lowPerformance,
            glowEnabled = !lowPerformance,
        )
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
                        animate = !lowPerformance,
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
                        onToggleRomanize = { setRomanize(!romanizePreferred) },
                        onOpenSettings = { showSettings = true },
                        interactive = controlsVisible,
                        shown = { controlsShown },
                        onControlsHeight = { controlsHeightPx = it },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }

        // Coming back from the system's settings refreshes the grant (onResume), which closes it.
        SlModal(
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
                prefs = LyricsPreferences(
                    originalWordMotion = originalWordMotion,
                    onOriginalWordMotionChange = setOriginalWordMotion,
                    wordMotionBoost = WORD_MOTION_BOOST,
                    lowPerformance = lowPerformance,
                    onLowPerformanceChange = setLowPerformance,
                    simpleLyricsMode = simpleLyricsMode,
                    onSimpleLyricsModeChange = { on ->
                        simpleLyricsMode = on
                        uiPrefs.edit().putBoolean("simpleLyricsMode", on).apply()
                    },
                    simpleAnimationStyle = simpleAnimationStyle,
                    onSimpleAnimationStyleChange = { style ->
                        simpleAnimationStyle = style
                        uiPrefs.edit().putString("simpleAnimationStyle", style.name).apply()
                    },
                    minimalLyricsMode = minimalLyricsMode,
                    onMinimalLyricsModeChange = { on ->
                        minimalLyricsMode = on
                        uiPrefs.edit().putBoolean("minimalLyricsMode", on).apply()
                    },
                ),
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

/** Asks for the notification-listener grant, which is how Android hands out media sessions. */
@Composable
private fun NotificationAccessMessage(openNotificationAccess: () -> Unit) {
    SlModalMessage(
        title = "Allow notification access",
        description = "Spicy Player reads the song playing in your music app, and controls it, through its media " +
            "notification. Android files that under notification access. You can turn it off again in system settings.",
        icon = {
            Icon(Icons.Rounded.NotificationsActive, null, Modifier.size(24.dp), tint = SpicyColors.TextPrimary)
        },
    )
    SlModalGap()
    SlModalButton("Open notification access", openNotificationAccess, style = SlButtonStyle.Primary, fill = true)
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

