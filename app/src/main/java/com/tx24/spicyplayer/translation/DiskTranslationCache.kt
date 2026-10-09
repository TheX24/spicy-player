package com.tx24.spicyplayer.translation

import com.google.gson.Gson
import java.io.File

class DiskTranslationCache(private val directory: File) : TranslationCache {
    private val gson = Gson()
    private data class Entry(val version: Int, val result: TranslationResult)

    override fun read(key: TranslationKey, lineCount: Int): TranslationResult? = runCatching {
        val file = File(directory, key.fileName())
        if (!file.isFile || file.length() > 2 * 1024 * 1024) return null
        val entry = gson.fromJson(file.readText(), Entry::class.java)
        entry.result.takeIf {
            entry.version == TRANSLATION_CACHE_VERSION && it.key == key && it.texts.size == lineCount && it.origins.size == lineCount
        }
    }.getOrNull()

    override fun write(result: TranslationResult) {
        runCatching {
            directory.mkdirs()
            val destination = File(directory, result.key.fileName())
            val temporary = File.createTempFile("translation-", ".tmp", directory)
            try {
                temporary.writeText(gson.toJson(Entry(TRANSLATION_CACHE_VERSION, result)))
                if (!temporary.renameTo(destination)) {
                    destination.delete()
                    temporary.renameTo(destination)
                }
            } finally { temporary.delete() }
            // A modest, disposable cache; never a second permanent lyrics library.
            directory.listFiles()?.filter { it.extension == "json" }?.sortedByDescending(File::lastModified)
                ?.drop(100)?.forEach(File::delete)
        }
    }
}
