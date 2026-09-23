package com.tx24.spicyplayer.latencytest

data class TimedWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val attached: Boolean,
)

data class TimedLine(
    val startMs: Long,
    val endMs: Long,
    val words: List<TimedWord>,
    val role: com.tx24.spicyplayer.lyrics.spicy.models.LineRole = com.tx24.spicyplayer.lyrics.spicy.models.LineRole.LEAD,
    val groupId: Int? = null,
    val agent: String? = null,
    val oppositeAligned: Boolean = false,
)

sealed interface LyricsState {
    data object Idle : LyricsState
    data object Loading : LyricsState
    data class Ready(
        val lines: List<TimedLine>,
        val source: String?,
        val maker: String?,
        val uploader: String?,
        val songwriters: List<String>,
        val provider: String = "Spicy Lyrics",
        val plainText: String? = null,
        val lyricsType: com.tx24.spicyplayer.lyrics.spicy.models.LyricsType = com.tx24.spicyplayer.lyrics.spicy.models.LyricsType.Syllable,
    ) : LyricsState
    data class Error(val message: String) : LyricsState
}
