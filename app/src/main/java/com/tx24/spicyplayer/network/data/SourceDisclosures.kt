package com.tx24.spicyplayer.network.data

/**
 * What switching a source on shares, said once before it's switched on. Out of the box only
 * Spicy Lyrics and the open databases that invite apps to use them (LRCLIB, AMLL TTML DB) are on;
 * every other source asks first.
 */
data class SourceDisclosure(
    /** The source or setting it's for. */
    val id: String,
    val name: String,
    /** Who gets asked, in plain words. */
    val recipient: String,
    /** What they get. */
    val sends: String = TITLE_ARTIST,
    /** Anything else worth knowing first. */
    val note: String? = null,
) {
    val description: String
        get() = buildString {
            append("Turning this on sends $sends to $recipient each time it looks for lyrics. ")
            append("No account or device details are sent. ")
            append("This source isn't run by Spicy Lyrics, so its own terms apply.")
            if (note != null) append(" ").append(note)
        }
}

private const val TITLE_ARTIST = "the song's title and artist"
private const val WITH_LENGTH = "the song's title, artist and length"

object SourceDisclosures {
    /** The setting that lays human-written romanizations from Genius over the lyrics. */
    const val GENIUS_ROMANIZATION_ID = "genius_romanization"

    private val byId = listOf(
        SourceDisclosure("unison", "Unison", "Unison (unison.boidu.dev), a community lyrics service"),
        SourceDisclosure("rmm_revival", "RMM Revival", "Apple's iTunes search and RMM Revival (rmmreviv.al)"),
        SourceDisclosure("lrc_red", "lrc.red", "lrc.red, which relays Apple Music lyrics"),
        SourceDisclosure("bini_lyrics", "BiniLyrics", "BiniLyrics (binimum.org), which relays Apple Music lyrics", WITH_LENGTH),
        SourceDisclosure("kugou", "Kugou", "Kugou's servers in China", WITH_LENGTH),
        SourceDisclosure("qq_music", "QQ Music", "Tencent's QQ Music servers in China"),
        SourceDisclosure("kuwo", "Kuwo", "Kuwo's servers in China"),
        SourceDisclosure("netease", "NetEase", "NetEase Cloud Music's servers in China"),
        SourceDisclosure("lrcmux", "LRCMux", "LRCMux (lrcmux.dev), which relays Musixmatch lyrics", WITH_LENGTH),
        SourceDisclosure("genius", "Genius", "Genius (genius.com)"),
        SourceDisclosure("youtube_transcript", "YouTube transcripts", "YouTube's search (youtube.com)"),
        SourceDisclosure(GENIUS_ROMANIZATION_ID, "Human romanizations", "Genius (genius.com)",
            note = "It's only asked for songs in a script that gets romanized."),
    ).associateBy(SourceDisclosure::id)

    /**
     * The disclosure for [descriptor], or null when it needs none: sources on by default, rank-only
     * slots that are never asked, and the user's own sources, whose address they typed.
     */
    fun forSource(descriptor: LyricsSourceDescriptor): SourceDisclosure? = when {
        descriptor.defaultEnabled || descriptor.rankOnly || descriptor.upstreamFamily == "custom" -> null
        else -> byId[descriptor.id] ?: SourceDisclosure(descriptor.id, descriptor.displayName, descriptor.displayName)
    }

    fun forId(id: String): SourceDisclosure? = byId[id]
}
