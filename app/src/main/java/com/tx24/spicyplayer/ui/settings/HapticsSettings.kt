package com.tx24.spicyplayer.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LinearScale
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tx24.spicyplayer.ui.components.SpicyButton
import com.tx24.spicyplayer.ui.theme.SpicySpacing
import com.tx24.spicyplayer.haptics.HapticPlayer
import com.tx24.spicyplayer.haptics.MusicHapticsStyle
import com.tx24.spicyplayer.haptics.MusicPulse
import com.tx24.spicyplayer.ui.components.SettingRow
import com.tx24.spicyplayer.ui.components.SettingsSection
import com.tx24.spicyplayer.ui.components.SpicyBipolarSlider
import com.tx24.spicyplayer.ui.components.SpicySelect
import com.tx24.spicyplayer.ui.components.ToggleRow

/** Device → Haptics: touch feedback, and vibrating with the music. */
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
        SettingRow(
            label = "Try the music haptics",
            description = "Feel each one at the strength set above.",
            icon = Icons.Rounded.TouchApp,
            enabled = settings.musicHaptics && player.canPlayMusic,
            stacked = true,
        ) {
            val enabled = settings.musicHaptics && player.canPlayMusic
            Row(horizontalArrangement = Arrangement.spacedBy(SpicySpacing.S2)) {
                listOf("Kick" to MusicPulse.Kick, "Snare" to MusicPulse.Snare, "Note" to MusicPulse.Note, "Drop" to MusicPulse.Drop)
                    .forEach { (label, pulse) ->
                        SpicyButton(
                            label,
                            { player.play(pulse, (0.9f * settings.musicHapticsStrength / 100f).coerceAtMost(1f)) },
                            Modifier.weight(1f),
                            enabled,
                            horizontalPadding = 0.dp,
                        )
                    }
            }
        }
    }
}
