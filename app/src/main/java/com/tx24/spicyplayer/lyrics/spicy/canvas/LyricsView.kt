package com.tx24.spicyplayer.lyrics.spicy.canvas

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.drawable.toBitmap
import coil.Coil
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalDensity
import com.tx24.spicyplayer.lyrics.fadingEdge
import com.tx24.spicyplayer.lyrics.spicy.RenderConfig
import com.tx24.spicyplayer.lyrics.spicy.animation.LineAnimState
import com.tx24.spicyplayer.lyrics.spicy.animation.LyricsAnimator
import com.tx24.spicyplayer.lyrics.spicy.models.Line
import com.tx24.spicyplayer.lyrics.spicy.models.LyricsType
import com.tx24.spicyplayer.lyrics.spicy.models.FooterLine
import com.tx24.spicyplayer.lyrics.spicy.models.LyricsFooter
import com.tx24.spicyplayer.lyrics.spicy.parser.LetterSynthesizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * The main lyrics display component representing the split architecture.
 * Delegates text measurement to LyricsLayoutCalculator,
 * scroll physics to ScrollManager,
 * and drawing to LyricsRenderer extensions.
 *
 * @param lines The list of lyric lines to display.
 * @param currentTimeMs The current playback time in milliseconds.
 * @param onSeekWord Callback triggered when a user taps a line to seek to its start time.
 */
@Composable
fun LyricsView(
    lines: List<Line>,
    documentId: String,
    footer: LyricsFooter = LyricsFooter(),
    currentTimeMs: () -> Long,
    onSeekWord: (Long) -> Unit,
    modifier: Modifier = Modifier,
    fontSizeScale: Float = 1.0f,
    config: RenderConfig = RenderConfig.FULL,
    lyricsType: LyricsType = LyricsType.Syllable,
    romanize: Boolean = false,
    focusAnchorFraction: Float = 0.25f,
    // When set, the active line's top (not its centre) is kept this far below the view's top,
    // ("Top" scrolling); focusAnchorFraction is then unused.
    activeLineTopPx: Float? = null,
    // Without activeLineTopPx, the active line's centre sits this far above the focus anchor.
    focusLiftPx: Float = 0f,
    // Invoked with the raw frame-nanos at the top of this view's own animation frame, before
    // currentTimeMs() is read. Lets a caller (e.g. the playback clock smoothing in
    // SpicyLyricsPlayer) piggyback on this view's single withFrameNanos loop instead of running
    // a second, independent one — halving the Choreographer callbacks registered per lyrics
    // screen. Optional so other/future callers aren't forced to supply one.
    onFrameTick: ((Long) -> Unit)? = null,
    // Paused, the frame loop rests once nothing moves, looking in a few times a second for a seek.
    isPlaying: Boolean = true,
) {
    val textMeasurer = rememberTextMeasurer()
    // What is on screen: lines and their layouts, swapped together once new layouts are measured.
    // Until then the previous lyrics stay up, so a source switch mid-song (a better answer
    // arriving) replaces them in one frame instead of blanking the view while it measures.
    var shown by remember { mutableStateOf<ShownLyrics?>(null) }
    val shownId = shown?.documentId
    val lineLayouts = shown?.layouts.orEmpty()
    // The type and credits of what is shown, not of what is still being measured.
    val incomingType = lyricsType
    val incomingFooter = footer
    val lyricsType = shown?.lyricsType ?: incomingType
    val footer = shown?.footer ?: incomingFooter
    val coroutineScope = rememberCoroutineScope()

    // Letter synthesis only reads the mode-dependent thresholds, not the motion boost.
    val letterConfig = config.copy(wordMotionBoost = 1f)
    // Measured lyrics for these lines, per variant (romanized or not, width, size...). The other
    // romanization variant is measured ahead, so the romanize button swaps in a finished layout.
    val measuredCache = remember(lines) { HashMap<MeasureKey, MeasuredLyrics>() }

    val animator = remember(shownId) { LyricsAnimator(coroutineScope, config) }
    LaunchedEffect(config) { animator.config = config }
    val isStatic = lyricsType == LyricsType.Static

    // Keep the latest time provider without recomposing on every position tick: the frame loop
    // invokes it off-composition (inside withFrameNanos), so the changing clock never re-runs this
    // composable's body — only the Canvas redraws when the derived anim state actually changes.
    val currentTimeProvider by rememberUpdatedState(currentTimeMs)
    val linesUpdated by rememberUpdatedState(shown?.lines.orEmpty())
    val lineLayoutsUpdated by rememberUpdatedState(lineLayouts)
    val onFrameTickUpdated by rememberUpdatedState(onFrameTick)
    val isPlayingUpdated by rememberUpdatedState(isPlaying)

    val scrollPolicy = remember(shownId) { ScrollPolicyController() }
    // Read by the drag handler, written by the frame loop.
    val contentHeightForDrag = remember(shownId) { FloatArray(1) }
    val density = LocalDensity.current
    val scrollManager = remember(shownId) { ScrollManager().also { it.reset() } }
    // Wakes a resting frame loop at once (a drag or tap), rather than at its next look.
    val wake = remember(shownId) { Channel<Unit>(Channel.CONFLATED) }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    BoxWithConstraints(modifier = modifier.fillMaxSize().clipToBounds()) {
        val canvasWidth = constraints.maxWidth.toFloat()
        val canvasHeight = constraints.maxHeight.toFloat()
        // Compact fullscreen keeps the active lyric in the upper portion of the viewport.
        val centerY = activeLineTopPx ?: (ScrollPolicyController.anchorY(canvasHeight, focusAnchorFraction) - focusLiftPx)
        val alignTop = activeLineTopPx != null
        // The frame loop and tap handler outlive a change of anchor (the header shown or hidden
        // mid-song), so they read the latest one; a change also wakes a resting loop to re-anchor.
        val centerYUpdated by rememberUpdatedState(centerY)
        val alignTopUpdated by rememberUpdatedState(alignTop)
        val canvasHeightUpdated by rememberUpdatedState(canvasHeight)
        LaunchedEffect(centerY, alignTop) { wake.trySend(Unit) }
        val footerMetrics = remember(canvasWidth, density.density, fontSizeScale, lyricsType) {
            LyricsLayoutMetrics(canvasWidth, density.density, lyricsType, fontSizeScale)
        }
        val footerSlot = footerMetrics.contentSlot(false, false, false)
        val lineGapUpdated by rememberUpdatedState(footerMetrics.lineGapPx)
        val rowHeightUpdated by rememberUpdatedState(footerMetrics.lineHeightPx(footerMetrics.baseFontSizeSp))
        // Matched to a Spicy Lyrics screenshot, relative to the lyric size L: "Written by" 0.47L,
        // the rest ~0.34L (its Mixed.css), all in the lyrics font; gaps ~0.45L above the block
        // and 0.2-0.3L between rows; the avatar ~1.4x the credit text, right after the name.
        // CREDIT_SCALE enlarges it all a little for a phone screen. L is the synced lyric size
        // whatever the lyrics type, so static lyrics (drawn smaller) get the same credits.
        val creditBaseSp = remember(canvasWidth, density.density, fontSizeScale) {
            LyricsLayoutMetrics(canvasWidth, density.density, LyricsType.Syllable, fontSizeScale).baseFontSizeSp
        }
        scrollManager.pxPerReferencePx = creditBaseSp * density.density / REFERENCE_LYRIC_SIZE_PX
        val footerLayouts = remember(footer, creditBaseSp, footerSlot.widthPx, canvasWidth, LyricsLayoutCalculator.useSystemFont) {
            val lyricPx = creditBaseSp * density.density
            val constraints = Constraints(maxWidth = footerSlot.widthPx.roundToInt().coerceAtLeast(1))
            footer.lines().mapIndexed { index, line ->
                val (size, alpha, margin) = when (line.kind) {
                    FooterLine.Kind.WRITERS -> Triple(0.47f, 0.7f, 0.25f)
                    FooterLine.Kind.PROVIDER -> Triple(0.34f, 0.55f, 0.25f)
                    FooterLine.Kind.NOTE -> Triple(0.35f, 0.65f, 0.3f)
                    FooterLine.Kind.CONTRIBUTOR -> Triple(0.34f, 1f, 0.2f)
                }
                val fontSp = creditBaseSp * size * CREDIT_SCALE
                val text = if (line.kind == FooterLine.Kind.CONTRIBUTOR && line.label != null && line.name != null) {
                    // "Made by " dimmer, then the bold, underlined "@name" (.song-info-profile-section).
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = Color.White.copy(alpha = 0.6f))) { append("${line.label} ") }
                        withStyle(SpanStyle(
                            color = Color.White.copy(alpha = 0.75f),
                            fontWeight = FontWeight.Bold,
                            textDecoration = TextDecoration.Underline,
                        )) { append("@${line.name}") }
                    }
                } else AnnotatedString(line.text)
                val avatar = if (line.avatarUrl != null) fontSp * density.density * 1.4f else 0f
                val layout = textMeasurer.measure(
                    text,
                    TextStyle(
                        fontFamily = LyricsLayoutCalculator.spicyFontFamily,
                        fontSize = fontSp.sp,
                        fontWeight = if (line.kind == FooterLine.Kind.NOTE) FontWeight.Bold else FontWeight.SemiBold,
                    ),
                    constraints = Constraints(maxWidth = (constraints.maxWidth - avatar).roundToInt().coerceAtLeast(1)),
                )
                FooterRow(
                    line, layout, alpha,
                    marginTop = lyricPx * (if (index == 0) 0.45f else margin),
                    height = maxOf(layout.size.height.toFloat(), avatar),
                    avatarSize = avatar,
                    avatarGap = 2f * density.density,
                )
            }
        }
        val footerHeight = footerLayouts.sumOf { (it.marginTop + it.height).toDouble() }.toFloat()
        val footerHeightUpdated by rememberUpdatedState(footerHeight)
        val avatars by produceState(emptyMap<String, ImageBitmap>(), footer) {
            value = footer.lines().mapNotNull(FooterLine::avatarUrl).distinct().mapNotNull { url ->
                loadAvatar(context, url)?.let { url to it }
            }.toMap()
        }
        // Recalculate layouts whenever the lyrics, dimensions, or font size change.
        // A newer key cancels a measurement still running, so only the latest one lands.
        val useSystemFont = LyricsLayoutCalculator.useSystemFont
        LaunchedEffect(lines, letterConfig, canvasWidth, fontSizeScale, romanize, documentId, incomingType, incomingFooter, useSystemFont) {
            suspend fun measure(romanized: Boolean): MeasuredLyrics {
                val key = MeasureKey(letterConfig, canvasWidth, fontSizeScale, romanized, incomingType, useSystemFont)
                measuredCache[key]?.let { return it }
                return withContext(Dispatchers.Default) {
                    // Per-letter emphasis for held words (mode-dependent thresholds, romanized
                    // display). Syllable mode only; Line/Static never letter-split.
                    val display = if (incomingType == LyricsType.Syllable) LetterSynthesizer.apply(lines, letterConfig, romanized) else lines
                    MeasuredLyrics(display, LyricsLayoutCalculator.calculateLineLayouts(
                        display, canvasWidth, textMeasurer, density.density, incomingType, fontSizeScale, romanized, letterConfig.isSimple,
                    ))
                }.also { measuredCache[key] = it }
            }
            val measured = measure(romanize)
            shown = ShownLyrics(documentId, measured.lines, measured.layouts, incomingType, incomingFooter)
            if (lines.any { line -> line.words.any { it.romanizedText != null } }) measure(!romanize)
        }

        if (lineLayouts.isEmpty()) return@BoxWithConstraints

        var animStates by remember { mutableStateOf<List<LineAnimState>>(emptyList()) }
        var dynamicYOffsets by remember { mutableStateOf(FloatArray(0)) }
        var lastFrameTimeNanos by remember { mutableLongStateOf(0L) }
        
        // The high-frequency animation loop, restarted with each new document's animator and scroll.
        LaunchedEffect(shownId) {
            // Reused per-frame scratch for the dynamic Y offsets: filled every frame but only
            // published to state when its contents actually change, so a paused/idle screen stops
            // invalidating the Canvas (a fresh FloatArray each frame was forcing a redraw via array
            // identity-equality even when nothing moved).
            var dynamicYScratch = FloatArray(0)
            var settledYScratch = FloatArray(0)
            var stillFrames = 0
            var lastLayouts: List<LineLayout>? = null
            while (true) {
                if (stillFrames >= REST_AFTER_STILL_FRAMES) {
                    withTimeoutOrNull(REST_POLL_MS) { wake.receive() }
                }
                withFrameNanos { frameTimeNanos ->
                    val previousStates = animStates
                    val previousOffsets = dynamicYOffsets
                    val previousScroll = scrollManager.animScrollY
                    onFrameTickUpdated?.invoke(frameTimeNanos)

                    val currentLayouts = lineLayoutsUpdated
                    val currentLines = linesUpdated
                    // The same lyrics laid out again (romanized, resized): seen here, on the frame
                    // that first draws the new layouts, so the scroll jumps with them.
                    if (lastLayouts != null && currentLayouts !== lastLayouts) scrollManager.onRelayout()
                    lastLayouts = currentLayouts
                    val currentTime = currentTimeProvider()

                    // Unclamped: springs integrate analytically over any dt.
                    val deltaTime = if (lastFrameTimeNanos == 0L) {
                        0.016f
                    } else {
                        ((frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000f).coerceAtLeast(0f)
                    }
                    lastFrameTimeNanos = frameTimeNanos

                    if (currentLayouts.size == currentLines.size && currentLines.isNotEmpty()) {
                        // 1. Step the animator for visual properties (scale, opacity, glow).
                        animStates = animator.animate(currentLines, currentTime, deltaTime, scrollManager.hideLineBlur, lyricsType)

                        // 1.5 Calculate dynamic Y offsets based on interlude scales.
                        var accumulatedY = 0f
                        if (dynamicYScratch.size != currentLayouts.size) {
                            dynamicYScratch = FloatArray(currentLayouts.size)
                        }
                        val newDynamicYOffsets = dynamicYScratch

                        // Where each line settles once running interlude animations finish; the
                        // scroll aims here so it doesn't chase an opening or closing gap.
                        var settledY = 0f
                        if (settledYScratch.size != currentLayouts.size) {
                            settledYScratch = FloatArray(currentLayouts.size)
                        }
                        for (i in currentLayouts.indices) {
                            val layout = currentLayouts[i]
                            val state = animStates.getOrNull(i)

                            if (layout.isInterlude) {
                                val line = layout.line
                                val open = if (currentTime >= line.startMs &&
                                    currentTime <= line.endMs - LyricsAnimator.PRE_HIDDEN_DOT_LINE_MS) 1f else 0f
                                settledYScratch[i] = layout.yOffset + settledY + rowHeightUpdated / 2f * open
                                settledY += (rowHeightUpdated + lineGapUpdated) * open
                            } else {
                                settledYScratch[i] = layout.yOffset + settledY
                            }

                            if (layout.isInterlude) {
                                // An open interlude is a full lyric row plus the normal gap;
                                // the dots are drawn centred in that row.
                                val scale = state?.scale?.coerceIn(0f, 1f) ?: 0f
                                newDynamicYOffsets[i] = layout.yOffset + accumulatedY + rowHeightUpdated / 2f * scale
                                accumulatedY += (rowHeightUpdated + lineGapUpdated) * scale
                            } else {
                                newDynamicYOffsets[i] = layout.yOffset + accumulatedY
                            }
                        }
                        // Publish only when the offsets actually changed (i.e. an interlude is
                        // expanding/collapsing); otherwise the Canvas keeps the last array and
                        // isn't invalidated. copyOf() so the published snapshot is immutable while
                        // the scratch keeps mutating next frame.
                        if (!newDynamicYOffsets.contentEquals(dynamicYOffsets)) {
                            dynamicYOffsets = newDynamicYOffsets.copyOf()
                        }

                        // 2. Resolve the lead/background overlap policy, then anchor
                        // that one line: its centre at the focus point, or its top (compact mode).
                        val decision = scrollPolicy.decide(currentLines, currentTime)
                        // A lead with no words of its own (the line is only background vocals)
                        // has no height; its background vocals stand in for it.
                        val targetIndex = decision.targetIndex?.let { index ->
                            val next = currentLayouts.getOrNull(index + 1)
                            if (currentLayouts[index].line.words.isEmpty() && next != null && next.isBackground &&
                                next.line.groupId == currentLayouts[index].line.groupId) index + 1 else index
                        }
                        var targetY: Float? = targetIndex?.let { index ->
                            // An interlude's offset is already the centre of its dots, in a row
                            // one lyric line tall.
                            val half = when {
                                alignTopUpdated -> if (currentLayouts[index].isInterlude) -rowHeightUpdated / 2f else 0f
                                currentLayouts[index].isInterlude -> 0f
                                else -> currentLayouts[index].height / 2f
                            }
                            -(settledYScratch[index] + half)
                        }
                        val targetVisiblePx = targetIndex?.let { index ->
                            val top = centerYUpdated + scrollManager.animScrollY + newDynamicYOffsets[index]
                            val bottom = top + currentLayouts[index].height
                            (minOf(bottom, canvasHeightUpdated) - maxOf(top, 0f)).coerceAtLeast(0f)
                        } ?: Float.POSITIVE_INFINITY

                        // 3. Step the scroll spring and handle user overrides.
                        val lastLayout = currentLayouts.lastOrNull()
                        // Credits scroll into reach too, most visibly when static lyrics are scrolled by hand.
                        val totalContentHeight = (lastLayout?.yOffset ?: 0f) + (lastLayout?.height ?: 0f) + accumulatedY +
                            footerHeightUpdated

                        // Static lyrics have no timing to follow: leave scrolling entirely to the user.
                        if (isStatic) targetY = null
                        contentHeightForDrag[0] = totalContentHeight
                        scrollManager.updateScroll(
                            deltaTime, totalContentHeight, targetIndex.takeIf { targetY != null }, targetY,
                            snap = decision.motion == ScrollMotion.SNAP,
                            targetVisiblePx = targetVisiblePx,
                        )
                    }
                    val still = !isPlayingUpdated && !scrollManager.isUserScrolling &&
                        animStates == previousStates && dynamicYOffsets === previousOffsets &&
                        scrollManager.animScrollY == previousScroll
                    stillFrames = if (still) stillFrames + 1 else 0
                }
            }
        }

        // The sole renderer mask: transparent through 16dp, ramping to opaque at 64dp,
        // with a symmetric bottom edge.
        val maskStops = lyricsMaskStops(canvasHeight, density.density)
        val fadeBrush = remember(maskStops) {
            Brush.verticalGradient(
                0f to Color.Transparent,
                maskStops.outerTop to Color.Transparent,
                maskStops.innerTop to Color.Black,
                maskStops.innerBottom to Color.Black,
                maskStops.outerBottom to Color.Transparent,
                1f to Color.Transparent,
            )
        }

        // Where each credit row sits below the last line, in content space (before scrolling).
        // Shared by drawing and tapping so a tap always lands on what was drawn.
        fun footerRowTops(): List<Float> {
            var y = lineLayouts.lastOrNull()?.let { layout ->
                dynamicYOffsets.getOrElse(lineLayouts.lastIndex) { layout.yOffset } + layout.height
            } ?: 0f
            return footerLayouts.map { row -> (y + row.marginTop).also { y = it + row.height } }
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .fadingEdge(fadeBrush)
                .pointerInput(scrollManager) {
                    // Interaction: Dragging.
                    detectDragGestures(
                        onDragStart = { scrollManager.onDragStart(); wake.trySend(Unit) },
                        onDragEnd = { scrollManager.onDragEnd() },
                        onDragCancel = { scrollManager.onDragEnd() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            scrollManager.onDrag(dragAmount.y, contentHeightForDrag[0])
                        }
                    )
                }
                .pointerInput(isStatic, footerLayouts, shown) {
                    detectTapGestures { tapOffset ->
                        val currentScrollY = scrollManager.animScrollY
                        val adjustedTapY = tapOffset.y - (centerYUpdated + currentScrollY)

                        // A credit with a profile opens it.
                        footerLayouts.zip(footerRowTops()).firstOrNull { (row, top) ->
                            row.line.profileUrl != null && adjustedTapY in top..(top + row.height)
                        }?.let { (row, _) ->
                            runCatching { uriHandler.openUri(row.line.profileUrl!!) }
                            return@detectTapGestures
                        }

                        // Tapping a line seeks to it. Static lyrics are not seekable.
                        if (isStatic) return@detectTapGestures

                        for (i in lineLayouts.indices) {
                            val layout = lineLayouts[i]
                            val layoutDynamicY = dynamicYOffsets.getOrElse(i) { layout.yOffset }
                            if (adjustedTapY >= layoutDynamicY && adjustedTapY <= layoutDynamicY + layout.height) {
                                if (layout.isInterlude || layout.isSongwriter) continue
                                if (layout.line.words.isNotEmpty()) {
                                    onSeekWord(layout.line.startMs)
                                    scrollManager.onSeek()
                                    wake.trySend(Unit)
                                }
                                return@detectTapGestures
                            }
                        }
                    }
                }
        ) {
            val scrollOffset = centerY + scrollManager.animScrollY

            lineLayouts.forEachIndexed { lineIdx, layout ->
                val lineAnim = animStates.getOrNull(lineIdx) ?: return@forEachIndexed
                val dynamicY = dynamicYOffsets.getOrElse(lineIdx) { layout.yOffset }

                // Optimization: Don't draw invisible lines.
                if (lineAnim.opacity <= 0.01f) return@forEachIndexed

                // Optimization: Don't draw lines off-screen.
                val lineScreenY = scrollOffset + dynamicY
                if (lineScreenY < -layout.height * 3 || lineScreenY > canvasHeight + layout.height * 3) {
                    return@forEachIndexed
                }

                val lineStartX = getLineStartX(layout)

                when {
                    layout.isInterlude -> drawInterludeGroup(layout, lineAnim, lineStartX, scrollOffset, dynamicY)
                    lyricsType == LyricsType.Static -> drawStaticLine(layout, lineAnim, lineStartX, scrollOffset, dynamicY)
                    lyricsType == LyricsType.Line -> drawLineModeLine(layout, lineAnim, lineStartX, scrollOffset, dynamicY, config)
                    // Minimal Lyrics Mode shrinks inactive lines (a CSS `scale`: paint only, no reflow)
                    // about their start edge, like line-synced lines.
                    lineAnim.scale != 1f -> withTransform({
                        scale(lineAnim.scale, lineAnim.scale, Offset(
                            if (layout.isRightAligned) lineStartX + layout.totalWidth else lineStartX,
                            dynamicY + scrollOffset + layout.height / 2f,
                        ))
                    }) { drawStandardLine(layout, lineAnim, lineStartX, scrollOffset, dynamicY, config) }
                    else -> drawStandardLine(layout, lineAnim, lineStartX, scrollOffset, dynamicY, config)
                }
            }

            footerLayouts.zip(footerRowTops()).forEach { (row, top) ->
                val y = top + scrollOffset
                val x = footerSlot.startPx
                val textHeight = row.text.size.height.toFloat()
                drawText(
                    textLayoutResult = row.text,
                    color = Color.White,
                    alpha = row.alpha,
                    topLeft = Offset(x, y + (row.height - textHeight) / 2f),
                )
                // The avatar follows the name, a 24px circle.
                row.line.avatarUrl?.let { avatars[it] }?.let { avatar ->
                    val ax = x + row.text.size.width + row.avatarGap
                    val ay = y + (row.height - row.avatarSize) / 2f
                    clipPath(Path().apply { addOval(Rect(ax, ay, ax + row.avatarSize, ay + row.avatarSize)) }) {
                        drawImage(
                            avatar,
                            dstOffset = IntOffset(ax.roundToInt(), ay.roundToInt()),
                            dstSize = IntSize(row.avatarSize.roundToInt(), row.avatarSize.roundToInt()),
                        )
                    }
                }
            }
        }
    }
}

private data class MeasureKey(
    val config: RenderConfig,
    val widthPx: Float,
    val fontSizeScale: Float,
    val romanize: Boolean,
    val type: LyricsType,
    val systemFont: Boolean,
)

private class MeasuredLyrics(val lines: List<Line>, val layouts: List<LineLayout>)

private class ShownLyrics(
    val documentId: String,
    val lines: List<Line>,
    val layouts: List<LineLayout>,
    val lyricsType: LyricsType,
    val footer: LyricsFooter,
)

/** Paused and this many frames without a change, the frame loop rests. */
private const val REST_AFTER_STILL_FRAMES = 30
/** How often a resting frame loop looks for a change (a seek while paused). */
private const val REST_POLL_MS = 150L

/** Scales the credits up from their desktop proportions, for a phone screen. */
private const val CREDIT_SCALE = 1.15f
/** The desktop lyric size (its 3.5rem cap), in CSS px. */
private const val REFERENCE_LYRIC_SIZE_PX = 56f

private class FooterRow(
    val line: FooterLine,
    val text: TextLayoutResult,
    val alpha: Float,
    val marginTop: Float,
    val height: Float,
    val avatarSize: Float,
    val avatarGap: Float,
)

private suspend fun loadAvatar(context: Context, url: String): ImageBitmap? = try {
    val request = ImageRequest.Builder(context).data(url).size(128).allowHardware(false).build()
    (Coil.imageLoader(context).execute(request) as? SuccessResult)?.drawable?.toBitmap()?.asImageBitmap()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}

/**
 * Calculates the horizontal starting position of a line based on its alignment and the presence of duets.
 */
private fun getLineStartX(
    layout: LineLayout,
): Float {
    return if (layout.isRightAligned) {
        layout.contentStartX + layout.contentWidth - layout.totalWidth
    } else {
        layout.contentStartX
    }
}
