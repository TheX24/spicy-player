package com.tx24.spicyplayer.ui.settings

import com.tx24.spicyplayer.ui.components.drawBrandMark
import com.tx24.spicyplayer.ui.components.drawBrandRamp
import com.tx24.spicyplayer.ui.components.rememberBrandGlyph
import com.tx24.spicyplayer.ui.components.BrandTitleSize
import com.tx24.spicyplayer.ui.components.SpicyBrandLine
import com.tx24.spicyplayer.ui.theme.LocalSpicyPalette
import androidx.compose.ui.unit.em
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material.icons.rounded.ColorLens
import com.tx24.spicyplayer.ui.components.LocalUiAnimations

import androidx.compose.animation.core.snap

import androidx.compose.animation.core.AnimationSpec

import com.tx24.spicyplayer.analytics.UsageCounter
import com.tx24.spicyplayer.analytics.UsageStats
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Album
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tx24.spicyplayer.BuildConfig
import com.tx24.spicyplayer.haptics.withHaptic
import com.tx24.spicyplayer.lyrics.spicy.SimpleAnimationStyle
import com.tx24.spicyplayer.playback.ExternalPlaybackViewModel
import com.tx24.spicyplayer.playback.PlayerUiState
import com.tx24.spicyplayer.ui.components.GlassButton
import com.tx24.spicyplayer.ui.components.LocalSettingsQuery
import com.tx24.spicyplayer.ui.components.Searchable
import com.tx24.spicyplayer.ui.components.SettingsSkeleton
import com.tx24.spicyplayer.ui.components.SettingsSection
import com.tx24.spicyplayer.ui.components.SpicyButton
import com.tx24.spicyplayer.ui.components.SpicySearchBar
import com.tx24.spicyplayer.ui.components.outlinedCard
import com.tx24.spicyplayer.ui.theme.SpicyColors
import com.tx24.spicyplayer.ui.theme.SpicyMotion
import com.tx24.spicyplayer.ui.theme.SpicySpacing
import com.tx24.spicyplayer.ui.theme.SpicyType
import com.tx24.spicyplayer.update.UpdateStatus
import com.tx24.spicyplayer.update.UpdateViewModel
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

internal enum class SettingsPage(val title: String) {
    ThisSong("This song"),
    Lyrics("Lyrics"),
    ScrollSync("Scroll & Sync"),
    Background("Background"),
    NowPlaying("Now Playing"),
    Controls("Controls"),
    Theme("Theme"),
    Sources("Sources"),
    Device("Device"),
    Advanced("Advanced"),
}

/**
 * The settings, over the song's own background, blurred and dimmed, opening like the pop-ups
 * do (fade in, 0.96 → 1 scale, 220 ms).
 * The first page lists groups; each opens its own page, which slides in from the right.
 * Back, including Android's predictive back gesture, steps out one page at a time; on the first
 * page the gesture shrinks the whole screen toward the lyrics before it closes.
 *
 * Stays composed until its closing animation ends, then calls [onClosed].
 */
@Composable
fun SettingsScreen(
    state: PlayerUiState,
    viewModel: ExternalPlaybackViewModel,
    updater: UpdateViewModel,
    settings: AppSettings,
    backdrop: HazeState?,
    contentPadding: PaddingValues,
    onClosed: () -> Unit,
    onOpenLyricsManager: () -> Unit = {},
    onOpenSpotifySearch: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val palette = settings.settingsTheme.palette
    val glyph = rememberBrandGlyph()
    // Interface animations off: every move is instant.
    val animate = LocalUiAnimations.current
    val modal: AnimationSpec<Float> = if (animate) tween(SpicyMotion.MODAL_MS, easing = SpicyMotion.Modal) else snap()
    val open = remember { Animatable(0f) }
    // How far a back gesture on the first page has gone, and from which edge.
    val backSwipe = remember { Animatable(0f) }
    var swipeFromLeft by remember { mutableStateOf(true) }
    // 0 on the first page, 1 once a group's page has slid in.
    val pageShift = remember { Animatable(0f) }
    var page by remember { mutableStateOf<SettingsPage?>(null) }
    var closing by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val homeScroll = rememberScrollState()
    // Building a page's real rows can take a few hundred ms (more in debug builds), and an
    // animation started alongside would be over before it's seen. So a page opens with a
    // skeleton, which is quick to build, and swaps in its rows once it has settled.
    var homeReady by remember { mutableStateOf(false) }
    var pageReady by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withFrameNanos { }  // the frame that builds the screen, before timing starts
        open.animateTo(1f, modal)
        homeReady = true
    }

    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            open.animateTo(0f, modal)
            onClosed()
        }
    }
    fun show(target: SettingsPage) {
        UsageStats.count(UsageCounter.page(target.name))
        page = target
        pageReady = false
        scope.launch {
            pageShift.snapTo(0f)
            withFrameNanos { }
            pageShift.animateTo(1f, modal)
            pageReady = true
        }
    }
    fun back() {
        scope.launch {
            pageShift.animateTo(0f, modal)
            page = null
        }
    }

    PredictiveBackHandler(enabled = !closing) { progress ->
        val onPage = page != null
        try {
            progress.collect { event ->
                if (onPage) {
                    pageShift.snapTo(1f - event.progress * PAGE_PEEK)
                } else {
                    swipeFromLeft = event.swipeEdge == BackEventCompat.EDGE_LEFT
                    backSwipe.snapTo(event.progress)
                }
            }
            if (onPage) {
                pageShift.animateTo(0f, modal)
                page = null
            } else {
                close()
            }
        } catch (e: CancellationException) {
            scope.launch { if (onPage) pageShift.animateTo(1f, modal) else backSwipe.animateTo(0f, modal) }
            throw e
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            // Takes every touch, so nothing reaches the lyrics underneath.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
    ) {
        // The pop-up dim and blur, over the whole screen.
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = open.value * (1f - BACKDROP_PEEK * backSwipe.value) }
                .then(
                    if (backdrop != null) {
                        Modifier.hazeEffect(backdrop, SettingsBackdrop) {
                            // Blurs a third-size copy: the backdrop is soft anyway, and a full-size
                            // blur of the whole screen every frame costs the phone about half its
                            // frame rate.
                            inputScale = HazeInputScale.Fixed(BACKDROP_INPUT_SCALE)
                        }
                    } else {
                        // Without blur (Android before 12) the page's text would still read through.
                        Modifier.background(Color.Black.copy(alpha = 0.97f))
                    },
                ),
        )
        // The brand's glass: a deep tint, its ramp, and the logo cropped big off the top-right
        // corner, fading with the dim.
        palette.brand?.let { brand ->
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = open.value * (1f - BACKDROP_PEEK * backSwipe.value) }
                    .drawBehind {
                        drawRect(brand.fieldDeep.copy(alpha = 0.14f))
                        drawBrandRamp(brand, fromAlpha = 0.26f, toAlpha = 0.4f)
                        val width = minOf(MARK_WIDTH.toPx(), size.width * 0.9f)
                        drawBrandMark(
                            glyph, brand.tint, alpha = 0.4f, width = width,
                            right = -width * MARK_RIGHT, top = -width * MARK_TOP, degrees = -14f,
                        )
                    },
            )
        }
        CompositionLocalProvider(LocalSpicyPalette provides palette) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val scale = (MODAL_CLOSED_SCALE + (1f - MODAL_CLOSED_SCALE) * open.value) *
                        (1f - (1f - BACK_GESTURE_SCALE) * backSwipe.value)
                    scaleX = scale
                    scaleY = scale
                    alpha = open.value
                    translationX = (if (swipeFromLeft) 1f else -1f) * backSwipe.value * BACK_GESTURE_SHIFT.toPx()
                },
        ) {
            val width = constraints.maxWidth.toFloat()
            // Stays built under a group's page, so going back doesn't wait on rebuilding it.
            run {
                Page(
                    title = "Settings",
                    onBack = ::close,
                    contentPadding = contentPadding,
                    scroll = homeScroll,
                    ready = homeReady,
                    skeleton = { HomeSkeleton() },
                    modifier = Modifier.graphicsLayer {
                        translationX = -pageShift.value * width * HOME_PARALLAX
                        alpha = 1f - pageShift.value
                    },
                    header = { SpicySearchBar(query, { query = it }, Modifier.fillMaxWidth().padding(top = SpicySpacing.S4)) },
                ) {
                    if (query.isBlank()) {
                        HomeGroups(state, settings, updater, onOpen = ::show)
                    } else {
                        SearchResults(query, state, viewModel, updater, settings, onOpenLyricsManager, onOpenSpotifySearch)
                    }
                }
            }
            page?.let { shown ->
                Page(
                    title = shown.title,
                    onBack = ::back,
                    contentPadding = contentPadding,
                    scroll = rememberScrollState(),
                    ready = pageReady,
                    skeleton = { PageSkeleton(shown) },
                    modifier = Modifier
                        .graphicsLayer {
                            translationX = (1f - pageShift.value) * width * PAGE_TRAVEL
                            alpha = pageShift.value
                        }
                        // Keeps touches from falling through to the first page underneath.
                        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
                    header = { Spacer(Modifier.height(SpicySpacing.S2)) },
                ) {
                    PageContent(shown, state, viewModel, updater, settings, onOpenLyricsManager, onOpenSpotifySearch)
                }
            }
        }
        }
    }
}

@Composable
private fun PageContent(
    page: SettingsPage,
    state: PlayerUiState,
    viewModel: ExternalPlaybackViewModel,
    updater: UpdateViewModel,
    settings: AppSettings,
    onOpenLyricsManager: () -> Unit,
    onOpenSpotifySearch: () -> Unit,
) {
    when (page) {
        SettingsPage.ThisSong -> ThisSongContent(state, viewModel, onOpenLyricsManager, onOpenSpotifySearch)
        SettingsPage.Lyrics -> LyricsContent(state, viewModel, settings)
        SettingsPage.ScrollSync -> ScrollSyncContent(state, viewModel, settings)
        SettingsPage.Background -> BackgroundContent(settings)
        SettingsPage.NowPlaying -> NowPlayingContent(settings)
        SettingsPage.Controls -> ControlsContent(settings)
        SettingsPage.Theme -> ThemeContent(settings)
        SettingsPage.Sources -> SourcesContent(state, viewModel)
        SettingsPage.Device -> DeviceContent(settings)
        SettingsPage.Advanced -> AdvancedContent(state, viewModel, updater, settings)
    }
}

/** A scrolling page under a glass back button and its title. */
@Composable
private fun Page(
    title: String,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
    scroll: ScrollState,
    ready: Boolean,
    skeleton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(contentPadding)
            .padding(start = SpicySpacing.S4, end = SpicySpacing.S4, top = SpicySpacing.S2, bottom = 40.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassButton(onClick = onBack, contentDescription = "Back", size = 40.dp) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, null, tint = SpicyColors.TextPrimary, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.size(SpicySpacing.S3))
            if (SpicyColors.brand != null) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SpicyBrandLine()
                    Text(title, style = SpicyType.Title.copy(fontSize = BrandTitleSize, lineHeight = 1.1.em, letterSpacing = (-0.025f).em))
                }
            } else {
                Text(title, style = SpicyType.Title.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold))
            }
        }
        header()
        if (ready) {
            val animate = LocalUiAnimations.current
            val fade = remember { Animatable(if (animate) 0f else 1f) }
            LaunchedEffect(Unit) { if (animate) fade.animateTo(1f, tween(SpicyMotion.FAST_MS, easing = SpicyMotion.Standard)) }
            Column(Modifier.fillMaxWidth().graphicsLayer { alpha = fade.value }, content = content)
        } else {
            skeleton()
        }
    }
}

@Composable
private fun HomeSkeleton() {
    Spacer(Modifier.height(SpicySpacing.S4))
    SettingsSkeleton(rows = 1, cards = true)
    Spacer(Modifier.height(SpicySpacing.S3))
    SettingsSkeleton(rows = 4, modifier = Modifier.outlinedCard())
}

@Composable
private fun PageSkeleton(page: SettingsPage) {
    when (page) {
        SettingsPage.ThisSong -> {
            SettingsSkeleton(rows = 1, cards = true)
            SettingsSkeleton(rows = 5)
        }
        SettingsPage.Lyrics -> SettingsSkeleton(rows = 11)
        SettingsPage.ScrollSync -> SettingsSkeleton(rows = 6)
        SettingsPage.Background -> SettingsSkeleton(rows = 3)
        SettingsPage.NowPlaying -> SettingsSkeleton(rows = 8)
        SettingsPage.Controls -> SettingsSkeleton(rows = 10)
        SettingsPage.Theme -> SettingsSkeleton(rows = 3)
        SettingsPage.Sources -> SettingsSkeleton(rows = 13, cards = true)
        SettingsPage.Device -> SettingsSkeleton(rows = 7)
        SettingsPage.Advanced -> SettingsSkeleton(rows = 11)
    }
}

@Composable
private fun HomeGroups(state: PlayerUiState, settings: AppSettings, updater: UpdateViewModel, onOpen: (SettingsPage) -> Unit) {
    Spacer(Modifier.height(SpicySpacing.S4))
    Column(Modifier.fillMaxWidth().outlinedCard()) {
        GroupRow(Icons.Rounded.MusicNote, SettingsPage.ThisSong.title, "${state.title} · ${songSummary(state)}") {
            onOpen(SettingsPage.ThisSong)
        }
    }
    Spacer(Modifier.height(SpicySpacing.S3))
    Column(Modifier.fillMaxWidth().outlinedCard()) {
        GroupRow(
            Icons.Rounded.TextFields,
            SettingsPage.Lyrics.title,
            listOfNotNull(
                when {
                    settings.simpleLyricsMode && settings.minimalLyricsMode -> "Simple and Minimal"
                    settings.simpleLyricsMode -> "Simple"
                    settings.minimalLyricsMode -> "Minimal"
                    else -> null
                },
                if (settings.originalWordMotion) "Original word motion" else "Boosted word motion",
                "${settings.lyricsSize.label.lowercase()} text".takeIf { settings.lyricsSize != LyricsSize.Default },
                when (settings.lyricsFont) {
                    LyricsFont.Default -> null
                    LyricsFont.System -> "system font"
                    LyricsFont.Custom -> settings.customFontName.ifBlank { "custom font" }
                },
                "no blur".takeIf { !settings.distanceBlur || settings.lowPerformance },
                "no glow".takeIf { !settings.glow || settings.lowPerformance },
            ).joinToString().replaceFirstChar(Char::uppercase),
        ) { onOpen(SettingsPage.Lyrics) }
        GroupDivider()
        GroupRow(
            Icons.Rounded.Timer,
            SettingsPage.ScrollSync.title,
            listOfNotNull(
                if (state.lyricDelayMs == 0) "No delay on ${state.outputLabel}" else "${state.lyricDelayMs.signed()} ms on ${state.outputLabel}",
                "early scroll".takeIf { settings.scrollLeadEnabled },
                "smooth scrolling".takeIf { settings.smoothScrolling },
            ).joinToString(),
        ) { onOpen(SettingsPage.ScrollSync) }
        GroupDivider()
        GroupRow(
            Icons.Rounded.Palette,
            SettingsPage.Background.title,
            listOfNotNull(
                when (settings.backgroundType) {
                    BackgroundType.Default -> "Dynamic"
                    else -> settings.backgroundType.label
                },
                "still".takeIf { settings.backgroundType.moving && (settings.staticBackground || settings.lowPerformance) },
                "animated".takeIf { settings.backgroundType == BackgroundType.CoverArt && settings.animatedBackground && !settings.lowPerformance },
            ).joinToString(),
        ) { onOpen(SettingsPage.Background) }
        GroupDivider()
        GroupRow(
            Icons.Rounded.Album,
            SettingsPage.NowPlaying.title,
            listOfNotNull(
                if (settings.hideHeader) "Header hidden" else "${settings.headerSize.label} header",
                "animated cover".takeIf { settings.animatedCover && !settings.lowPerformance },
                "release year".takeIf { settings.releaseYearPosition != ReleaseYearPosition.Off },
            ).joinToString(),
        ) { onOpen(SettingsPage.NowPlaying) }
        GroupDivider()
        GroupRow(
            Icons.Rounded.TouchApp,
            SettingsPage.Controls.title,
            listOf(
                if (settings.autoHideControls) "Hide after ${settings.controlsHideDelay.label}" else "Always shown",
                "${listOf(settings.playerButtons, settings.romanizeButton, settings.resyncButton, settings.expandButton, settings.quickSettingsButton, settings.lyricsManagerButton, settings.queueButton).count { it }} of 7 extra buttons",
            ).joinToString(),
        ) { onOpen(SettingsPage.Controls) }
        GroupDivider()
        GroupRow(
            Icons.Rounded.ColorLens,
            SettingsPage.Theme.title,
            listOf(
                "${settings.settingsTheme.label} settings",
                "${settings.popupTheme.label.lowercase()} pop-ups",
                "${settings.appIcon.label.lowercase()} icon",
            ).joinToString(),
        ) { onOpen(SettingsPage.Theme) }
    }
    Spacer(Modifier.height(SpicySpacing.S3))
    Column(Modifier.fillMaxWidth().outlinedCard()) {
        GroupRow(
            Icons.Rounded.Layers,
            SettingsPage.Sources.title,
            "${state.sourceOrder.count { it !in state.disabledSourceIds }} of ${state.sourceOrder.size} on",
        ) { onOpen(SettingsPage.Sources) }
        GroupDivider()
        GroupRow(
            Icons.Rounded.Smartphone,
            SettingsPage.Device.title,
            listOfNotNull(
                if (settings.keepScreenOn) "Stays on while playing" else "Turns off as usual",
                if (settings.highRefreshRate) "full refresh rate" else "60 Hz",
                "low performance".takeIf { settings.lowPerformance },
                "haptics to the music".takeIf { settings.musicHaptics },
            ).joinToString(),
        ) { onOpen(SettingsPage.Device) }
        GroupDivider()
        GroupRow(Icons.Rounded.Tune, SettingsPage.Advanced.title, "Updates, cache and diagnostics") { onOpen(SettingsPage.Advanced) }
    }
    Spacer(Modifier.height(SpicySpacing.S6))
    AboutCard(state, updater, settings)
}

@Composable
private fun GroupRow(
    icon: ImageVector,
    label: String,
    summary: String,
    trailing: ImageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(
        if (pressed) SpicyColors.TintBg else Color.Transparent,
        tween(SpicyMotion.FAST_MS, easing = SpicyMotion.Standard),
        label = "groupPress",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .background(background)
            .clickable(interaction, indication = null, role = Role.Button, onClick = withHaptic(HapticFeedbackType.ContextClick, onClick))
            .padding(horizontal = 14.dp, vertical = SpicySpacing.S3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = SpicyColors.TextSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(SpicySpacing.S3))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = SpicyType.Body.copy(fontWeight = FontWeight.Medium))
            Text(summary, style = SpicyType.Caption.copy(color = SpicyColors.TextSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(trailing, null, tint = SpicyColors.TextTertiary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun GroupDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(SpicyColors.Hairline))
}

/**
 * The footer card, kept short: the name, version, channel and update status with a manual check,
 * then the links out two by two. The status checks quietly on its own and claims nothing until it knows.
 */
@Composable
private fun AboutCard(state: PlayerUiState, updater: UpdateViewModel, settings: AppSettings) {
    val context = LocalContext.current
    val update by updater.state.collectAsState()
    LaunchedEffect(Unit) { updater.checkQuietly(settings.includePrereleases) }
    // Found or hidden as one card; inside it, everything shows.
    Searchable("About", "Version", "GitHub", "Spicy Player", "Updates", "Check for updates", "Feedback", "Bug", "Report", "Feature", "Suggest", "Discord") {
        CompositionLocalProvider(LocalSettingsQuery provides "") {
            Column(
                Modifier.fillMaxWidth().outlinedCard().padding(SpicySpacing.S4),
                verticalArrangement = Arrangement.spacedBy(SpicySpacing.S3),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Spicy Player", style = SpicyType.Headline)
                        Text(versionLine(update.status, updater.enabled), style = SpicyType.Footnote.copy(color = SpicyColors.TextSecondary))
                    }
                    if (updater.enabled) UpdateButton(update.status, onUpdate = updater::show) { updater.check(settings.includePrereleases) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(SpicySpacing.S2)) {
                    FooterLink("GitHub", Modifier.weight(1f)) { openUrl(context, PROJECT_URL) }
                    FooterLink("Discord", Modifier.weight(1f), DISCORD_BLURPLE) { openUrl(context, DISCORD_URL) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(SpicySpacing.S2)) {
                    // Fills in the form's version and debug info; the reporter sees both before sending.
                    FooterLink("Report a bug", Modifier.weight(1f), BUG_PINK) {
                        val debug = Uri.encode(debugReport(context, state).take(MAX_PREFILLED_DEBUG))
                        openUrl(context, "$PROJECT_URL/issues/new?template=bug_report.yml&version=${Uri.encode(BuildConfig.VERSION_NAME)}&debug=$debug")
                    }
                    FooterLink("Suggest a feature", Modifier.weight(1f)) { openUrl(context, "$PROJECT_URL/issues/new?template=feature_request.yml") }
                }
            }
        }
    }
}

@Composable
private fun FooterLink(label: String, modifier: Modifier, brand: Color? = null, onClick: () -> Unit) {
    SpicyButton(label, onClick, modifier, horizontalPadding = 8.dp, brand = brand, external = true)
}

/** "v0.6.0 · pre-release · Latest": the status only once a check has said something. */
@Composable
private fun versionLine(status: UpdateStatus, enabled: Boolean): AnnotatedString = buildAnnotatedString {
    append("v${BuildConfig.VERSION_NAME} · ${buildChannel()}")
    if (!enabled) return@buildAnnotatedString
    val (text, color) = when (status) {
        UpdateStatus.UpToDate -> "Latest" to SpicyColors.StatusSuccess
        is UpdateStatus.Available -> "Update available" to SpicyColors.StatusWarning
        is UpdateStatus.Failed -> "Couldn't check" to SpicyColors.TextTertiary
        else -> return@buildAnnotatedString
    }
    append(" · ")
    withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold)) { append(text) }
}

/** Checks on demand; once an update is found, opens it instead. */
@Composable
private fun UpdateButton(status: UpdateStatus, onUpdate: () -> Unit, onCheck: () -> Unit) {
    when (status) {
        is UpdateStatus.Available -> SpicyButton("Update", onUpdate, brand = SpicyColors.StatusWarning)
        UpdateStatus.Checking -> SpicyButton("Checking…", {}, enabled = false)
        is UpdateStatus.Downloading -> SpicyButton("${(status.progress * 100).toInt()}%", {}, enabled = false)
        is UpdateStatus.Installing -> SpicyButton("Installing", {}, enabled = false)
        UpdateStatus.Idle, UpdateStatus.UpToDate, is UpdateStatus.Failed -> SpicyButton("Check for updates", onCheck)
    }
}

/**
 * Which channel this build is from: every 0.x release, and any version with a suffix ("1.0.0-beta"),
 * is published as a pre-release, the same rule the update check uses to offer pre-releases.
 */
private fun buildChannel(): String = when {
    BuildConfig.DEBUG -> "debug"
    BuildConfig.VERSION_NAME.startsWith("0.") || '-' in BuildConfig.VERSION_NAME -> "pre-release"
    else -> "stable"
}

private val DISCORD_BLURPLE = Color(88, 101, 242)
private val BUG_PINK = Color(255, 94, 138)

/** Every page's rows at once, each hiding unless it matches the search. */
@Composable
private fun SearchResults(query: String, state: PlayerUiState, viewModel: ExternalPlaybackViewModel, updater: UpdateViewModel, settings: AppSettings, onOpenLyricsManager: () -> Unit, onOpenSpotifySearch: () -> Unit) {
    CompositionLocalProvider(LocalSettingsQuery provides query) {
        EmptyOr(
            content = {
                Column(Modifier.fillMaxWidth()) {
                    SettingsPage.entries.forEach { page ->
                        SettingsSection(page.title) { PageContent(page, state, viewModel, updater, settings, onOpenLyricsManager, onOpenSpotifySearch) }
                    }
                    AboutCardSpacer(state, updater, settings)
                }
            },
            empty = {
                Text(
                    "No settings match “${query.trim()}”",
                    style = SpicyType.Body.copy(color = SpicyColors.TextSecondary),
                    modifier = Modifier.padding(horizontal = 2.dp, vertical = SpicySpacing.S6),
                )
            },
        )
    }
}

@Composable
private fun AboutCardSpacer(state: PlayerUiState, updater: UpdateViewModel, settings: AppSettings) {
    Searchable("About", "Version", "GitHub", "Spicy Player", "Updates", "Check for updates", "Feedback", "Bug", "Report", "Feature", "Suggest", "Discord") { Spacer(Modifier.height(SpicySpacing.S4)) }
    AboutCard(state, updater, settings)
}

/** [content], or [empty] in its place when [content] lays out with no height. */
@Composable
private fun EmptyOr(content: @Composable () -> Unit, empty: @Composable () -> Unit) {
    Layout(contents = listOf(content, empty)) { (contentMeasurables, emptyMeasurables), constraints ->
        val loose = constraints.copy(minHeight = 0)
        val placeables = contentMeasurables.map { it.measure(loose) }.takeIf { list -> list.sumOf { it.height } > 0 }
            ?: emptyMeasurables.map { it.measure(loose) }
        layout(constraints.maxWidth, placeables.sumOf { it.height }) {
            var y = 0
            placeables.forEach { it.place(0, y); y += it.height }
        }
    }
}

internal fun Int.signed(): String = if (this > 0) "+$this" else toString()
internal fun Long.signed(): String = if (this > 0) "+$this" else toString()

/** Behind settings, the page dims to 45% black over a strong blur of the lyrics. */
private val SettingsBackdrop = HazeStyle(
    backgroundColor = Color.Black,
    tints = listOf(HazeTint(Color.Black.copy(alpha = 0.45f))),
    blurRadius = 32.dp,
    noiseFactor = 0f,
    fallbackTint = HazeTint(Color.Black.copy(alpha = 0.8f)),
)

private const val BACKDROP_INPUT_SCALE = 1f / 3f

/** The scale a pop-up opens from and closes to. */
private const val MODAL_CLOSED_SCALE = 0.96f

/** Material's predictive back for a full screen: down to 90%, nudged 8dp away from the edge. */
private const val BACK_GESTURE_SCALE = 0.9f
private val BACK_GESTURE_SHIFT = 8.dp

/** The mark over the themed backdrop: 420px across, 130px off the right edge and 150px off the top. */
private val MARK_WIDTH = 420.dp
private const val MARK_RIGHT = 130f / 420f
private const val MARK_TOP = 150f / 420f

/** How much the lyrics show through while a back gesture closes settings. */
private const val BACKDROP_PEEK = 0.35f

/** How far a group's page moves when it slides in, as a share of the width. */
private const val PAGE_TRAVEL = 0.3f
private const val HOME_PARALLAX = 0.15f

/** How far a back gesture pulls a group's page toward the first page before letting go. */
private const val PAGE_PEEK = 0.5f

private const val PROJECT_URL = "https://github.com/TheX24/spicy-player"
// Keeps the prefilled form link well under the length GitHub accepts.
private const val MAX_PREFILLED_DEBUG = 3000

// The server invite, not the thread link: a thread link only opens for people already in the server.
private const val DISCORD_URL = "https://discord.com/invite/uqgXU5wh8j"

internal fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
