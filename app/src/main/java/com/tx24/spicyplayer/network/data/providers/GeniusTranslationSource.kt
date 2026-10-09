package com.tx24.spicyplayer.network.data.providers

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.tx24.spicyplayer.translation.GeniusTranslationPair
import com.tx24.spicyplayer.translation.TranslationLanguages
import com.tx24.spicyplayer.translation.languageCode
import kotlin.coroutines.cancellation.CancellationException
import okhttp3.OkHttpClient

internal class GeniusTranslationSource(client: OkHttpClient, gson: Gson) {
    private val pages = GeniusSongPages(client, gson)

    suspend fun find(title: String, artist: String, target: String): GeniusTranslationPair? = try {
        val hits = pages.search(title, artist, romanizations = false)
        var found: GeniusTranslationPair? = null
        for (hit in hits) {
            if (pages.isRomanization(hit) || !pages.ours(hit, title, artist)) continue
            val song = pages.record(hit.get("id")?.asLong ?: continue) ?: continue
            val linked = linkedTranslation(song, target) ?: continue
            val original = pages.linesAt(song.get("url")?.asString.orEmpty(), minimum = 1) ?: continue
            val translated = pages.linesAt(linked.get("url")?.asString.orEmpty(), minimum = 1) ?: continue
            found = GeniusTranslationPair.fromText(original.joinToString("\n"), translated.joinToString("\n"), song.get("language")?.asString)
            if (found != null) break
        }
        found
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

/** Only the linked page's language field selects a translation, never a title hint. */
internal fun linkedTranslation(song: JsonObject, target: String): JsonObject? =
    song.getAsJsonArray("translation_songs")?.mapNotNull { it.takeIf { value -> value.isJsonObject }?.asJsonObject }
        ?.firstOrNull { linked ->
            val language = linked.get("language")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            val code = TranslationLanguages.common.entries.firstOrNull { it.value.equals(language, ignoreCase = true) }?.key ?: languageCode(language)
            code != null && code == languageCode(target)
        }
