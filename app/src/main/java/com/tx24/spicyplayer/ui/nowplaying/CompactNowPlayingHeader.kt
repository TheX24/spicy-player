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

/** CSS's default `ease` timing, used by SL's plain `transition: opacity 0.24s`. */
private val CssEase = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

/** `MB_anim_enter .75s cubic-bezier(0.835, -0.008, 0.149, 0.866)`. */
private val CoverEnterEasing = CubicBezierEasing(0.835f, -0.008f, 0.149f, 0.866f)

/**
 * Port of Spicy Lyrics' compact-mode NowBar header: square cover on the left, lined up with the
 * lyrics, and the song name and artists centred beside it, scrolling when too long. It occupies the top
 * [CompactHeaderMetrics.barBottomPx] of the page; lyrics start at [CompactHeaderMetrics.lyricsTopPx].
 */
@Composable
fun CompactNowPlayingHeader(
    info: NowPlayingInfo,
    metrics: CompactHeaderMetrics,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val artwork = rememberSessionArtwork(info.artwork, info.artworkUri, maxDimension = ARTWORK_MAX_PX)
    with(density) {
        Box(modifier.fillMaxWidth().height(metrics.barBottomPx.toDp())) {
            HeaderArtwork(
                artwork = artwork,
                direction = info.direction,
                metrics = metrics,
                modifier = Modifier
                    .offset { IntOffset(metrics.contentStartPx.roundToInt(), metrics.barTopPx.roundToInt()) }
                    .size(metrics.artSizePx.toDp()),
            )
            HeaderMetadata(
                title = info.title,
                artists = info.artists,
                metrics = metrics,
                modifier = Modifier
                    .offset { IntOffset(metrics.textStartPx.roundToInt(), metrics.barTopPx.roundToInt()) }
                    .width(metrics.textWidthPx.toDp())
                    .height(metrics.barHeightPx.toDp()),
            )
        }
    }
}

/**
 * Song name over artists. On a change it follows SL's `UpdateNowBar`: fade out
 * (`.tr_VisuallyHidden`), swap the text at 350 ms, fade back in 80 ms later.
 */
@Composable
private fun HeaderMetadata(
    title: String,
    artists: String,
    metrics: CompactHeaderMetrics,
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

    val titleStyle = TextStyle(
        fontFamily = LyricsLayoutCalculator.spicyFontFamily,
        // SL asks for 900, but its lyrics font (like ours) stops at Bold, so SL shows Bold too.
        fontWeight = FontWeight.Bold,
        fontSize = metrics.titleSizeSp.sp,
        lineHeight = metrics.titleLineHeightSp.sp,
        lineHeightStyle = CssLineHeight,
    )
    val artistsStyle = TextStyle(
        fontFamily = LyricsLayoutCalculator.spicyFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = metrics.artistsSizeSp.sp,
        lineHeight = metrics.artistsLineHeightSp.sp,
        lineHeightStyle = CssLineHeight,
    )
    Column(
        modifier = modifier.graphicsLayer { this.alpha = alpha.value },
        // Not SL (`justify-content: end`): centred on the cover.
        verticalArrangement = Arrangement.Center,
    ) {
        MarqueeLine(shown.first, titleStyle, Color.White.copy(alpha = 0.95f), metrics.textWidthPx)
        MarqueeLine(shown.second, artistsStyle, Color.White.copy(alpha = 0.7f), metrics.textWidthPx)
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
            .height(with(density) { layout.size.height.toDp() })
            .clipToBounds(),
    ) {
        val textLeft = padStart + HeaderMarquee.offsetPx(phase, containerWidthPx, spanWidth)
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

/**
 * `.MediaBox .MediaImageContainer` with SL's cover change: the new cover (`.ti_ToImage`) slides
 * in from the right casting a shadow to its left, while the old one (`.fi_FromImage`) gets a
 * masked 12px blur; after 1.1 s the new cover becomes the resting one. Not SL: going back to an
 * earlier track ([TrackDirection.Backward]) mirrors it, so the cover comes in from the left.
 */
@Composable
private fun HeaderArtwork(
    artwork: SessionArtwork?,
    direction: TrackDirection,
    metrics: CompactHeaderMetrics,
    modifier: Modifier,
) {
    val latestDirection by rememberUpdatedState(direction)
    var current by remember { mutableStateOf<SessionArtwork?>(null) }
    var incoming by remember { mutableStateOf<SessionArtwork?>(null) }
    val slide = remember { Animatable(0f) }
    val blur = remember { Animatable(0f) }
    // +1 slides the new cover in from the right (SL), -1 from the left.
    var enterFrom by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(artwork?.fingerprint) {
        // SL keeps the last cover when the player has none to show.
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

    BoxWithConstraints(
        modifier.drawBehind {
            // Hardware canvases only draw shadow layers for shapes from API 28.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || current == null) return@drawBehind
            drawIntoCanvas { canvas ->
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.TRANSPARENT
                    setShadowLayer(shadowRadius, 0f, shadowOffsetY, shadowColor)
                }
                val radius = size.width * CompactHeaderMetrics.ART_CORNER_FRACTION
                canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, radius, radius, paint)
            }
        },
    ) {
        // `.MediaImageContainer { border-radius: 3cqh }` against the square cover.
        val shape = RoundedCornerShape(maxWidth * CompactHeaderMetrics.ART_CORNER_FRACTION)
        Box(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .graphicsLayer { alpha = 0.95f },
        ) {
            val resting = current
            if (resting == null) {
                // SL's skeleton while no cover has loaded yet.
                Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.1f)))
            } else {
                val bitmap = remember(resting) { resting.bitmap.asImageBitmap() }
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
