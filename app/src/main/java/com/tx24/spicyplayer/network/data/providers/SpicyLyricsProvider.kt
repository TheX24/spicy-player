package com.tx24.spicyplayer.network.data.providers

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.tx24.spicyplayer.network.data.LyricsCapability
import com.tx24.spicyplayer.network.data.LyricsAttribution
import com.tx24.spicyplayer.network.data.LyricsContributor
import com.tx24.spicyplayer.network.data.LyricsLookupRequest
import com.tx24.spicyplayer.network.data.LyricsSourceDescriptor
import com.tx24.spicyplayer.network.data.ProviderFailureCategory
import com.tx24.spicyplayer.network.data.ProviderResult
import com.tx24.spicyplayer.network.data.RemoteLyricsPayload
import com.tx24.spicyplayer.network.data.RemoteLyricsProvider
import com.tx24.spicyplayer.network.data.RetryAfterParser
import com.tx24.spicyplayer.network.data.SourceReleaseChannel
import com.tx24.spicyplayer.network.data.awaitResponse
import com.tx24.spicyplayer.network.data.spotify.LocalTrackMetadata
import com.tx24.spicyplayer.network.data.spotify.SpotifyTrackResolution
import com.tx24.spicyplayer.network.data.spotify.SpotifyTrackResolver
import java.io.IOException
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SpicyLyricsClientKey

@Singleton
class SpicyLyricsProvider @Inject constructor(
    private val client: OkHttpClient,
    private val gson: Gson,
    private val spotifyResolver: SpotifyTrackResolver,
    @SpicyLyricsClientKey private val apiKey: String,
) : RemoteLyricsProvider {
    override val descriptor = LyricsSourceDescriptor(
        id = "spicy_lyrics",
        displayName = "Spicy Lyrics",
        defaultPriority = 10,
        capabilities = setOf(
            LyricsCapability.WORD_SYNC,
            LyricsCapability.LINE_SYNC,
            LyricsCapability.PLAIN_TEXT,
            LyricsCapability.TRANSLATION,
            LyricsCapability.TRANSLITERATION,
            LyricsCapability.CONTRIBUTOR_CREDITS,
        ),
        releaseChannel = SourceReleaseChannel.RECOMMENDED,
    )

    override suspend fun fetch(request: LyricsLookupRequest): ProviderResult {
        if (apiKey.isBlank()) {
            return ProviderResult.Unavailable(
                ProviderFailureCategory.AUTHENTICATION,
                "SPICY_LYRICS_CLIENT_KEY was not supplied at build time",
            )
        }

        val spotifyId = request.spotifyTrackId ?: run {
            val resolution = spotifyResolver.resolve(
                LocalTrackMetadata(
                    title = request.title,
                    artist = request.artist,
                    album = request.album,
                    durationMs = request.durationSeconds * 1_000L,
                )
            )
            when (resolution) {
                is SpotifyTrackResolution.Matched -> resolution.track.candidate.id
                is SpotifyTrackResolution.Ambiguous, SpotifyTrackResolution.NotFound -> return ProviderResult.NeedsMatch
            }
        }
        val url = "https://api.spicylyrics.org/v1/lyrics"
            .toHttpUrl()
            .newBuilder()
            .addPathSegment(spotifyId)
            .build()
        val httpRequest = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()

        return try {
            client.newCall(httpRequest).awaitResponse().use { response ->
                when (response.code) {
                    200 -> parseHit(response.body?.string().orEmpty())
                    404 -> ProviderResult.Miss
                    429 -> ProviderResult.CoolingDown(
                        RetryAfterParser.deadline(response.header("Retry-After"))
                    )
                    503 -> ProviderResult.Queued(
                        response.header("Retry-After")?.let(RetryAfterParser::deadline)
                    )
                    401, 403 -> ProviderResult.Unavailable(ProviderFailureCategory.AUTHENTICATION)
                    in 400..499 -> ProviderResult.Unavailable(ProviderFailureCategory.CLIENT_REQUEST)
                    else -> ProviderResult.Unavailable(
                        ProviderFailureCategory.SERVER,
                        "Spicy Lyrics returned HTTP ${response.code}",
                        retryable = response.code >= 500,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            ProviderResult.Unavailable(ProviderFailureCategory.NETWORK, error.message, retryable = true)
        } catch (error: Exception) {
            ProviderResult.Unavailable(ProviderFailureCategory.MALFORMED_RESPONSE, error.message)
        }
    }

    internal fun parseHit(json: String): ProviderResult {
        val root = gson.fromJson(json, JsonObject::class.java)
        if (root.get("Status")?.asInt != 200) return ProviderResult.Miss
        val body = root.getAsJsonObject("Body") ?: return malformed("Missing Body")
        val content = body.getAsJsonArray("Content") ?: return malformed("Missing Content")
        val ttml = SpicyLyricsTtmlConverter.convert(body, content)
            ?: return malformed("No usable lyric content")
        val upload = body.getAsJsonObject("UploadAttribution")
        return ProviderResult.Hit(RemoteLyricsPayload(
            ttmlLyrics = ttml,
            attribution = LyricsAttribution(
                providerName = "Spicy Lyrics",
                originName = spicyOriginName(body.get("source")?.takeIf { it.isJsonPrimitive }?.asString),
                songwriters = body.getAsJsonArray("SongWriters")?.mapNotNull { it.takeIf { value -> value.isJsonPrimitive }?.asString }.orEmpty(),
                maker = upload?.contributor("Maker"),
                uploader = upload?.contributor("Uploader"),
            ),
        ))
    }

    private fun malformed(message: String) = ProviderResult.Unavailable(
        ProviderFailureCategory.MALFORMED_RESPONSE,
        message,
    )

    private fun JsonObject.contributor(name: String): LyricsContributor? {
        val value = get(name) ?: return null
        if (value.isJsonPrimitive) return value.asString.takeIf(String::isNotBlank)?.let(::LyricsContributor)
        if (!value.isJsonObject) return null
        val item = value.asJsonObject
        val username = sequenceOf("username", "Username", "name", "Name")
            .mapNotNull { key -> item.get(key)?.takeIf { it.isJsonPrimitive }?.asString }
            .firstOrNull(String::isNotBlank) ?: return null
        val url = sequenceOf("url", "Url", "profileUrl", "ProfileUrl")
            .mapNotNull { key -> item.get(key)?.takeIf { it.isJsonPrimitive }?.asString }
            .firstOrNull { it.startsWith("https://") }
        return LyricsContributor(username, url)
    }

    private fun spicyOriginName(raw: String?): String = when (raw?.trim()?.lowercase()?.replace('-', '_')?.replace(' ', '_')) {
        "apple", "apple_music", "am" -> "Apple Music"
        "spotify", "spotify_lyrics" -> "Spotify"
        "spicy", "spicy_lyrics", "community" -> "Spicy Lyrics Community"
        null, "" -> "Spicy Lyrics"
        else -> raw.trim()
    }
}

internal object SpicyLyricsTtmlConverter {
    fun convert(body: JsonObject, content: JsonArray): String? {
        val defaultAgent = content.mapNotNull { it.takeIf { value -> value.isJsonObject }?.asJsonObject }
            .firstNotNullOfOrNull { it.string("Agent") ?: it.getAsJsonObject("Lead")?.string("Agent") }
        val transliterations = StringBuilder()
        val paragraphs = buildList {
            content.forEach { element ->
                val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                if (item.string("Type").equals("Interlude", ignoreCase = true)) return@forEach
                val lead = item.getAsJsonObject("Lead") ?: return@forEach
                val agent = item.string("Agent") ?: lead.string("Agent")
                val opposite = item.get("OppositeAligned")?.takeIf { it.isJsonPrimitive }?.asBoolean
                    ?: (agent != null && defaultAgent != null && agent != defaultAgent)
                val key = "L${size + 1}"
                paragraph(key, lead, item.getAsJsonArray("Background"), agent, opposite)?.let(::add) ?: return@forEach
                transliteration(lead.getAsJsonArray("Syllables"))?.let {
                    transliterations.append("<text for=\"$key\">$it</text>")
                }
            }
        }
        if (paragraphs.isEmpty()) return null
        val writers = body.getAsJsonArray("SongWriters")
            ?.mapNotNull { it.takeIf { value -> value.isJsonPrimitive }?.asString }
            .orEmpty()
            .joinToString("") { "<songwriter>${xml(it)}</songwriter>" }
        return """<?xml version="1.0" encoding="UTF-8"?><tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" xmlns:spicy="https://spicylyrics.org/ns/ttml" itunes:timing="word"><head><metadata><songwriters>$writers</songwriters>${if (transliterations.isEmpty()) "" else "<transliterations><transliteration>$transliterations</transliteration></transliterations>"}</metadata></head><body><div>${paragraphs.joinToString("")}</div></body></tt>"""
    }

    /** SL's per-syllable romanization, only when every lead syllable has one (the parser matches by position). */
    private fun transliteration(syllables: JsonArray?): String? {
        val texts = syllables?.map { element ->
            element.takeIf { it.isJsonObject }?.asJsonObject?.string("TransliteratedText")?.trim()
                ?.takeIf(String::isNotEmpty) ?: return null
        }
        return texts?.takeIf(List<String>::isNotEmpty)?.joinToString("") { "<span>${xml(it)}</span>" }
    }

    private fun paragraph(key: String, lead: JsonObject, backgrounds: JsonArray?, agent: String?, opposite: Boolean): String? {
        val leadSpans = spans(lead.getAsJsonArray("Syllables"))
        if (leadSpans.isEmpty()) return null
        val start = lead.number("StartTime") ?: leadSpans.first().start
        val end = lead.number("EndTime") ?: leadSpans.last().end
        val bg = backgrounds?.mapNotNull { element ->
            val group = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            spans(group.getAsJsonArray("Syllables")).takeIf(List<TimedText>::isNotEmpty)
        }.orEmpty().joinToString("") { group ->
            "<span ttm:role=\"x-bg\">${group.joinToString("") { it.xmlSpan() }}</span>"
        }
        val agentAttribute = agent?.let { " ttm:agent=\"${xml(it)}\"" }.orEmpty()
        return "<p begin=\"${seconds(start)}\" end=\"${seconds(end)}\" itunes:key=\"$key\"$agentAttribute spicy:oppositeAligned=\"$opposite\">${leadSpans.joinToString("") { it.xmlSpan() }}$bg</p>"
    }

    private fun spans(array: JsonArray?): List<TimedText> = array?.mapIndexedNotNull { index, element ->
        val word = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapIndexedNotNull null
        val text = word.string("Text") ?: return@mapIndexedNotNull null
        val start = word.number("StartTime") ?: return@mapIndexedNotNull null
        val end = word.number("EndTime") ?: return@mapIndexedNotNull null
        // Spicy marks whether this syllable joins the NEXT one, while TTML
        // spacing is expressed before the current span.
        val attached = index > 0 && array[index - 1].takeIf { it.isJsonObject }
            ?.asJsonObject?.get("IsPartOfWord")?.asBoolean == true
        TimedText(text, start, end, attached)
    }.orEmpty()

    private data class TimedText(val text: String, val start: Double, val end: Double, val attached: Boolean) {
        fun xmlSpan(): String = "<span begin=\"${seconds(start)}\" end=\"${seconds(end)}\">${if (!attached) " " else ""}${xml(text)}</span>"
    }

    private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.number(name: String): Double? = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
    private fun seconds(value: Double) = "${"%.3f".format(java.util.Locale.ROOT, value)}s"
    private fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
