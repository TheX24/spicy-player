package com.tx24.spicyplayer.playback

import android.content.Context
import androidx.core.content.edit
import com.tx24.spicyplayer.translation.TranslationPreferences
import com.tx24.spicyplayer.translation.TranslationProvider
import com.tx24.spicyplayer.translation.TranslationConsent

/** Lookup preferences travel in backups; credentials and consent stay on this device. */
internal class TranslationSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("translation", 0)
    private val keys = context.getSharedPreferences("deepl_key", 0)
    private val languages = context.getSharedPreferences("song_languages", 0)

    fun read(): TranslationPreferences {
        val stored = preferences.getString("provider", null)
        val provider = TranslationProvider.stored(stored)
        if (stored != provider.name) preferences.edit { putString("provider", provider.name) }
        return TranslationPreferences(
            targetLanguage = preferences.getString("targetLanguage", null) ?: java.util.Locale.getDefault().language,
            automatic = preferences.getBoolean("automatic", false),
            provider = provider,
            excludedLanguages = preferences.getStringSet("excludedLanguages", emptySet()).orEmpty().toSet(),
            humanTranslations = preferences.getBoolean("humanTranslations", true),
        )
    }
    fun save(value: TranslationPreferences) {
        preferences.edit {
            putString("targetLanguage", value.targetLanguage)
            putBoolean("automatic", value.automatic)
            putString("provider", value.provider.name)
            putStringSet("excludedLanguages", value.excludedLanguages)
            putBoolean("humanTranslations", value.humanTranslations)
        }
    }
    fun key(): String = keys.getString("key", "").orEmpty()
    fun saveKey(key: String) { keys.edit { putString("key", key) } }
    fun language(song: String?): String? = song?.let { languages.getString(it, null) }
    fun saveLanguage(song: String, language: String?) {
        languages.edit { if (language == null) remove(song) else putString(song, language) }
    }
    fun disclosed(provider: TranslationProvider, human: Boolean): Boolean =
        TranslationConsent.covered(preferences.getStringSet("disclosed", emptySet()).orEmpty(), provider, human)
    fun disclose(id: String) {
        preferences.edit { putStringSet("disclosed", preferences.getStringSet("disclosed", emptySet()).orEmpty() + id) }
    }
}
