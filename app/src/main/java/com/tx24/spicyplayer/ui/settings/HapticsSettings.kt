package com.tx24.spicyplayer.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LinearScale
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.tx24.spicyplayer.haptics.HapticPlayer
import com.tx24.spicyplayer.haptics.MusicHapticsStyle
import com.tx24.spicyplayer.haptics.MusicPulse
import com.tx24.spicyplayer.ui.components.SettingRow
import com.tx24.spicyplayer.ui.components.SettingsSection
import com.tx24.spicyplayer.ui.components.SpicyBipolarSlider
import com.tx24.spicyplayer.ui.components.SpicySelect
import com.tx24.spicyplayer.ui.components.ToggleRow

/** Screen → Haptics: touch feedback, and vibrating with the music. */
@Composable
internal fun HapticsSection(settings: AppSettings) {
    val context = LocalContext.current
    val player = remember { HapticPlayer(context) }
    SettingsSection("Haptics") {
        ToggleRow(
            label = "Touch haptics",
            checked = settings.touchHaptics,
            onCheckedChange = { settings.touchHaptics = it },
            description = "Tick on buttons, switches, sliders and gestures. Your phone's touch feedback setting applies too.",
            icon = Icons.Rounded.Vibration,
        )
        ToggleRow(
            label = "Haptics to the music",
            checked = settings.musicHaptics && player.canPlayMusic,
            onCheckedChange = { settings.musicHaptics = it },
            description = if (player.canPlayMusic) {
                "Feel the song while the lyrics are on screen, with a swell into every drop. Works for songs found on Spotify."
            } else {
                "Needs a vibration motor and Android 8 or newer."
            },
            icon = Icons.Rounded.Equalizer,
            enabled = player.canPlayMusic,
        )
        SettingRow(
            label = "Music haptics follow",
            description = "The drums: kicks deep, snares crisp, and the notes where there are no drums. The beat: a steady pulse, firmer on each bar.",
            icon = Icons.Rounded.GraphicEq,
            enabled = settings.musicHaptics && player.canPlayMusic,
        ) {
            SpicySelect(
                value = settings.musicHapticsStyle.name,
                options = MusicHapticsStyle.entries.map { it.name },
                labels = MusicHapticsStyle.entries.map { it.label },
                onChange = { settings.musicHapticsStyle = MusicHapticsStyle.valueOf(it) },
                enabled = settings.musicHaptics && player.canPlayMusic,
            )
        }
        SettingRow(
            label = "Music haptics strength",
            description = "How strong the vibrations to the music are. Android's media vibration setting can also turn them off.",
            icon = Icons.Rounded.LinearScale,
            enabled = settings.musicHaptics && player.canPlayMusic,
            stacked = true,
        ) {
            SpicyBipolarSlider(
                value = settings.musicHapticsStrength,
                range = 25..200,
                step = 25,
                onValueChange = {
                    settings.musicHapticsStrength = it
                    // Feel the new strength right away.
                    player.play(MusicPulse.Snare, 0.8f * it / 100f)
                },
                default = 100,
                unit = "%",
                enabled = settings.musicHaptics && player.canPlayMusic,
            )
        }
    }
}
