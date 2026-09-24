package com.tx24.spicyplayer.network.data

/**
 * Turns what a player reports into names a lyrics search can use. Players of local files often
 * report the file name as the title ("03 - Artist - Song (Official Video).mp3") with no artist
 * or "<unknown>"; tagged tracks pass through unchanged.
 */
object TrackNameCleaner {
    data class Names(val title: String, val artist: String)

    private val extension = Regex(
        "\\.(mp3|flac|m4a|aac|ogg|oga|opus|wav|wma|aiff?|alac|ape|wv|mka|webm|mp4|m4v|mkv|3gp)$",
        RegexOption.IGNORE_CASE,
    )
    // "03 - ", "1-03. ", "07) ", "01 " (a bare number only when zero-padded, so "7 rings" stays).
    private val trackNumber = Regex("^(?:\\d{1,3}(?:-\\d{1,3})?\\s*[-.)_]\\s*|0\\d\\s+)(?=\\S)")
    // Download-site leftovers: "(Official Music Video)", "[Lyrics]", "(HD)". Kept narrow so real
    // title brackets ("(Remix)", "(feat. X)", "(Taylor's Version)") survive.
    private val junk = Regex(
        "\\s*[(\\[](?:official\\s+)?(?:music\\s+|lyric\\s+|lyrics\\s+)?(?:video|audio|visualizer|lyrics?|mv|hd|hq|4k)[)\\]]",
        RegexOption.IGNORE_CASE,
    )
    private val artistSeparator = Regex("\\s+[-–—]\\s+")
    private val unknownArtists = setOf("", "<unknown>", "unknown", "unknown artist")

    fun clean(title: String, artist: String): Names {
        val rawTitle = title.trim()
        val artistKnown = artist.trim().lowercase() !in unknownArtists
        val fileName = extension.containsMatchIn(rawTitle)
        if (artistKnown && !fileName) return Names(rawTitle, artist.trim())

        var name = rawTitle.replace(extension, "")
        // "Artist_-_Song" style names use underscores for every space.
        if (fileName && ' ' !in name) name = name.replace('_', ' ')
        name = name.replace(trackNumber, "").replace(junk, "").trim()

        if (artistKnown) return Names(name.ifEmpty { rawTitle }, artist.trim())
        val parts = name.split(artistSeparator, limit = 2)
        return if (parts.size == 2 && parts.all(String::isNotBlank)) {
            Names(title = parts[1].trim(), artist = parts[0].trim())
        } else {
            Names(title = name.ifEmpty { rawTitle }, artist = "")
        }
    }
}
