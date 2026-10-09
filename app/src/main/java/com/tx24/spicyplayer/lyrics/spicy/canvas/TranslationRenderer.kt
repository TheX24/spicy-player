package com.tx24.spicyplayer.lyrics.spicy.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import com.tx24.spicyplayer.lyrics.spicy.RenderConfig
import com.tx24.spicyplayer.lyrics.spicy.animation.LineAnimState
import com.tx24.spicyplayer.lyrics.spicy.animation.AppleMusicMotion

/** Whole-line text follows its parent's state, without inventing translated word timings. */
internal fun DrawScope.drawTranslationText(
    layout: LineLayout, animation: LineAnimState, startX: Float, scroll: Float, y: Float,
    config: RenderConfig, static: Boolean, replacement: Boolean = false,
) {
    val plan = lyricPaintPlan(animation.state, animation.isBackground, animation.opacity, animation.blur, config, lit = animation.lit)
    val alpha = when {
        static -> animation.opacity
        plan is LyricPaintPlan.InactiveShadow -> plan.alpha
        config.isAppleMusic -> (if (animation.isBackground) AppleMusicMotion.BACKGROUND_BRIGHT_ALPHA else AppleMusicMotion.BRIGHT_ALPHA) * animation.opacity
        else -> (if (animation.isBackground) 0.6f else config.gradientAlphaBright) * animation.opacity
    }
    val blur = if (static || plan !is LyricPaintPlan.InactiveShadow) 0f else plan.blurRadius
    fun draw(result: TextLayoutResult, offset: Offset, dimmer: Float) {
        val opacity = (alpha * dimmer).coerceIn(0f, 1f)
        val size = with(result.layoutInput.density) { result.layoutInput.style.fontSize.toPx() }
        // Blur is a separate pass, scaled by the actual lyric font size rather than density.
        if (blur > 0f && config.distanceBlurEnabled) {
            drawText(result, color = Color.Transparent, topLeft = offset,
                shadow = Shadow(Color.White.copy(alpha = opacity), blurRadius = blur * size / 56f))
            drawText(result, color = Color.Transparent, shadow = Shadow.None, topLeft = offset)
        } else drawText(result, color = Color.White.copy(alpha = opacity), shadow = Shadow.None, topLeft = offset)
    }
    withTransform({
        scale(animation.scale, animation.scale, Offset(
            if (layout.isRightAligned) startX + layout.totalWidth else startX, y + scroll + layout.height / 2f,
        ))
    }) {
        if (replacement) layout.words.forEach { draw(it.textLayoutResult, Offset(startX, y + scroll) + it.relativeOffset, 1f) }
        layout.supplements.forEach { draw(it.text, Offset(startX, y + scroll) + it.offset, 0.65f) }
    }
}
