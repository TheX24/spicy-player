package com.tx24.spicyplayer.latencytest

import com.tx24.spicyplayer.lyrics.spicy.parser.TtmlLyricsParser
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
                        TimedWord(word.text, word.startMs, word.endMs, word.isPartOfWord)
                    },
                    role = line.role,
                    groupId = line.groupId,
                    agent = line.agent,
                    oppositeAligned = line.oppositeAligned,
                )
            }.takeIf(List<TimedLine>::isNotEmpty)
        }
        val lines = ttmlLines ?: parseLrc(payload.syncedLyrics, durationMs)
        val plain = if (lines.isEmpty()) payload.plainLyrics?.takeIf(String::isNotBlank) else null
        require(lines.isNotEmpty() || plain != null) { "Selected lyrics contain no displayable text" }
        return LyricsState.Ready(
            lines = lines,
            plainText = plain,
            provider = attribution?.providerName ?: selection.source.displayName,
            source = attribution?.originName ?: selection.source.displayName,
            maker = attribution?.maker?.username,
            uploader = attribution?.uploader?.username,
            songwriters = (attribution?.songwriters.orEmpty() + parsedTtml?.songwriters.orEmpty())
                .map(String::trim).filter(String::isNotBlank).distinct(),
            lyricsType = when (selection.quality) {
                RemoteLyricsQuality.WORD_SYNCED -> LyricsType.Syllable
                RemoteLyricsQuality.LINE_SYNCED -> LyricsType.Line
                else -> LyricsType.Static
            },
        )
    }

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
