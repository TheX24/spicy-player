package com.tx24.spicyplayer.ui.nowplaying

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tx24.spicyplayer.lyrics.spicy.canvas.LyricsLayoutCalculator
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.Alignment
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Constraints
import com.tx24.spicyplayer.ui.controls.SpicyIcons

/** CSS's default `ease` timing, for a plain `transition: opacity 0.24s`. */
private val CssEase = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

/** `MB_anim_enter .75s cubic-bezier(0.835, -0.008, 0.149, 0.866)`. */
private val CoverEnterEasing = CubicBezierEasing(0.835f, -0.008f, 0.149f, 0.866f)

/**
 * The compact song header: square cover on the left, lined up with the
 * lyrics, and the song name and artists centred beside it, scrolling when too long. It occupies the top
 * [CompactHeaderMetrics.barBottomPx] of the page; lyrics start at [CompactHeaderMetrics.lyricsTopPx].
 *
 * [expansion] (0..1) turns it into the full now-playing view with the lyrics hidden:
 * the cover centred and big, the song text centred under it
 * ([ExpandedHeader]). The cover moves across; the compact text fades out and the expanded text in.
 *
 * The cover's gestures: drag it sideways to skip (left for the next song, right for the
 * previous), double-tap it to play or pause.
 */
@Composable
fun CompactNowPlayingHeader(
    info: NowPlayingInfo,
    metrics: CompactHeaderMetrics,
    modifier: Modifier = Modifier,
    expansion: () -> Float = { 0f },
    expanded: ExpandedHeader? = null,
    isPlaying: Boolean = true,
    onPlayPause: () -> Unit = {},
    onSkip: (TrackDirection) -> Unit = {},
) {
    val density = LocalDensity.current
    val artwork = rememberSessionArtwork(info.artwork, info.artworkUri, maxDimension = ARTWORK_MAX_PX)
    // Everything moves in the layout and draw phases, so the transition doesn't recompose the header.
    fun p() = if (expanded == null) 0f else expansion()
    fun artSize() = lerp(metrics.artSizePx, expanded?.artSizePx ?: metrics.artSizePx, p())
    fun artLeft() = lerp(metrics.contentStartPx, expanded?.artLeftPx ?: 0f, p())
    fun artTop() = lerp(metrics.barTopPx, expanded?.artTopPx ?: 0f, p())
    with(density) {
        Box(modifier.fillMaxSize()) {
            HeaderArtwork(
                artwork = artwork,
                direction = info.direction,
                metrics = metrics,
                cornerFraction = { lerp(CompactHeaderMetrics.ART_CORNER_FRACTION, CompactHeaderMetrics.NOWBAR_CORNER_FRACTION, p()) },
                modifier = Modifier
                    .layout { measurable, _ ->
                        val size = artSize().roundToInt().coerceAtLeast(1)
                        val placeable = measurable.measure(Constraints.fixed(size, size))
                        layout(size, size) { placeable.place(0, 0) }
                    }
                    .offset { IntOffset(artLeft().roundToInt(), artTop().roundToInt()) }
                    .coverGestures(isPlaying, onPlayPause, onSkip),
            )
            HeaderMetadata(
                title = info.title,
                artists = info.artists,
                titleStyle = headerTextStyle(FontWeight.Bold, metrics.titleSizeSp, metrics.titleLineHeightSp),
                artistsStyle = headerTextStyle(FontWeight.Normal, metrics.artistsSizeSp, metrics.artistsLineHeightSp),
                titleWidthPx = metrics.textWidthPx,
                artistsWidthPx = metrics.textWidthPx,
                centered = false,
                modifier = Modifier
                    .offset { IntOffset(metrics.textStartPx.roundToInt(), metrics.barTopPx.roundToInt()) }
                    .graphicsLayer { alpha = 1f - (p() / TEXT_SWAP).coerceIn(0f, 1f) }
                    .height(metrics.barHeightPx.toDp()),
            )
            if (expanded != null) {
                HeaderMetadata(
                    title = info.title,
                    artists = info.artists,
                    titleStyle = headerTextStyle(FontWeight.Bold, expanded.titleSizeSp, expanded.titleLineHeightSp),
                    artistsStyle = headerTextStyle(FontWeight.Normal, expanded.artistsSizeSp, expanded.artistsLineHeightSp),
                    // `.SongName { max-width: 85cqw }`, `.Artists { max-width: 80cqw }`.
                    titleWidthPx = expanded.artSizePx * 0.85f,
                    artistsWidthPx = expanded.artSizePx * 0.8f,
                    centered = true,
                    modifier = Modifier
                        // Under the cover wherever it is, `margin-top: 5cqw` of it.
                        .offset {
                            val size = artSize()
                            IntOffset(
                                (artLeft() + size / 2f - expanded.artSizePx / 2f).roundToInt(),
                                (artTop() + size * 1.05f).roundToInt(),
                            )
                        }
                        .graphicsLayer { alpha = ((p() - (1f - TEXT_SWAP)) / TEXT_SWAP).coerceIn(0f, 1f) }
                        .width(expanded.artSizePx.toDp())
                        .height(expanded.textHeightPx.toDp()),
                )
            }
        }
    }
}

private fun lerp(from: Float, to: Float, t: Float) = from + (to - from) * t

/** The part of the transition each text takes to fade: out over the first 40%, in over the last. */
private const val TEXT_SWAP = 0.4f

private fun headerTextStyle(weight: FontWeight, sizeSp: Float, lineHeightSp: Float) = TextStyle(
    fontFamily = LyricsLayoutCalculator.spicyFontFamily,
    // The design asks for 900, but the lyrics font stops at Bold.
    fontWeight = weight,
    fontSize = sizeSp.sp,
    lineHeight = lineHeightSp.sp,
    lineHeightStyle = CssLineHeight,
)

/**
 * The cover's gestures:
 * - a sideways drag moves the whole cover with the finger, cut off at the edges of its place;
 *   let go, it always springs back into place, like lyrics bouncing back at the top of their
 *   page. Pulled past a third of the way (or flung), the song skips that way too (left: next,
 *   right: previous), and the new cover comes in with the usual cover change;
 * - a double tap plays or pauses, with a glass badge flashing what it did.
 */
private fun Modifier.coverGestures(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onSkip: (TrackDirection) -> Unit,
): Modifier = composed {
    val latestPlayPause by rememberUpdatedState(onPlayPause)
    val latestSkip by rememberUpdatedState(onSkip)
    val latestPlaying by rememberUpdatedState(isPlaying)
    val swipe = remember { Animatable(0f) }
    val badge = remember { Animatable(0f) }
    // What the badge shows: what the double tap just switched to.
    var badgePlaying by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    var widthPx by remember { mutableFloatStateOf(1f) }
    val playPainter = rememberVectorPainter(SpicyIcons.Play)
    val pausePainter = rememberVectorPainter(SpicyIcons.Pause)
    this
        .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
        // The cut-off: nothing of the cover shows past its place's sides (its shadow still
        // falls below).
        .drawWithContent {
            clipRect(0f, -size.height, size.width, size.height * 2f) { this@drawWithContent.drawContent() }
        }
        .graphicsLayer { translationX = swipe.value }
        .drawWithContent {
            drawContent()
            val b = badge.value
            if (b <= 0f) return@drawWithContent
            // A glass disc with the glyph, popping in at 0.8 → 1 and fading.
            val radius = size.minDimension * 0.16f * (0.8f + 0.2f * b.coerceAtMost(1f))
            drawCircle(Color.Black.copy(alpha = 0.35f * b), radius, center)
            drawCircle(Color.White.copy(alpha = 0.18f * b), radius, center, style = Stroke(1.dp.toPx()))
            val painter = if (badgePlaying) playPainter else pausePainter
            val glyph = radius * 0.8f
            val aspect = painter.intrinsicSize.width / painter.intrinsicSize.height
            translate(center.x - glyph * aspect / 2f, center.y - glyph / 2f) {
                with(painter) { draw(androidx.compose.ui.geometry.Size(glyph * aspect, glyph), alpha = b) }
            }
        }
        .pointerInput(Unit) {
            detectTapGestures(
                onDoubleTap = {
                    badgePlaying = !latestPlaying
                    latestPlayPause()
                    scope.launch {
                        badge.snapTo(1f)
                        delay(350)
                        badge.animateTo(0f, tween(300))
                    }
                },
            )
        }
        .pointerInput(Unit) {
            val velocity = VelocityTracker()
            detectHorizontalDragGestures(
                onDragStart = { velocity.resetTracking() },
                onHorizontalDrag = { change, amount ->
                    change.consume()
                    velocity.addPosition(change.uptimeMillis, change.position)
                    scope.launch { swipe.snapTo((swipe.value + amount).coerceIn(-widthPx, widthPx)) }
                },
                onDragEnd = {
                    val vx = velocity.calculateVelocity().x
                    val fling = COVER_FLING_DP * density
                    when {
                        swipe.value < -widthPx / 3f || vx < -fling -> latestSkip(TrackDirection.Forward)
                        swipe.value > widthPx / 3f || vx > fling -> latestSkip(TrackDirection.Backward)
                    }
                    scope.launch { swipe.animateTo(0f, CoverReturn) }
                },
                onDragCancel = { scope.launch { swipe.animateTo(0f, CoverReturn) } },
            )
        }
}

/** The cover's way back into place: a quick spring that just settles, no wobble. */
private val CoverReturn = spring<Float>(dampingRatio = 0.9f, stiffness = 500f)

/** A sideways fling faster than this (dp/s) skips even when the cover moved only a little. */
private const val COVER_FLING_DP = 1_000f

/**
 * Song name over artists. On a change it fades out, swaps the text at 350 ms, and fades back
 * in 80 ms later.
 */
@Composable
private fun HeaderMetadata(
    title: String,
    artists: String,
    titleStyle: TextStyle,
    artistsStyle: TextStyle,
    titleWidthPx: Float,
    artistsWidthPx: Float,
    centered: Boolean,
    modifier: Modifier,
) {
    var shown by remember { mutableStateOf(title to artists) }
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(title, artists) {
        val next = title to artists
        if (next == shown) {
            alpha.animateTo(1f, tween(METADATA_FADE_MS, easing = CssEase))
            return@LaunchedEffect
        }
        launch { alpha.animateTo(0f, tween(METADATA_FADE_MS, easing = CssEase)) }
        delay(350)
        shown = next
        delay(80)
        alpha.animateTo(1f, tween(METADATA_FADE_MS, easing = CssEase))
    }
    Column(
        modifier = modifier.graphicsLayer { this.alpha = alpha.value },
        // Centred on the cover, compact or expanded.
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        MarqueeLine(shown.first, titleStyle, Color.White.copy(alpha = 0.95f), titleWidthPx)
        MarqueeLine(shown.second, artistsStyle, Color.White.copy(alpha = 0.7f), artistsWidthPx)
    }
}

/** CSS line-height: the extra leading is split evenly above and below the text. */
private val CssLineHeight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

/** One `.SongName`/`.Artists` row: a single line that scrolls per [HeaderMarquee] when it overflows. */
@Composable
private fun MarqueeLine(text: String, style: TextStyle, color: Color, containerWidthPx: Float) {
    val measurer = rememberTextMeasurer()
    val layout = remember(text, style) { measurer.measure(text, style, maxLines = 1, softWrap = false) }
    val padStart = HeaderMarquee.spanStartPaddingPx(containerWidthPx)
    val spanWidth = padStart + layout.size.width + HeaderMarquee.spanEndPaddingPx(containerWidthPx)
    val elementWidth = minOf(spanWidth, containerWidthPx)
    val overflows = spanWidth > containerWidthPx

    var phase by remember(text) { mutableFloatStateOf(0f) }
    LaunchedEffect(text, overflows) {
        phase = 0f
        if (!overflows) return@LaunchedEffect
        val start = withFrameMillis { it }
        while (true) {
            withFrameMillis { phase = HeaderMarquee.phase(it - start) }
        }
    }

    val (maskStart, maskEnd) = HeaderMarquee.maskStops(containerWidthPx, elementWidth)
    val density = LocalDensity.current
    Canvas(
        Modifier
            .width(with(density) { elementWidth.toDp() })
            .height(with(density) { layout.size.height.toDp() }),
    ) {
        val textLeft = padStart + HeaderMarquee.offsetPx(phase, containerWidthPx, spanWidth)
        // Cut off at the sides only: glyphs from a fallback font (CJK, emoji) can reach past the
        // line box above and below, and a full clip sliced their bottoms off.
        clipRect(0f, -size.height, size.width, size.height * 2f) {
            // The edge mask as the text's own gradient, pinned to the element while the text moves.
            val mask = Brush.horizontalGradient(
                0f to color.copy(alpha = 0f),
                maskStart to color,
                maskEnd to color,
                1f to color.copy(alpha = 0f),
                startX = -textLeft,
                endX = elementWidth - textLeft,
            )
            translate(left = textLeft) { drawText(layout, brush = mask) }
        }
    }
}

/**
 * The cover, with its change animation: the new cover slides in from the right casting a
 * shadow to its left, while the old one gets a masked 12px blur; after 1.1 s the new cover
 * becomes the resting one. Going back to an earlier track ([TrackDirection.Backward]) mirrors
 * it, so the cover comes in from the left.
 */
@Composable
private fun HeaderArtwork(
    artwork: SessionArtwork?,
    direction: TrackDirection,
    metrics: CompactHeaderMetrics,
    cornerFraction: () -> Float,
    modifier: Modifier,
) {
    val latestDirection by rememberUpdatedState(direction)
    var current by remember { mutableStateOf<SessionArtwork?>(null) }
    var incoming by remember { mutableStateOf<SessionArtwork?>(null) }
    val slide = remember { Animatable(0f) }
    val blur = remember { Animatable(0f) }
    // +1 slides the new cover in from the right, -1 from the left.
    var enterFrom by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(artwork?.fingerprint) {
        // Keep the last cover when the player has none to show.
        val next = artwork ?: return@LaunchedEffect
        incoming = null
        slide.snapTo(0f)
        blur.snapTo(0f)
        val resting = current
        if (resting == null || resting.fingerprint == next.fingerprint) {
            current = next
            return@LaunchedEffect
        }
        incoming = next
        enterFrom = if (latestDirection == TrackDirection.Backward) -1f else 1f
        coroutineScope {
            launch { slide.animateTo(1f, tween(750, easing = CoverEnterEasing)) }
            launch { blur.animateTo(1f, tween(850, easing = CssEase)) }
        }
        delay(1_100L - 850L)
        current = next
        incoming = null
        slide.snapTo(0f)
        blur.snapTo(0f)
    }

    val density = LocalDensity.current
    val shadowColor = Color.Black.copy(alpha = 0.271f).toArgb()
    val shadowOffsetY = with(density) { metrics.artShadowOffsetYDp.dp.toPx() }
    val shadowRadius = cssBlurToShadowRadius(with(density) { metrics.artShadowBlurDp.dp.toPx() })
    val incomingShadowX = with(density) { metrics.incomingShadowOffsetXDp.dp.toPx() }
    val incomingShadowBlur = with(density) { metrics.incomingShadowBlurDp.dp.toPx() }
    val blurRadius = with(density) { metrics.outgoingBlurDp.dp.toPx() }

    Box(
        modifier.drawBehind {
            // Hardware canvases only draw shadow layers for shapes from API 28.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || current == null) return@drawBehind
            drawIntoCanvas { canvas ->
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.TRANSPARENT
                    setShadowLayer(shadowRadius, 0f, shadowOffsetY, shadowColor)
                }
                val radius = size.width * cornerFraction()
                canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, radius, radius, paint)
            }
        },
    ) {
        // `.MediaImageContainer { border-radius: 3cqh }` against the square cover (2cqh expanded).
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    shape = RoundedCornerShape(size.width * cornerFraction())
                    clip = true
                    alpha = 0.95f
                },
        ) {
            val resting = current
            if (resting == null) {
                // A skeleton while no cover has loaded yet.
                Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.1f)))
            } else {
                val bitmap = remember(resting) { resting.bitmap.asImageBitmap() }
                Box(Modifier.fillMaxSize()) {
                    Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    if (incoming != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Image(
                            bitmap,
                            null,
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    alpha = blur.value
                                    renderEffect = BlurEffect(blurRadius, blurRadius, TileMode.Clamp)
                                    compositingStrategy = CompositingStrategy.Offscreen
                                }
                                .drawWithContent {
                                    drawContent()
                                    // `mask-image: linear-gradient(90deg, transparent, black 40%)`, clear on
                                    // the side the new cover comes from.
                                    val mask = if (enterFrom > 0f) {
                                        Brush.horizontalGradient(0f to Color.Transparent, 0.4f to Color.Black)
                                    } else {
                                        Brush.horizontalGradient(0.6f to Color.Black, 1f to Color.Transparent)
                                    }
                                    drawRect(mask, blendMode = BlendMode.DstIn)
                                },
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            }
            incoming?.let { next ->
                val bitmap = remember(next) { next.bitmap.asImageBitmap() }
                Image(
                    bitmap,
                    null,
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationX = enterFrom * (1f - slide.value) * size.width }
                        .drawBehind {
                            // Shadow on the cover's leading edge, mirrored when it comes in from the left.
                            if (enterFrom < 0f) {
                                scale(-1f, 1f, pivot = center) { leadingShadowBand(incomingShadowX, incomingShadowBlur) }
                            } else {
                                leadingShadowBand(incomingShadowX, incomingShadowBlur)
                            }
                        },
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
}

/** Visible part of `.ti_ToImage { box-shadow: -20px 0 21px rgba(0,0,0,.14) }`: a soft band left of the cover. */
private fun DrawScope.leadingShadowBand(offsetX: Float, blur: Float) {
    val outer = offsetX - blur / 2f
    val inner = (offsetX + blur / 2f).coerceAtMost(0f)
    drawRect(
        Brush.horizontalGradient(
            0f to Color.Transparent,
            ((inner - outer) / -outer).coerceIn(0f, 1f) to Color.Black.copy(alpha = 0.14f),
            1f to Color.Black.copy(alpha = 0.14f),
            startX = outer,
            endX = 0f,
        ),
        topLeft = Offset(outer, 0f),
        size = androidx.compose.ui.geometry.Size(-outer, size.height),
    )
}

/**
 * A CSS shadow's blur length is twice the Gaussian sigma; Android's shadow radius maps to
 * sigma = radius * 0.57735 + 0.5 (Skia's conversion), so invert that.
 */
private fun cssBlurToShadowRadius(cssBlurPx: Float): Float =
    ((cssBlurPx / 2f - 0.5f) / 0.57735f).coerceAtLeast(0.1f)

private const val ARTWORK_MAX_PX = 1024
private const val METADATA_FADE_MS = 240
