package com.tx24.spicyplayer.network.data.providers

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.tx24.spicyplayer.lyrics.spicy.romanization.HumanRomanization
import com.tx24.spicyplayer.network.data.awaitResponse
import com.tx24.spicyplayer.network.data.spotify.SpotifyTrackMatcher
import kotlin.coroutines.cancellation.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Finds a human-written romanization of a song on Genius, through Genius's public web endpoints
 * (no token).
 *
 * Two ways in, in order. A page that calls itself a romanization ("… (Romanized)", or filed
 * under the language "romanization") is taken where it is for the right artist. Failing that,
 * the song itself is looked up and its own record asked for the romanization Genius files
 * against it, which can't be the wrong song. A page needs at least 4 lyric lines to count.
 */
internal class GeniusRomanizationSource(private val client: OkHttpClient, private val gson: Gson) {

    /** The romanization's lyric lines, or null when Genius has none for this song. */
    suspend fun find(title: String, artist: String): List<String>? {
        val hits = search(title, artist)
        for (hit in hits) {
            if (!isRomanization(hit) || !byUs(hit, artist)) continue
            linesAt(hit.str("url"))?.let { return it }
        }
        for (hit in hits) {
            if (isRomanization(hit) || !ours(hit, title, artist)) continue
            val linked = linkedRomanization(hit.get("id")?.asLong ?: continue) ?: continue
            linesAt(linked.str("url"))?.let { return it }
        }
        return null
    }

    /** Four queries, stopping once any romanization turns up; romanizations first. */
    private suspend fun search(title: String, artist: String): List<JsonObject> {
        val cleanTitle = title.replace(BRACKETED, "").trim()
        val cleanArtist = artist.replace(BRACKETED, "").trim()
        val queries = listOf(
            "$title $artist",
            "$cleanTitle $cleanArtist Romanized",
            "$cleanTitle Romanized",
            "$title Genius Romanizations",
        )
        val hits = mutableListOf<JsonObject>()
        val seen = mutableSetOf<Long>()
        for (query in queries) {
            if (query.isBlank()) continue
            try {
                val url = "https://genius.com/api/search/multi".toHttpUrl().newBuilder()
                    .addQueryParameter("q", query.trim()).build()
                val sections = getJson(url.toString())?.getAsJsonObject("response")?.getAsJsonArray("sections")
                sections?.forEach { section ->
                    val s = section.asJsonObject
                    if (s.str("type") !in setOf("top_hit", "song")) return@forEach
                    s.getAsJsonArray("hits")?.forEach { h ->
                        val res = h.asJsonObject.getAsJsonObject("result") ?: return@forEach
                        val id = res.get("id")?.takeIf { !it.isJsonNull }?.asLong ?: return@forEach
                        if (isSong(res) && seen.add(id)) hits += res
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
            }
            if (hits.any(::isRomanization)) break
        }
        return hits.sortedBy { !isRomanization(it) }
    }

    /** A song, not an album: an album's id is a different id space and would fetch another song. */
    private fun isSong(hit: JsonObject): Boolean {
        val type = hit.str("_type").ifEmpty { hit.str("type") }
        return type.isEmpty() || type == "song"
    }

    /** Only entries that claim to be romanizations: the original-script page is no use. */
    private fun isRomanization(hit: JsonObject): Boolean {
        if (hit.str("language").lowercase() in ROMAN_LANGUAGES) return true
        val fields = listOf(hit.str("title"), hit.str("full_title"), hit.str("title_with_featured"), artistOf(hit))
        return fields.any { ROMAN_HINT.containsMatchIn(it) }
    }

    /**
     * Whether a romanization page is for [artist]. They're filed under a Genius account, so the
     * page title is where the musician is named: "Ado - 踊 (Odo) (Romanized)". Unknown counts as yes.
     */
    private fun byUs(hit: JsonObject, artist: String): Boolean {
        if (artist.isBlank()) return true
        val bare = hit.str("title").replace(ROMAN_HINT, "").trim(' ', '-', '(', ')', '[', ']')
        if (" - " !in bare) return true
        val ours = norm(artist)
        val theirs = norm(bare.substringBefore(" - "))
        if (ours.isEmpty() || theirs.isEmpty()) return true
        return ours in theirs || theirs in ours
    }

    /** Whether a search hit is the song that was asked for: same artist, and one title holds the other. */
    private fun ours(hit: JsonObject, title: String, artist: String): Boolean {
        val who = norm(artistOf(hit))
        val asked = norm(artist)
        if (asked.isNotEmpty() && who.isNotEmpty() && asked !in who && who !in asked) return false
        val a = norm(hit.str("title"))
        val b = norm(title)
        return a.isNotEmpty() && b.isNotEmpty() && (a == b || a in b || b in a)
    }

    /** The romanization Genius files against a song (`translation_songs`), if any. */
    private suspend fun linkedRomanization(songId: Long): JsonObject? = try {
        getJson("https://genius.com/api/songs/$songId")?.getAsJsonObject("response")?.getAsJsonObject("song")
            ?.getAsJsonArray("translation_songs")
            ?.map { it.asJsonObject }
            ?.firstOrNull { it.str("language").lowercase() in ROMAN_LANGUAGES }
    } catch (c: CancellationException) {
        throw c
    } catch (_: Exception) {
        null
    }

    /** A Genius song page's lyric lines, cleaned; null unless there are at least 4. */
    private suspend fun linesAt(url: String): List<String>? {
        if (url.isBlank()) return null
        return try {
            val html = client.newCall(Request.Builder().url(url).header("User-Agent", BROWSER_UA).get().build())
                .awaitResponse().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
            HumanRomanization.cleanLines(geniusLyricsText(html)).takeIf { it.size >= 4 }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            null
        }
    }

    // Genius' front answers OkHttp's own user agent with HTTP 401.
    private suspend fun getJson(url: String): JsonObject? =
        client.newCall(Request.Builder().url(url).header("Referer", "https://genius.com/").header("User-Agent", BROWSER_UA).get().build())
            .awaitResponse().use { r -> if (r.isSuccessful) gson.fromJson(r.body?.string(), JsonObject::class.java) else null }

    private fun artistOf(hit: JsonObject) = hit.getAsJsonObject("primary_artist")?.str("name").orEmpty()
    private fun norm(s: String) = SpotifyTrackMatcher.normalize(s).replace(" ", "")
    private fun JsonObject.str(name: String): String = get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private companion object {
        val ROMAN_HINT = Regex("romani[sz]ed|romani[sz]ation|\\bromaji\\b", RegexOption.IGNORE_CASE)
        val ROMAN_LANGUAGES = setOf("romanization", "romanized")
        val BRACKETED = Regex("[(\\[{].*?[)\\]}]")
        const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36"
    }
}
