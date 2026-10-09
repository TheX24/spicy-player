package com.tx24.spicyplayer.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tx24.spicyplayer.analytics.UsageCounter
import com.tx24.spicyplayer.analytics.UsageStats
import com.tx24.spicyplayer.playback.ExternalPlaybackViewModel
import com.tx24.spicyplayer.playback.PlayerUiState
import com.tx24.spicyplayer.ui.components.LocalUiAnimations
import com.tx24.spicyplayer.ui.components.SettingRow
import com.tx24.spicyplayer.ui.components.SettingsSkeleton
import com.tx24.spicyplayer.ui.components.SpicyButton
import com.tx24.spicyplayer.ui.components.SpicyModal
import com.tx24.spicyplayer.ui.components.screenSlide
import com.tx24.spicyplayer.ui.lyricsmanager.LyricsManagerContent
import com.tx24.spicyplayer.ui.theme.SpicySpacing
import dev.chrisbanes.haze.HazeState

private enum class QuickScreen { Main, Queue, LyricsManager }

/**
 * The quick settings pop-up, from its floating button: the player's queue (when it shares one)
 * and the Lyrics Manager, then the delays, tuned while the lyrics keep playing behind it rather
 * than from inside Settings. The queue and the Lyrics Manager open as screens of this pop-up,
 * sliding in over it, with a way back.
 */
@Composable
fun QuickSettingsModal(
    visible: Boolean,
    state: PlayerUiState,
    viewModel: ExternalPlaybackViewModel,
    settings: AppSettings,
    backdrop: HazeState?,
    /** Opened from the queue's own floating button: start on the queue. */
    startOnQueue: Boolean = false,
    onDismiss: () -> Unit,
) {
    var screen by remember { mutableStateOf(QuickScreen.Main) }
    var uploading by remember { mutableStateOf(false) }
    LaunchedEffect(visible, state.lyrics) { if (visible) viewModel.detectLyricsLanguage() }
    // Opens on its first screen each time.
    LaunchedEffect(visible) {
        if (visible) {
            screen = if (startOnQueue) QuickScreen.Queue else QuickScreen.Main
            if (startOnQueue) UsageStats.count(UsageCounter.QUEUE)
            uploading = false
        }
    }
    fun open(target: QuickScreen) {
        if (target == QuickScreen.Queue) UsageStats.count(UsageCounter.QUEUE)
        if (target == QuickScreen.LyricsManager) {
            UsageStats.count(UsageCounter.LYRICS_MANAGER)
            viewModel.refreshLocalLyrics()
        }
        screen = target
    }
    SpicyModal(
        visible = visible,
        onDismissRequest = onDismiss,
        backdrop = backdrop,
        title = when (screen) {
            QuickScreen.Main -> "Quick settings"
            QuickScreen.Queue -> "Queue"
            QuickScreen.LyricsManager -> if (uploading) "Upload TTML" else "Local Lyrics DB"
        },
        skeleton = { SettingsSkeleton(rows = if (state.queue.isNotEmpty()) 5 else 4) },
        // The header's back arrow (and Back) step out of a screen before Back closes the pop-up.
        onBack = when {
            screen == QuickScreen.Main -> null
            uploading -> ({ uploading = false })
            else -> ({ screen = QuickScreen.Main })
        },
    ) {
        val animate = LocalUiAnimations.current
        AnimatedContent(
            targetState = screen,
            transitionSpec = { screenSlide(forward = targetState != QuickScreen.Main, animate = animate) },
            label = "quickSettingsScreen",
        ) { shown ->
            Column(verticalArrangement = Arrangement.spacedBy(SpicySpacing.S3)) {
                when (shown) {
                    QuickScreen.Main -> MainScreen(state, viewModel, ::open)
                    // Its own height: the list scrolls inside the pop-up's body.
                    QuickScreen.Queue -> QueueList(state, viewModel, onJumped = onDismiss, modifier = Modifier.height(QUEUE_HEIGHT))
                    QuickScreen.LyricsManager -> LyricsManagerContent(state, viewModel, settings, uploading, { uploading = it }, onDismiss)
                }
            }
        }
    }
}

@Composable
private fun MainScreen(state: PlayerUiState, viewModel: ExternalPlaybackViewModel, open: (QuickScreen) -> Unit) {
    if (state.queue.isNotEmpty()) {
        SettingRow(
            label = "Queue",
            description = "${state.queue.size} ${if (state.queue.size == 1) "song" else "songs"}. Tap one to play it.",
            icon = Icons.AutoMirrored.Rounded.QueueMusic,
        ) {
            SpicyButton("Open", onClick = { open(QuickScreen.Queue) })
        }
    }
    SettingRow(
        label = "Lyrics Manager",
        description = "Save or upload lyrics for this song, and link a lyrics folder.",
        icon = Icons.Rounded.LibraryMusic,
    ) {
        SpicyButton("Open", onClick = { open(QuickScreen.LyricsManager) })
    }
    LyricsLanguageRow(state, viewModel)
    SongDelayRow(state, viewModel)
    SettingRow(
        label = "Output delay",
        description = "For every song on ${state.outputLabel}. Move it right if the lyrics run ahead of the song.",
        icon = Icons.Rounded.Timer,
        stacked = true,
    ) {
        DelayControl(state.lyricDelayMs, viewModel::setLyricDelay)
    }
}

/** The queue's height in the pop-up: room for about eight songs. */
private val QUEUE_HEIGHT = 440.dp
