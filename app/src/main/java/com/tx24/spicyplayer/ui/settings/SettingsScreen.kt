package com.tx24.spicyplayer.ui.settings

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
import androidx.compose.material.icons.rounded.SystemUpdate
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tx24.spicyplayer.BuildConfig
import com.tx24.spicyplayer.lyrics.spicy.SimpleAnimationStyle
import com.tx24.spicyplayer.playback.ExternalPlaybackViewModel
import com.tx24.spicyplayer.playback.PlayerUiState
import com.tx24.spicyplayer.ui.components.GlassButton
import com.tx24.spicyplayer.ui.components.LocalSettingsQuery
import com.tx24.spicyplayer.ui.components.Searchable
import com.tx24.spicyplayer.ui.components.SettingRow
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
    Appearance("Appearance"),
    Screen("Screen"),
    Sync("Sync"),
    Sources("Sources"),
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
) {
    val scope = rememberCoroutineScope()
    val modal = tween<Float>(SpicyMotion.MODAL_MS, easing = SpicyMotion.Modal)
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
                        SearchResults(query, state, viewModel, updater, settings, onOpenLyricsManager)
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
                    PageContent(shown, state, viewModel, updater, settings, onOpenLyricsManager)
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
) {
    when (page) {
        SettingsPage.ThisSong -> ThisSongContent(state, viewModel, onOpenLyricsManager)
        SettingsPage.Lyrics -> LyricsContent(state, viewModel, settings)
        SettingsPage.Appearance -> AppearanceContent(settings)
        SettingsPage.Screen -> ScreenContent(settings)
        SettingsPage.Sync -> SyncContent(state, viewModel, settings)
        SettingsPage.Sources -> SourcesContent(state, viewModel)
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
            Text(title, style = SpicyType.Title.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold))
        }
        header()
        if (ready) {
            val fade = remember { Animatable(0f) }
            LaunchedEffect(Unit) { fade.animateTo(1f, tween(SpicyMotion.FAST_MS, easing = SpicyMotion.Standard)) }
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
            SettingsSkeleton(rows = 3)
        }
        SettingsPage.Lyrics -> SettingsSkeleton(rows = 14)
        SettingsPage.Appearance -> SettingsSkeleton(rows = 6)
        SettingsPage.Screen -> SettingsSkeleton(rows = 7)
        SettingsPage.Sync -> SettingsSkeleton(rows = 2)
        SettingsPage.Sources -> SettingsSkeleton(rows = 10, cards = true)
        SettingsPage.Advanced -> SettingsSkeleton(rows = 8)
    }
}

@Composable
private fun HomeGroups(state: PlayerUiState, settings: AppSettings, updater: UpdateViewModel, onOpen: (SettingsPage) -> Unit) {
    Spacer(Modifier.height(SpicySpacing.S4))
    Column(Modifier.fillMaxWidth().outlinedCard()) {
        GroupRow(Icons.Rounded.MusicNote, SettingsPage.ThisSong.title, "${state.title} · ${lyricsSummary(state.lyrics)}") {
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
            ).joinToString().replaceFirstChar(Char::uppercase),
        ) { onOpen(SettingsPage.Lyrics) }
        GroupDivider()
        GroupRow(
            Icons.Rounded.Palette,
            SettingsPage.Appearance.title,
            listOf(
                when (settings.backgroundType) {
                    BackgroundType.Default -> "Dynamic background"
                    else -> "${settings.backgroundType.label} background"
                },
                "still".takeIf { settings.backgroundType.moving && (settings.staticBackground || settings.lowPerformance) },
                "animated cover".takeIf { settings.animatedCover && !settings.lowPerformance },
                "no blur".takeIf { !settings.distanceBlur || settings.lowPerformance },
                "no glow".takeIf { !settings.glow || settings.lowPerformance },
            ).filterNotNull().joinToString(),
        ) { onOpen(SettingsPage.Appearance) }
        GroupDivider()
        GroupRow(
            Icons.Rounded.Smartphone,
            SettingsPage.Screen.title,
            listOfNotNull(
                if (settings.keepScreenOn) "Stays on while playing" else "Turns off as usual",
                if (settings.highRefreshRate) "full refresh rate" else "60 Hz",
                "low performance".takeIf { settings.lowPerformance },
            ).joinToString(),
        ) { onOpen(SettingsPage.Screen) }
        GroupDivider()
        GroupRow(
            Icons.Rounded.Timer,
            SettingsPage.Sync.title,
            if (state.lyricDelayMs == 0) "No delay on ${state.outputLabel}" else "${state.lyricDelayMs.signed()} ms on ${state.outputLabel}",
        ) { onOpen(SettingsPage.Sync) }
        GroupDivider()
        GroupRow(
            Icons.Rounded.Layers,
            SettingsPage.Sources.title,
            "${state.sourceOrder.count { it !in state.disabledSourceIds }} of ${state.sourceOrder.size} on",
        ) { onOpen(SettingsPage.Sources) }
        GroupDivider()
        GroupRow(Icons.Rounded.Tune, SettingsPage.Advanced.title, "Updates, cache and diagnostics") { onOpen(SettingsPage.Advanced) }
    }
    Spacer(Modifier.height(SpicySpacing.S6))
    AboutCard(updater, settings)
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
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
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

/** The footer card: the build's identity and a way out to the project. */
@Composable
private fun AboutCard(updater: UpdateViewModel, settings: AppSettings) {
    val context = LocalContext.current
    val update by updater.state.collectAsState()
    // Found or hidden as one card; inside it, every row shows.
    Searchable("About", "Version", "GitHub", "Spicy Player", "Updates", "Check for updates") {
        CompositionLocalProvider(LocalSettingsQuery provides "") {
            Column(Modifier.fillMaxWidth().outlinedCard()) {
                Row(
                    // The rows' 14dp inset, so the buttons line up with the one below.
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = SpicySpacing.S4),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Spicy Player", style = SpicyType.Headline)
                        Text("Version ${BuildConfig.VERSION_NAME}", style = SpicyType.Footnote.copy(color = SpicyColors.TextSecondary))
                    }
                    SpicyButton("GitHub", onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL))) }
                    })
                }
                if (updater.enabled) {
                    GroupDivider()
                    // A found update reopens its pop-up; otherwise the button checks again.
                    val available = update.status as? UpdateStatus.Available
                    SettingRow(
                        label = "Check for updates",
                        description = updateLine(update.status),
                        icon = Icons.Rounded.SystemUpdate,
                        // The group rows' 14dp inset.
                        modifier = Modifier.padding(horizontal = 2.dp),
                    ) {
                        SpicyButton(
                            if (available != null) "Update" else "Check",
                            onClick = { if (available != null) updater.show() else updater.check(settings.includePrereleases) },
                            enabled = update.status != UpdateStatus.Checking,
                        )
                    }
                }
            }
        }
    }
}

/** How the last update check went, under the Updates card's title. */
private fun updateLine(status: UpdateStatus): String = when (status) {
    UpdateStatus.Idle -> "Look for a newer version on GitHub."
    UpdateStatus.Checking -> "Checking…"
    UpdateStatus.UpToDate -> "You're on the newest version."
    is UpdateStatus.Available -> "Version ${status.release.version} is ready to install."
    is UpdateStatus.Downloading -> "Downloading… ${(status.progress * 100).toInt()}%"
    is UpdateStatus.Installing -> "Waiting for Android's installer."
    is UpdateStatus.Failed -> status.message
}

/** Every page's rows at once, each hiding unless it matches the search. */
@Composable
private fun SearchResults(query: String, state: PlayerUiState, viewModel: ExternalPlaybackViewModel, updater: UpdateViewModel, settings: AppSettings, onOpenLyricsManager: () -> Unit) {
    CompositionLocalProvider(LocalSettingsQuery provides query) {
        EmptyOr(
            content = {
                Column(Modifier.fillMaxWidth()) {
                    SettingsPage.entries.forEach { page ->
                        SettingsSection(page.title) { PageContent(page, state, viewModel, updater, settings, onOpenLyricsManager) }
                    }
                    AboutCardSpacer(updater, settings)
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
private fun AboutCardSpacer(updater: UpdateViewModel, settings: AppSettings) {
    Searchable("About", "Version", "GitHub", "Spicy Player", "Updates", "Check for updates") { Spacer(Modifier.height(SpicySpacing.S4)) }
    AboutCard(updater, settings)
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

/** How much the lyrics show through while a back gesture closes settings. */
private const val BACKDROP_PEEK = 0.35f

/** How far a group's page moves when it slides in, as a share of the width. */
private const val PAGE_TRAVEL = 0.3f
private const val HOME_PARALLAX = 0.15f

/** How far a back gesture pulls a group's page toward the first page before letting go. */
private const val PAGE_PEEK = 0.5f

private const val PROJECT_URL = "https://github.com/TheX24/spicy-player"
