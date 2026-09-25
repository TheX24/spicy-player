package com.tx24.spicyplayer.ui.nowplaying

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.tx24.spicyplayer.lyrics.spicy.canvas.LyricsLayoutCalculator
import kotlinx.coroutines.launch

/*
 * NOT USED YET — kept for the controls area planned near the bottom of the lyrics screen (like
 * the original Spicy Player's), which will replace the temporary panel in latencytest.
 *
 * This is a port of SL's controls over a hovered cover (`.MediaBox .MediaContent`): the
 * playback row and the glass timeline. It was shown by tapping the header cover; that
 * interaction was dropped, so the composables below are unwired. Sizes are SL's container units
 * against a square box of side `side`, measured against a render of SL's CSS; when this moves
 * to its own area, swap `side` for that area's width and re-tune.
 */

/**
 * Transport state and actions for [ArtworkControls].
 *
 * @param customActions the player's own buttons (shuffle, repeat, like, ...) as published in its
 * PlaybackState; SL's shuffle and repeat slots are the natural home for the first two.
 */
class PlaybackControlsState(
    val isPlaying: Boolean,
    val canSeek: Boolean,
    val durationMs: Long,
    /** The player's position (not the lyric clock, which carries the output delay). */
    val positionMs: () -> Long,
    val onPlayPause: () -> Unit,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onSeek: (Long) -> Unit,
    val customActions: List<SessionCustomAction> = emptyList(),
    val onCustomAction: (String) -> Unit = {},
)

/**
 * SL's playback row and timeline over a square of [side]. Shuffle and repeat keep their slots so
 * the row spaces out like SL's; they are left empty until the player's custom actions are wired
 * into them.
 */
@Composable
internal fun ArtworkControls(side: Dp, controls: PlaybackControlsState, modifier: Modifier = Modifier) {
    Box(modifier) {
        PlaybackRow(
            side = side,
            controls = controls,
            // `.PlaybackControls { bottom: 12cqh }` (the compact Header has no Timeline of its own).
            modifier = Modifier.align(Alignment.BottomCenter).offset { IntOffset(0, -(side * 0.12f).roundToPx()) },
        )
        Timeline(side, controls, Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * `.PlaybackControls`: `width: 100cqw; height: 10cqh; padding: 0 20cqh; justify-content:
 * space-between`. Its children are sized in its own (content-box) units: shuffle and repeat 7cqw,
 * skips 12cqw, play/pause 9.25cqw; each glyph fills its control's width.
 */
@Composable
private fun PlaybackRow(side: Dp, controls: PlaybackControlsState, modifier: Modifier) {
    val content = side * 0.6f
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(side * 0.10f)
            .padding(horizontal = side * 0.20f),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(content * 0.07f))
        PressableGlyph(SlIcons.TrackSkip, content * 0.12f, rotate = true, onClick = controls.onPrevious)
        PressableGlyph(
            if (controls.isPlaying) SlIcons.Pause else SlIcons.Play,
            content * 0.0925f,
            onClick = controls.onPlayPause,
        )
        PressableGlyph(SlIcons.TrackSkip, content * 0.12f, onClick = controls.onNext)
        Spacer(Modifier.width(content * 0.07f))
    }
}

/** `transition: transform 0.175s cubic-bezier(0.37, 0, 0.63, 1)` into `.Pressed`. */
private val PressEasing = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)
private val CssEase = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

/**
 * A `.PlaybackControl`: shrinks to `--ShrinkScale: 0.9` while held, then plays SL's 0.6 s
 * `pressAnimation` bounce on release.
 */
@Composable
private fun PressableGlyph(icon: ImageVector, width: Dp, rotate: Boolean = false, onClick: () -> Unit) {
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    Image(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier
            .width(width)
            .aspectRatio(icon.viewportWidth / icon.viewportHeight)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                rotationZ = if (rotate) 180f else 0f
            }
            .pointerInput(onClick) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    scope.launch { scale.animateTo(SHRINK, tween(175, easing = PressEasing)) }
                    val up = waitForUpOrCancellation()
                    scope.launch { scale.animateTo(1f, PressBounce) }
                    if (up != null) {
                        up.consume()
                        onClick()
                    }
                }
            },
    )
}

private const val SHRINK = 0.9f

/** `@keyframes pressAnimation`: scale(1 - ShrinkDelta * k) with CSS's default `ease` per step. */
private val PressBounce = keyframes {
    val delta = 1f - SHRINK
    durationMillis = 600
    (1f - delta * 1f) at 0 using CssEase
    (1f - delta * -0.32f) at 96 using CssEase
    (1f - delta * 0.13f) at 168 using CssEase
    (1f - delta * -0.05f) at 264 using CssEase
    (1f - delta * 0.02f) at 354 using CssEase
    (1f - delta * -0.01f) at 438 using CssEase
    1f at 528 using CssEase
}

/**
 * `.MediaContent .Timeline`: pinned to the cover's bottom, `padding: 5cqw 7cqw`, `gap: .6cqw`;
 * times at `--default-font-size` (`clamp(.5rem, 3cqw, 2.4rem)`, weight 500, 60% white, at least
 * 5ch wide) around SL's default glass slider (`Exp_NewProgressBar`): 2.2cqh tall, 3cqh while
 * dragged, white fill as the progress.
 */
@Composable
private fun Timeline(side: Dp, controls: PlaybackControlsState, modifier: Modifier) {
    val density = LocalDensity.current
    val fontPx = with(density) { (side * 0.03f).toPx().coerceIn(8.dp.toPx(), 38.4.dp.toPx()) }
    val timeStyle = TextStyle(
        fontFamily = LyricsLayoutCalculator.spicyFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = with(density) { fontPx.toSp() },
        fontFeatureSettings = "tnum",
        color = Color.White.copy(alpha = 0.6f),
    )
    val measurer = rememberTextMeasurer()
    val fiveCh = with(density) { measurer.measure("00000", timeStyle).size.width.toDp() }

    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var positionMs by remember { mutableLongStateOf(controls.positionMs()) }
    LaunchedEffect(controls.positionMs) {
        while (true) withFrameMillis { positionMs = controls.positionMs() }
    }
    val duration = controls.durationMs.coerceAtLeast(0L)
    val shownMs = dragFraction?.let { (it * duration).toLong() } ?: positionMs
    val progress = if (duration > 0L) (shownMs.toFloat() / duration).coerceIn(0f, 1f) else 0f

    val thickness = remember { Animatable(0.022f) }
    LaunchedEffect(dragFraction != null) {
        // `transition: height 0.28s cubic-bezier(0.34, 1.56, 0.64, 1)`.
        thickness.animateTo(if (dragFraction != null) 0.03f else 0.022f, tween(280, easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)))
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = side * 0.07f, vertical = side * 0.05f),
        horizontalArrangement = Arrangement.spacedBy(side * 0.006f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(formatClock(shownMs), style = timeStyle.copy(textAlign = TextAlign.End), maxLines = 1, modifier = Modifier.widthIn(min = fiveCh))
        Canvas(
            Modifier
                .weight(1f)
                .padding(horizontal = side * 0.015f)
                .height(side * 0.03f)
                .pointerInput(controls.canSeek, duration) {
                    if (!controls.canSeek || duration <= 0L) return@pointerInput
                    detectTapGestures { controls.onSeek((it.x / size.width * duration).toLong().coerceIn(0L, duration)) }
                }
                .pointerInput(controls.canSeek, duration) {
                    if (!controls.canSeek || duration <= 0L) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { dragFraction = (it.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            dragFraction?.let { controls.onSeek((it * duration).toLong()) }
                            dragFraction = null
                        },
                        onDragCancel = { dragFraction = null },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        },
                    )
                },
        ) {
            val barHeight = side.toPx() * thickness.value
            val top = (size.height - barHeight) / 2f
            val radius = CornerRadius(barHeight / 2f)
            // `--material-regular-bg: rgba(0,0,0,.1)` with the capsule's 1px white inner edge.
            drawRoundRect(Color.Black.copy(alpha = 0.1f), Offset(0f, top), Size(size.width, barHeight), radius)
            drawRoundRect(
                Color.White.copy(alpha = 0.16f), Offset(0f, top), Size(size.width, barHeight), radius,
                style = Stroke(width = 1.dp.toPx()),
            )
            // The fill is a scaleX of a full-width white bar, so it keeps the capsule's rounded clip.
            val capsule = Path().apply {
                addRoundRect(RoundRect(0f, top, size.width, top + barHeight, radius))
            }
            clipPath(capsule) {
                drawRect(Color.White, Offset(0f, top), Size(size.width * progress, barHeight))
            }
        }
        Text(formatClock(duration), style = timeStyle.copy(textAlign = TextAlign.Start), maxLines = 1, modifier = Modifier.widthIn(min = fiveCh))
    }
}

internal fun formatClock(ms: Long): String {
    val totalSeconds = (ms / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

/** SL's playback glyphs (`Styling/Icons.ts`), filled white. */
private object SlIcons {
    val TrackSkip = icon(
        35f, 20f,
        "M 19.467 19.905 C 20.008 19.905 20.463 19.746 21.005 19.426 L 33.61 12.023 C 34.533 11.482 35 10.817 35 9.993 C 35 9.158 34.545 8.53 33.61 7.977 L 21.005 0.574 C 20.463 0.254 19.998 0.094 19.456 0.094 C 18.374 0.094 17.475 0.917 17.475 2.418 L 17.475 9.49 C 17.315 8.898 16.873 8.408 16.135 7.977 L 3.529 0.574 C 3 0.254 2.533 0.094 1.993 0.094 C 0.911 0.094 0 0.917 0 2.418 L 0 17.582 C 0 19.083 0.91 19.906 1.993 19.906 C 2.533 19.906 3 19.746 3.529 19.426 L 16.135 12.023 C 16.861 11.593 17.315 11.088 17.475 10.485 L 17.475 17.582 C 17.475 19.083 18.386 19.906 19.467 19.906 L 19.467 19.905 Z",
    )
    val Play = icon(
        18f, 20f,
        "M 1.558 20 C 2.006 20 2.381 19.838 2.874 19.561 L 16.622 11.572 C 17.527 11.053 17.894 10.65 17.894 9.997 C 17.894 9.35 17.527 8.948 16.622 8.419 L 2.874 0.439 C 2.381 0.153 2.006 0 1.558 0 C 0.706 0 0.106 0.654 0.106 1.694 L 0.106 18.298 C 0.106 19.346 0.706 20 1.558 20 L 1.558 20 Z",
    )
    val Pause = icon(
        15f, 20f,
        "M 4.427 19.963 C 5.513 19.963 6.06 19.416 6.06 18.33 L 6.06 1.66 C 6.06 0.545 5.513 0.037 4.427 0.037 L 1.633 0.037 C 0.548 0.037 0 0.575 0 1.66 L 0 18.331 C -0.009 19.416 0.538 19.963 1.633 19.963 L 4.427 19.963 Z M 13.377 19.963 C 14.462 19.963 15 19.416 15 18.33 L 15 1.66 C 15 0.545 14.462 0.037 13.376 0.037 L 10.573 0.037 C 9.487 0.037 8.949 0.575 8.949 1.66 L 8.949 18.331 C 8.949 19.416 9.487 19.963 10.573 19.963 L 13.376 19.963 L 13.377 19.963 Z",
    )

    private fun icon(width: Float, height: Float, path: String): ImageVector =
        ImageVector.Builder(defaultWidth = width.dp, defaultHeight = height.dp, viewportWidth = width, viewportHeight = height)
            .addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.White))
            .build()
}
