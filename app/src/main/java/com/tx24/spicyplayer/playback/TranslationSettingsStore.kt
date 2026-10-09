package com.tx24.spicyplayer.playback

import android.content.Context
import androidx.core.content.edit
import com.tx24.spicyplayer.translation.TranslationPreferences
import com.tx24.spicyplayer.translation.TranslationProvider

/** Lookup preferences travel in backups; credentials and consent stay on this device. */
internal class TranslationSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("translation", 0)
    private val keys = context.getSharedPreferences("deepl_key", 0)
    private val languages = context.getSharedPreferences("song_languages", 0)

    fun read(): TranslationPreferences = TranslationPreferences(
        targetLanguage = preferences.getString("targetLanguage", null) ?: java.util.Locale.getDefault().language,
        automatic = preferences.getBoolean("automatic", false),
        provider = runCatching { TranslationProvider.valueOf(preferences.getString("provider", "Unison")!!) }
            .getOrDefault(TranslationProvider.Unison),
        excludedLanguages = preferences.getStringSet("excludedLanguages", emptySet()).orEmpty().toSet(),
    )
    fun save(value: TranslationPreferences) {
        preferences.edit {
            putString("targetLanguage", value.targetLanguage)
            putBoolean("automatic", value.automatic)
            putString("provider", value.provider.name)
            putStringSet("excludedLanguages", value.excludedLanguages)
        }
    }
    fun key(): String = keys.getString("key", "").orEmpty()
    fun saveKey(key: String) { keys.edit { putString("key", key) } }
    fun language(song: String?): String? = song?.let { languages.getString(it, null) }
    fun saveLanguage(song: String, language: String?) {
        languages.edit { if (language == null) remove(song) else putString(song, language) }
    }
    fun disclosed(provider: TranslationProvider): Boolean = preferences.getStringSet("disclosed", emptySet()).orEmpty().contains(provider.name)
    fun disclose(provider: TranslationProvider) {
        preferences.edit { putStringSet("disclosed", preferences.getStringSet("disclosed", emptySet()).orEmpty() + provider.name) }
    }
}
