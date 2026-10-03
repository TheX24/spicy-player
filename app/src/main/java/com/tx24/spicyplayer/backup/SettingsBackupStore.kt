package com.tx24.spicyplayer.backup

import android.content.Context
import android.content.SharedPreferences
import com.tx24.spicyplayer.BuildConfig
import com.tx24.spicyplayer.analytics.UsageStats

/** Reads [SettingsBackup.STORES] out of the app's SharedPreferences, and writes a backup back in. */
class SettingsBackupStore(private val context: Context) {

    fun export(): String = SettingsBackup.encode(
        SettingsBackup.STORES.associateWith { name -> prefs(name).all.filterKeys { it !in kept(name) } },
        BuildConfig.VERSION_NAME,
    )

    /**
     * Replaces each store the backup has with its contents; stores it doesn't have stay as they
     * are. Throws [SettingsBackup.InvalidBackup] before changing anything if the file is bad.
     */
    fun restore(text: String) {
        val stores = SettingsBackup.decode(text)
        for ((name, values) in stores) {
            val prefs = prefs(name)
            val kept = kept(name)
            val restored = values.filterKeys { it !in kept }.toMutableMap()
            // The custom font's file isn't in the backup, so "Custom" only comes back if this
            // install already has one picked.
            if (name == "ui" && restored["lyricsFont"] == "Custom" && prefs.getString("customFontFile", "").isNullOrEmpty()) {
                restored.remove("lyricsFont")
            }
            prefs.edit().apply {
                prefs.all.keys.filter { it !in kept }.forEach(::remove)
                restored.forEach { (key, value) -> put(key, value) }
            }.commit()
        }
    }

    private fun prefs(name: String): SharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    /**
     * Keys left out of a backup and left alone by a restore: the custom font's file is on this
     * device, and the usage-stats answer was given on this device.
     */
    private fun kept(store: String): Set<String> =
        if (store == "ui") setOf("customFontFile", "customFontName", UsageStats.KEY_ENABLED, UsageStats.KEY_ASKED) else emptySet()

    @Suppress("UNCHECKED_CAST")
    private fun SharedPreferences.Editor.put(key: String, value: Any) {
        when (value) {
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is String -> putString(key, value)
            is Set<*> -> putStringSet(key, value as Set<String>)
        }
    }
}
