package com.tx24.spicyplayer.ui.settings

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tx24.spicyplayer.BuildConfig
import com.tx24.spicyplayer.lyrics.spicy.SimpleAnimationStyle
import com.tx24.spicyplayer.lyrics.spicy.canvas.PinnedFooterMode
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * How the lyrics screen looks and behaves, saved in the `ui` preferences. Each setting reads as
 * Compose state, so changing one redraws what uses it; writing one also saves it.
 *
 * Lookup, source and sync settings live in the view model instead (see `docs/settings.md`).
 */
class AppSettings(private val prefs: SharedPreferences) {
    /** Romanized lyrics where the song has them; the floating button, not a settings row. */
    var romanize by boolean("romanize", false)

    var simpleLyricsMode by boolean("simpleLyricsMode", false)
    var simpleAnimationStyle by enum("simpleAnimationStyle", SimpleAnimationStyle.CALCULATE)
    var minimalLyricsMode by boolean("minimalLyricsMode", false)
    var originalWordMotion by boolean("originalWordMotion", false)
    var lyricsSize by enum("lyricsSize", LyricsSize.Default)
    /** Carries over the old "Use System Font" switch. */
    var lyricsFont by enum("lyricsFont", if (prefs.getBoolean("systemFont", false)) LyricsFont.System else LyricsFont.Default)
    /** The picked font's file in the app's storage (`LyricsFontFile`), and the name it came with. */
    var customFontFile by string("customFontFile", "")
    var customFontName by string("customFontName", "")
    var showScrollToActive by boolean("showScrollToActive", true)
    var pinnedFooter by enum("pinnedFooter", PinnedFooterMode.Off)
    var duetLinePadding by boolean("duetLinePadding", true)

    var scrollLeadEnabled by boolean("scrollLeadEnabled", false)
    var scrollLeadMs by int("scrollLeadMs", 250)
    var smoothScrolling by boolean("smoothScrolling", false)
    var seekFadeCompensation by boolean("seekFadeCompensation", true)

    var distanceBlur by boolean("distanceBlur", true)
    var glow by boolean("glow", true)
    /** Drops the costliest effects: the moving background, animated cover, glass blur, and lyric blur and glow. */
    var lowPerformance by boolean("lowPerformance", false)

    /** Carries over the old Default/Legacy switch. */
    var backgroundType by enum(
        "backgroundType",
        if (prefs.getBoolean("legacyBackground", false)) BackgroundType.Legacy else BackgroundType.Default,
    )
    /** How much the image backgrounds ([BackgroundType.image]) are blurred, in dp (0..67). */
    var backgroundBlur by int("backgroundBlur", 0)
    /** The moving background speeds up and slows down with the song's tempo, loudness and beats. */
    var beatReactiveBackground by boolean("beatReactiveBackground", true)
    /** Holds the moving backgrounds (Default, Legacy) still. */
    var staticBackground by boolean("staticBackground", false)
    var releaseYearPosition by enum("releaseYearPosition", ReleaseYearPosition.Off)
    var expandWithoutLyrics by boolean("expandWithoutLyrics", false)
    /** The record's looping Apple Music cover in place of the still one, where it has one. */
    var animatedCover by boolean("animatedCover", false)

    var keepScreenOn by boolean("keepScreenOn", true)
    /** No song header: the lyrics fill the page and scroll with the active line near the centre. */
    var hideHeader by boolean("hideHeader", false)
    /** The screen's full refresh rate (90/120 Hz) instead of 60 Hz, which costs battery. */
    var highRefreshRate by boolean("highRefreshRate", false)
    var autoHideControls by boolean("autoHideControls", true)
    /** A floating button that opens the Lyrics Manager (it is always in Settings → This song). */
    var lyricsManagerButton by boolean("lyricsManagerButton", true)
    /** How the Lyrics Manager's last upload was applied; the upload screen reopens on it. */
    var ttmlUploadSaves by boolean("ttmlUploadSaves", true)

    /** Pre-releases offered as updates. On by default while the app itself is one (0.x). */
    var includePrereleases by boolean("includePrereleases", BuildConfig.VERSION_NAME.startsWith("0."))

    /** The word motion used when [originalWordMotion] is off (`RenderConfig.wordMotionBoost`). */
    val wordMotionBoost get() = if (originalWordMotion) 1f else WORD_MOTION_BOOST

    private fun boolean(key: String, default: Boolean) =
        Setting(prefs.getBoolean(key, default)) { prefs.edit().putBoolean(key, it).apply() }

    private fun string(key: String, default: String) =
        Setting(prefs.getString(key, default) ?: default) { prefs.edit().putString(key, it).apply() }

    private fun int(key: String, default: Int) =
        Setting(prefs.getInt(key, default)) { prefs.edit().putInt(key, it).apply() }

    private inline fun <reified E : Enum<E>> enum(key: String, default: E) =
        Setting(runCatching { enumValueOf<E>(prefs.getString(key, null)!!) }.getOrDefault(default)) {
            prefs.edit().putString(key, it.name).apply()
        }

    private class Setting<T>(initial: T, private val save: (T) -> Unit) : ReadWriteProperty<Any?, T> {
        private var value by mutableStateOf(initial)
        override fun getValue(thisRef: Any?, property: KProperty<*>): T = value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            this.value = value
            save(value)
        }
    }

    companion object {
        /** Sung words grow and lift this much more than the desktop amount, which reads better on a phone. */
        const val WORD_MOTION_BOOST = 1.25f
    }
}

/**
 * What fills the screen behind the lyrics: the moving cover ([Default], [Legacy]), a still image
 * ([Auto] and [ArtistHeader]: the artist's Spotify header, else the cover; [CoverArt]), or the
 * cover's colours ([Color]).
 */
enum class BackgroundType(val label: String) {
    Default("Default"),
    Legacy("Legacy"),
    Auto("Auto"),
    ArtistHeader("Artist Header"),
    CoverArt("Cover Art"),
    Color("Color");

    /** A still picture, which the blur setting softens. */
    val image get() = this == Auto || this == ArtistHeader || this == CoverArt
    val usesArtistHeader get() = this == Auto || this == ArtistHeader
    /** Moves on its own (and can be held still). */
    val moving get() = this == Default || this == Legacy
}

/** The strongest [AppSettings.backgroundBlur]. */
const val MAX_BACKGROUND_BLUR = 67

/** Where the song's release year shows beside the artists, if at all. */
enum class ReleaseYearPosition(val label: String) { Off("Off"), Left("Left"), Right("Right") }

/** The lyric text size against the default, which follows the screen width. */
enum class LyricsSize(val scale: Float, val label: String) {
    Small(0.85f, "Small"),
    Default(1f, "Default"),
    Large(1.15f, "Large"),
    Larger(1.3f, "Larger"),
}
