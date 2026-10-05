package com.tx24.spicyplayer.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.runtime.Composable
import com.tx24.spicyplayer.playback.ExternalPlaybackViewModel
import com.tx24.spicyplayer.playback.PlayerUiState
import com.tx24.spicyplayer.ui.components.SettingRow
import com.tx24.spicyplayer.ui.components.SettingsSkeleton
import com.tx24.spicyplayer.ui.components.SpicyButton
import com.tx24.spicyplayer.ui.components.SpicyModal
import dev.chrisbanes.haze.HazeState

/**
 * The quick settings pop-up, from its floating button: the Lyrics Manager and the player's
 * queue (when it shares one), then the delays, tuned while the lyrics keep playing behind it
 * rather than from inside Settings.
 */
@Composable
fun QuickSettingsModal(
    visible: Boolean,
    state: PlayerUiState,
    viewModel: ExternalPlaybackViewModel,
    backdrop: HazeState?,
    onOpenLyricsManager: () -> Unit,
    onOpenQueue: () -> Unit,
    onDismiss: () -> Unit,
) {
    SpicyModal(
        visible = visible,
        onDismissRequest = onDismiss,
        backdrop = backdrop,
        title = "Quick settings",
        skeleton = { SettingsSkeleton(rows = if (state.queue.isNotEmpty()) 4 else 3) },
    ) {
        if (state.queue.isNotEmpty()) {
            SettingRow(
                label = "Queue",
                description = "${state.queue.size} ${if (state.queue.size == 1) "song" else "songs"}. Tap one to play it.",
                icon = Icons.AutoMirrored.Rounded.QueueMusic,
            ) {
                SpicyButton("Open", onClick = onOpenQueue)
            }
        }
        SettingRow(
            label = "Lyrics Manager",
            description = "Save or upload lyrics for this song, and link a lyrics folder.",
            icon = Icons.Rounded.LibraryMusic,
        ) {
            SpicyButton("Open", onClick = onOpenLyricsManager)
        }
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
}
