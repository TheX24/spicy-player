package com.tx24.spicyplayer.ui.settings

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tx24.spicyplayer.lyrics.spicy.SimpleAnimationStyle
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
    var systemFont by boolean("systemFont", false)

    var distanceBlur by boolean("distanceBlur", true)
    var glow by boolean("glow", true)
    /** Drops the costliest effects: the moving background, glass blur, and lyric blur and glow. */
    var lowPerformance by boolean("lowPerformance", false)

    var legacyBackground by boolean("legacyBackground", false)
    var staticBackground by boolean("staticBackground", false)
    var expandWithoutLyrics by boolean("expandWithoutLyrics", false)

    var keepScreenOn by boolean("keepScreenOn", true)
    var autoHideControls by boolean("autoHideControls", true)

    /** The word motion used when [originalWordMotion] is off (`RenderConfig.wordMotionBoost`). */
    val wordMotionBoost get() = if (originalWordMotion) 1f else WORD_MOTION_BOOST

    private fun boolean(key: String, default: Boolean) =
        Setting(prefs.getBoolean(key, default)) { prefs.edit().putBoolean(key, it).apply() }

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

/** The lyric text size against the default, which follows the screen width. */
enum class LyricsSize(val scale: Float, val label: String) {
    Small(0.85f, "Small"),
    Default(1f, "Default"),
    Large(1.15f, "Large"),
    Larger(1.3f, "Larger"),
}
