package com.tx24.spicyplayer.ui.nowplaying

/**
 * Geometry of Spicy Lyrics' compact-mode NowBar (the song header across the top of the page),
 * resolved for one page size. Every ratio names the SL rule it comes from (`ContentBox.css`,
 * `#SpicyLyricsPage.CompactMode ...`); SL's `cqh`/`cqw` are percentages of the nearest query
 * container, which is the page for the bar itself and the bar for everything inside it.
 *
 * Departures from SL, chosen on a phone: the cover sits as far below the top as the lyrics are
 * from the side (SL's 6cqh left a gap), the song text is centred on the cover (SL's is
 * bottom-aligned), and there is more room between cover and text.
 *
 * Pure arithmetic in pixels so it can move to a shared module later.
 *
 * @param pageWidthPx width of the whole lyrics page (SL's `#SpicyLyricsPage`).
 * @param pageHeightPx height of the whole lyrics page.
 * @param density px per dp.
 * @param lyricFontSizeSp the renderer's base lyric size for this page (see `LyricsLayoutMetrics`).
 */
data class CompactHeaderMetrics(
    val pageWidthPx: Float,
    val pageHeightPx: Float,
    val density: Float,
    val lyricFontSizeSp: Float,
) {
    private val pageWidthDp = pageWidthPx / density.coerceAtLeast(0.01f)

    /**
     * `.Header { margin-left: var(--LyricsLeftSidePadding) }`: the artwork lines up with the
     * lyrics' left edge, which in this renderer is the 5% content inset (`LyricsLayoutMetrics`).
     */
    val contentStartPx = pageWidthPx * LYRICS_SIDE_INSET
    val contentEndPx = pageWidthPx * (1f - LYRICS_SIDE_INSET)

    /** Not SL (`margin-top: 6cqh`): the same gap above the cover as beside it. */
    val barTopPx = contentStartPx

    /** `--Compact_NowBarHeight: 15cqh` against the page. */
    val barHeightPx = pageHeightPx * 0.15f

    /** `.Header { --MediaBoxSize: 100cqh }` against the bar: the artwork is as tall as the bar. */
    val artSizePx = barHeightPx

    /** Not SL (`--CompactNowBarHeaderGap: 8cqh`): 13cqh, so the text doesn't crowd the cover. */
    val gapPx = barHeightPx * 0.13f

    /** `.Metadata { left: calc(var(--MediaBoxSize) + var(--CompactNowBarHeaderGap)) }`. */
    val textStartPx = contentStartPx + artSizePx + gapPx
    val textWidthPx = (contentEndPx - textStartPx).coerceAtLeast(0f)

    val barBottomPx = barTopPx + barHeightPx

    /** `.LyricsContainer { margin-top: 21cqh }`, which is where SL's bar ends. */
    val lyricsTopPx = barBottomPx

    /**
     * Type and effect sizes. SL writes them in CSS px next to compact lyrics of
     * `clamp(3rem, 7cqw, 4rem)`; scaling them by our lyric size over that keeps SL's ratio of
     * header text to lyrics at this page width (same rule as the renderer's blur and glow).
     */
    /** Our lyric size over SL's compact one (`clamp(3rem, 7cqw, 4rem)`): CSS px to dp next to the lyrics. */
    val lyricsScale = lyricFontSizeSp / (pageWidthDp * 0.07f).coerceIn(SL_COMPACT_LYRICS_MIN_PX, SL_COMPACT_LYRICS_MAX_PX)
    val scale = lyricsScale * TEXT_BOOST

    /**
     * Where the active line's top sits below the top of the lyrics. Compact mode scrolls "Top",
     * not "Center" (`GetScrollType`): `scrollLyricsToIndex(i, "start", _, -85)` leaves the line's
     * top 85px down the lyrics viewport.
     */
    val activeLineTopPx = 85f * lyricsScale * density

    /** `.SongName { font-size: 2.5rem; line-height: 3rem }`, in sp. */
    val titleSizeSp = 40f * scale
    val titleLineHeightSp = 48f * scale

    /** `.Artists { font-size: 1.5rem; line-height: 2rem }`, in sp. */
    val artistsSizeSp = 24f * scale
    val artistsLineHeightSp = 32f * scale

    /** `.MediaImageContainer { box-shadow: 0 9px 20px 0 rgba(0,0,0,.271) }`, in dp. */
    val artShadowOffsetYDp = 9f * scale
    val artShadowBlurDp = 20f * scale

    /** `.ti_ToImage { box-shadow: -20px 0 21px 0 rgba(0,0,0,.14) }`, in dp. */
    val incomingShadowOffsetXDp = -20f * scale
    val incomingShadowBlurDp = 21f * scale

    /** `.fi_FromImage::before { backdrop-filter: blur(12px) }`, in dp. */
    val outgoingBlurDp = 12f * scale

    companion object {
        /** Content inset of the (non-duet) lyric slot in `LyricsLayoutMetrics.contentSlot`. */
        const val LYRICS_SIDE_INSET = 0.05f

        /** Header type against SL's sizes (1 = SL's). */
        const val TEXT_BOOST = 1f

        /** `.MediaImageContainer { border-radius: 3cqh }`; the square `.MediaBox` is its container. */
        const val ART_CORNER_FRACTION = 0.03f
        private const val SL_COMPACT_LYRICS_MIN_PX = 48f
        private const val SL_COMPACT_LYRICS_MAX_PX = 64f
    }
}
