package com.tx24.spicyplayer.lyrics

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceDefaultsMigrationTest {
    private val version = NextLyricsBackend.SOURCE_DEFAULTS_VERSION
    private val notice = NextLyricsBackend.RESET_NOTICE

    @Test
    fun `an older install's choices are reset and the user is told`() {
        val prefs = MemoryPrefs(mutableMapOf(
            "defaults" to 1,
            "order" to "spicy_lyrics,musixmatch,lrclib",
            "disabled" to setOf("kugou"),
            "humanRomanizations" to true,
            "blends" to setOf("blend_a"),
            "custom" to "[]",
        ))

        NextLyricsBackend.migrateSourceDefaults(prefs)

        assertNull(prefs.values["order"])
        assertNull(prefs.values["disabled"])
        assertNull(prefs.values["humanRomanizations"])
        assertEquals(version, prefs.values["defaults"])
        assertEquals(true, prefs.values[notice])
        // Not source choices: left alone.
        assertEquals(setOf("blend_a"), prefs.values["blends"])
        assertEquals("[]", prefs.values["custom"])
    }

    @Test
    fun `a fresh install isn't told about a reset it never had`() {
        val prefs = MemoryPrefs(mutableMapOf())

        NextLyricsBackend.migrateSourceDefaults(prefs)

        assertEquals(version, prefs.values["defaults"])
        assertEquals(false, prefs.values[notice])
    }

    @Test
    fun `runs once`() {
        val prefs = MemoryPrefs(mutableMapOf("defaults" to 1))
        NextLyricsBackend.migrateSourceDefaults(prefs)
        prefs.edit().putString("order", "spicy_lyrics,lrclib").putBoolean(notice, false).apply()

        NextLyricsBackend.migrateSourceDefaults(prefs)

        assertEquals("spicy_lyrics,lrclib", prefs.values["order"])
        assertFalse(prefs.values[notice] as Boolean)
    }

    @Test
    fun `a restored older backup is reset again`() {
        val prefs = MemoryPrefs(mutableMapOf("defaults" to version, "order" to "spicy_lyrics"))
        // What a restore of a backup from before the change writes.
        prefs.values.clear()
        prefs.values += mapOf("defaults" to 1, "order" to "musixmatch,spicy_lyrics", "disabled" to emptySet<String>())

        NextLyricsBackend.migrateSourceDefaults(prefs)

        assertNull(prefs.values["order"])
        assertTrue(prefs.values[notice] as Boolean)
    }

    /** Just enough of [SharedPreferences] for the migration. */
    private class MemoryPrefs(val values: MutableMap<String, Any>) : SharedPreferences {
        override fun getAll(): Map<String, *> = values
        override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: Set<String>?) = values[key] as? Set<String> ?: defValues
        override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float) = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
        override fun contains(key: String) = key in values
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val puts = mutableMapOf<String, Any?>()
            private var clear = false
            override fun putString(key: String, value: String?) = apply { puts[key] = value }
            override fun putStringSet(key: String, values: Set<String>?) = apply { puts[key] = values }
            override fun putInt(key: String, value: Int) = apply { puts[key] = value }
            override fun putLong(key: String, value: Long) = apply { puts[key] = value }
            override fun putFloat(key: String, value: Float) = apply { puts[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value }
            override fun remove(key: String) = apply { puts[key] = null }
            override fun clear() = apply { clear = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clear) values.clear()
                puts.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            }
        }
    }
}
