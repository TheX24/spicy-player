package com.tx24.spicyplayer.haptics

import com.tx24.spicyplayer.network.data.spotify.AudioAnalysis
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * What a music haptic marks. The first three follow the drums ([MusicHapticsStyle.Drums]), [Note]
 * the notes where there are no drums; [Beat] and [Downbeat] are the steady beat
 * ([MusicHapticsStyle.Beat]); [Accent] and [Drop] are the big moments in either.
 */
enum class MusicPulse { Kick, Snare, Note, Beat, Downbeat, Accent, Drop }

/** What the music haptics follow. */
enum class MusicHapticsStyle(val label: String) { Drums("The drums"), Beat("The beat") }

/**
 * One vibration in a song, at [atMs] into it, [strength] 0..1, with [roomMs] of quiet before the
 * next one (a long effect only fits where there is room for it). A [MusicPulse.Drop] starts its
 * swell at [atMs] and lands [DROP_RISE_MS] later.
 */
class MusicHaptic(val atMs: Long, val pulse: MusicPulse, val strength: Float, val roomMs: Long = Long.MAX_VALUE)

/** How long a [MusicPulse.Drop]'s swell runs before it lands. */
const val DROP_RISE_MS = 500L

/**
 * Turns a song's audio analysis into the vibrations that play along with it.
 *
 * [MusicHapticsStyle.Drums] feels what is played. The beat grid gives the timing; the sound on
 * each beat says what it is: whether anything is hit there at all (a sharp rise in loudness), and
 * whether it's a kick or a snare, by its timbre against the beats either side (a backbeat's snare
 * or clap sounds brighter and snappier than the kick around it). Strong hits between beats
 * (syncopation) join in. Sections without drums follow the notes instead, lightly.
 *
 * [MusicHapticsStyle.Beat] is a steady pulse: every beat, firmer on each bar's first.
 *
 * Both mark drops (a section that comes in much louder than the one before, with a swell into the
 * bigger ones) and big hits, and both follow the loudness: quiet parts are faint, silence still.
 */
object MusicHapticScore {
    /** Beats this unsure are left out. */
    private const val MIN_BEAT_CONFIDENCE = 0.05f
    /** Pulses quieter than this (0..1) are left out. */
    private const val MIN_STRENGTH = 0.12f
    /** How far a bar may start from a beat and still make it a downbeat, in seconds. */
    private const val DOWNBEAT_WINDOW = 0.06f

    /** A segment starting this close to a beat is the sound on that beat, in seconds. */
    private const val ON_BEAT = 0.07f
    /** A rise of this many dB into a segment is a hit. */
    private const val HIT_JUMP_DB = 6f
    /** A section with hits on this share of its beats has drums. */
    private const val DRUM_SECTION_SHARE = 0.3f
    /** How much brighter and snappier than the beats either side (in track standard deviations) a snare is. */
    private const val SNARE_CONTRAST = 0.35f
    /** A hit between beats joins in when it rises this many dB, attacks this sharply, and is this loud. */
    private const val OFFBEAT_JUMP_DB = 12f
    private const val OFFBEAT_ATTACK = 0.5f
    private const val OFFBEAT_LEVEL = 0.4f
    /** Notes (in sections without drums) at least this far apart, in seconds. */
    private const val NOTE_GAP = 0.15f

    /** A section this many dB louder than the one before comes in with an accent... */
    private const val DROP_DB = 4f
    /** ...and with a swell into it from this many. */
    private const val RISE_DB = 6f
    /** A segment whose peak is this many dB over its start is a big hit... */
    private const val HIT_ATTACK_DB = 20f
    /** ...when it also peaks this many dB over the track's average loudness. */
    private const val HIT_OVER_TRACK_DB = 1f
    /** Following the drums, a hit at least this loud (0..1)... */
    private const val COMEBACK_LEVEL = 0.6f
    /** ...after this long with nothing louder than [QUIET_LEVEL] is an accent, in seconds. */
    private const val COMEBACK_PAUSE = 1f
    private const val QUIET_LEVEL = 0.2f
    /** Accents closer together than this blur into one, in seconds. */
    private const val MIN_ACCENT_GAP = 3f
    /** An accent takes the place of a pulse this close, in seconds. */
    private const val ACCENT_SNAP = 0.12f
    /** Pulses closer together than this blur on the motor; the weaker goes, in milliseconds. */
    private const val MIN_GAP_MS = 90L

    fun build(analysis: AudioAnalysis, style: MusicHapticsStyle = MusicHapticsStyle.Drums): List<MusicHaptic> {
        val song = Song(analysis)
        val pulses = when (style) {
            MusicHapticsStyle.Drums -> drums(song)
            MusicHapticsStyle.Beat -> steadyBeat(song)
        }
        return finish(withAccents(pulses, song, style))
    }

    // Drums

    private fun drums(song: Song): MutableList<MusicHaptic> {
        val out = ArrayList<MusicHaptic>()
        val onBeat = song.beats.map { song.soundAt(it.start) }
        // Each beat's brightness and snap, to compare with its neighbours.
        val character = onBeat.map { i -> i?.let { song.brightness[it] + song.attack[it] } }
        val drumSections = song.a.sections.map { section ->
            val inSection = song.beats.indices.filter { song.beats[it].start in section.start..<section.start + section.duration }
            val hits = inSection.count { b -> onBeat[b]?.let { song.jump(it) >= HIT_JUMP_DB && song.level(it) >= 0.2f } == true }
            inSection.isNotEmpty() && hits >= DRUM_SECTION_SHARE * inSection.size
        }
        fun drumsAt(t: Float): Boolean {
            val s = song.sectionIndex(t)
            // Before the first section (or with none), trust the whole song.
            return if (s >= 0) drumSections[s] else drumSections.count { it } * 2 >= drumSections.size
        }

        song.beats.forEachIndexed { b, beat ->
            if (!drumsAt(beat.start)) return@forEachIndexed
            val seg = onBeat[b] ?: return@forEachIndexed
            val hit = ((song.jump(seg) - 3f) / 12f).coerceIn(0f, 1f)
            var strength = song.level(seg) * (0.35f + 0.65f * hit)
            val neighbours = listOfNotNull(character.getOrNull(b - 1), character.getOrNull(b + 1))
            val snare = neighbours.isNotEmpty() && character[b]!! - neighbours.average() >= SNARE_CONTRAST
            if (!snare && song.isDownbeat(beat.start)) strength *= 1.15f
            if (strength >= MIN_STRENGTH) {
                out += MusicHaptic(ms(beat.start), if (snare) MusicPulse.Snare else MusicPulse.Kick, strength.coerceAtMost(1f))
            }
        }

        // Syncopation: strong hits away from the beats, kick or snare by their sound against the song's.
        val snareCharacter = character.filterNotNull().let { if (it.isEmpty()) 0f else it.average().toFloat() }
        song.segments.forEachIndexed { i, seg ->
            if (song.nearBeat(seg.start) || !drumsAt(seg.start)) return@forEachIndexed
            if (song.jump(i) < OFFBEAT_JUMP_DB || song.attack[i] < OFFBEAT_ATTACK || song.level(i) < OFFBEAT_LEVEL) return@forEachIndexed
            val snare = song.brightness[i] + song.attack[i] > snareCharacter + SNARE_CONTRAST
            out += MusicHaptic(ms(seg.start), if (snare) MusicPulse.Snare else MusicPulse.Kick, 0.8f * song.level(i))
        }

        // No drums: a light touch on each clear note.
        var lastNote = Float.NEGATIVE_INFINITY
        song.segments.forEachIndexed { i, seg ->
            if (drumsAt(seg.start) || seg.start - lastNote < NOTE_GAP) return@forEachIndexed
            val level = song.level(i)
            if (song.jump(i) < HIT_JUMP_DB || level < 0.25f) return@forEachIndexed
            out += MusicHaptic(ms(seg.start), MusicPulse.Note, 0.2f + 0.4f * level)
            lastNote = seg.start
        }
        return out
    }

    // Steady beat

    private fun steadyBeat(song: Song): MutableList<MusicHaptic> {
        val out = ArrayList<MusicHaptic>()
        for (beat in song.beats) {
            val level = song.levelAt(beat.start)
            if (level < MIN_STRENGTH) continue
            val sure = 0.7f + 0.3f * beat.confidence.coerceIn(0f, 1f)
            out += if (song.isDownbeat(beat.start)) {
                MusicHaptic(ms(beat.start), MusicPulse.Downbeat, (0.45f + 0.55f * level) * sure)
            } else {
                MusicHaptic(ms(beat.start), MusicPulse.Beat, (0.25f + 0.45f * level) * sure)
            }
        }
        return out
    }

    // Accents and drops

    private fun withAccents(pulses: MutableList<MusicHaptic>, song: Song, style: MusicHapticsStyle): MutableList<MusicHaptic> {
        val candidates = ArrayList<Accent>()
        song.a.sections.zipWithNext { before, section ->
            val jump = section.loudness - before.loudness
            if (jump >= DROP_DB) candidates += Accent(section.start, (0.7f + jump / 20f).coerceAtMost(1f), rise = jump >= RISE_DB)
        }
        var quietSince = 0f
        song.segments.forEachIndexed { i, seg ->
            val attack = seg.loudnessMax - seg.loudnessStart
            val bigHit = attack >= HIT_ATTACK_DB && seg.loudnessMax >= song.a.loudness + HIT_OVER_TRACK_DB
            // Following the drums, the kicks already carry the hits; only the music crashing back
            // in after a pause stands out.
            val comeback = song.level(i) >= COMEBACK_LEVEL && seg.start - quietSince >= COMEBACK_PAUSE
            if (if (style == MusicHapticsStyle.Drums) comeback else bigHit) {
                candidates += Accent(seg.peakAt, (0.6f + (attack - HIT_ATTACK_DB) / 40f).coerceIn(0.6f, 0.9f), rise = false)
            }
            if (song.level(i) >= QUIET_LEVEL) quietSince = seg.start + 0.001f
        }
        // Strongest first, so a drop wins over a hit beside it.
        val kept = ArrayList<Accent>()
        for (c in candidates.sortedByDescending { it.strength }) {
            if (kept.none { abs(it.at - c.at) < MIN_ACCENT_GAP }) kept += c
        }
        for (accent in kept) {
            // It lands on the pulse nearest it, if one is close.
            val near = pulses.filter { abs(it.atMs - ms(accent.at)) <= ms(ACCENT_SNAP) }.minByOrNull { abs(it.atMs - ms(accent.at)) }
            val at = near?.atMs ?: ms(accent.at)
            if (accent.rise) {
                // Nothing may cut the swell short.
                pulses.removeAll { it.atMs in (at - DROP_RISE_MS)..at + MIN_GAP_MS }
                pulses += MusicHaptic(at - DROP_RISE_MS, MusicPulse.Drop, accent.strength)
            } else {
                near?.let(pulses::remove)
                pulses += MusicHaptic(at, MusicPulse.Accent, accent.strength)
            }
        }
        return pulses
    }

    private class Accent(val at: Float, val strength: Float, val rise: Boolean)

    /** In time order, too-close pulses thinned (the stronger stays), each told how much room it has. */
    private fun finish(pulses: MutableList<MusicHaptic>): List<MusicHaptic> {
        pulses.sortBy { it.atMs }
        val thinned = ArrayList<MusicHaptic>(pulses.size)
        for (p in pulses) {
            val last = thinned.lastOrNull()
            // A drop's swell keeps its place; what follows it too closely goes.
            val lastEnd = last?.let { if (it.pulse == MusicPulse.Drop) it.atMs + DROP_RISE_MS else it.atMs }
            when {
                last == null || p.atMs - lastEnd!! >= MIN_GAP_MS -> thinned += p
                last.pulse != MusicPulse.Drop && p.pulse != MusicPulse.Drop && p.strength > last.strength -> thinned[thinned.lastIndex] = p
                p.pulse == MusicPulse.Drop -> thinned[thinned.lastIndex] = p
            }
        }
        return thinned.mapIndexed { i, p ->
            val next = thinned.getOrNull(i + 1)
            MusicHaptic(p.atMs, p.pulse, p.strength, next?.let { it.atMs - p.atMs } ?: Long.MAX_VALUE)
        }
    }

    /**
     * How loud [dB] is, 0..1, against the track's average: 15 dB under it is 0, 3 dB over it is 1
     * (a hit's peak usually sits a few dB over the average).
     */
    internal fun level(dB: Float, trackLoudness: Float): Float =
        ((dB - (trackLoudness - 15f)) / 18f).coerceIn(0f, 1f)

    private fun ms(seconds: Float) = (seconds * 1000f).toLong()

    /** The analysis, sorted and measured once. */
    private class Song(val a: AudioAnalysis) {
        val segments = a.segments.sortedBy { it.start }
        private val starts = FloatArray(segments.size) { segments[it].start }
        val beats = a.beats.filter { it.confidence >= MIN_BEAT_CONFIDENCE }
        private val beatStarts = FloatArray(beats.size) { beats[it].start }
        private val bars = a.bars.sorted().toFloatArray()
        private val sectionStarts = a.sections.map { it.start }.toFloatArray()

        // Timbre in track standard deviations: only comparable within one song.
        val brightness = standardised { it.brightness }
        val attack = standardised { it.attack }

        fun jump(i: Int) = segments[i].loudnessMax - segments[i].loudnessStart
        fun level(i: Int) = level(segments[i].loudnessMax, a.loudness)

        /** The loudest segment starting within [ON_BEAT] of [t], or null. */
        fun soundAt(t: Float): Int? {
            var i = lastAtOrBefore(starts, t + ON_BEAT)
            var best: Int? = null
            while (i >= 0 && starts[i] >= t - ON_BEAT) {
                if (best == null || segments[i].loudnessMax > segments[best].loudnessMax) best = i
                i--
            }
            return best
        }

        /** How loud it is at [t]: the sound on it, else the segment playing, else the section. */
        fun levelAt(t: Float): Float {
            soundAt(t)?.let { return level(it) }
            val i = lastAtOrBefore(starts, t)
            if (i >= 0) return level(i)
            return level(a.sections.getOrNull(sectionIndex(t))?.loudness ?: a.loudness, a.loudness)
        }

        fun nearBeat(t: Float): Boolean {
            val i = lastAtOrBefore(beatStarts, t)
            return (i >= 0 && t - beatStarts[i] < 0.09f) || (i + 1 < beatStarts.size && beatStarts[i + 1] - t < 0.09f)
        }

        fun isDownbeat(t: Float): Boolean {
            val i = lastAtOrBefore(bars, t + DOWNBEAT_WINDOW)
            return i >= 0 && abs(bars[i] - t) <= DOWNBEAT_WINDOW
        }

        fun sectionIndex(t: Float) = lastAtOrBefore(sectionStarts, t)

        private inline fun standardised(value: (AudioAnalysis.Segment) -> Float): FloatArray {
            if (segments.isEmpty()) return FloatArray(0)
            val mean = segments.sumOf { value(it).toDouble() } / segments.size
            val sd = sqrt(segments.sumOf { (value(it) - mean).let { d -> d * d } } / segments.size).takeIf { it > 1e-6 } ?: 1.0
            return FloatArray(segments.size) { ((value(segments[it]) - mean) / sd).toFloat() }
        }

        private fun lastAtOrBefore(sorted: FloatArray, t: Float): Int {
            var lo = 0
            var hi = sorted.size - 1
            var at = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (sorted[mid] <= t) { at = mid; lo = mid + 1 } else hi = mid - 1
            }
            return at
        }
    }
}
