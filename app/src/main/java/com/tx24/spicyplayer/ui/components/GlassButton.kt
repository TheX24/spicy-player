package com.tx24.spicyplayer.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tx24.spicyplayer.ui.theme.SpicyGlass
import com.tx24.spicyplayer.ui.theme.SpicyMotion
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.launch

/**
 * The inside of a glass button on a circle: a translucent fill, a 1px ring, and bright top
 * and bottom highlights (the `inset 0 ±1px 0` shadows, which show as thin crescents). [fill]
 * changes when pressed. Pair with [glassCast] outside any clip.
 */
fun Modifier.glassCircle(fill: () -> Color = { SpicyGlass.Fill }): Modifier = drawWithCache {
    val radius = size.minDimension / 2f
    val center = Offset(size.width / 2f, size.height / 2f)
    val px = 1.dp.toPx()
    val circle = Path().apply { addOval(Rect(center, radius)) }
    fun shifted(dy: Float) = Path().apply { addOval(Rect(center.copy(y = center.y + dy), radius)) }
    val topCrescent = Path().apply { op(circle, shifted(px), PathOperation.Difference) }
    val bottomCrescent = Path().apply { op(circle, shifted(-px), PathOperation.Difference) }
    onDrawBehind {
        drawCircle(fill(), radius = radius, center = center)
        drawCircle(SpicyGlass.EdgeRing, radius = radius - px / 2f, center = center, style = Stroke(px))
        drawPath(topCrescent, SpicyGlass.EdgeTop)
        drawPath(bottomCrescent, SpicyGlass.EdgeBottom)
    }
}

/** `.ViewControl`'s cast shadow, `0 10px 24px -8px`: a disc shrunk by the spread, blurred, moved down. */
fun Modifier.glassCast(): Modifier = drawWithCache {
    val radius = size.minDimension / 2f
    val center = Offset(size.width / 2f, size.height / 2f + SpicyGlass.CastOffsetY.toPx())
    val castRadius = radius + SpicyGlass.CastSpread.toPx()
    val sigma = SpicyGlass.CastBlur.toPx() / 2f
    val outer = castRadius + sigma * 2f
    val cast = Brush.radialGradient(
        0f to SpicyGlass.CastColor,
        ((castRadius - sigma) / outer).coerceAtLeast(0f) to SpicyGlass.CastColor,
        (castRadius / outer) to SpicyGlass.CastColor.copy(alpha = SpicyGlass.CastColor.alpha / 2f),
        1f to Color.Transparent,
        center = center,
        radius = outer,
    )
    onDrawBehind { drawCircle(cast, radius = outer, center = center) }
}

/**
 * A round glass button: [size] across, blurring the [LocalBackdrop] behind it, scaling to 0.94
 * while held with a springy easing, and brightening its fill.
 */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    content: @Composable () -> Unit,
) {
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    val backdrop = LocalBackdrop.current
    // The screen recomposes on every touch; keying the gesture on a fresh lambda would cancel
    // the press before it lifts.
    val currentOnClick by rememberUpdatedState(onClick)
    val fill by animateColorAsState(
        if (pressed) SpicyGlass.FillPressed else SpicyGlass.Fill,
        tween(300, easing = SpicyMotion.Standard),
        label = "glassFill",
    )
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .glassCast()
            .then(
                backdrop?.let {
                    Modifier
                        .clip(CircleShape)
                        .hazeEffect(it, SpicyGlass.material)
                } ?: Modifier,
            )
            .glassCircle { fill }
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
                onClick { currentOnClick(); true }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    pressed = true
                    scope.launch { scale.animateTo(PRESSED_SCALE, tween(280, easing = SpicyMotion.Overshoot)) }
                    val up = waitForUpOrCancellation()
                    pressed = false
                    scope.launch { scale.animateTo(1f, tween(280, easing = SpicyMotion.Overshoot)) }
                    if (up != null) {
                        up.consume()
                        currentOnClick()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** `.ViewControl:active { scale: 0.94 }`. */
private const val PRESSED_SCALE = 0.94f
