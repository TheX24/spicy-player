package com.tx24.spicyplayer.latencytest

import com.tx24.spicyplayer.lyrics.spicy.canvas.LyricsLayoutCalculator
import com.tx24.spicyplayer.lyrics.spicy.parser.TtmlLyricsParser
import com.tx24.spicyplayer.lyrics.spicy.romanization.RomanizationService
import com.tx24.spicyplayer.network.data.RemoteLyricsSelection
import com.tx24.spicyplayer.network.data.RemoteLyricsQuality
import com.tx24.spicyplayer.lyrics.spicy.models.LyricsType

/** Keeps the selected provider's best format and provenance through to the renderer. */
internal object RemoteLyricsAdapter {
    private val lrcStamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")

    fun render(selection: RemoteLyricsSelection, durationMs: Long): LyricsState.Ready {
        val payload = selection.payload
        val attribution = payload.attribution
        val parsedTtml = payload.ttmlLyrics?.takeIf(String::isNotBlank)?.let { ttml ->
            TtmlLyricsParser.parse(ttml.byteInputStream())
        }
        val ttmlLines = parsedTtml?.lines?.let { parsedLines ->
            parsedLines.map { line ->
                TimedLine(
                    startMs = line.startMs,
                    endMs = line.endMs,
                    words = line.words.map { word ->
                        TimedWord(word.text, word.startMs, word.endMs, word.isPartOfWord, word.romanizedText)
                    },
                    role = line.role,
                    groupId = line.groupId,
                    agent = line.agent,
                    oppositeAligned = line.oppositeAligned,
                )
            }.takeIf(List<TimedLine>::isNotEmpty)
        }
        // Plain text becomes untimed lines, rendered by the lyrics view's static mode like the
        // reference, so it gets the same layout, romanization and credits as synced lyrics.
        val parsed = (ttmlLines ?: parseLrc(payload.syncedLyrics, durationMs)).ifEmpty { staticLines(payload.plainLyrics) }
        // Word-synced text is already split per syllable; line-timed text can arrive as one
        // word per line, which the layout (it wraps between words only) can never wrap.
        val unromanized = if (selection.quality == RemoteLyricsQuality.WORD_SYNCED) parsed else parsed.map(::wrappable)
        require(unromanized.isNotEmpty()) { "Selected lyrics contain no displayable text" }
        // On-device romanization fills only the words the source left unromanized.
        val computed = RomanizationService.romanize(unromanized.map { line -> line.words.map(TimedWord::text) })
        val lines = unromanized.mapIndexed { l, line ->
            line.copy(words = line.words.mapIndexed { w, word -> word.copy(romanized = word.romanized ?: computed[l][w]) })
        }
        return LyricsState.Ready(
            lines = lines,
            provider = attribution?.providerName ?: selection.source.displayName,
            source = attribution?.originName ?: selection.source.displayName,
            maker = attribution?.maker,
            uploader = attribution?.uploader,
            songwriters = (attribution?.songwriters.orEmpty() + parsedTtml?.songwriters.orEmpty())
                .map { decodeEntities(it).trim() }.filter(String::isNotBlank).distinct(),
            lyricsType = when (selection.quality) {
                RemoteLyricsQuality.WORD_SYNCED -> LyricsType.Syllable
                RemoteLyricsQuality.LINE_SYNCED -> LyricsType.Line
                else -> LyricsType.Static
            },
        )
    }

    /** Some sources escape twice ("Tom &amp;amp; Jerry"), so one XML decode still leaves "&amp;". */
    internal fun decodeEntities(value: String): String = value
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&")

    /** Splits at spaces, and CJK per character (glued), so long line-timed lines can wrap. */
    internal fun wrappable(line: TimedLine): TimedLine = line.copy(words = line.words.flatMap { word ->
        val pieces = word.text.split(Regex("\\s+")).filter(String::isNotEmpty).flatMapIndexed { tokenIdx, token ->
            val runs = mutableListOf<String>()
            for (c in token) {
                if (LyricsLayoutCalculator.isCjk(c) || runs.isEmpty() || LyricsLayoutCalculator.isCjk(runs.last().last())) runs += c.toString()
                else runs[runs.lastIndex] += c
            }
            runs.mapIndexed { runIdx, run -> run to if (runIdx > 0) true else tokenIdx == 0 && word.attached }
        }
        if (pieces.size <= 1) listOf(word)
        else pieces.map { (text, attached) -> TimedWord(text, word.startMs, word.endMs, attached) }
    })

    private fun staticLines(plain: String?): List<TimedLine> = plain.orEmpty().lines()
        .map(String::trim).filter(String::isNotEmpty)
        .map { text -> TimedLine(0L, 0L, listOf(TimedWord(text, 0L, 0L, false))) }

    private fun parseLrc(raw: String?, durationMs: Long): List<TimedLine> {
        val entries = raw.orEmpty().lineSequence().flatMap { line ->
            val text = line.replace(lrcStamp, "").trim()
            if (text.isBlank()) emptySequence() else lrcStamp.findAll(line).map { match ->
                val minutes = match.groupValues[1].toLong()
                val seconds = match.groupValues[2].toLong()
                val fraction = match.groupValues[3].let { if (it.isBlank()) 0L else it.padEnd(3, '0').take(3).toLong() }
                ((minutes * 60 + seconds) * 1_000 + fraction) to text
            }
        }.distinct().sortedBy(Pair<Long, String>::first).toList()
        return entries.mapIndexed { index, (start, text) ->
            val end = entries.getOrNull(index + 1)?.first
                ?: durationMs.takeIf { it > start }
                ?: start + 4_000
            TimedLine(start, end.coerceAtLeast(start + 1), listOf(TimedWord(text, start, end, false)))
        }
    }
}
