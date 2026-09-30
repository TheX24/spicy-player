package com.tx24.spicyplayer.ui.settings

import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.OpenInFull
import com.tx24.spicyplayer.ui.nowplaying.HeaderSize
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixOff
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.HideImage
import androidx.compose.material.icons.rounded.Height
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.FormatIndentIncrease
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.VerticalAlignCenter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.tx24.spicyplayer.lyrics.spicy.canvas.PinnedFooterMode
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Flare
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.BlurLinear
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.MotionPhotosPaused
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.StayCurrentPortrait
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.CallMerge
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowUp
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tx24.spicyplayer.lyrics.LyricsState
import com.tx24.spicyplayer.lyrics.spicy.SimpleAnimationStyle
import com.tx24.spicyplayer.lyrics.spicy.SyllableMerge
import com.tx24.spicyplayer.lyrics.spicy.models.LyricsType
import com.tx24.spicyplayer.network.data.LyricsCapability
import com.tx24.spicyplayer.network.data.LyricsSourceDescriptor
import com.tx24.spicyplayer.network.data.ProviderAttempt
import com.tx24.spicyplayer.network.data.ProviderAttemptOutcome
import com.tx24.spicyplayer.network.data.RemoteLyricsQuality
import com.tx24.spicyplayer.network.data.SourceReleaseChannel
import com.tx24.spicyplayer.playback.ExternalPlaybackViewModel
import com.tx24.spicyplayer.playback.LOCAL_SOURCE
import com.tx24.spicyplayer.playback.UPLOADED_SOURCE
import com.tx24.spicyplayer.playback.PlayerUiState
import com.tx24.spicyplayer.ui.components.DISABLED_ALPHA
import com.tx24.spicyplayer.ui.components.DescriptionStyle
import com.tx24.spicyplayer.ui.components.RowLabel
import com.tx24.spicyplayer.ui.components.SpicySelect
import com.tx24.spicyplayer.ui.components.Searchable
import com.tx24.spicyplayer.ui.components.SettingRow
import com.tx24.spicyplayer.ui.components.SettingsSection
import com.tx24.spicyplayer.ui.components.SpicyBipolarSlider
import com.tx24.spicyplayer.ui.components.SpicyButton
import com.tx24.spicyplayer.ui.components.SpicyIconButton
import com.tx24.spicyplayer.ui.components.SpicyTextField
import com.tx24.spicyplayer.ui.components.SpicyToggle
import com.tx24.spicyplayer.ui.components.ToggleRow
import com.tx24.spicyplayer.ui.components.outlinedCard
import com.tx24.spicyplayer.ui.theme.SpicyColors
import com.tx24.spicyplayer.ui.theme.SpicySpacing
import com.tx24.spicyplayer.ui.theme.SpicyType
import com.tx24.spicyplayer.update.UpdateViewModel

/*
 * What each group's page holds. Every page also shows up, row by row, in the search results, so a
 * page's first rows go without a section title (the page header says it) and later groups get one.
 */

@Composable
internal fun ThisSongContent(
    state: PlayerUiState,
    viewModel: ExternalPlaybackViewModel,
    onOpenLyricsManager: () -> Unit,
    onOpenSpotifySearch: () -> Unit,
) {
    val context = LocalContext.current
    var spotifyInput by remember { mutableStateOf("") }
    LaunchedEffect(state.title, state.artist) { spotifyInput = "" }

    Searchable("This song", "Now playing", state.title, state.artist) {
        Column(
            Modifier.fillMaxWidth().outlinedCard(tinted = true).padding(SpicySpacing.S4),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(state.title, style = SpicyType.Headline)
            Text(state.artist, style = SpicyType.Caption.copy(color = SpicyColors.TextSecondary))
            Spacer(Modifier.height(SpicySpacing.S2))
            Text(lyricsSummary(state.lyrics), style = SpicyType.Caption)
            state.lookupStatus?.let { Text(it, style = DescriptionStyle) }
        }
        Spacer(Modifier.height(SpicySpacing.S2))
    }
    SettingRow(
        label = "Wrong lyrics?",
        description = "Paste the song's Spotify link and the lyrics will come from that recording.",
        icon = Icons.Rounded.Link,
        stacked = true,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SpicySpacing.S2)) {
            SpicyTextField(spotifyInput, { spotifyInput = it }, placeholder = "Spotify link or track ID", modifier = Modifier.weight(1f))
            if (spotifyInput.isBlank()) {
                SpicyButton("Paste", onClick = {
                    val pasted = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                        ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    spotifyInput = pasted
                    viewModel.overrideSpotifyId(pasted)
                })
            } else {
                SpicyButton("Use", onClick = { viewModel.overrideSpotifyId(spotifyInput) })
            }
        }
    }
    SettingRow(label = "Find on Spotify", description = "Pick the right recording from Spotify's results for this song.", icon = Icons.Rounded.Search) {
        SpicyButton("Search", onClick = onOpenSpotifySearch)
    }
    state.manualSpotifyId?.let { id ->
        SettingRow(label = "Using your Spotify link", description = id, icon = Icons.Rounded.Link) {
            SpicyButton("Auto-match", onClick = viewModel::clearSpotifyIdOverride)
        }
    }
    SettingRow(label = "Look again", description = "Ask the sources again instead of using the saved lyrics.", icon = Icons.Rounded.Refresh) {
        SpicyButton("Retry", onClick = { viewModel.loadLyrics(force = true) })
    }
    SettingRow(
        label = "Lyrics Manager",
        description = "Use your own TTML for this song, once or saved, and manage the songs you've saved.",
        icon = Icons.Rounded.LibraryMusic,
    ) {
        SpicyButton("Open", onClick = onOpenLyricsManager)
    }
    state.status?.let { status ->
        Searchable("Status", status) {
            Text(status, style = DescriptionStyle, modifier = Modifier.padding(horizontal = SpicySpacing.S3, vertical = SpicySpacing.S2))
        }
    }
}

@Composable
internal fun LyricsContent(state: PlayerUiState, viewModel: ExternalPlaybackViewModel, settings: AppSettings) {
    ToggleRow(
        label = "Simple Lyrics Mode",
        checked = settings.simpleLyricsMode,
        onCheckedChange = { settings.simpleLyricsMode = it },
        description = "Remove extra visual effects from lyrics.",
        icon = Icons.Rounded.AutoFixOff,
    )
    SettingRow(
        label = "Simple Mode: Text Animation Style",
        description = "How lyrics text transitions are rendered in Simple Lyrics Mode.",
        icon = Icons.Rounded.Animation,
        enabled = settings.simpleLyricsMode,
    ) {
        SpicySelect(
            value = settings.simpleAnimationStyle.name,
            options = SimpleAnimationStyle.entries.map { it.name },
            labels = SimpleAnimationStyle.entries.map { it.name.lowercase() },
            onChange = { settings.simpleAnimationStyle = SimpleAnimationStyle.valueOf(it) },
            enabled = settings.simpleLyricsMode,
        )
    }
    ToggleRow(
        label = "Minimal Lyrics Mode",
        checked = settings.minimalLyricsMode,
        onCheckedChange = { settings.minimalLyricsMode = it },
        description = "Hides sung lyrics lines.",
        icon = Icons.Rounded.VisibilityOff,
    )
    ToggleRow(
        label = "Original word motion",
        checked = settings.originalWordMotion,
        onCheckedChange = { settings.originalWordMotion = it },
        description = "The desktop amount of grow and lift on sung words. Off: ${AppSettings.WORD_MOTION_BOOST}×, which reads better on a phone.",
        icon = Icons.Rounded.Height,
    )
    SettingRow(
        label = "Merge syllables",
        description = "Draw a word split into syllables as one word. Held words only merges the words that are then long enough for the held-word letter effect.",
        icon = Icons.Rounded.CallMerge,
    ) {
        SpicySelect(
            value = settings.syllableMerge.name,
            options = SyllableMerge.entries.map { it.name },
            labels = SyllableMerge.entries.map { it.label },
            onChange = { settings.syllableMerge = SyllableMerge.valueOf(it) },
        )
    }
    ToggleRow(
        label = "Duet Line Padding",
        checked = settings.duetLinePadding,
        onCheckedChange = { settings.duetLinePadding = it },
        description = "Indents lyrics lines on the side they lean away from when a song has duet lines, so the two voices read as separate columns. Disable to give every line the same slight padding.",
        icon = Icons.Rounded.FormatIndentIncrease,
    )
    SettingsSection("Effects") {
        ToggleRow(
            label = "Blur distant lines",
            checked = settings.distanceBlur && !settings.lowPerformance,
            onCheckedChange = { settings.distanceBlur = it },
            description = "Soften the lines further from the one being sung.",
            icon = Icons.Rounded.BlurOn,
            enabled = !settings.lowPerformance,
        )
        ToggleRow(
            label = "Glow",
            checked = settings.glow && !settings.lowPerformance,
            onCheckedChange = { settings.glow = it },
            description = "Let sung words glow.",
            icon = Icons.Rounded.Flare,
            enabled = !settings.lowPerformance,
        )
    }
    SettingsSection("Text") {
        SettingRow(label = "Lyrics size", description = "Make the lyrics smaller or bigger than the screen's default.", icon = Icons.Rounded.FormatSize) {
            SpicySelect(
                value = settings.lyricsSize.name,
                options = LyricsSize.entries.map { it.name },
                labels = LyricsSize.entries.map { it.label },
                onChange = { settings.lyricsSize = LyricsSize.valueOf(it) },
            )
        }
        LyricsFontRows(settings)
    }
}

/**
 * The lyrics font: ours, the phone's, or a font file picked from the phone. Choosing Custom with
 * nothing picked yet opens the picker.
 */
@Composable
private fun LyricsFontRows(settings: AppSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val imported = LyricsFontFile.import(context, uri)
            failed = imported == null
            if (imported != null) {
                settings.customFontFile = imported.fileName
                settings.customFontName = imported.displayName
                settings.lyricsFont = LyricsFont.Custom
            }
        }
    }
    val pick = { picker.launch(FONT_MIME_TYPES) }
    val hasCustom = settings.customFontFile.isNotBlank()
    SettingRow(label = "Lyrics Font", description = "Draw the lyrics in their own font, your phone's, or any font file on your phone.", icon = Icons.Rounded.FontDownload) {
        SpicySelect(
            value = settings.lyricsFont.name,
            options = LyricsFont.entries.map { it.name },
            labels = LyricsFont.entries.map { if (it == LyricsFont.Custom && hasCustom) settings.customFontName else it.label },
            onChange = { choice ->
                val font = LyricsFont.valueOf(choice)
                if (font == LyricsFont.Custom && !hasCustom) pick() else settings.lyricsFont = font
            },
        )
    }
    SettingRow(
        label = "Font File",
        description = when {
            failed -> "That file isn't a font Android can read. Pick a .ttf or .otf file."
            hasCustom -> "Using ${settings.customFontName}."
            else -> "Pick a .ttf or .otf font file from your phone."
        },
        icon = Icons.Rounded.FolderOpen,
    ) {
        SpicyButton(if (hasCustom) "Change" else "Choose", onClick = pick)
    }
}

/** Font files come labelled all sorts of ways, so the picker offers these (and checks the file). */
private val FONT_MIME_TYPES = arrayOf(
    "font/*", "application/font-sfnt", "application/x-font-ttf", "application/x-font-otf",
    "application/vnd.ms-opentype", "application/octet-stream",
)

@Composable
internal fun ScrollSyncContent(state: PlayerUiState, viewModel: ExternalPlaybackViewModel, settings: AppSettings) {
    SettingRow(
        label = "Lyric delay",
        description = "Saved for ${state.outputLabel}. Move it right if the lyrics run ahead of the song.",
        icon = Icons.Rounded.Timer,
        stacked = true,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SpicySpacing.S2)) {
            SpicyBipolarSlider(
                value = state.lyricDelayMs,
                range = -DELAY_RANGE_MS..DELAY_RANGE_MS,
                step = DELAY_STEP_MS,
                onValueChange = viewModel::setLyricDelay,
                unit = "ms",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(SpicySpacing.S2)) {
                SpicyButton("−$DELAY_STEP_MS ms", onClick = { viewModel.adjustLyricDelay(-DELAY_STEP_MS) })
                SpicyButton("+$DELAY_STEP_MS ms", onClick = { viewModel.adjustLyricDelay(DELAY_STEP_MS) })
            }
        }
    }
    ToggleRow(
        label = "Seek Fade-in Compensation",
        checked = settings.seekFadeCompensation,
        onCheckedChange = { settings.seekFadeCompensation = it },
        description = "Tapping a line jumps 300ms before it, so the player's fade-in doesn't cut off the start. Best for rap or fast-paced songs.",
        icon = Icons.Rounded.FastRewind,
    )
    SettingsSection("Scrolling") {
        ToggleRow(
            label = "Early Scroll",
            checked = settings.scrollLeadEnabled,
            onCheckedChange = { settings.scrollLeadEnabled = it },
            description = "Start scrolling to the next line slightly before it becomes active, so the move feels less abrupt.",
            icon = Icons.Rounded.KeyboardDoubleArrowUp,
        )
        SettingRow(
            label = "Early Scroll Time",
            description = "How early the next line is scrolled to, before it becomes active.",
            icon = Icons.Rounded.Timer,
            enabled = settings.scrollLeadEnabled,
            stacked = true,
        ) {
            SpicyBipolarSlider(
                value = settings.scrollLeadMs,
                range = 0..800,
                step = 10,
                onValueChange = { settings.scrollLeadMs = it },
                default = 250,
                unit = "ms",
                enabled = settings.scrollLeadEnabled,
            )
        }
        ToggleRow(
            label = "Show Scroll to Active Button",
            checked = settings.showScrollToActive,
            onCheckedChange = { settings.showScrollToActive = it },
            description = "Show an arrow when the active lyric is outside the viewport.",
            icon = Icons.Rounded.VerticalAlignCenter,
        )
        ToggleRow(
            label = "Smooth Scrolling",
            checked = settings.smoothScrolling,
            onCheckedChange = { settings.smoothScrolling = it },
            description = "Makes the lyrics scroll smoothly.",
            icon = Icons.Rounded.Waves,
        )
    }
}

@Composable
internal fun BackgroundContent(settings: AppSettings) {
    val type = settings.backgroundType
    SettingRow(
        label = "Background Type",
        description = "Choose the dynamic, legacy, static image, or color background.",
        icon = Icons.Rounded.Wallpaper,
    ) {
        SpicySelect(
            value = type.name,
            options = BackgroundType.entries.map { it.name },
            labels = BackgroundType.entries.map { it.label },
            onChange = { settings.backgroundType = BackgroundType.valueOf(it) },
        )
    }
    if (type.image) {
        SettingRow(
            label = "Background Blur",
            description = "Soften the static background image.",
            icon = Icons.Rounded.BlurLinear,
            stacked = true,
        ) {
            SpicyBipolarSlider(
                value = settings.backgroundBlur,
                range = 0..MAX_BACKGROUND_BLUR,
                step = 1,
                onValueChange = { settings.backgroundBlur = it },
                unit = "dp",
            )
        }
    }
    if (type == BackgroundType.CoverArt) {
        ToggleRow(
            label = "Animated Background",
            checked = settings.animatedBackground && !settings.lowPerformance,
            onCheckedChange = { settings.animatedBackground = it },
            description = "Play the album's animated cover from Apple Music behind the lyrics, where it has one.",
            icon = Icons.Rounded.Animation,
            enabled = !settings.lowPerformance,
        )
    }
    if (type.moving) {
        ToggleRow(
            label = "Still Background",
            checked = settings.staticBackground || settings.lowPerformance,
            onCheckedChange = { settings.staticBackground = it },
            description = "Hold the background still instead of animating it.",
            icon = Icons.Rounded.MotionPhotosPaused,
            enabled = !settings.lowPerformance,
        )
    }
    if (type == BackgroundType.Default) {
        ToggleRow(
            label = "Move with the Music",
            checked = settings.beatReactiveBackground && !settings.staticBackground && !settings.lowPerformance,
            onCheckedChange = { settings.beatReactiveBackground = it },
            description = "Speed the background up and down with the song's tempo, loudness and beats, where Spotify has them.",
            icon = Icons.Rounded.GraphicEq,
            enabled = !settings.staticBackground && !settings.lowPerformance,
        )
    }
}

@Composable
internal fun NowPlayingContent(settings: AppSettings) {
    ToggleRow(
        label = "Hide the song header",
        checked = settings.hideHeader,
        onCheckedChange = { settings.hideHeader = it },
        description = "Show only the lyrics, centred on the page (in landscape, without the cover panel). The expand button still opens the big cover.",
        icon = Icons.Rounded.HideImage,
    )
    SettingRow(
        label = "Song Header Size",
        description = "How tall the song header is. Small is a slim row, like Apple Music's.",
        icon = Icons.Rounded.Height,
        enabled = !settings.hideHeader,
    ) {
        SpicySelect(
            value = settings.headerSize.name,
            options = HeaderSize.entries.map { it.name },
            labels = HeaderSize.entries.map { it.label },
            onChange = { settings.headerSize = HeaderSize.valueOf(it) },
        )
    }
    SettingRow(
        label = "Release Year Position",
        description = "Show the release year beside the artists.",
        icon = Icons.Rounded.CalendarMonth,
    ) {
        SpicySelect(
            value = settings.releaseYearPosition.name,
            options = ReleaseYearPosition.entries.map { it.name },
            labels = ReleaseYearPosition.entries.map { it.label },
            onChange = { settings.releaseYearPosition = ReleaseYearPosition.valueOf(it) },
        )
    }
    ToggleRow(
        label = "Show the cover without lyrics",
        checked = settings.expandWithoutLyrics,
        onCheckedChange = { settings.expandWithoutLyrics = it },
        description = "Grow the song header into the big cover when a song has no lyrics.",
        icon = Icons.Rounded.Album,
    )
    ToggleRow(
        label = "Animated Cover",
        checked = settings.animatedCover && !settings.lowPerformance,
        onCheckedChange = { settings.animatedCover = it },
        description = "Play the album's animated cover from Apple Music, where it has one.",
        icon = Icons.Rounded.Animation,
        enabled = !settings.lowPerformance,
    )
    SettingsSection("Credits") {
        SettingRow(
            label = "Pinned Lyrics Footer",
            description = "Keep source and community credits visible. Full also pins writers.",
            icon = Icons.Rounded.PushPin,
        ) {
            SpicySelect(
                value = settings.pinnedFooter.name,
                options = PinnedFooterMode.entries.map { it.name },
                labels = PinnedFooterMode.entries.map { it.label },
                onChange = { settings.pinnedFooter = PinnedFooterMode.valueOf(it) },
            )
        }
        ToggleRow(
            label = "Open Profiles in Browser",
            checked = settings.profilesInBrowser,
            onCheckedChange = { settings.profilesInBrowser = it },
            description = "Open contributor profiles in your browser instead of inside the app.",
            icon = Icons.Rounded.OpenInBrowser,
        )
    }
}

@Composable
internal fun ControlsContent(settings: AppSettings) {
    ToggleRow(
        label = "Hide controls",
        checked = settings.autoHideControls,
        onCheckedChange = { settings.autoHideControls = it },
        description = "Fade the controls out a while after the last touch. A touch brings them back.",
        icon = Icons.Rounded.TouchApp,
    )
    SettingRow(
        label = "Hide After",
        description = "How long the controls stay after the last touch.",
        icon = Icons.Rounded.Timer,
        enabled = settings.autoHideControls,
    ) {
        SpicySelect(
            value = settings.controlsHideDelay.name,
            options = ControlsHideDelay.entries.map { it.name },
            labels = ControlsHideDelay.entries.map { it.label },
            onChange = { settings.controlsHideDelay = ControlsHideDelay.valueOf(it) },
        )
    }
    ToggleRow(
        label = "Hide while paused",
        checked = settings.hideControlsWhilePaused,
        onCheckedChange = { settings.hideControlsWhilePaused = it },
        description = "Fade the controls while the song is paused too, not only while it plays.",
        icon = Icons.Rounded.PauseCircle,
        enabled = settings.autoHideControls,
    )
    SettingsSection("Floating Buttons") {
        ToggleRow(
            label = "Player buttons",
            checked = settings.playerButtons,
            onCheckedChange = { settings.playerButtons = it },
            description = "Show the extra buttons the player offers, like Like or Save. Shuffle and repeat always stay.",
            icon = Icons.Rounded.Widgets,
        )
        ToggleRow(
            label = "Romanize button",
            checked = settings.romanizeButton,
            onCheckedChange = { settings.romanizeButton = it },
            description = "Show the button that romanizes lyrics, on songs that have them.",
            icon = Icons.Rounded.Translate,
        )
        ToggleRow(
            label = "Resync button",
            checked = settings.resyncButton,
            onCheckedChange = { settings.resyncButton = it },
            description = "Show the button that snaps the lyrics back in time with the player.",
            icon = Icons.Rounded.Sync,
        )
        ToggleRow(
            label = "Expand button",
            checked = settings.expandButton,
            onCheckedChange = { settings.expandButton = it },
            description = "Show the button that opens the big cover. It stays while the cover is open, to get back.",
            icon = Icons.Rounded.OpenInFull,
        )
        ToggleRow(
            label = "Lyrics Manager button",
            checked = settings.lyricsManagerButton,
            onCheckedChange = { settings.lyricsManagerButton = it },
            description = "Show the button that opens the Lyrics Manager. It's always in This song too.",
            icon = Icons.Rounded.LibraryMusic,
        )
    }
}

@Composable
internal fun DeviceContent(settings: AppSettings) {
    ToggleRow(
        label = "Keep the screen on",
        checked = settings.keepScreenOn,
        onCheckedChange = { settings.keepScreenOn = it },
        description = "Stop the screen from turning off while music plays.",
        icon = Icons.Rounded.StayCurrentPortrait,
    )
    SettingsSection("Performance") {
        ToggleRow(
            label = "Smoother motion",
            checked = settings.highRefreshRate,
            onCheckedChange = { settings.highRefreshRate = it },
            description = "Draw at your screen's full refresh rate instead of 60 Hz. Uses more battery.",
            icon = Icons.Rounded.Animation,
        )
        ToggleRow(
            label = "Low performance mode",
            checked = settings.lowPerformance,
            onCheckedChange = { settings.lowPerformance = it },
            description = "Stills the background and cover and turns off blur and glow, for smoother lyrics on slower phones.",
            icon = Icons.Rounded.Speed,
        )
    }
    HapticsSection(settings)
}

@Composable
internal fun SourcesContent(state: PlayerUiState, viewModel: ExternalPlaybackViewModel) {
    var clientKey by remember { mutableStateOf("") }
    Searchable("Sources", "Priority", "Order", *state.sourceOrder.mapNotNull { id -> state.sourceDescriptors.firstOrNull { it.id == id }?.displayName }.toTypedArray()) {
        Text(
            "Higher sources are asked first. Switched-off sources are skipped. Spicy Lyrics stands here for its community syncs; the Apple Music lyrics it serves rank as Apple Music.",
            style = DescriptionStyle,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp, bottom = SpicySpacing.S3),
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(SpicySpacing.S2)) {
        state.sourceOrder.forEachIndexed { index, id ->
            val source = state.sourceDescriptors.firstOrNull { it.id == id } ?: return@forEachIndexed
            Searchable("Sources", source.displayName, source.summary()) {
                SourceCard(
                    rank = index + 1,
                    source = source,
                    enabled = id !in state.disabledSourceIds,
                    canMoveUp = index > 0,
                    canMoveDown = index < state.sourceOrder.lastIndex,
                    onEnabledChange = { viewModel.setSourceEnabled(id, it) },
                    onMove = { viewModel.moveSource(id, it) },
                )
            }
        }
    }
    SettingsSection("Romanization") {
        ToggleRow(
            label = "Human romanizations",
            checked = state.humanRomanizations,
            onCheckedChange = viewModel::setHumanRomanizations,
            description = "Use a romanization written by people on Genius where it lines up with the lyrics, for readings no romanizer can guess.",
            icon = Icons.Rounded.Translate,
        )
    }
    SettingsSection("Blends") {
        Searchable("Blends", "Word timing", *state.blendDescriptors.map { it.displayName }.toTypedArray()) {
            Text(
                "Lines from the best source above, word timing from the donors. Each ranks just above its donors " +
                    "and only runs while every donor is switched on.",
                style = DescriptionStyle,
                modifier = Modifier.padding(horizontal = 2.dp).padding(bottom = SpicySpacing.S1),
            )
        }
        state.blendDescriptors.forEach { blend ->
            ToggleRow(
                label = blend.displayName,
                checked = blend.id in state.enabledBlendIds,
                onCheckedChange = { viewModel.setBlendEnabled(blend.id, it) },
                icon = Icons.Rounded.CallMerge,
            )
        }
    }
    SettingsSection("Spicy Lyrics key") {
        SettingRow(
            label = "Your Spicy Lyrics key",
            description = "Leave it empty to use the built-in key.",
            icon = Icons.Rounded.Key,
            stacked = true,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SpicySpacing.S2)) {
                SpicyTextField(clientKey, { clientKey = it }, placeholder = "sl_pk_…", password = true, modifier = Modifier.weight(1f))
                SpicyButton("Use", onClick = { viewModel.useApiKey(clientKey) })
            }
        }
    }
}

/**
 * A source's card: the rank in a ring, the name and what it gives, then up, down and
 * the switch. Tapping the card flips the switch; a switched-off source dims.
 */
@Composable
private fun SourceCard(
    rank: Int,
    source: LyricsSourceDescriptor,
    enabled: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .outlinedCard(tinted = true)
            .toggleable(enabled, remember { MutableInteractionSource() }, indication = null, role = Role.Switch, onValueChange = onEnabledChange)
            .padding(SpicySpacing.S3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpicySpacing.S2),
    ) {
        Box(
            Modifier
                .size(28.dp)
                .background(RankFill, CircleShape)
                .border(1.dp, SpicyColors.Hairline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("$rank", style = SpicyType.Caption.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"))
        }
        RowLabel(source.displayName, source.summary(), Modifier.weight(1f).alpha(if (enabled) 1f else DISABLED_ALPHA))
        SpicyIconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
            Icon(Icons.Rounded.KeyboardArrowUp, "Move ${source.displayName} up", tint = SpicyColors.TextPrimary, modifier = Modifier.size(20.dp))
        }
        SpicyIconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
            Icon(Icons.Rounded.KeyboardArrowDown, "Move ${source.displayName} down", tint = SpicyColors.TextPrimary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(2.dp))
        SpicyToggle(enabled)
    }
}

/** The rank ring's fill. */
private val RankFill = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.09f)

private fun LyricsSourceDescriptor.summary(): String = buildList {
    add(
        when {
            LyricsCapability.WORD_SYNC in capabilities -> "Word synced"
            LyricsCapability.LINE_SYNC in capabilities -> "Line synced"
            else -> "Unsynced"
        },
    )
    if (LyricsCapability.TRANSLATION in capabilities) add("translations")
    if (releaseChannel == SourceReleaseChannel.EXPERIMENTAL) add("experimental")
}.joinToString(" · ")

@Composable
internal fun AdvancedContent(state: PlayerUiState, viewModel: ExternalPlaybackViewModel, updater: UpdateViewModel, settings: AppSettings) {
    SettingRow(
        label = "Clear All Caches for Current Song",
        description = "Remove all cached lyrics data for the currently playing track.",
        icon = Icons.Rounded.DeleteSweep,
    ) {
        SpicyButton("Clear", onClick = viewModel::clearCurrentSongCaches)
    }
    SettingRow(
        label = "Clear Stored Lyrics Cache",
        description = "Delete lyrics that have been cached for up to 3 days.",
        icon = Icons.Rounded.DeleteForever,
    ) {
        SpicyButton("Clear Cache", onClick = viewModel::clearStoredLyricsCache)
    }
    SettingRow(
        label = "Clear Current Song from Internal State",
        description = "Remove the current song's lyrics from the in-memory state only.",
        icon = Icons.Rounded.Memory,
    ) {
        SpicyButton("Clear State", onClick = viewModel::clearCurrentSongFromMemory)
    }
    if (updater.enabled) {
        SettingsSection("Updates") {
            ToggleRow(
                label = "Include pre-releases",
                checked = settings.includePrereleases,
                onCheckedChange = { settings.includePrereleases = it },
                description = "Also offer test builds, which get new things first and may be rough.",
                icon = Icons.Rounded.Science,
            )
        }
    }
    val session = listOfNotNull(
        "Player" to (state.sourcePackage ?: "None"),
        "Output" to state.outputLabel,
        state.matchInfo?.let { "Match" to it },
        state.detectedSpotifyId?.let { "Spotify ID" to it },
        if (state.canSeek) null else "Seeking" to "This player doesn't allow it",
        state.lastCommandLatencyMs?.let { "Command answered" to "in $it ms" },
        state.clockDriftMs?.let { "Clock drift" to "${it.signed()} ms" },
    )
    SettingsSection("Session") { InfoLines(session) }
    (state.lyrics as? LyricsState.Ready)?.let { lyrics ->
        val details = listOfNotNull(
            "Source" to lyrics.provider,
            lyrics.source?.let { "Origin" to it },
            lyrics.maker?.let { "Made by" to it.username },
            lyrics.uploader?.let { "Uploaded by" to it.username },
            lyrics.songwriters.takeIf { it.isNotEmpty() }?.let { "Writers" to it.joinToString() },
        )
        SettingsSection("These lyrics") { InfoLines(details) }
    }
    SettingsSection("Last lookup") {
        Searchable("Last lookup", "Attempts", "Diagnostics") {
            if (state.providerAttempts.isEmpty()) {
                Text("No lookup yet", style = DescriptionStyle, modifier = Modifier.padding(horizontal = SpicySpacing.S3, vertical = SpicySpacing.S1))
            }
            state.providerAttempts.forEach { attempt -> AttemptLine(attempt, state) }
        }
    }
}

/** Label and value pairs, searchable by their labels. */
@Composable
private fun InfoLines(lines: List<Pair<String, String>>) {
    lines.forEach { (label, value) ->
        Searchable(label, value, "Diagnostics") {
            Row(Modifier.fillMaxWidth().padding(horizontal = SpicySpacing.S3, vertical = SpicySpacing.S1)) {
                Text(label, style = SpicyType.Caption.copy(color = SpicyColors.TextSecondary), modifier = Modifier.width(INFO_LABEL_WIDTH))
                Text(value, style = SpicyType.Caption, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AttemptLine(attempt: ProviderAttempt, state: PlayerUiState) {
    val name = (state.sourceDescriptors + LOCAL_SOURCE + UPLOADED_SOURCE)
        .firstOrNull { it.id == attempt.sourceId }?.displayName ?: attempt.sourceId
    val (outcome, color) = when (attempt.outcome) {
        ProviderAttemptOutcome.HIT -> "Found" to SpicyColors.StatusSuccess
        ProviderAttemptOutcome.MISS -> "Not found" to SpicyColors.TextTertiary
        ProviderAttemptOutcome.NEEDS_MATCH -> "Needs a Spotify link" to SpicyColors.StatusWarning
        ProviderAttemptOutcome.DISABLED -> "Switched off" to SpicyColors.TextTertiary
        ProviderAttemptOutcome.COOLING_DOWN -> "Resting after errors" to SpicyColors.StatusWarning
        ProviderAttemptOutcome.QUEUED, ProviderAttemptOutcome.PENDING -> "Asking…" to SpicyColors.StatusInfo
        ProviderAttemptOutcome.SKIPPED -> "Skipped" to SpicyColors.TextTertiary
        ProviderAttemptOutcome.UNAVAILABLE, ProviderAttemptOutcome.MALFORMED_HIT -> "Failed" to SpicyColors.StatusDanger
    }
    val detail = listOfNotNull(
        attempt.quality.takeIf { it != RemoteLyricsQuality.NONE }?.label(),
        attempt.failureCategory?.name?.lowercase()?.replace('_', ' '),
        attempt.message,
    ).joinToString(" · ")
    Row(Modifier.fillMaxWidth().padding(horizontal = SpicySpacing.S3, vertical = SpicySpacing.S1)) {
        Text(name, style = SpicyType.Caption.copy(color = SpicyColors.TextSecondary), modifier = Modifier.width(INFO_LABEL_WIDTH))
        Column(Modifier.weight(1f)) {
            Text(outcome, style = SpicyType.Caption.copy(color = color, fontWeight = FontWeight.Medium))
            if (detail.isNotEmpty()) Text(detail, style = DescriptionStyle)
        }
    }
}

private fun RemoteLyricsQuality.label() = when (this) {
    RemoteLyricsQuality.WORD_SYNCED -> "word synced"
    RemoteLyricsQuality.LINE_SYNCED -> "line synced"
    RemoteLyricsQuality.PLAIN -> "unsynced"
    RemoteLyricsQuality.NONE -> "nothing"
}

/** One line on where the current lyrics stand. */
internal fun lyricsSummary(lyrics: LyricsState): String = when (lyrics) {
    is LyricsState.Ready -> "${lyrics.provider}, " + when (lyrics.lyricsType) {
        LyricsType.Syllable -> "word synced"
        LyricsType.Line -> "line synced"
        LyricsType.Static -> "unsynced"
    }
    LyricsState.Loading -> "Looking for lyrics…"
    is LyricsState.Error -> lyrics.message
    LyricsState.Idle -> "No lyrics yet"
}

/** The delay slider's reach and step; the buttons nudge by one step. */
private const val DELAY_RANGE_MS = 1_000
private const val DELAY_STEP_MS = 10

private val INFO_LABEL_WIDTH = 116.dp
