package com.tx24.spicyplayer.latencytest

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.tx24.spicyplayer.lyrics.spicy.models.LineRole
import com.tx24.spicyplayer.BuildConfig
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import kotlin.math.roundToLong

internal interface SpicyLyricsApi {
    @GET("v1/lyrics/{spotifyId}")
    suspend fun lyrics(
        @Path("spotifyId") spotifyId: String,
        @Header("Authorization") authorization: String,
    ): JsonObject
}

internal class SpicyLyricsClient(
    private val apiKey: String = BuildConfig.SPICY_LYRICS_CLIENT_KEY,
    private val api: SpicyLyricsApi = Retrofit.Builder()
        .baseUrl("https://api.spicylyrics.org/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(SpicyLyricsApi::class.java),
) {
    suspend fun fetch(spotifyId: String, runtimeApiKey: String = ""): LyricsState.Ready {
        val effectiveApiKey = runtimeApiKey.ifBlank { apiKey }
        require(effectiveApiKey.isNotBlank()) {
            "Enter a Spicy Lyrics client key"
        }
        return try {
            val root = api.lyrics(spotifyId, "Bearer $effectiveApiKey")
            val body = root.getAsJsonObject("Body")
            val upload = body?.getAsJsonObject("UploadAttribution")
            LyricsState.Ready(
                lines = parse(root),
                source = body?.text("source"),
                maker = upload?.contributor("Maker"),
                uploader = upload?.contributor("Uploader"),
                songwriters = body?.getAsJsonArray("SongWriters")?.mapNotNull {
                    it.takeIf { value -> value.isJsonPrimitive }?.asString
                }.orEmpty(),
            )
        } catch (error: HttpException) {
            throw IllegalStateException(
                if (error.code() == 404) "No Spicy Lyrics entry for this track" else "Spicy Lyrics returned HTTP ${error.code()}",
                error,
            )
        }
    }

    internal fun parse(root: JsonObject): List<TimedLine> {
        if (root.get("Status")?.asInt != 200) error("Spicy Lyrics returned a non-success response")
        val content = root.getAsJsonObject("Body")?.getAsJsonArray("Content")
            ?: error("Spicy Lyrics response has no Content")

        val defaultAgent = content.mapNotNull { it.takeIf { value -> value.isJsonObject }?.asJsonObject }
            .firstNotNullOfOrNull { it.text("Agent") ?: it.getAsJsonObject("Lead")?.text("Agent") }
        return content.flatMapIndexed { index, element ->
            val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@flatMapIndexed emptyList()
            if (item.text("Type").equals("Interlude", ignoreCase = true)) return@flatMapIndexed emptyList()
            val lead = item.getAsJsonObject("Lead") ?: return@flatMapIndexed emptyList()
            val agent = item.text("Agent") ?: lead.text("Agent")
            val opposite = item.get("OppositeAligned")?.takeIf { it.isJsonPrimitive }?.asBoolean
                ?: (agent != null && defaultAgent != null && agent != defaultAgent)
            buildList {
                lead.toTimedLine(LineRole.LEAD, index, agent, opposite)?.let(::add)
                item.getAsJsonArray("Background")?.forEach { background ->
                    background.takeIf { it.isJsonObject }?.asJsonObject
                        ?.toTimedLine(LineRole.BACKGROUND, index, agent, opposite)?.let(::add)
                }
            }
        }.also { require(it.isNotEmpty()) { "Spicy Lyrics returned no timed words" } }
    }

    private fun JsonObject.toTimedLine(role: LineRole, groupId: Int, agent: String?, opposite: Boolean): TimedLine? {
        val words = getAsJsonArray("Syllables").toTimedWords()
        if (words.isEmpty()) return null
        return TimedLine(
            startMs = number("StartTime")?.secondsToMs() ?: words.first().startMs,
            endMs = number("EndTime")?.secondsToMs() ?: words.last().endMs,
            words = words,
            role = role,
            groupId = groupId,
            agent = agent,
            oppositeAligned = opposite,
        )
    }

    private fun JsonArray?.toTimedWords(): List<TimedWord> = this?.mapIndexedNotNull { index, element ->
        val word = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapIndexedNotNull null
        val text = word.text("Text") ?: return@mapIndexedNotNull null
        val start = word.number("StartTime") ?: return@mapIndexedNotNull null
        val end = word.number("EndTime") ?: return@mapIndexedNotNull null
        TimedWord(
            text = text,
            startMs = start.secondsToMs(),
            endMs = end.secondsToMs(),
            attached = index > 0 && get(index - 1).takeIf { it.isJsonObject }
                ?.asJsonObject?.get("IsPartOfWord")?.asBoolean == true,
        )
    }.orEmpty()

    private fun JsonObject.text(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.contributor(key: String): String? {
        val value = get(key) ?: return null
        if (value.isJsonPrimitive) return value.asString.takeIf(String::isNotBlank)
        if (!value.isJsonObject) return null
        val entry = value.asJsonObject
        return sequenceOf("username", "Username", "name", "Name")
            .mapNotNull { name -> entry.text(name) }.firstOrNull(String::isNotBlank)
    }

    private fun JsonObject.number(key: String): Double? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble

    private fun Double.secondsToMs(): Long = (this * 1_000.0).roundToLong()
}
