package com.tx24.spicyplayer.ui.settings

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppSettingsCorruptValuesTest {
    @Test fun wrongTypesReadAsDefaultsInsteadOfCrashing() {
        val settings = AppSettings(Prefs(mapOf("lowPerformance" to "yes", "backgroundBlur" to 999, "scrollLeadMs" to "fast")))

        assertFalse(settings.lowPerformance)
        assertEquals(MAX_BACKGROUND_BLUR, settings.backgroundBlur)
        assertEquals(250, settings.scrollLeadMs)
    }

    @Test fun knowsWhatTypeEachSettingIsStoredAs() {
        val types = AppSettings(Prefs(emptyMap())).storedTypes
        assertEquals(Boolean::class, types["lowPerformance"])
        assertEquals(Int::class, types["backgroundBlur"])
        assertEquals(String::class, types["lyricsSize"])
    }

    /** Read-only, and casting like Android's: a value of another type throws. */
    private class Prefs(private val values: Map<String, Any>) : SharedPreferences {
        override fun getAll() = values
        override fun getString(key: String, defValue: String?) = values[key]?.let { it as String } ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?) =
            @Suppress("UNCHECKED_CAST") (values[key] as Set<String>?)?.toMutableSet() ?: defValues
        override fun getInt(key: String, defValue: Int) = values[key]?.let { it as Int } ?: defValue
        override fun getLong(key: String, defValue: Long) = values[key]?.let { it as Long } ?: defValue
        override fun getFloat(key: String, defValue: Float) = values[key]?.let { it as Float } ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = values[key]?.let { it as Boolean } ?: defValue
        override fun contains(key: String) = key in values
        override fun edit(): SharedPreferences.Editor = error("read-only")
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }
}
