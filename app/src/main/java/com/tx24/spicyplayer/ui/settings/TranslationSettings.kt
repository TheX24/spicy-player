package com.tx24.spicyplayer.ui.settings

import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.tx24.spicyplayer.playback.ExternalPlaybackViewModel
import com.tx24.spicyplayer.playback.PlayerUiState
import com.tx24.spicyplayer.translation.DeepLKey
import com.tx24.spicyplayer.translation.TranslationLanguages
import com.tx24.spicyplayer.translation.TranslationMode
import com.tx24.spicyplayer.translation.TranslationProvider
import com.tx24.spicyplayer.ui.components.*
import com.tx24.spicyplayer.ui.theme.SpicySpacing

@Composable
internal fun TranslationContent(state: PlayerUiState, viewModel: ExternalPlaybackViewModel, settings: AppSettings) {
    val preferences = state.translationPreferences
    val languages = TranslationLanguages.common.toMutableMap().apply {
        if (preferences.targetLanguage !in this) put(preferences.targetLanguage, TranslationLanguages.name(preferences.targetLanguage))
    }.toList().sortedBy { it.second }
    var choosingExcluded by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }
    SettingRow("Target language", description = "Translate lyrics into this language.") {
        SpicySelect(preferences.targetLanguage, languages.map { it.first }, viewModel::setTranslationTarget, labels = languages.map { it.second })
    }
    SettingRow("Display mode", description = "Show translations below the lyrics or in their place.") {
        SpicySelect(settings.translationMode.name, TranslationMode.entries.map { it.name },
            { settings.translationMode = TranslationMode.valueOf(it) }, labels = TranslationMode.entries.map { it.label })
    }
    ToggleRow("Translate automatically", preferences.automatic, viewModel::setTranslateAutomatically,
        description = "Translate songs in other languages as their lyrics arrive.")
    SettingRow("Provider", description = "Choose who translates the original lyric lines.") {
        SpicySelect(preferences.provider.name, TranslationProvider.entries.map { it.name },
            { viewModel.setTranslationProvider(TranslationProvider.valueOf(it)) }, labels = TranslationProvider.entries.map { it.label })
    }
    ToggleRow("Use human translations from Genius", preferences.humanTranslations, viewModel::setHumanTranslations,
        description = "Use human translations where Genius's original lyrics confidently match, then fill gaps with the selected provider.")
    if (preferences.provider == TranslationProvider.DeepL) {
        SettingRow("DeepL API key", description = if (state.deepLKeyPresent) "A key is saved on this device. Paste a new key to replace it." else "Paste your DeepL API key. Free keys end in :fx.", stacked = true) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SpicySpacing.S2)) {
                SpicyTextField(key, { key = it }, placeholder = "DeepL API key", password = true, modifier = Modifier.weight(1f))
                SpicyButton("Use", onClick = {
                    viewModel.useDeepLKey(key)
                    if (DeepLKey.valid(key)) key = ""
                }, enabled = key.isNotBlank())
            }
        }
        if (state.deepLKeyPresent) {
            SettingRow("Remove DeepL key", description = "Forget the key on this device.") {
                SpicyButton("Remove", onClick = { viewModel.useDeepLKey("") })
            }
        }
    }
    SettingRow("Don't translate", description = "Leave songs in languages you already read in their original form.") {
        SpicyButton(if (preferences.excludedLanguages.isEmpty()) "Choose" else "${preferences.excludedLanguages.size} selected", onClick = { choosingExcluded = true })
    }
    SpicyModal(visible = choosingExcluded, onDismissRequest = { choosingExcluded = false }, title = "Don't translate", backdrop = LocalBackdrop.current) {
        CompositionLocalProvider(LocalSettingsQuery provides "") {
            languages.forEach { (code, name) ->
                ToggleRow(name, code in preferences.excludedLanguages, { selected ->
                    viewModel.setTranslationExcluded(if (selected) preferences.excludedLanguages + code else preferences.excludedLanguages - code)
                })
            }
        }
        SpicyButton("Done", onClick = { choosingExcluded = false })
    }
}

@Composable
internal fun LyricsLanguageRow(state: PlayerUiState, viewModel: ExternalPlaybackViewModel) {
    val detected = state.lyricsLanguage?.let { "Detected: ${TranslationLanguages.name(it)}" } ?: "Not detected yet"
    val languages = TranslationLanguages.common.toList().sortedBy { it.second }
    SettingRow("Lyrics language", description = detected, icon = Icons.Rounded.Translate) {
        SpicySelect(state.lyricsLanguageOverride ?: "auto", listOf("auto") + languages.map { it.first },
            { viewModel.setLyricsLanguage(it.takeUnless { code -> code == "auto" }) },
            labels = listOf("Auto") + languages.map { it.second }, enabled = state.localLyricsKey != null)
    }
}
