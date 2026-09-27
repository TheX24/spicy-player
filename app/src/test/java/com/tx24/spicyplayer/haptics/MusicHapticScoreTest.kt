package com.tx24.spicyplayer.haptics

import com.google.gson.JsonParser
import com.tx24.spicyplayer.network.data.spotify.AudioAnalysis
import com.tx24.spicyplayer.network.data.spotify.LocalTrackMetadata
import com.tx24.spicyplayer.network.data.spotify.SharedSpotify
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class MusicHapticScoreTest {
    private val analysis = AudioAnalysis(
        tempo = 120f,
        loudness = -10f,
        sections = listOf(
            AudioAnalysis.Section(start = 0f, duration = 4f, loudness = -30f, tempo = 120f),
            AudioAnalysis.Section(start = 4f, duration = 4f, loudness = -8f, tempo = 120f),
        ),
        beats = (0 until 16).map { AudioAnalysis.Beat(start = it * 0.5f, duration = 0.5f, confidence = 0.8f) },
        bars = listOf(0f, 2f, 4f, 6f),
        segments = (0 until 16).map {
            val t = it * 0.5f
            // Near-silent first half, loud second half.
            val peak = if (t < 4f) -40f else -6f
            AudioAnalysis.Segment(start = t, loudnessStart = peak - 5f, loudnessMax = peak, peakAt = t + 0.02f)
        },
    )

    @Test fun silenceIsStillAndTheDropSwellsIn() {
        val score = MusicHapticScore.build(analysis, MusicHapticsStyle.Beat)
        // The 22 dB jump at 4 s swells in from 3.5 s; nothing plays in the silence before.
        val drop = score.first()
        assertEquals(MusicPulse.Drop, drop.pulse)
        assertEquals(4_000L - DROP_RISE_MS, drop.atMs)
        assertTrue(score.drop(1).all { it.atMs >= 4_000 + 90 })
    }

    @Test fun barsStartOnDownbeats() {
        val score = MusicHapticScore.build(analysis, MusicHapticsStyle.Beat).associateBy { it.atMs }
        assertEquals(MusicPulse.Downbeat, score[6_000L]?.pulse)
        assertEquals(MusicPulse.Beat, score[6_500L]?.pulse)
        assertTrue(score.getValue(6_000L).strength > score.getValue(6_500L).strength)
    }

    /** Eight bars of a kick on 1 and 3 and a brighter, snappier snare on 2 and 4. */
    private val drumLoop = AudioAnalysis(
        tempo = 120f,
        loudness = -8f,
        sections = listOf(AudioAnalysis.Section(start = 0f, duration = 16f, loudness = -8f, tempo = 120f)),
        beats = (0 until 32).map { AudioAnalysis.Beat(start = it * 0.5f, duration = 0.5f, confidence = 0.9f) },
        bars = (0 until 8).map { it * 2f },
        segments = (0 until 32).flatMap {
            val t = it * 0.5f
            val snare = it % 2 == 1
            listOf(
                AudioAnalysis.Segment(t, -30f, -6f, t + 0.02f, brightness = if (snare) 40f else -20f, attack = if (snare) 60f else 20f),
                // A quiet pad between the hits.
                AudioAnalysis.Segment(t + 0.25f, -20f, -18f, t + 0.3f, brightness = 0f, attack = 0f),
            )
        },
    )

    @Test fun kicksAndSnaresComeFromTheSound() {
        val score = MusicHapticScore.build(drumLoop).associateBy { it.atMs }
        assertEquals(MusicPulse.Kick, score[4_000L]?.pulse)
        assertEquals(MusicPulse.Snare, score[4_500L]?.pulse)
        assertEquals(MusicPulse.Kick, score[5_000L]?.pulse)
        assertEquals(MusicPulse.Snare, score[5_500L]?.pulse)
        // The pad isn't a hit.
        assertTrue(score[4_250L] == null)
    }

    @Test fun withoutDrumsTheNotesPlayLightly() {
        // The fixture's quiet half has no hits, its loud half rises only 5 dB a segment: no drums.
        val score = MusicHapticScore.build(analysis)
        assertTrue(score.none { it.pulse == MusicPulse.Kick || it.pulse == MusicPulse.Snare })
    }

    @Test fun eachPulseKnowsItsRoom() {
        val score = MusicHapticScore.build(drumLoop)
        score.zipWithNext { a, b -> assertEquals(b.atMs - a.atMs, a.roomMs) }
    }

    @Test fun levelRunsFrom15dBUnderToThreeOver() {
        assertEquals(0f, MusicHapticScore.level(-25f, -10f), 1e-6f)
        assertEquals(1f, MusicHapticScore.level(-7f, -10f), 1e-6f)
    }

    @Test fun compactCopyKeepsBarsAndSegments() {
        val copy = AudioAnalysis.fromJson(JsonParser.parseString(analysis.toJson().toString()).asJsonObject)
        assertNotNull(copy)
        assertEquals(4, copy!!.bars.size)
        assertEquals(16, copy.segments.size)
        assertEquals(MusicHapticScore.build(analysis).size, MusicHapticScore.build(copy).size)
        // Timbre too: the drums read the same from the copy.
        val loop = AudioAnalysis.fromJson(JsonParser.parseString(drumLoop.toJson().toString()).asJsonObject)!!
        assertEquals(MusicHapticScore.build(drumLoop).map { it.pulse }, MusicHapticScore.build(loop).map { it.pulse })
    }

    /** Saves a few real songs' analyses (compact form) to HAPTICS_DUMP_DIR, for tuning outside the app. */
    @Test fun dumpRealSongs() = runBlocking {
        val dir = System.getenv("HAPTICS_DUMP_DIR") ?: return@runBlocking
        val extras = SharedSpotify.extras(Files.createTempDirectory("analysis").toFile())
        songs.forEach { track ->
            val a = extras.trackIds(track, null).firstOrNull()?.let { extras.audioAnalysis(it) } ?: return@forEach
            java.io.File(dir, track.title.replace(Regex("[^A-Za-z0-9]+"), "_") + ".json").writeText(a.toJson().toString())
        }
    }

    private val songs = listOf(
        LocalTrackMetadata("Blinding Lights", "The Weeknd", "After Hours", 200_000),
        LocalTrackMetadata("bad guy", "Billie Eilish", "WHEN WE ALL FALL ASLEEP, WHERE DO WE GO?", 194_000),
        LocalTrackMetadata("Bohemian Rhapsody", "Queen", "A Night At The Opera", 354_000),
        LocalTrackMetadata("Levels", "Avicii", "Levels", 199_000),
        LocalTrackMetadata("Someone Like You", "Adele", "21", 285_000),
        LocalTrackMetadata("Billie Jean", "Michael Jackson", "Thriller", 294_000),
        LocalTrackMetadata("Smells Like Teen Spirit", "Nirvana", "Nevermind", 301_000),
        LocalTrackMetadata("HUMBLE.", "Kendrick Lamar", "DAMN.", 177_000),
        LocalTrackMetadata("Uptown Funk", "Mark Ronson", "Uptown Special", 270_000),
        LocalTrackMetadata("Clair de Lune", "Claude Debussy", "Suite bergamasque", 300_000),
    )

    /** Prints how busy each style is for a few real songs, and where the snares fall; RUN_HAPTICS_TEST=1, with --info. */
    @Test fun realSongs() = runBlocking {
        assumeTrue(System.getenv("RUN_HAPTICS_TEST") == "1")
        val extras = SharedSpotify.extras(Files.createTempDirectory("analysis").toFile())
        songs.forEach { track ->
            val a = extras.trackIds(track, null).firstOrNull()?.let { extras.audioAnalysis(it) }
            if (a == null) { println("${track.title}: no analysis"); return@forEach }
            val minutes = (a.segments.lastOrNull()?.start ?: 1f) / 60f
            val drums = MusicHapticScore.build(a)
            val beat = MusicHapticScore.build(a, MusicHapticsStyle.Beat)
            val counts = MusicPulse.entries.mapNotNull { p -> drums.count { it.pulse == p }.takeIf { it > 0 }?.let { "$p $it" } }
            // Beat in the bar (0-based) of each on-beat snare: pop's backbeat is 1 and 3.
            val bars = a.bars.sorted()
            val snareAt = drums.filter { it.pulse == MusicPulse.Snare }.mapNotNull { s ->
                val t = s.atMs / 1000f
                val bar = bars.lastOrNull { it <= t + 0.03f } ?: return@mapNotNull null
                a.beats.count { it.start >= bar - 0.03f && it.start < t - 0.03f }
            }.groupingBy { it }.eachCount().toSortedMap()
            println(
                "${track.title}: drums ${"%.0f".format(drums.size / minutes)}/min (${counts.joinToString()}), " +
                    "beat ${"%.0f".format(beat.size / minutes)}/min; snares by beat $snareAt",
            )
        }
    }
}
