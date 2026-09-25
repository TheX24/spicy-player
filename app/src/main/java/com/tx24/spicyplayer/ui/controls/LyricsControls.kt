package com.tx24.spicyplayer.ui.controls

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tx24.spicyplayer.lyrics.spicy.canvas.LyricsLayoutCalculator
import com.tx24.spicyplayer.ui.components.GlassButton
import com.tx24.spicyplayer.ui.components.LocalBackdrop
import com.tx24.spicyplayer.ui.nowplaying.SessionCustomAction
import com.tx24.spicyplayer.ui.theme.SpicyColors
import com.tx24.spicyplayer.ui.theme.SpicyMotion
import com.tx24.spicyplayer.ui.theme.SpicySpacing
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.launch

/**
 * Transport state and actions for [LyricsControls].
 *
 * @param customActions the player's own buttons (shuffle, repeat, like, ...) as published in its
 * PlaybackState. Shuffle and repeat take SL's slots beside the skips; the rest become floating
 * buttons.
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
 * The lyrics screen's bottom controls, top to bottom: the timeline with its times underneath,
 * SL's playback row (shuffle, previous, play/pause, next, repeat) as plain glyphs, and SL's
 * floating ViewControls (romanize, the player's other actions, settings). Behind them, the lyrics
 * blur and darken towards the bottom, starting [SHADE_REACH] above the controls. While not
 * [interactive] (hidden), touches on the controls are swallowed.
 */
@Composable
fun LyricsControls(
    controls: PlaybackControlsState,
    romanizeAvailable: Boolean,
    romanized: Boolean,
    onToggleRomanize: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    /** Height of the controls themselves, without the shade above them, in px. */
    onControlsHeight: (Int) -> Unit = {},
) {
    val backdrop = LocalBackdrop.current
    Box(modifier.fillMaxWidth()) {
        // The shade: blur that grows from nothing at the top of this area to full strength at the
        // controls, plus a light darkening, so white controls read over anything.
        Box(
            Modifier
                .matchParentSize()
                .then(
                    backdrop?.let {
                        Modifier.hazeEffect(it) {
                            backgroundColor = Color.Black
                            blurRadius = SHADE_BLUR
                            noiseFactor = 0f
                            tints = emptyList()
                            fallbackTint = HazeTint(Color.Black.copy(alpha = 0.25f))
                            progressive = HazeProgressive.verticalGradient(
                                easing = LinearOutSlowInEasing,
                                startIntensity = 0f,
                                endIntensity = 1f,
                            )
                        }
                    } ?: Modifier,
                )
                .background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = SHADE_ALPHA))),
        )
        Box(Modifier.padding(top = SHADE_REACH).onSizeChanged { onControlsHeight(it.height) }) {
            ControlsColumn(controls, romanizeAvailable, romanized, onToggleRomanize, onOpenSettings)
            // Hidden controls still own their area: a touch there only brings them back, rather
            // than seeking a lyric line or pressing a button nobody can see.
            if (!interactive) {
                Box(
                    Modifier
                        .matchParentSize()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown().consume()
                                do {
                                    val event = awaitPointerEvent()
                                    event.changes.forEach { it.consume() }
                                } while (event.changes.any { it.pressed })
                            }
                        },
                )
            }
        }
    }
}

@Composable
private fun ControlsColumn(
    controls: PlaybackControlsState,
    romanizeAvailable: Boolean,
    romanized: Boolean,
    onToggleRomanize: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val shuffle = controls.customActions.firstOrNull { it.kind == ActionKind.Shuffle }
    val repeat = controls.customActions.firstOrNull { it.kind == ActionKind.Repeat }
    val others = controls.customActions.filter { it.kind == ActionKind.Other }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = BOTTOM_MARGIN),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Timeline(controls, Modifier.padding(horizontal = SIDE_MARGIN))
        PlaybackRow(controls, shuffle, repeat, Modifier.padding(horizontal = SIDE_MARGIN - SpicySpacing.S2))
        Spacer(Modifier.height(SpicySpacing.S4))
        Row(horizontalArrangement = Arrangement.spacedBy(SpicySpacing.S2)) {
            if (romanizeAvailable) {
                GlassButton(
                    onClick = onToggleRomanize,
                    contentDescription = if (romanized) "Show original lyrics" else "Romanize lyrics",
                ) {
                    // SL shows what a tap switches to: "A" to go back, the kana mark to romanize.
                    if (romanized) {
                        Image(rememberVectorPainter(SlIcons.DisableRomanization), null, Modifier.size(17.dp))
                    } else {
                        Image(rememberVectorPainter(SlIcons.EnableRomanization), null, Modifier.size(15.dp))
                    }
                }
            }
            others.forEach { action ->
                GlassButton(onClick = { controls.onCustomAction(action.action) }, contentDescription = action.name) {
                    ActionIcon(action, Modifier.size(20.dp))
                }
            }
            GlassButton(onClick = onOpenSettings, contentDescription = "Settings") {
                Image(rememberVectorPainter(SlIcons.Settings), null, Modifier.size(20.dp))
            }
        }
    }
}

private val SIDE_MARGIN = 28.dp
private val BOTTOM_MARGIN = 84.dp
/** How far above the controls the shade starts. */
private val SHADE_REACH = 96.dp
private val SHADE_BLUR = 24.dp
private const val SHADE_ALPHA = 0.25f

/** Where a player's custom action goes. Players only give a name, so this reads the name. */
internal enum class ActionKind { Shuffle, Repeat, Other }

internal val SessionCustomAction.kind: ActionKind
    get() {
        val text = "$name $action".lowercase()
        return when {
            "shuffle" in text -> ActionKind.Shuffle
            "repeat" in text || "loop" in text -> ActionKind.Repeat
            else -> ActionKind.Other
        }
    }

/** A player's own icon, in white like everything else here; its name when it sent no icon. */
@Composable
private fun ActionIcon(action: SessionCustomAction, modifier: Modifier) {
    val bitmap = action.icon
    if (bitmap != null) {
        val painter = remember(bitmap) { BitmapPainter(bitmap.asImageBitmap()) }
        Image(painter, null, modifier, colorFilter = ColorFilter.tint(SpicyColors.TextPrimary))
    } else {
        Text(action.name.take(2), style = TimeStyle.copy(color = SpicyColors.TextPrimary))
    }
}

/**
 * `.PlaybackControls`: shuffle and repeat 7 units wide, skips 12, play/pause 9.25, spread across
 * the row. A slot the player has no action for stays empty so play stays centred.
 */
@Composable
private fun PlaybackRow(
    controls: PlaybackControlsState,
    shuffle: SessionCustomAction?,
    repeat: SessionCustomAction?,
    modifier: Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SideAction(shuffle, controls)
        PressableGlyph(rememberVectorPainter(SlIcons.TrackSkip), SKIP_WIDTH, SlIcons.TrackSkip.aspect, "Previous", rotate = true, onClick = controls.onPrevious)
        val playIcon = if (controls.isPlaying) SlIcons.Pause else SlIcons.Play
        PressableGlyph(
            rememberVectorPainter(playIcon), PLAY_WIDTH, playIcon.aspect,
            if (controls.isPlaying) "Pause" else "Play",
            onClick = controls.onPlayPause,
        )
        PressableGlyph(rememberVectorPainter(SlIcons.TrackSkip), SKIP_WIDTH, SlIcons.TrackSkip.aspect, "Next", onClick = controls.onNext)
        SideAction(repeat, controls)
    }
}

private val SKIP_WIDTH = 42.dp
private val PLAY_WIDTH = SKIP_WIDTH * (9.25f / 12f)
private val SIDE_WIDTH = SKIP_WIDTH * (7f / 12f)

@Composable
private fun SideAction(action: SessionCustomAction?, controls: PlaybackControlsState) {
    if (action == null) {
        Spacer(Modifier.size(TOUCH_SIZE))
        return
    }
    val bitmap = action.icon
    if (bitmap == null) {
        Spacer(Modifier.size(TOUCH_SIZE))
        return
    }
    val painter = remember(bitmap) { BitmapPainter(bitmap.asImageBitmap()) }
    PressableGlyph(
        painter, SIDE_WIDTH, 1f, action.name,
        colorFilter = ColorFilter.tint(SpicyColors.TextPrimary),
        onClick = { controls.onCustomAction(action.action) },
    )
}

private val ImageVector.aspect get() = viewportWidth / viewportHeight

/** Glyphs get a 48dp touch area however small they are drawn. */
private val TOUCH_SIZE = 48.dp

/** `transition: transform 0.175s cubic-bezier(0.37, 0, 0.63, 1)` into `.Pressed`. */
private val PressEasing = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)
private val CssEase = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

/**
 * A `.PlaybackControl`: shrinks to `--ShrinkScale: 0.9` while held, then plays SL's 0.6 s
 * `pressAnimation` bounce on release.
 */
@Composable
private fun PressableGlyph(
    painter: Painter,
    width: Dp,
    aspect: Float,
    description: String,
    rotate: Boolean = false,
    colorFilter: ColorFilter? = null,
    onClick: () -> Unit,
) {
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    // The screen recomposes on every touch; keying the gesture on a fresh lambda would cancel
    // the press before it lifts.
    val currentOnClick by rememberUpdatedState(onClick)
    Box(
        modifier = Modifier
            .size(TOUCH_SIZE)
            .semantics {
                role = Role.Button
                contentDescription = description
                onClick { currentOnClick(); true }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    scope.launch { scale.animateTo(SHRINK, tween(175, easing = PressEasing)) }
                    val up = waitForUpOrCancellation()
                    scope.launch { scale.animateTo(1f, PressBounce) }
                    if (up != null) {
                        up.consume()
                        currentOnClick()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painter,
            contentDescription = null,
            colorFilter = colorFilter,
            modifier = Modifier
                .width(width)
                .aspectRatio(aspect)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    rotationZ = if (rotate) 180f else 0f
                },
        )
    }
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

/** Times: SL's `--default-font-size` look (weight 500, 60% white, tabular figures). */
private val TimeStyle = TextStyle(
    fontFamily = LyricsLayoutCalculator.spicyFontFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 13.sp,
    fontFeatureSettings = "tnum",
    color = SpicyColors.TextSecondary,
)

/**
 * SL's glass slider (`Exp_NewProgressBar`): a frosted capsule whose white fill is the progress,
 * thickening while dragged. Seeks once, on release, because MediaSession seeks are slow and
 * seeking along the drag would make the lyrics jump about. Elapsed and total time sit underneath.
 */
@Composable
private fun Timeline(controls: PlaybackControlsState, modifier: Modifier) {
    val current by rememberUpdatedState(controls)
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var positionMs by remember { mutableLongStateOf(controls.positionMs()) }
    LaunchedEffect(controls.positionMs) {
        while (true) withFrameMillis { positionMs = controls.positionMs() }
    }
    val duration = controls.durationMs.coerceAtLeast(0L)
    val shownMs = dragFraction?.let { (it * duration).toLong() } ?: positionMs
    val progress = if (duration > 0L) (shownMs.toFloat() / duration).coerceIn(0f, 1f) else 0f

    val thickness = remember { Animatable(BAR_HEIGHT.value) }
    LaunchedEffect(dragFraction != null) {
        // `transition: height 0.28s cubic-bezier(0.34, 1.56, 0.64, 1)`.
        thickness.animateTo(
            if (dragFraction != null) BAR_HEIGHT_DRAGGING.value else BAR_HEIGHT.value,
            tween(280, easing = SpicyMotion.Overshoot),
        )
    }
    val seekable = controls.canSeek && duration > 0L

    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(BAR_TOUCH_HEIGHT)
                .pointerInput(seekable, duration) {
                    if (!seekable) return@pointerInput
                    detectTapGestures { current.onSeek((it.x / size.width * duration).toLong().coerceIn(0L, duration)) }
                }
                .pointerInput(seekable, duration) {
                    if (!seekable) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { dragFraction = (it.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            dragFraction?.let { current.onSeek((it * duration).toLong()) }
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
            val barHeight = thickness.value.dp.toPx()
            val top = (size.height - barHeight) / 2f
            val radius = CornerRadius(barHeight / 2f)
            // `--material-regular-bg: rgba(0,0,0,.1)` with the capsule's 1px white inner edge.
            drawRoundRect(Color.Black.copy(alpha = 0.1f), Offset(0f, top), Size(size.width, barHeight), radius)
            drawRoundRect(
                Color.White.copy(alpha = 0.16f), Offset(0f, top), Size(size.width, barHeight), radius,
                style = Stroke(width = 1.dp.toPx()),
            )
            // The fill is a scaleX of a full-width white bar, so it keeps the capsule's rounded clip.
            val capsule = Path().apply { addRoundRect(RoundRect(0f, top, size.width, top + barHeight, radius)) }
            clipPath(capsule) {
                drawRect(Color.White, Offset(0f, top), Size(size.width * progress, barHeight))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatClock(shownMs), style = TimeStyle, maxLines = 1)
            Text(formatClock(duration), style = TimeStyle, maxLines = 1)
        }
    }
}

private val BAR_HEIGHT = 10.dp
private val BAR_HEIGHT_DRAGGING = 14.dp
private val BAR_TOUCH_HEIGHT = 32.dp

internal fun formatClock(ms: Long): String {
    val totalSeconds = (ms / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

/** SL's glyphs (`Styling/Icons.ts`) in white. */
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
    val DisableRomanization = icon(
        750f, 900f,
        "m529.42,632.32H214.71l-81.89,163.5H13.31L377.06,80.35l350.9,715.47h-121.41l-77.13-163.5Zm-45.23-95.48l-109.03-228.9-114.27,228.9h223.3Z",
    )
    val EnableRomanization = icon(
        125.45f, 131.07f,
        "m53.38,130.41c-12.54-2.87-20.86-14.36-19.98-27.42.59-7.62,5.8-15.12,13.07-18.69,4.28-2.11,11.02-3.4,17.75-3.46h4.8v-12.71c.06-16,.64-17.99,5.98-20.74,4.86-2.46,10.96-.47,13.3,4.34,1.17,2.34,1.23,3.52,1.23,17.23v14.65l2.81,1.05c13.59,5.1,30.59,17.87,32.34,24.38,1.17,4.34-.88,8.79-4.92,10.72-4.1,1.93-5.63,1.41-13.89-5.27-4.69-3.69-12.83-9.02-15.29-9.96-.88-.29-1.05,0-1.05,1.64,0,2.93-1.58,8.5-3.34,11.78-1.93,3.46-6.74,8.03-10.43,9.79-6.21,2.99-15.88,4.16-22.38,2.7v-.03Zm11.84-20.51c1.05-.47,2.4-1.46,2.87-2.29,1-1.52,1.41-5.39.7-6.15-.64-.59-12.66-.18-13.95.53-1.23.64-1.46,4.92-.29,6.45,1.82,2.34,6.86,3.05,10.66,1.46h0Z",
        "m6.33,103.4c-4.39-1.99-6.91-6.04-6.21-9.9.23-1.11,2.23-4.8,4.51-8.32,7.21-11.19,17.64-31.23,18.98-36.56l.35-1.46h-8.67c-7.62,0-8.91-.18-10.66-1.17-2.99-1.76-4.34-3.93-4.34-6.91,0-3.52,1.64-6.04,5.1-7.73,2.81-1.41,3.4-1.46,13.89-1.46h10.96l.64-3.93c.35-2.23,1.05-6.86,1.58-10.43,1-7.21,1.93-9.79,4.22-12.19,2.34-2.46,4.39-3.34,7.85-3.34,5.74,0,9.26,3.34,9.26,8.79,0,1.46-.64,5.8-1.46,9.67-.76,3.87-1.46,7.27-1.46,7.56,0,.94,2.99-.29,7.97-3.28,6.04-3.57,9.32-4.22,12.42-2.23,4.51,2.81,4.92,10.84.82,16.35-2.7,3.63-10.9,6.33-20.92,6.91l-6.45.35-1.99,5.33c-3.63,9.67-9.43,22.73-15.35,34.34-6.74,13.3-9.43,17.64-11.72,18.98-2.46,1.46-6.86,1.76-9.32.64h0Z",
        "m109.17,57.17c-11.19-4.69-29.82-13.3-30.88-14.24-4.69-4.22-3.46-12.42,2.17-15.12,4.28-1.99,6.56-1.29,24.9,7.73,15.12,7.38,16.88,8.44,18.34,10.61,1.99,2.87,2.34,6.8.76,9.2-1.29,1.99-5.21,3.81-8.26,3.81-1.35,0-4.34-.88-7.03-1.99Z",
    )

    /** Lucide's settings cog, stroked 2 units wide with round caps and joins. */
    val Settings: ImageVector = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .apply {
            listOf(
                "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2Z",
                "M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0Z",
            ).forEach { path ->
                addPath(
                    PathParser().parsePathString(path).toNodes(),
                    stroke = SolidColor(SpicyColors.TextPrimary),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }
        .build()

    private fun icon(width: Float, height: Float, vararg paths: String): ImageVector =
        ImageVector.Builder(defaultWidth = width.dp, defaultHeight = height.dp, viewportWidth = width, viewportHeight = height)
            .apply { paths.forEach { addPath(PathParser().parsePathString(it).toNodes(), fill = SolidColor(Color.White)) } }
            .build()
}
