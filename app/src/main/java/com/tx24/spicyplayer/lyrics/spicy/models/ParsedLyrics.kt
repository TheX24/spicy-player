package com.tx24.spicyplayer.lyrics.spicy.models

/** Non-timed attribution displayed after the vocal timeline. */
data class LyricsFooter(
    val songwriters: List<String> = emptyList(),
    val provenance: LyricsProvenance? = null,
    /** Community sync credits (Spicy Lyrics' "Made by" / "Uploaded by"). */
    val maker: String? = null,
    val uploader: String? = null,
) {
    /**
     * The lines shown after the lyrics, mirroring spicy-lyrics' ApplyLyricsCredits and
     * ApplyIsByCommunity: writers, then the community block, else the lyric source.
     */
    fun lines(): List<FooterLine> = buildList {
        if (songwriters.isNotEmpty()) add(FooterLine("Written by: ${songwriters.joinToString(", ")}", FooterLine.Kind.WRITERS))
        if (maker != null || uploader != null) {
            add(FooterLine("These lyrics have been provided by our community", FooterLine.Kind.NOTE))
            maker?.let { add(FooterLine("Made by @$it", FooterLine.Kind.CONTRIBUTOR)) }
            uploader?.let { add(FooterLine("${if (maker != null) "Uploaded by" else "Made by"} @$it", FooterLine.Kind.CONTRIBUTOR)) }
        } else provenance?.let { p ->
            val contributor = p.contributor?.takeIf(String::isNotBlank)?.let { " • $it" }.orEmpty()
            add(FooterLine("Lyrics: ${p.provider}$contributor", FooterLine.Kind.NOTE))
        }
    }
}

data class FooterLine(val text: String, val kind: Kind) {
    enum class Kind { WRITERS, NOTE, CONTRIBUTOR }
}

data class LyricsProvenance(
    val provider: String,
    val contributor: String? = null,
)

/** Normalized lyric input shared by static, line-synced, and syllable-synced renderers. */
data class LyricsDocument(
    val lines: List<Line>,
    val footer: LyricsFooter = LyricsFooter(),
    /** Synchronization granularity; drives which render path is used. */
    val type: LyricsType = LyricsType.Syllable,
    /** Song URI + source + SHA-256 of the raw lyric payload. */
    val documentId: String = "",
) {
    val songwriters: List<String> get() = footer.songwriters

    /** True if any word carries romanized text (from TTML/API metadata or on-device romanization). */
    val hasTransliteration: Boolean
        get() = lines.any { line -> line.words.any { it.romanizedText != null } }
}

typealias ParsedLyrics = LyricsDocument
