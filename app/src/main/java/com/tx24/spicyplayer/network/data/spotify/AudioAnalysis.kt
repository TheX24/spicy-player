package com.tx24.spicyplayer.network.data.spotify

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * The parts of Spotify's audio analysis the background moves with: the track's tempo and
 * loudness, its sections, and its beats. Times are in seconds, loudness in dB.
 */
class AudioAnalysis(
    val tempo: Float,
    val loudness: Float,
    val sections: List<Section>,
    val beats: List<Beat>,
) {
    class Section(val start: Float, val duration: Float, val loudness: Float, val tempo: Float)
    class Beat(val start: Float, val duration: Float, val confidence: Float)

    /**
     * A compact copy for the disk cache: the full analysis runs to ~300 KB, mostly segments and
     * tatums nobody here reads. Built by hand rather than through Gson's reflection, which R8's
     * renaming would break.
     */
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("tempo", tempo)
        addProperty("loudness", loudness)
        add("sections", JsonArray().apply {
            sections.forEach { s -> add(numbers(s.start, s.duration, s.loudness, s.tempo)) }
        })
        add("beats", JsonArray().apply {
            beats.forEach { b -> add(numbers(b.start, b.duration, b.confidence)) }
        })
    }

    companion object {
        /** Spotify's own analysis document, or null when it lacks the track, sections or beats. */
        fun fromSpotify(root: JsonObject): AudioAnalysis? = runCatching {
            val track = root.getAsJsonObject("track") ?: return null
            val sections = root.getAsJsonArray("sections") ?: return null
            val beats = root.getAsJsonArray("beats") ?: return null
            AudioAnalysis(
                tempo = track.float("tempo"),
                loudness = track.float("loudness"),
                sections = sections.map { e ->
                    val o = e.asJsonObject
                    Section(o.float("start"), o.float("duration"), o.float("loudness"), o.float("tempo"))
                },
                beats = beats.map { e ->
                    val o = e.asJsonObject
                    Beat(o.float("start"), o.float("duration"), o.float("confidence"))
                },
            )
        }.getOrNull()

        /** Reads what [toJson] wrote. */
        fun fromJson(root: JsonObject): AudioAnalysis? = runCatching {
            AudioAnalysis(
                tempo = root.get("tempo").asFloat,
                loudness = root.get("loudness").asFloat,
                sections = root.getAsJsonArray("sections").map { e ->
                    val a = e.asJsonArray
                    Section(a[0].asFloat, a[1].asFloat, a[2].asFloat, a[3].asFloat)
                },
                beats = root.getAsJsonArray("beats").map { e ->
                    val a = e.asJsonArray
                    Beat(a[0].asFloat, a[1].asFloat, a[2].asFloat)
                },
            )
        }.getOrNull()

        private fun JsonObject.float(key: String): Float = get(key)?.takeUnless(JsonElement::isJsonNull)?.asFloat ?: 0f

        private fun numbers(vararg values: Float) = JsonArray().apply { values.forEach(::add) }
    }
}
