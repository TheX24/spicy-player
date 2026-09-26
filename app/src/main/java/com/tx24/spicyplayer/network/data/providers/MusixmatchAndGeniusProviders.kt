package com.tx24.spicyplayer.network.data.providers

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.tx24.spicyplayer.network.data.*
import com.tx24.spicyplayer.network.data.spotify.SpotifyTrackMatcher
import java.io.File
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Musixmatch through its iOS app's door. The desktop app's door no longer hands out tokens, and
 * widely shared tokens answer every search with a decoy track and gibberish lyrics.
 *
 * `token.get` gives a token to anyone who asks as the app, but a few requests in a row get a
 * captcha refusal for a while, so the token is kept in [tokenFile] and reused until Musixmatch
 * rejects it, and a refusal waits [TOKEN_COOLDOWN_MS] before asking again.
 */
@Singleton
class MusixmatchLyricsProvider @Inject constructor(
    private val client: OkHttpClient,
    private val gson: Gson,
    private val tokenFile: File? = null,
) : RemoteLyricsProvider {
    override val descriptor = LyricsSourceDescriptor(
        "musixmatch", "Musixmatch", 100,
        setOf(LyricsCapability.WORD_SYNC, LyricsCapability.LINE_SYNC, LyricsCapability.PLAIN_TEXT),
        upstreamFamily = "musixmatch",
        releaseChannel = SourceReleaseChannel.EXTENDED,
    )

    @Volatile private var token: String? = null
    @Volatile private var refusedAt = 0L

    override suspend fun fetch(r: LyricsLookupRequest): ProviderResult = try {
        var message = token()?.let { ask(r, it) }
        if (message != null && message.status() == 401) {
            // The token went stale: one fresh one, then give up.
            forgetToken()
            message = token()?.let { ask(r, it) }
        }
        when {
            message == null -> ProviderResult.Unavailable(ProviderFailureCategory.AUTHENTICATION, "Musixmatch gave no app token")
            message.status() == 401 -> ProviderResult.Unavailable(ProviderFailureCategory.AUTHENTICATION, "Musixmatch HTTP 401")
            message.status() != 200 -> ProviderResult.Miss
            else -> read(r, message)
        }
    } catch (c: CancellationException) {
        throw c
    } catch (e: IOException) {
        ProviderResult.Unavailable(ProviderFailureCategory.NETWORK, e.message, true)
    } catch (e: Exception) {
        ProviderResult.Unavailable(ProviderFailureCategory.MALFORMED_RESPONSE, e.message)
    }

    /** Everything Musixmatch has for the track, word timing included, in one request. */
    private suspend fun ask(r: LyricsLookupRequest, token: String): JsonObject? {
        val url = "$BASE/macro.subtitles.get".toHttpUrl().newBuilder()
            .addQueryParameter("app_id", APP)
            .addQueryParameter("format", "json")
            .addQueryParameter("usertoken", token)
            .addQueryParameter("namespace", "lyrics_richsynched")
            .addQueryParameter("subtitle_format", "lrc")
            .addQueryParameter("optional_calls", "track.richsync")
            .addQueryParameter("richsync_compact_type", "words")
            .addQueryParameter("q_track", r.title)
            .addQueryParameter("q_artist", r.artist)
            .apply {
                r.spotifyTrackId?.let { addQueryParameter("track_spotify_id", it) }
                if (r.album.isNotBlank()) addQueryParameter("q_album", r.album)
                if (r.durationSeconds > 0) {
                    addQueryParameter("q_duration", r.durationSeconds.toString())
                    addQueryParameter("f_subtitle_length", r.durationSeconds.toString())
                }
            }
            .build()
        return get(url).getAsJsonObject("message")
    }

    private fun read(r: LyricsLookupRequest, message: JsonObject): ProviderResult {
        val calls = message.path("body", "macro_calls") ?: return ProviderResult.Miss
        val track = calls.path("matcher.track.get", "message", "body", "track")
        if (track != null) {
            val title = track.get("track_name")?.asString.orEmpty()
            val artist = track.get("artist_name")?.asString.orEmpty()
            if (SpotifyTrackMatcher.normalize(title) != SpotifyTrackMatcher.normalize(r.title) ||
                !SpotifyTrackMatcher.normalize(artist).contains(SpotifyTrackMatcher.normalize(r.artist))
            ) return ProviderResult.Miss
        }
        val rich = calls.path("track.richsync.get", "message", "body", "richsync")?.get("richsync_body")?.asString
        if (!rich.isNullOrBlank() && !poisoned(rich)) {
            RichSyncToTtml.convert(gson.fromJson(rich, JsonArray::class.java))?.let { return ProviderResult.Hit(RemoteLyricsPayload(ttmlLyrics = it)) }
        }
        val lrc = calls.path("track.subtitles.get", "message", "body")?.getAsJsonArray("subtitle_list")
            ?.firstOrNull()?.asJsonObject?.getAsJsonObject("subtitle")?.get("subtitle_body")?.asString
        if (!lrc.isNullOrBlank() && !poisoned(lrc)) return ProviderResult.Hit(RemoteLyricsPayload(syncedLyrics = lrc))
        val plain = calls.path("track.lyrics.get", "message", "body", "lyrics")?.get("lyrics_body")?.asString
        return if (!plain.isNullOrBlank() && !poisoned(plain)) ProviderResult.Hit(RemoteLyricsPayload(plainLyrics = plain)) else ProviderResult.Miss
    }

    private suspend fun token(): String? {
        token?.let { return it }
        tokenFile?.let { file -> runCatching { file.readText().trim() }.getOrNull()?.takeIf(String::isNotBlank) }
            ?.let { return it.also { token = it } }
        if (System.currentTimeMillis() - refusedAt < TOKEN_COOLDOWN_MS) return null
        val fresh = get("$BASE/token.get".toHttpUrl().newBuilder().addQueryParameter("app_id", APP).addQueryParameter("format", "json").build())
            .path("message", "body")?.get("user_token")?.takeIf { it.isJsonPrimitive }?.asString
            ?.takeUnless { it.isBlank() || it.all { c -> c == '0' } }
        if (fresh == null) {
            refusedAt = System.currentTimeMillis()
            return null
        }
        token = fresh
        tokenFile?.let { file -> runCatching { file.parentFile?.mkdirs(); file.writeText(fresh) } }
        return fresh
    }

    private fun forgetToken() {
        token = null
        tokenFile?.delete()
    }

    private suspend fun get(url: okhttp3.HttpUrl): JsonObject {
        val request = Request.Builder().url(url).get().apply { HEADERS.forEach { (k, v) -> header(k, v) } }.build()
        return client.newCall(request).awaitResponse().use { response ->
            if (!response.isSuccessful) throw ProviderHttpException("Musixmatch", response.code)
            gson.fromJson(response.body?.string(), JsonObject::class.java)
        }
    }

    private fun JsonObject.status() = path("header")?.get("status_code")?.asInt ?: 0

    /** Musixmatch's decoy lyrics, served to clients it has decided are scraping. */
    private fun poisoned(s: String) = listOf("wob gopini den", "tefe woxica fero", "gogoh vudob wiya", "keric sohu peduf").any { s.contains(it, true) }

    private fun JsonObject.path(vararg p: String): JsonObject? {
        var c = this
        for (k in p) c = c.get(k)?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        return c
    }

    companion object {
        const val BASE = "https://apic-appmobile.musixmatch.com/ws/1.1"
        const val APP = "mac-ios-v2.0"
        private const val TOKEN_COOLDOWN_MS = 30 * 60_000L
        private val HEADERS = mapOf(
            "X-Cookie" to "x-mxm-token-guid=",
            "x-mxm-app-version" to "10.1.1",
            "X-User-Agent" to "Musixmatch/2025120901 CFNetwork/3860.300.31 Darwin/25.2.0",
            "Accept-Language" to "en-US,en;q=0.9",
            "Accept" to "application/json",
        )
    }
}

/**
 * Musixmatch richsync to word-timed TTML. Richsync writes the spaces as entries of their own, so
 * a space entry both ends the word before it (where the singing stopped) and separates two
 * words; two entries with no space between them are parts of one word.
 */
internal object RichSyncToTtml {
    fun convert(rows: JsonArray): String? {
        val paragraphs = rows.mapIndexedNotNull { index, element ->
            val row = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapIndexedNotNull null
            val lineStart = row.get("ts")?.asDouble ?: return@mapIndexedNotNull null
            val lineEnd = row.get("te")?.asDouble ?: lineStart
            val words = mutableListOf<RichWord>()
            var gap: Double? = null
            for (entry in row.getAsJsonArray("l") ?: return@mapIndexedNotNull null) {
                val item = entry.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val text = item.get("c")?.asString ?: continue
                val at = lineStart + (item.get("o")?.asDouble ?: 0.0)
                if (text.isBlank()) {
                    if (gap == null) gap = at
                    continue
                }
                words.lastOrNull()?.let { previous ->
                    previous.end = gap ?: at
                    previous.spaceAfter = gap != null
                }
                words += RichWord(text, at, at)
                gap = null
            }
            if (words.isEmpty()) return@mapIndexedNotNull null
            words.last().end = maxOf(lineEnd, words.last().start)
            // A word ended where it began never lights up; it runs to the next word instead.
            words.forEachIndexed { i, word ->
                if (word.end <= word.start) word.end = words.getOrNull(i + 1)?.start?.takeIf { it > word.start } ?: maxOf(lineEnd, word.start)
            }
            val spans = words.joinToString("") { word ->
                "<span begin=\"${sec(word.start)}\" end=\"${sec(word.end)}\">${xml(word.text)}</span>" + if (word.spaceAfter) " " else ""
            }.trimEnd()
            "<p begin=\"${sec(words.first().start)}\" end=\"${sec(maxOf(lineEnd, words.last().end))}\" xml:id=\"L$index\">$spans</p>"
        }
        if (paragraphs.isEmpty()) return null
        return """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="word"><body><div>${paragraphs.joinToString("")}</div></body></tt>"""
    }

    private class RichWord(val text: String, val start: Double, var end: Double, var spaceAfter: Boolean = false)

    private fun sec(v: Double) = "${"%.3f".format(Locale.ROOT, v)}s"
    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

@Singleton class GeniusLyricsProvider @Inject constructor(private val client:OkHttpClient,private val gson:Gson):RemoteLyricsProvider{
 override val descriptor=LyricsSourceDescriptor("genius","Genius",130,setOf(LyricsCapability.PLAIN_TEXT, LyricsCapability.CONTRIBUTOR_CREDITS),releaseChannel=SourceReleaseChannel.EXPERIMENTAL)
 override suspend fun fetch(r:LyricsLookupRequest):ProviderResult { return try{val url="https://genius.com/api/search/multi".toHttpUrl().newBuilder().addQueryParameter("q","${r.artist} ${r.title}").build();val root=get(url);val sections=root.getAsJsonObject("response")?.getAsJsonArray("sections")?:return ProviderResult.Miss;var page:String?=null
  sections.flatMap{it.asJsonObject.getAsJsonArray("hits")?.toList().orEmpty()}.map{it.asJsonObject.getAsJsonObject("result")}.firstOrNull{res->SpotifyTrackMatcher.normalize(res.get("title")?.asString.orEmpty())==SpotifyTrackMatcher.normalize(r.title)&&SpotifyTrackMatcher.normalize(res.getAsJsonObject("primary_artist")?.get("name")?.asString.orEmpty()).contains(SpotifyTrackMatcher.normalize(r.artist))}?.let{page=it.get("url")?.asString}
  val target=page?:return ProviderResult.Miss;val html=client.newCall(Request.Builder().url(target).header("User-Agent",BROWSER_UA).get().build()).awaitResponse().use{if(!it.isSuccessful)throw ProviderHttpException("Genius",it.code);it.body?.string().orEmpty()};val text=geniusLyricsText(html);if(text.isBlank())ProviderResult.Miss else ProviderResult.Hit(RemoteLyricsPayload(plainLyrics=text))
 }catch(c:CancellationException){throw c}catch(e:ProviderHttpException){e.unavailable()}catch(e:IOException){ProviderResult.Unavailable(ProviderFailureCategory.NETWORK,e.message,true)}catch(e:Exception){ProviderResult.Unavailable(ProviderFailureCategory.MALFORMED_RESPONSE,e.message)} }
 // Genius' front answers OkHttp's own user agent with HTTP 401.
 private suspend fun get(url:okhttp3.HttpUrl)=client.newCall(Request.Builder().url(url).header("Referer","https://genius.com/").header("User-Agent",BROWSER_UA).get().build()).awaitResponse().use{r->if(!r.isSuccessful)throw ProviderHttpException("Genius",r.code);gson.fromJson(r.body?.string(),JsonObject::class.java)}
 private companion object { const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36" }
}

/**
 * The lyrics on a Genius song page: the text of every `data-lyrics-container` div, which nest
 * divs of their own (a header with a translations menu, annotations). Parts marked
 * `data-exclude-from-selection` are page furniture, not lyrics, and are skipped.
 */
internal fun geniusLyricsText(html: String): String {
    val tag = Regex("<(/?)([a-zA-Z0-9]+)([^>]*)>")
    val containers = mutableListOf<String>()
    var from = 0
    while (true) {
        val open = Regex("<div[^>]*data-lyrics-container=[\"']true[\"'][^>]*>", RegexOption.IGNORE_CASE).find(html, from) ?: break
        val out = StringBuilder()
        var depth = 1
        var excludedAt = -1
        var at = open.range.last + 1
        while (depth > 0) {
            val next = tag.find(html, at) ?: break
            if (excludedAt < 0) out.append(html, at, next.range.first)
            at = next.range.last + 1
            val name = next.groupValues[2].lowercase()
            val closing = next.groupValues[1] == "/"
            if (name == "br") {
                if (excludedAt < 0) out.append('\n')
                continue
            }
            if (name !in NESTING_TAGS || next.groupValues[3].trimEnd().endsWith("/")) continue
            if (closing) {
                depth--
                if (depth == excludedAt) excludedAt = -1
            } else {
                if (excludedAt < 0 && "data-exclude-from-selection=\"true\"" in next.groupValues[3]) excludedAt = depth
                depth++
            }
        }
        containers += out.toString()
        from = at
    }
    return containers.joinToString("\n")
        .replace("&amp;", "&").replace("&#x27;", "'").replace("&#39;", "'").replace("&quot;", "\"")
        .lines().joinToString("\n") { it.trim() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

/** Tags that open and close around content; void and inline tags don't change the nesting that matters. */
private val NESTING_TAGS = setOf("div", "span", "a", "i", "b", "em", "strong", "p", "button", "ul", "li", "svg", "path", "label", "section")
