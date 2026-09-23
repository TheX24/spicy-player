package com.tx24.spicyplayer.latencytest

import android.content.Context
import android.content.Intent
import android.content.ClipboardManager
import android.os.Bundle
import android.provider.Settings
import com.tx24.spicyplayer.BuildConfig
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tx24.spicyplayer.lyrics.spicy.canvas.SpicyLyricsView
import com.tx24.spicyplayer.lyrics.spicy.models.Line
import com.tx24.spicyplayer.lyrics.spicy.models.Word
import com.tx24.spicyplayer.lyrics.spicy.models.buildDisplayTimeline

class MainActivity : ComponentActivity() {
    private val playbackViewModel: ExternalPlaybackViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                LatencyTestApp(
                    viewModel = playbackViewModel,
                    openNotificationAccess = {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        playbackViewModel.refresh()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LatencyTestApp(
    openNotificationAccess: () -> Unit,
    viewModel: ExternalPlaybackViewModel,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var clientKey by remember { mutableStateOf("") }
    var spotifyIdInput by remember { mutableStateOf("") }
    var scrubPosition by remember { mutableStateOf<Long?>(null) }
    // Global and persisted, like the reference's "romanization" setting.
    val uiPrefs = remember { context.getSharedPreferences("ui", Context.MODE_PRIVATE) }
    var romanizePreferred by remember { mutableStateOf(uiPrefs.getBoolean("romanize", false)) }
    val romanizationAvailable = (state.lyrics as? LyricsState.Ready)?.let { ready ->
        ready.plainRomanized != null || ready.lines.any { line -> line.words.any { it.romanized != null } }
    } == true
    val romanize = romanizePreferred && romanizationAvailable
    var showOptions by remember { mutableStateOf(false) }
    var showDebug by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refresh() }
    LaunchedEffect(state.title, state.artist) { spotifyIdInput = "" }

    Scaffold { padding ->
        if (!state.accessGranted) {
            PermissionScreen(
                modifier = Modifier.padding(padding),
                openNotificationAccess = openNotificationAccess,
                refresh = viewModel::refresh,
            )
            return@Scaffold
        }

        Box(Modifier.fillMaxSize().padding(padding)) {
            SpicySessionBackground(
                artwork = state.artwork,
                artworkUri = state.artworkUri,
                isPlaying = state.isPlaying,
                modifier = Modifier.fillMaxSize(),
            )
            Column(Modifier.fillMaxSize()) {
                Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        state.artwork?.let { artwork ->
                            Image(
                                bitmap = artwork.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.size(54.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(state.title, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                            Text(state.artist, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        }
                    }
                }

                LyricsPanel(
                    lyrics = state.lyrics,
                    currentTimeMs = viewModel::currentLyricPositionMs,
                    onSeek = viewModel::seekTo,
                    romanize = romanize,
                    modifier = Modifier.weight(1f),
                )

                Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        (state.lyrics as? LyricsState.Ready)?.let { lyrics ->
                            val credits = buildList {
                                lyrics.maker?.let { add("Maker: $it") }
                                lyrics.uploader?.let { add("Uploader: $it") }
                                if (lyrics.songwriters.isNotEmpty()) add("Writers: ${lyrics.songwriters.joinToString()}")
                            }
                            if (credits.isNotEmpty()) Text(credits.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                        }
                        val duration = state.durationMs.coerceAtLeast(1L)
                        Slider(
                            value = (scrubPosition ?: state.positionMs).coerceIn(0L, duration).toFloat(),
                            onValueChange = { scrubPosition = it.toLong() },
                            onValueChangeFinished = {
                                scrubPosition?.let(viewModel::seekTo)
                                scrubPosition = null
                            },
                            valueRange = 0f..duration.toFloat(),
                            enabled = state.durationMs > 0L && state.canSeek,
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text((scrubPosition ?: state.positionMs).clock(), style = MaterialTheme.typography.labelMedium)
                            Text(state.durationMs.clock(), style = MaterialTheme.typography.labelMedium)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ControlButton(Icons.Rounded.SkipPrevious, "Previous", onClick = viewModel::skipPrevious)
                            ControlButton(Icons.Rounded.FastRewind, "Back 5 seconds", enabled = state.canSeek, onClick = { viewModel.seekBy(-5_000L) })
                            ControlButton(
                                if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                if (state.isPlaying) "Pause" else "Play",
                                onClick = viewModel::playPause,
                            )
                            ControlButton(Icons.Rounded.FastForward, "Forward 5 seconds", enabled = state.canSeek, onClick = { viewModel.seekBy(5_000L) })
                            ControlButton(Icons.Rounded.SkipNext, "Next", onClick = viewModel::skipNext)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            TextButton(onClick = {
                                showOptions = !showOptions
                                showDebug = false
                            }) { Text(if (showOptions) "Hide options" else "Options") }
                            TextButton(onClick = {
                                showDebug = !showDebug
                                showOptions = false
                            }) { Text(if (showDebug) "Hide debug" else "Debug") }
                        }
                        state.status?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall)
                        }
                        state.lookupStatus?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                        if (showOptions) {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                run {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = clientKey,
                            onValueChange = { clientKey = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            label = { Text("Your SL client key (blank = built-in)") },
                        )
                        Button(onClick = { viewModel.useApiKey(clientKey) }, modifier = Modifier.padding(start = 8.dp)) {
                            Text("Use key")
                        }
                    }
                }
                OutlinedTextField(
                    value = spotifyIdInput,
                    onValueChange = { spotifyIdInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Spotify track ID or URL") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        val pasted = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                        spotifyIdInput = pasted
                        viewModel.overrideSpotifyId(pasted)
                    }) { Text("Paste ID") }
                    TextButton(onClick = { viewModel.overrideSpotifyId(spotifyIdInput) }) { Text("Use typed ID") }
                }
                if (state.manualSpotifyId != null) {
                    TextButton(onClick = viewModel::clearSpotifyIdOverride) { Text("Return to auto-match") }
                }
                Text("Lyric delay: ${state.lyricDelayMs.formatSigned()} ms • ${state.outputLabel}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { viewModel.adjustLyricDelay(-50) }) { Text("50 ms earlier") }
                    TextButton(onClick = viewModel::resetLyricDelay) { Text("Reset") }
                    TextButton(onClick = { viewModel.adjustLyricDelay(50) }) { Text("50 ms later") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (romanizationAvailable) "Romanize" else "Romanize (nothing to romanize)", modifier = Modifier.weight(1f))
                    Switch(
                        checked = romanizePreferred,
                        enabled = romanizationAvailable,
                        onCheckedChange = {
                            romanizePreferred = it
                            uiPrefs.edit().putBoolean("romanize", it).apply()
                        },
                    )
                }
                Text("Lyric sources", style = MaterialTheme.typography.titleMedium)
                state.sourceOrder.forEachIndexed { index, id ->
                    val source = state.sourceDescriptors.firstOrNull { it.id == id } ?: return@forEachIndexed
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(source.displayName)
                            Text(source.releaseChannel.name.lowercase(), style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = { viewModel.moveSource(id, -1) }, enabled = index > 0) { Text("↑") }
                        TextButton(onClick = { viewModel.moveSource(id, 1) }, enabled = index < state.sourceOrder.lastIndex) { Text("↓") }
                        Switch(
                            checked = id !in state.disabledSourceIds,
                            onCheckedChange = { viewModel.setSourceEnabled(id, it) },
                        )
                    }
                }
            }
        }
                        if (showDebug) {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Session: ${state.sourcePackage ?: "none"}")
                Text("Output: ${state.outputLabel}")
                state.matchInfo?.let { Text(it) }
                state.detectedSpotifyId?.let { Text("Spotify ID: $it") }
                (state.lyrics as? LyricsState.Ready)?.let { lyrics ->
                    Text("Provider: ${lyrics.provider}")
                    lyrics.source?.let { Text("Origin: $it") }
                    lyrics.maker?.let { Text("Maker: $it") }
                    lyrics.uploader?.let { Text("Uploader: $it") }
                    if (lyrics.songwriters.isNotEmpty()) Text("Writers: ${lyrics.songwriters.joinToString()}")
                }
                Text("Last lyric lookup", style = MaterialTheme.typography.titleMedium)
                if (state.providerAttempts.isEmpty()) Text("No lookup results yet")
                state.providerAttempts.forEach { attempt ->
                    val name = state.sourceDescriptors.firstOrNull { it.id == attempt.sourceId }?.displayName
                        ?: attempt.sourceId
                    Text("$name: ${if (attempt.outcome.name == "NEEDS_MATCH") "Spotify ID needed; enter one in Options" else attempt.outcome}" +
                        (if (attempt.quality.name != "NONE") " (${attempt.quality})" else "") +
                        (attempt.failureCategory?.let { " · $it" } ?: "") +
                        (attempt.message?.let { " · $it" } ?: ""))
                }
                TextButton(onClick = { viewModel.loadLyrics(force = true) }) { Text("Retry lyrics") }
                state.lastCommandLatencyMs?.let { Text("Session acknowledgement: $it ms") }
                state.clockDriftMs?.let { Text("Session clock drift: ${it.formatSigned()} ms") }
                if (!state.canSeek) Text("This player does not expose MediaSession seeking")
                TextButton(onClick = viewModel::resync) {
                    Icon(Icons.Rounded.Sync, contentDescription = null)
                    Text("Resync session")
                }
            }
        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionScreen(
    modifier: Modifier,
    openNotificationAccess: () -> Unit,
    refresh: () -> Unit,
) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("MediaSession access required", style = MaterialTheme.typography.titleLarge)
                Text("Enable notification access so this test app can see and control the active player session.")
                Button(onClick = openNotificationAccess) { Text("Open notification access") }
                Button(onClick = refresh) { Text("Refresh") }
            }
        }
    }
}

@Composable
private fun ControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    FilledIconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = description)
    }
}

@Composable
private fun LyricsPanel(
    lyrics: LyricsState,
    currentTimeMs: () -> Long,
    onSeek: (Long) -> Unit,
    romanize: Boolean,
    modifier: Modifier = Modifier,
) {
    when (lyrics) {
        LyricsState.Idle -> Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("Waiting for track lyrics", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LyricsState.Loading -> Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is LyricsState.Error -> Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(lyrics.message, color = MaterialTheme.colorScheme.error)
        }
        is LyricsState.Ready -> {
            if (lyrics.lines.isEmpty()) {
                Box(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)) {
                    Text((if (romanize) lyrics.plainRomanized else null) ?: lyrics.plainText.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                }
                return
            }
            val rendererLines = remember(lyrics.lines) {
                buildDisplayTimeline(lyrics.lines.map { line ->
                    Line(
                        words = line.words.map { word ->
                            Word(word.text, word.startMs, word.endMs, isPartOfWord = word.attached, romanizedText = word.romanized)
                        },
                        startMs = line.startMs,
                        endMs = line.endMs,
                        agent = line.agent,
                        role = line.role,
                        groupId = line.groupId,
                        oppositeAligned = line.oppositeAligned,
                    )
                }, minimalMode = false)
            }
            SpicyLyricsView(
                lines = rendererLines,
                documentId = remember(lyrics.lines) { lyrics.lines.hashCode().toString() },
                currentTimeMs = currentTimeMs,
                onSeekWord = onSeek,
                romanize = romanize,
                lyricsType = lyrics.lyricsType,
                modifier = modifier,
            )
        }
    }
}

private fun Long.clock(): String {
    if (this <= 0L) return "0:00"
    val totalSeconds = this / 1_000L
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}

private fun Number.formatSigned(): String = if (toLong() > 0) "+$this" else toString()
