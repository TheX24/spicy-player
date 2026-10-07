package com.tx24.spicyplayer.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.tx24.spicyplayer.BuildConfig
import com.tx24.spicyplayer.playback.PlayerUiState
import com.tx24.spicyplayer.playback.SyncTrace
import com.tx24.spicyplayer.ui.components.SettingRow
import com.tx24.spicyplayer.ui.components.SpicyButton
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * What Settings → Advanced shows, plus the app, phone and player versions, as plain text for a bug
 * report. Keys, tokens and URL queries are blanked out.
 */
internal fun debugReport(context: Context, state: PlayerUiState): String {
    val playerVersion = state.sourcePackage?.let { pkg ->
        runCatching { context.packageManager.getPackageInfo(pkg, 0).versionName }.getOrNull()
    }
    val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
    val report = buildString {
        appendLine("Spicy Lyrics Mobile ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Song: ${state.title} · ${state.artist}" + state.album.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty())
        sessionLines(state).forEach { (label, value) ->
            appendLine("$label: $value" + if (label == "Player" && playerVersion != null) " $playerVersion" else "")
        }
        appendLine("Delays: output ${state.lyricDelayMs.signed()} ms, this song ${state.songDelayMs.signed()} ms")
        appendLine("Lyrics: ${songSummary(state)}")
        lyricsLines(state)?.forEach { (label, value) -> appendLine("$label: $value") }
        appendLine()
        appendLine("Last lookup:")
        if (state.providerAttempts.isEmpty()) appendLine("  none yet")
        state.providerAttempts.forEach { attempt ->
            val detail = attemptDetail(attempt) { time.format(it) }
            appendLine("  ${attemptSourceName(attempt, state)}: ${attempt.outcome.label()}" + if (detail.isNotEmpty()) " ($detail)" else "")
        }
        val sync = SyncTrace.lines(SystemClock.elapsedRealtime())
        if (sync.isNotEmpty()) {
            appendLine()
            appendLine("Sync (newest last):")
            sync.forEach { appendLine("  $it") }
        }
    }
    return redactSecrets(report).trimEnd()
}

/** Blanks Spicy Lyrics keys and URL queries, where provider tokens travel. */
internal fun redactSecrets(text: String): String = text
    .replace(Regex("""sl_(pk|sk)_[A-Za-z0-9_-]+"""), "sl_$1_…")
    .replace(Regex("""(https?://[^\s?#]+)\?\S*"""), "$1?…")

/** Copies [debugReport], for the Discord thread or a bug report written by hand. */
@Composable
internal fun CopyDebugInfoRow(state: PlayerUiState) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }
    SettingRow(
        label = "Copy debug info",
        description = "Your app, phone and player versions, this song and the details below, to paste into a bug report.",
        icon = Icons.Rounded.ContentCopy,
    ) {
        SpicyButton(if (copied) "Copied" else "Copy", onClick = {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("Spicy Lyrics Mobile debug info", debugReport(context, state)))
            copied = clipboard != null
        })
    }
}
