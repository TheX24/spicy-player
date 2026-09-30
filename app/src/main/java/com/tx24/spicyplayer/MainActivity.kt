package com.tx24.spicyplayer

import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import com.tx24.spicyplayer.lyrics.LyricsNotices
import com.tx24.spicyplayer.ui.spotifysearch.SpotifySearchModal
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.tx24.spicyplayer.haptics.HapticPlayer
import com.tx24.spicyplayer.haptics.ProvideTouchHaptics
import com.tx24.spicyplayer.haptics.playMusicHaptics
import com.tx24.spicyplayer.ui.components.SpicyButton
import com.tx24.spicyplayer.ui.components.SpicyButtonStyle
import com.tx24.spicyplayer.ui.components.SpicyModal
import com.tx24.spicyplayer.ui.components.SpicyToastHost
import com.tx24.spicyplayer.ui.lyricsmanager.LyricsManagerModal
import com.tx24.spicyplayer.ui.components.SpicyModalActions
import com.tx24.spicyplayer.ui.components.SpicyModalButton
import com.tx24.spicyplayer.ui.components.SpicyModalGap
import com.tx24.spicyplayer.ui.components.SpicyModalHeading
import com.tx24.spicyplayer.ui.components.SpicyModalMessage
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Window
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
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
import androidx.compose.runtime.mutableIntStateOf
import com.tx24.spicyplayer.lyrics.spicy.RenderConfig
import com.tx24.spicyplayer.lyrics.spicy.ScrollConfig
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
import com.tx24.spicyplayer.lyrics.spicy.canvas.LyricsViewState
import com.tx24.spicyplayer.lyrics.spicy.canvas.ActiveLineDirection
import com.tx24.spicyplayer.lyrics.spicy.canvas.PinnedFooterMode
import com.tx24.spicyplayer.ui.profile.ProfileModal
import com.tx24.spicyplayer.lyrics.spicy.canvas.PinnedLyricsFooter
import com.tx24.spicyplayer.ui.components.GlassButton
import com.tx24.spicyplayer.ui.settings.LyricsFont
import com.tx24.spicyplayer.ui.settings.LyricsFontFile
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import com.tx24.spicyplayer.lyrics.spicy.models.Line
import com.tx24.spicyplayer.lyrics.spicy.models.LyricsCredit
import com.tx24.spicyplayer.lyrics.spicy.models.LyricsFooter
import com.tx24.spicyplayer.lyrics.spicy.models.LyricsProvenance
import com.tx24.spicyplayer.lyrics.spicy.models.LyricsType
import com.tx24.spicyplayer.lyrics.spicy.models.Word
import com.tx24.spicyplayer.lyrics.spicy.models.buildDisplayTimeline
import com.tx24.spicyplayer.playback.ExternalPlaybackViewModel
import com.tx24.spicyplayer.playback.PlayerLimit
import com.tx24.spicyplayer.playback.PlayerUiState
import com.tx24.spicyplayer.ui.background.SpicySessionBackground
import com.tx24.spicyplayer.ui.components.LocalBackdrop
import com.tx24.spicyplayer.ui.components.SpicyModalNotes
import com.tx24.spicyplayer.ui.components.SpicyVersionRow
import com.tx24.spicyplayer.ui.controls.ActionKind
import com.tx24.spicyplayer.ui.controls.CoverControls
import com.tx24.spicyplayer.ui.controls.LyricsControls
import com.tx24.spicyplayer.ui.controls.kind
import com.tx24.spicyplayer.ui.nowplaying.LandscapeMetrics
import com.tx24.spicyplayer.ui.settings.PanelSide
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntOffset
import com.tx24.spicyplayer.ui.controls.PlaybackControlsState
import com.tx24.spicyplayer.ui.nowplaying.CompactHeaderMetrics
import com.tx24.spicyplayer.ui.nowplaying.CompactNowPlayingHeader
import com.tx24.spicyplayer.ui.nowplaying.MotionCoverQuery
import com.tx24.spicyplayer.ui.nowplaying.NowPlayingInfo
import com.tx24.spicyplayer.ui.nowplaying.forgetMotionCover
import com.tx24.spicyplayer.ui.nowplaying.rememberMotionCoverUrl
import com.tx24.spicyplayer.ui.nowplaying.ReleaseYear
import com.tx24.spicyplayer.ui.settings.BackgroundType
import com.tx24.spicyplayer.ui.settings.ReleaseYearPosition
import com.tx24.spicyplayer.playback.TrackExtrasWanted
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
import com.tx24.spicyplayer.ui.theme.SpicySpacing
import com.tx24.spicyplayer.ui.theme.SpicyType
import com.tx24.spicyplayer.update.UpdateStatus
import com.tx24.spicyplayer.update.UpdateUiState
import com.tx24.spicyplayer.update.UpdateViewModel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalUriHandler
import com.tx24.spicyplayer.lyrics.spicy.models.FooterLine
import com.tx24.spicyplayer.ui.controls.ControlsRevealGuard
import com.tx24.spicyplayer.ui.controls.LocalControlsRevealGuard

class MainActivity : ComponentActivity() {
    private val playbackViewModel: ExternalPlaybackViewModel by viewModels()
    private val updateViewModel: UpdateViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideSystemBars()
        setContent {
            val settings = remember { AppSettings(getSharedPreferences("ui", Context.MODE_PRIVATE)) }
            MaterialTheme(colorScheme = darkColorScheme()) {
                ProvideTouchHaptics(settings.touchHaptics) {
                    LyricsApp(
                        viewModel = playbackViewModel,
                        updater = updateViewModel,
                        settings = settings,
                        openNotificationAccess = {
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        },
                    )
                }
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
    updater: UpdateViewModel,
    settings: AppSettings,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val lowPerformance = settings.lowPerformance
    val hideHeader = settings.hideHeader
    val romanizationAvailable = (state.lyrics as? LyricsState.Ready)?.let { ready ->
        ready.lines.any { line -> line.words.any { it.romanized != null } }
    } == true
    val romanize = settings.romanize && romanizationAvailable
    // The lyrics font: ours, the phone's, or the file picked in settings (ours again if it's gone).
    val lyricsFont = settings.lyricsFont
    val customFontFile = settings.customFontFile
    val fontFamily = remember(lyricsFont, customFontFile) {
        when (lyricsFont) {
            LyricsFont.Default -> null
            LyricsFont.System -> FontFamily.Default
            LyricsFont.Custom -> LyricsFontFile.family(context, customFontFile)
        }
    }
    SideEffect {
        LyricsLayoutCalculator.setFont(fontFamily, if (fontFamily == null) "" else "$lyricsFont:$customFontFile")
    }
    // Shared with the lyrics: the scroll-to-active button and the pinned credits.
    val lyricsViewState = remember { LyricsViewState() }
    val pinnedFooter = settings.pinnedFooter
    // Awake while the music plays, so the lyrics can be followed without touching the phone.
    val view = LocalView.current
    val keepAwake = settings.keepScreenOn && state.isPlaying
    DisposableEffect(view, keepAwake) {
        view.keepScreenOn = keepAwake
        onDispose { view.keepScreenOn = false }
    }
    // 60 Hz unless asked otherwise: word sweeps and the background look the same, and a 90/120 Hz
    // screen would otherwise draw (and composite) half again or twice as many frames.
    val activity = LocalActivity.current
    val highRefreshRate = settings.highRefreshRate
    DisposableEffect(activity, highRefreshRate) {
        val window = activity?.window
        window?.let { setPreferredRefreshRate(it, if (highRefreshRate) null else 60f) }
        onDispose { window?.let { setPreferredRefreshRate(it, null) } }
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
    // Landscape whenever the window is wider than tall: the song sits in a panel beside the lyrics.
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    // In landscape the controls live on the cover, unless there's no panel and no big cover:
    // then they're the bottom bar, as in portrait.
    val controlsOnCover = landscape && (!hideHeader || headerExpanded)
    val bottomBar = !landscape || hideHeader
    // The panel slides to the other side over 0.4 s (`transition: left .4s`).
    val panelSide = animateFloatAsState(
        if (settings.panelSide == PanelSide.Right) 1f else 0f,
        tween(400, easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)),
        label = "panelSide",
    )
    // Where the cover is while the controls sit on it: only a touch there brings them up.
    val coverTouchArea = remember { CoverTouchArea() }
    // Elsewhere, where a touch leaves the controls alone: the cover's gestures, credit links.
    val revealGuard = remember { ControlsRevealGuard() }
    // Built fresh, not copied: the mode-dependent defaults are worked out in the constructor.
    val renderConfig = RenderConfig(
        simpleLyricsMode = settings.simpleLyricsMode,
        minimalLyricsMode = settings.minimalLyricsMode,
        simpleAnimationStyle = settings.simpleAnimationStyle,
        wordMotionBoost = settings.wordMotionBoost,
        wideDuetPadding = settings.duetLinePadding,
        syllableMerge = settings.syllableMerge,
        distanceBlurEnabled = settings.distanceBlur && !lowPerformance,
        glowEnabled = settings.glow && !lowPerformance,
        scroll = ScrollConfig(
            leadMs = if (settings.scrollLeadEnabled) settings.scrollLeadMs.coerceAtLeast(0).toLong() else 0L,
            smooth = settings.smoothScrolling,
            seekFadeCompensation = settings.seekFadeCompensation,
        ),
    )
    var showSettings by remember { mutableStateOf(false) }
    var showLyricsManager by remember { mutableStateOf(false) }
    var showSpotifySearch by remember { mutableStateOf(false) }
    // Android before 12 can't blur, so nothing blurs the page there: the glass and pop-ups fall
    // back to their solid fills instead of showing the page through.
    val backdrop = remember { HazeState().takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.S } }

    // Any touch shows the controls, and they fade after a few seconds without one.
    var controlsVisible by remember { mutableStateOf(true) }
    // Touch times go to the idle timer only: nothing in composition reads them, so a drag
    // (a touch event every frame) doesn't recompose the whole screen.
    val touches = remember { MutableStateFlow(0L) }
    // They stay while paused, and while the portrait big cover is up (it has nothing else to
    // show). On the landscape cover they always go, like the desktop page's once the mouse
    // rests: they'd keep the cover darkened.
    val hold = (!state.isPlaying && !controlsOnCover && !settings.hideControlsWhilePaused) ||
        (headerExpanded && !landscape) || !settings.autoHideControls
    val idleMs = settings.controlsHideDelay.millis
    LaunchedEffect(hold, idleMs) {
        if (hold) {
            controlsVisible = true
            return@LaunchedEffect
        }
        touches.collectLatest {
            delay(idleMs)
            controlsVisible = false
        }
    }

    LaunchedEffect(Unit) { viewModel.refresh() }
    val backgroundType = settings.backgroundType
    // Only what's on screen is looked up: the moving Kawarp background's beats, the artist's
    // header for the header backgrounds, the year where it shows.
    val beatReactive = backgroundType == BackgroundType.Default && settings.beatReactiveBackground &&
        !settings.staticBackground && !lowPerformance && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val hapticPlayer = remember { HapticPlayer(context) }
    val musicHapticsOn = settings.musicHaptics && hapticPlayer.canPlayMusic
    val extrasWanted = TrackExtrasWanted(
        releaseYear = settings.releaseYearPosition != ReleaseYearPosition.Off,
        artistHeader = backgroundType.usesArtistHeader,
        beats = beatReactive || musicHapticsOn,
    )
    LaunchedEffect(extrasWanted) { viewModel.setTrackExtrasWanted(extrasWanted) }
    // Vibrates with the beats while the song plays and the app is in front with the screen on.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(musicHapticsOn, state.isPlaying) {
        if (!musicHapticsOn || !state.isPlaying) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            playMusicHaptics(
                hapticPlayer,
                score = { viewModel.musicHaptics(settings.musicHapticsStyle) },
                positionMs = viewModel::currentLyricPositionMs,
                strength = { settings.musicHapticsStrength / 100f },
            )
        }
    }
    LaunchedEffect(Unit) { updater.checkOnLaunch(settings.includePrereleases) }
    val update by updater.state.collectAsState()

    // The bars are hidden, so this is only the camera cutout (and the keyboard in settings).
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        CompositionLocalProvider(LocalControlsRevealGuard provides revealGuard) {
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { revealGuard.screen = it }
                .pointerInput(Unit) {
                    // Sees every touch on its way down without taking it from what's underneath.
                    awaitPointerEventScope {
                        // What the touch under way holds back, decided where its first finger lands.
                        var hold = ControlsRevealGuard.Hold.None
                        var downAt = Offset.Zero
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val onCover = coverTouchArea.contains
                            if (event.type == PointerEventType.Press && event.changes.count { it.pressed } == 1) {
                                downAt = event.changes.first { it.pressed }.position
                                // The cover that carries the controls brings them up; nothing else holds.
                                hold = if (onCover != null) ControlsRevealGuard.Hold.None else revealGuard.holdAt(downAt)
                            }
                            if (hold == ControlsRevealGuard.Hold.Taps &&
                                event.changes.any { (it.position - downAt).getDistance() > viewConfiguration.touchSlop }
                            ) {
                                hold = ControlsRevealGuard.Hold.None
                            }
                            val held = hold != ControlsRevealGuard.Hold.None
                            if (event.changes.none { it.pressed }) hold = ControlsRevealGuard.Hold.None
                            if (held) continue
                            if (onCover != null && event.changes.none { onCover(it.position) }) continue
                            controlsVisible = true
                            touches.value = SystemClock.uptimeMillis()
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
                val headerMetrics = remember(pageWidth, pageHeight, density, topInsetPx, settings.headerSize) {
                    CompactHeaderMetrics(
                        pageWidthPx = pageWidth,
                        pageHeightPx = pageHeight,
                        density = density,
                        lyricFontSizeSp = LyricsLayoutMetrics(pageWidth, density, LyricsType.Syllable, 1f).baseFontSizeSp,
                        topInsetPx = topInsetPx,
                        size = settings.headerSize,
                    )
                }
                val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
                val belowTop = androidx.compose.foundation.layout.PaddingValues(
                    start = padding.calculateStartPadding(layoutDirection),
                    end = padding.calculateEndPadding(layoutDirection),
                    bottom = padding.calculateBottomPadding(),
                )
                val startInsetPx = with(LocalDensity.current) { belowTop.calculateStartPadding(layoutDirection).toPx() }
                val endInsetPx = with(LocalDensity.current) { belowTop.calculateEndPadding(layoutDirection).toPx() }
                // Landscape: the page between the cutout's insets, which is where the header and
                // lyrics are placed (`belowTop`).
                val landscapeMetrics = remember(pageWidth, pageHeight, density, topInsetPx, startInsetPx, endInsetPx) {
                    LandscapeMetrics(
                        pageWidthPx = pageWidth - startInsetPx - endInsetPx,
                        pageHeightPx = pageHeight - topInsetPx,
                        density = density,
                        topPx = topInsetPx,
                    )
                }
                // The lyrics' box beside the panel (or across the page without one). Mid-swap they
                // fade out and come back on the other side, rather than crossing the panel.
                val lyricsBoxLeft = landscapeMetrics.lyricsBox(withPanel = !hideHeader, panelOnRight = false)
                val lyricsBoxRight = landscapeMetrics.lyricsBox(withPanel = !hideHeader, panelOnRight = true)
                val lyricsBoxWidth = with(LocalDensity.current) { lyricsBoxLeft.widthPx.toDp() }
                fun Modifier.inLyricsBox(): Modifier = if (!landscape) this else this
                    .offset { IntOffset((if (panelSide.value < 0.5f) lyricsBoxLeft else lyricsBoxRight).leftPx.roundToInt(), 0) }
                    .width(lyricsBoxWidth)
                val swapFade = { if (landscape && !hideHeader) abs(1f - 2f * panelSide.value) else 1f }
                // Landscape lyrics keep the desktop page's size against the cover rather than
                // following their box's width.
                val lyricsFontScale = settings.lyricsSize.scale * if (landscape) {
                    landscapeMetrics.lyricFontSizeSp /
                        LyricsLayoutMetrics(lyricsBoxLeft.widthPx, density, LyricsType.Syllable, 1f).baseFontSizeSp
                } else 1f
                coverTouchArea.contains = if (!controlsOnCover) null else { position ->
                    val size = landscapeMetrics.coverSizePx
                    val t = if (hideHeader) 1f else expansion
                    val left = startInsetPx + landscapeMetrics.coverLeftPx(panelSide.value) +
                        (landscapeMetrics.centred.artLeftPx - landscapeMetrics.coverLeftPx(panelSide.value)) * t
                    val top = landscapeMetrics.coverTopPx
                    position.x in left..(left + size) && position.y in top..(top + size)
                }
                var controlsHeightPx by remember { mutableIntStateOf(0) }
                // Pinned credits sit just above the screen's edge, under the controls, and fade out
                // while the bottom controls show (they'd move under a finger otherwise). The lyrics
                // fade out above them.
                var pinnedHeightPx by remember { mutableIntStateOf(0) }
                val pinnedGapPx = with(LocalDensity.current) { PINNED_FOOTER_GAP.toPx() }
                val pinnedClearPx = with(LocalDensity.current) { 8.dp.toPx() }
                // While the controls show, a notice centres in the space they leave above them.
                val noticeBottomPx by animateFloatAsState(
                    if (controlsVisible && bottomBar) controlsHeightPx.toFloat() else 0f,
                    tween(SpicyMotion.CONTROLS_FADE_MS),
                    label = "noticeBottom",
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
                val playbackControls = PlaybackControlsState(
                    isPlaying = state.isPlaying,
                    canSeek = state.canSeek,
                    durationMs = state.durationMs,
                    positionMs = viewModel::currentPositionMs,
                    onPlayPause = viewModel::playPause,
                    onPrevious = viewModel::skipPrevious,
                    onNext = viewModel::skipNext,
                    onSeek = viewModel::seekTo,
                    // Shuffle and repeat stay in the playback row; only the player's other actions are floating buttons.
                    customActions = if (settings.playerButtons) state.customActions else state.customActions.filter { it.kind != ActionKind.Other },
                    onCustomAction = viewModel::sendCustomAction,
                    onResync = viewModel::resync,
                )
                val onOpenLyricsManager = if (settings.lyricsManagerButton) ({ showLyricsManager = true }) else null
                val romanizeButton = romanizationAvailable && settings.romanizeButton
                // Expanded, it's the only way back to the lyrics, so it stays.
                val expandButton = settings.expandButton || headerExpanded
                // The Cover Art background's animated cover; a stream that fails is forgotten, like the header's.
                val backgroundMotionQuery = MotionCoverQuery(state.artist, state.album, state.title)
                val backgroundMotionUrl = rememberMotionCoverUrl(
                    backgroundType == BackgroundType.CoverArt && settings.animatedBackground && !lowPerformance,
                    backgroundMotionQuery,
                )
                var failedBackgroundMotionUrl by remember { mutableStateOf<String?>(null) }
                val motionScope = rememberCoroutineScope()
                // Everything the glass controls blur.
                Box(Modifier.fillMaxSize().then(backdrop?.let { Modifier.hazeSource(it) } ?: Modifier)) {
                    SpicySessionBackground(
                        artwork = state.artwork,
                        artworkUri = state.artworkUri,
                        isPlaying = state.isPlaying,
                        modifier = Modifier.fillMaxSize(),
                        type = backgroundType,
                        animate = !lowPerformance && !settings.staticBackground,
                        blurDp = settings.backgroundBlur,
                        artistHeaderUrl = state.artistHeaderUrl,
                        artistHeaderPending = state.artistHeaderPending,
                        speed = if (beatReactive) viewModel::backgroundSpeed else null,
                        motionCoverUrl = backgroundMotionUrl?.takeIf { it != failedBackgroundMotionUrl },
                        onMotionCoverFailed = {
                            failedBackgroundMotionUrl = backgroundMotionUrl
                            motionScope.launch { forgetMotionCover(context, backgroundMotionQuery) }
                        },
                    )
                    Column(
                        Modifier
                            .padding(belowTop)
                            .inLyricsBox()
                            .fillMaxSize()
                            // Expanding, the lyrics fade out under the cover.
                            .graphicsLayer { alpha = (1f - expansion) * swapFade() },
                    ) {
                        // Without the header, the lyrics start just under the camera instead; in
                        // landscape they run the page's full height.
                        val lyricsTopPx = when {
                            landscape -> topInsetPx
                            hideHeader -> headerMetrics.barTopPx
                            else -> headerMetrics.lyricsTopPx
                        }
                        Spacer(Modifier.height(with(LocalDensity.current) { lyricsTopPx.toDp() }))
                        // Landscape lyrics scroll like the full page: centred, 30px high against lyrics
                        // at their size here, on the whole page (the low bar comes and goes over it).
                        val centredLiftPx = if (landscape) {
                            val lyricSp = landscapeMetrics.lyricFontSizeSp * settings.lyricsSize.scale
                            30f * lyricSp / 56f * density
                        } else {
                            30f * headerMetrics.lyricsScale * headerMetrics.density + controlsHeightPx / 2f
                        }
                        LyricsPanel(
                            lyrics = state.lyrics,
                            currentTimeMs = viewModel::currentLyricPositionMs,
                            onSeek = viewModel::seekTo,
                            romanize = romanize,
                            isPlaying = state.isPlaying,
                            // Compact scrolling keeps the active line near the top; without the
                            // header, full-page scrolling centres it, 30px high (`GetScrollType`), in the
                            // space above the controls (whether or not they are showing, so it holds still).
                            activeLineTopPx = headerMetrics.activeLineTopPx.takeUnless { hideHeader || landscape },
                            centredLiftPx = centredLiftPx,
                            noticeBottomPx = { noticeBottomPx },
                            viewState = lyricsViewState,
                            // Pinned credits fade out under the controls, so while those show the
                            // credits go back to the end of the lyrics.
                            pinnedFooter = if (controlsVisible && bottomBar) PinnedFooterMode.Off else pinnedFooter,
                            maskBottomPx = { if (pinnedHeightPx > 0) pinnedGapPx + pinnedHeightPx + pinnedClearPx else 0f },
                            config = renderConfig,
                            fontSizeScale = lyricsFontScale,
                            onSearchSpotify = { showSpotifySearch = true },
                            modifier = Modifier.weight(1f),
                        )
                    }
                // Hidden lyrics take no touches (no seeking through the cover view).
                if (headerExpanded) {
                    Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } })
                }
                // Hidden, the header only shows while the big cover is open or fading.
                val headerShowing by remember { derivedStateOf { expansion > 0f } }
                val expandedHeader = remember(headerMetrics, controlsHeightPx, landscape, landscapeMetrics) {
                    if (landscape) landscapeMetrics.centred else headerMetrics.expanded(pageHeight - controlsHeightPx)
                }
                CompactNowPlayingHeader(
                    info = NowPlayingInfo(state.title, state.artist, state.album, state.artwork, state.artworkUri, state.trackDirection),
                    releaseYear = ReleaseYear(state.releaseYear, state.releaseYearPending, settings.releaseYearPosition),
                    metrics = headerMetrics,
                    modifier = Modifier.padding(belowTop),
                    expansion = { expansion },
                    expanded = expandedHeader,
                    isPlaying = state.isPlaying,
                    onPlayPause = viewModel::playPause,
                    onSkip = { direction -> if (direction == TrackDirection.Forward) viewModel.skipNext() else viewModel.skipPrevious() },
                    animatedCover = settings.animatedCover && !lowPerformance && (!hideHeader || headerShowing),
                    hidden = hideHeader,
                    interactive = !hideHeader || headerExpanded,
                    panel = landscapeMetrics.panel.takeIf { landscape },
                    panelLeft = { landscapeMetrics.coverLeftPx(panelSide.value) },
                    coverOverlay = if (!controlsOnCover || controlsGone) null else {
                        {
                            // No glass blur here: the cover is part of what the glass blurs.
                            CompositionLocalProvider(LocalBackdrop provides null) {
                                CoverControls(
                                    controls = playbackControls,
                                    romanizeAvailable = romanizeButton,
                                    showResync = settings.resyncButton,
                                    showExpand = expandButton,
                                    romanized = romanize,
                                    onToggleRomanize = { settings.romanize = !settings.romanize },
                                    onOpenSettings = { showSettings = true },
                                    onOpenLyricsManager = onOpenLyricsManager,
                                    expanded = headerExpanded,
                                    onToggleExpanded = toggleExpanded,
                                    // Only beside the lyrics: the big cover sits in the middle.
                                    onSwapSide = if (hideHeader || headerExpanded) null else ({
                                        settings.panelSide = if (settings.panelSide == PanelSide.Left) PanelSide.Right else PanelSide.Left
                                    }),
                                    shown = { controlsShown },
                                )
                            }
                        }
                    },
                )
                // The header sits in what the glass and the shade blur, so the shade blurs it
                // rather than painting the blurred page over it.
                }
                // In landscape, the bottom bar gives way to the controls on the big cover.
                val barFade = { if (landscape) 1f - expansion.coerceIn(0f, 1f) else 1f }
                val barGone by remember { derivedStateOf { controlsShown * barFade() == 0f } }
                if (bottomBar) CompositionLocalProvider(LocalBackdrop provides backdrop?.takeUnless { barGone || lowPerformance }) {
                    LyricsControls(
                        controls = playbackControls,
                        romanizeAvailable = romanizeButton,
                        showResync = settings.resyncButton,
                        showExpand = expandButton,
                        romanized = romanize,
                        onToggleRomanize = { settings.romanize = !settings.romanize },
                        onOpenSettings = { showSettings = true },
                        onOpenLyricsManager = onOpenLyricsManager,
                        expanded = headerExpanded,
                        onToggleExpanded = toggleExpanded,
                        interactive = controlsVisible && !controlsOnCover,
                        shown = { controlsShown * barFade() },
                        // No lyrics behind them when expanded, so no shade over the cover.
                        shade = { 1f - expansion.coerceIn(0f, 1f) },
                        onControlsHeight = { controlsHeightPx = it },
                        wide = landscape,
                        insets = belowTop,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
                // Over the controls' shade rather than under it, so neither blurs nor covers them.
                PinnedLyricsFooter(
                    state = lyricsViewState,
                    mode = pinnedFooter,
                    fontSizeScale = lyricsFontScale,
                    // Faded out, they let the controls under them take the touch.
                    tappable = !(bottomBar && controlsTarget),
                    onHeight = { pinnedHeightPx = it },
                    modifier = Modifier
                        .align(if (landscape) Alignment.BottomStart else Alignment.BottomCenter)
                        .padding(belowTop)
                        .inLyricsBox()
                        .graphicsLayer {
                            translationY = -pinnedGapPx
                            val underControls = if (bottomBar) controlsShown * barFade() else 0f
                            alpha = (1f - expansion) * swapFade() * (1f - underControls)
                        },
                )
                CompositionLocalProvider(LocalBackdrop provides backdrop?.takeUnless { lowPerformance }) {
                    ScrollToActiveButton(
                        direction = lyricsViewState.activeLineDirection.takeIf {
                            settings.showScrollToActive && !headerExpanded && !showSettings
                        },
                        onClick = lyricsViewState::scrollToActive,
                        topPx = when {
                            landscape -> topInsetPx
                            hideHeader -> headerMetrics.barTopPx
                            else -> headerMetrics.lyricsTopPx
                        },
                        bottomPx = { pinnedGapPx + maxOf(noticeBottomPx, if (pinnedHeightPx > 0) pinnedHeightPx + pinnedClearPx else 0f) },
                        modifier = Modifier.padding(belowTop).inLyricsBox(),
                    )
                }
            }
        }
        }

        // Coming back from the system's settings refreshes the grant (onResume), which closes it.
        SpicyModal(
            visible = !state.accessGranted,
            onDismissRequest = null,
            backdrop = backdrop,
        ) {
            NotificationAccessMessage(openNotificationAccess)
        }

        // Kept through the closing animation, after the view model has cleared it.
        var lastLimit by remember { mutableStateOf<PlayerLimit?>(null) }
        state.limitNotice?.let { lastLimit = it }
        SpicyModal(
            visible = state.limitNotice != null,
            onDismissRequest = viewModel::dismissLimitNotice,
            backdrop = backdrop,
        ) {
            lastLimit?.let { SpotifyLimitMessage(it, viewModel::dismissLimitNotice) }
        }

        // Kept through the closing animation, after it's been answered.
        var lastProfile by remember { mutableStateOf<FooterLine?>(null) }
        lyricsViewState.profileRequest?.let { lastProfile = it }
        if (!settings.profilesInBrowser) {
            ProfileModal(lastProfile, lyricsViewState.profileRequest != null, backdrop, lyricsViewState::dismissProfile)
        } else SpicyModal(
            visible = lyricsViewState.profileRequest != null,
            onDismissRequest = lyricsViewState::dismissProfile,
            backdrop = backdrop,
        ) {
            lastProfile?.let { OpenProfileMessage(it, lyricsViewState::dismissProfile) }
        }

        if (showSettings) {
            SettingsScreen(
                state = state,
                viewModel = viewModel,
                updater = updater,
                settings = settings,
                backdrop = backdrop,
                contentPadding = padding,
                onClosed = { showSettings = false },
                onOpenLyricsManager = { showLyricsManager = true },
                onOpenSpotifySearch = { showSpotifySearch = true },
            )
        }

        // Over settings, since it opens from there too.
        LyricsManagerModal(
            visible = showLyricsManager,
            state = state,
            viewModel = viewModel,
            settings = settings,
            backdrop = backdrop,
            onDismissRequest = { showLyricsManager = false },
        )

        // Over settings, since it opens from there too.
        SpotifySearchModal(
            visible = showSpotifySearch,
            state = state,
            viewModel = viewModel,
            backdrop = backdrop,
            onDismissRequest = { showSpotifySearch = false },
        )

        // Over settings, so a check from there answers in place.
        UpdatePopup(update, updater, backdrop)

        SpicyToastHost(viewModel.messages, Modifier.padding(padding).padding(top = SpicySpacing.S4))
    }
}

/** Asks for the display mode nearest [hz] at the current resolution, or the system's choice for null. */
private fun setPreferredRefreshRate(window: Window, hz: Float?) {
    @Suppress("DEPRECATION")
    val display = window.windowManager.defaultDisplay
    val current = display.mode
    val modeId = hz?.let {
        display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .minByOrNull { mode -> abs(mode.refreshRate - hz) }?.modeId
    } ?: 0
    if (window.attributes.preferredDisplayModeId != modeId) {
        window.attributes = window.attributes.apply { preferredDisplayModeId = modeId }
    }
}

/** Tells the touch watcher whether a touch landed on the landscape cover; null: anywhere counts. */
private class CoverTouchArea {
    var contains: ((Offset) -> Boolean)? = null
}

/** Pinned credits' distance above the controls or the screen's edge (20px + 1.25rem). */
private val PINNED_FOOTER_GAP = 20.dp
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
private fun LyricsNotice(
    message: String,
    detail: String?,
    bottomPx: () -> Float,
    modifier: Modifier,
    onSearchSpotify: (() -> Unit)? = null,
) {
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
            if (onSearchSpotify != null) {
                SpicyButton("Find on Spotify", onClick = onSearchSpotify, modifier = Modifier.padding(top = cqh * 3))
            }
        }
    }
}

/**
 * The arrow that takes the lyrics back to the line being sung once it has been scrolled out of
 * sight: at the top when the line is below, at the bottom (above the controls and pinned
 * credits) when it's above, pointing its way. It fades in over 0.2 s and grows from 0.92 with a
 * little overshoot.
 */
@Composable
private fun ScrollToActiveButton(
    direction: ActiveLineDirection?,
    onClick: () -> Unit,
    topPx: Float,
    bottomPx: () -> Float,
    modifier: Modifier = Modifier,
) {
    // Keeps its place and arrow while it fades out.
    var shownDirection by remember { mutableStateOf(ActiveLineDirection.Below) }
    if (direction != null) shownDirection = direction
    val visible = direction != null
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(200), label = "scrollToActiveAlpha")
    val scale by animateFloatAsState(
        if (visible) 1f else 0.92f,
        tween(160, easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)),
        label = "scrollToActiveScale",
    )
    val above = shownDirection == ActiveLineDirection.Above
    val rotation by animateFloatAsState(if (above) 180f else 0f, tween(200), label = "scrollToActiveArrow")
    if (alpha == 0f && !visible) return
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        // clamp(2rem, 8cqh, 6rem) from the lyrics' top or bottom edge.
        val edge = (maxHeight * 0.08f).coerceIn(32.dp, 96.dp)
        val top = with(density) { topPx.toDp() } + edge
        GlassButton(
            onClick = { if (visible) onClick() },
            contentDescription = if (above) "Scroll up to active lyric" else "Scroll down to active lyric",
            size = 48.dp,
            modifier = Modifier
                .align(if (above) Alignment.BottomEnd else Alignment.TopEnd)
                .padding(end = 24.dp, top = if (above) 0.dp else top)
                .graphicsLayer {
                    if (above) translationY = -(bottomPx() + edge.toPx())
                    this.alpha = alpha
                    scaleX = scale
                    scaleY = scale
                },
        ) {
            Icon(
                Icons.Rounded.KeyboardArrowDown,
                null,
                Modifier.size(28.dp).graphicsLayer { rotationZ = rotation },
                tint = SpicyColors.TextPrimary,
            )
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
private fun UpdatePopup(update: UpdateUiState, updater: UpdateViewModel, backdrop: HazeState?) {
    // Kept through the closing animation.
    var shown by remember { mutableStateOf(update.status) }
    if (update.prompt) shown = update.status
    val release = when (val status = shown) {
        is UpdateStatus.Available -> status.release
        is UpdateStatus.Downloading -> status.release
        is UpdateStatus.Installing -> status.release
        is UpdateStatus.Failed -> status.release
        else -> null
    }
    SpicyModal(
        visible = update.prompt && release != null,
        onDismissRequest = updater::later,
        backdrop = backdrop,
    ) {
        if (release == null) return@SpicyModal
        SpicyModalHeading("Update available", release.name.takeIf { it != release.tag && it != release.version })
        SpicyVersionRow(BuildConfig.VERSION_NAME, release.version)
        val status = when (val current = shown) {
            is UpdateStatus.Installing -> "Confirm the install in Android's prompt."
            is UpdateStatus.Failed -> current.message
            else -> null
        }
        (shown as? UpdateStatus.Downloading)?.let { DownloadProgress(it.progress) }
            ?: SpicyModalNotes(release.notes, status ?: "No notes for this version.".takeIf { release.notes.isEmpty() })
        SpicyModalGap()
        val busy = shown is UpdateStatus.Downloading || shown is UpdateStatus.Installing
        SpicyModalActions {
            if (!busy) SpicyModalButton("Skip this version", updater::skip, style = SpicyButtonStyle.Quiet)
            SpicyModalButton(if (busy) "Hide" else "Later", updater::later)
            if (!busy) {
                SpicyModalButton(if (shown is UpdateStatus.Failed) "Try again" else "Update", updater::install, style = SpicyButtonStyle.Primary)
            }
        }
    }
}

/** The download as a capsule filling with white, like the timeline, with the percentage under it. */
@Composable
private fun DownloadProgress(progress: Float) {
    val shown by animateFloatAsState(progress.coerceIn(0f, 1f), tween(200), label = "downloadProgress")
    Column(verticalArrangement = Arrangement.spacedBy(SpicySpacing.S2)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(CircleShape)
                .background(SpicyColors.TintBg)
                .border(1.dp, SpicyColors.Hairline, CircleShape),
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(shown).clip(CircleShape).background(Color.White))
        }
        Text(
            "Downloading… ${(progress * 100).roundToInt()}%",
            style = SpicyType.Footnote.copy(color = SpicyColors.TextSecondary, fontFeatureSettings = "tnum"),
        )
    }
}

/** Asks before leaving the app for a credit's profile, since the links sit where thumbs land. */
@Composable
private fun OpenProfileMessage(line: FooterLine, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val url = line.profileUrl ?: return
    SpicyModalMessage(
        title = line.name?.let { "Open @$it's profile?" } ?: "Open this profile?",
        description = url.removePrefix("https://").substringBefore('/'),
        icon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(24.dp), tint = SpicyColors.TextPrimary) },
    )
    SpicyModalGap()
    SpicyModalActions {
        SpicyModalButton("Cancel", onDismiss)
        SpicyModalButton("Open", {
            onDismiss()
            runCatching { uriHandler.openUri(url) }
        }, style = SpicyButtonStyle.Primary)
    }
}

@Composable
private fun SpotifyLimitMessage(limit: PlayerLimit, onDismiss: () -> Unit) {
    val (title, description) = when (limit) {
        PlayerLimit.Seek -> "Seeking needs Spotify Premium" to
            "Spotify Free doesn't let other apps move around in a song, so tapping a line or dragging " +
            "the timeline can't jump there. Playback carries on as normal."
        PlayerLimit.Previous -> "Going back needs Spotify Premium" to
            "Spotify Free doesn't allow going back to the previous song from here."
        PlayerLimit.Skips -> "Spotify isn't skipping" to
            "Spotify Free stops allowing skips for a while after a few. Skipping works again later."
    }
    SpicyModalMessage(
        title = title,
        description = description,
        icon = { Icon(Icons.Rounded.Info, null, Modifier.size(24.dp), tint = SpicyColors.TextPrimary) },
    )
    SpicyModalGap()
    SpicyModalButton("Got it", onDismiss, style = SpicyButtonStyle.Primary, fill = true)
}

@Composable
private fun LyricsPanel(
    lyrics: LyricsState,
    currentTimeMs: () -> Long,
    onSeek: (Long) -> Unit,
    romanize: Boolean,
    isPlaying: Boolean,
    activeLineTopPx: Float?,
    centredLiftPx: Float,
    noticeBottomPx: () -> Float,
    viewState: LyricsViewState,
    pinnedFooter: PinnedFooterMode,
    maskBottomPx: () -> Float,
    config: RenderConfig,
    fontSizeScale: Float,
    onSearchSpotify: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The loading skeleton: up as soon as the lookup starts, over whatever the panel
    // shows, fading in over 0.2s and out over 0.25s (ease-out).
    Box(modifier) {
        LyricsPanelContent(
            lyrics, currentTimeMs, onSeek, romanize, isPlaying, activeLineTopPx, centredLiftPx, noticeBottomPx,
            viewState, pinnedFooter, maskBottomPx, config, fontSizeScale, onSearchSpotify, Modifier.fillMaxSize(),
        )
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
    isPlaying: Boolean,
    activeLineTopPx: Float?,
    centredLiftPx: Float,
    noticeBottomPx: () -> Float,
    viewState: LyricsViewState,
    pinnedFooter: PinnedFooterMode,
    maskBottomPx: () -> Float,
    config: RenderConfig,
    fontSizeScale: Float,
    onSearchSpotify: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (lyrics) {
        LyricsState.Idle -> LyricsNotice("Waiting for a song", null, noticeBottomPx, modifier)
        LyricsState.Loading -> Unit
        // A song that was looked up and missed may just be matched to the wrong recording.
        is LyricsState.Error -> LyricsNotice(
            lyrics.message, lyrics.detail, noticeBottomPx, modifier,
            onSearchSpotify = onSearchSpotify.takeIf { lyrics !== LyricsNotices.missingMetadata },
        )
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
                isPlaying = isPlaying,
                activeLineTopPx = activeLineTopPx,
                focusAnchorFraction = 0.5f,
                focusLiftPx = centredLiftPx,
                lyricsType = lyrics.lyricsType,
                config = config,
                fontSizeScale = fontSizeScale,
                viewState = viewState,
                pinnedFooter = pinnedFooter,
                maskBottomPx = maskBottomPx,
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

