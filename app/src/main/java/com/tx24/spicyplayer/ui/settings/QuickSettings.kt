package com.tx24.spicyplayer.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.runtime.Composable
import com.tx24.spicyplayer.playback.ExternalPlaybackViewModel
import com.tx24.spicyplayer.playback.PlayerUiState
import com.tx24.spicyplayer.ui.components.SettingRow
import com.tx24.spicyplayer.ui.components.SpicyModal
import dev.chrisbanes.haze.HazeState

/**
 * The quick settings pop-up, from its floating button: the delays, tuned while the lyrics keep
 * playing behind it rather than from inside Settings.
 */
@Composable
fun QuickSettingsModal(
    visible: Boolean,
    state: PlayerUiState,
    viewModel: ExternalPlaybackViewModel,
    backdrop: HazeState?,
    onDismiss: () -> Unit,
) {
    SpicyModal(visible = visible, onDismissRequest = onDismiss, backdrop = backdrop, title = "Quick settings") {
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
