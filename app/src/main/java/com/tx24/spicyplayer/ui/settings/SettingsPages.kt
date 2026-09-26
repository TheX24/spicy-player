package com.tx24.spicyplayer.ui.settings

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
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
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
import com.tx24.spicyplayer.lyrics.spicy.models.LyricsType
import com.tx24.spicyplayer.network.data.LyricsCapability
import com.tx24.spicyplayer.network.data.LyricsSourceDescriptor
import com.tx24.spicyplayer.network.data.ProviderAttempt
import com.tx24.spicyplayer.network.data.ProviderAttemptOutcome
import com.tx24.spicyplayer.network.data.RemoteLyricsQuality
import com.tx24.spicyplayer.network.data.SourceReleaseChannel
import com.tx24.spicyplayer.playback.ExternalPlaybackViewModel
import com.tx24.spicyplayer.playback.PlayerUiState
import com.tx24.spicyplayer.ui.components.DISABLED_ALPHA
import com.tx24.spicyplayer.ui.components.DescriptionStyle
import com.tx24.spicyplayer.ui.components.RowLabel
import com.tx24.spicyplayer.ui.components.Searchable
import com.tx24.spicyplayer.ui.components.SettingRow
import com.tx24.spicyplayer.ui.components.SettingsSection
import com.tx24.spicyplayer.ui.components.SlBipolarSlider
import com.tx24.spicyplayer.ui.components.SlButton
import com.tx24.spicyplayer.ui.components.SlIconButton
import com.tx24.spicyplayer.ui.components.SlTextField
import com.tx24.spicyplayer.ui.components.SlToggle
import com.tx24.spicyplayer.ui.components.ToggleRow
import com.tx24.spicyplayer.ui.components.outlinedCard
import com.tx24.spicyplayer.ui.theme.SpicyColors
import com.tx24.spicyplayer.ui.theme.SpicySpacing
import com.tx24.spicyplayer.ui.theme.SpicyType

/*
 * What each group's page holds. Every page also shows up, row by row, in the search results, so a
 * page's first rows go without a section title (the page header says it) and later groups get one.
 */

@Composable
internal fun ThisSongContent(state: PlayerUiState, viewModel: ExternalPlaybackViewModel) {
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
        stacked = true,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SpicySpacing.S2)) {
            SlTextField(spotifyInput, { spotifyInput = it }, placeholder = "Spotify link or track ID", modifier = Modifier.weight(1f))
            if (spotifyInput.isBlank()) {
                SlButton("Paste", onClick = {
                    val pasted = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                        ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    spotifyInput = pasted
                    viewModel.overrideSpotifyId(pasted)
                })
            } else {
                SlButton("Use", onClick = { viewModel.overrideSpotifyId(spotifyInput) })
            }
        }
    }
    state.manualSpotifyId?.let { id ->
        SettingRow(label = "Using your Spotify link", description = id) {
            SlButton("Auto-match", onClick = viewModel::clearSpotifyIdOverride)
        }
    }
    SettingRow(label = "Look again", description = "Ask the sources again instead of using the saved lyrics.") {
        SlButton("Retry", onClick = { viewModel.loadLyrics(force = true) })
    }
    state.status?.let { status ->
        Searchable("Status", status) {
            Text(status, style = DescriptionStyle, modifier = Modifier.padding(horizontal = SpicySpacing.S3, vertical = SpicySpacing.S2))
        }
    }
}

@Composable
internal fun LyricsContent(prefs: LyricsPreferences) {
    ToggleRow(
        label = "Original word motion",
        checked = prefs.originalWordMotion,
        onCheckedChange = prefs.onOriginalWordMotionChange,
        description = "Spicy Lyrics' own amount of grow and lift on sung words. Off: ${prefs.wordMotionBoost}×, which reads better on a phone.",
    )
    ToggleRow(
        label = "Low performance mode",
        checked = prefs.lowPerformance,
        onCheckedChange = prefs.onLowPerformanceChange,
        description = "Stills the background and turns off blur and glow, for smoother lyrics on slower phones.",
    )
}

@Composable
internal fun SyncContent(state: PlayerUiState, viewModel: ExternalPlaybackViewModel) {
    SettingRow(
        label = "Lyric delay",
        description = "Saved for ${state.outputLabel}. Move it right if the lyrics run ahead of the song.",
        stacked = true,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SpicySpacing.S2)) {
            SlBipolarSlider(
                value = state.lyricDelayMs,
                range = -DELAY_RANGE_MS..DELAY_RANGE_MS,
                step = DELAY_STEP_MS,
                onValueChange = viewModel::setLyricDelay,
                unit = "ms",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(SpicySpacing.S2)) {
                SlButton("−$DELAY_STEP_MS ms", onClick = { viewModel.adjustLyricDelay(-DELAY_STEP_MS) })
                SlButton("+$DELAY_STEP_MS ms", onClick = { viewModel.adjustLyricDelay(DELAY_STEP_MS) })
            }
        }
    }
}

@Composable
internal fun SourcesContent(state: PlayerUiState, viewModel: ExternalPlaybackViewModel) {
    var clientKey by remember { mutableStateOf("") }
    Searchable("Sources", "Priority", "Order", *state.sourceOrder.mapNotNull { id -> state.sourceDescriptors.firstOrNull { it.id == id }?.displayName }.toTypedArray()) {
        Text(
            "Higher sources are asked first. Switched-off sources are skipped.",
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
            )
        }
    }
    SettingsSection("Spicy Lyrics key") {
        SettingRow(
            label = "Your Spicy Lyrics key",
            description = "Leave it empty to use the built-in key.",
            stacked = true,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SpicySpacing.S2)) {
                SlTextField(clientKey, { clientKey = it }, placeholder = "sl_pk_…", password = true, modifier = Modifier.weight(1f))
                SlButton("Use", onClick = { viewModel.useApiKey(clientKey) })
            }
        }
    }
}

/**
 * Pixel's `.sl-sp-source-card`: the rank in a ring, the name and what it gives, then up, down and
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
        SlIconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
            Icon(Icons.Rounded.KeyboardArrowUp, "Move ${source.displayName} up", tint = SpicyColors.TextPrimary, modifier = Modifier.size(20.dp))
        }
        SlIconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
            Icon(Icons.Rounded.KeyboardArrowDown, "Move ${source.displayName} down", tint = SpicyColors.TextPrimary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(2.dp))
        SlToggle(enabled)
    }
}

/** Pixel's `.sl-sp-source-rank` fill. */
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
internal fun AdvancedContent(state: PlayerUiState, viewModel: ExternalPlaybackViewModel) {
    SettingRow(label = "Clear lyrics cache", description = "Forget every saved lyric and look this song up again.") {
        SlButton("Clear", onClick = viewModel::clearLyricsCache)
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
    val name = state.sourceDescriptors.firstOrNull { it.id == attempt.sourceId }?.displayName ?: attempt.sourceId
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
