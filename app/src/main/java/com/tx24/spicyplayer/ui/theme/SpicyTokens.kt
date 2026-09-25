package com.tx24.spicyplayer.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tx24.spicyplayer.lyrics.spicy.canvas.LyricsLayoutCalculator
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint

/*
 * Spicy Lyrics' design tokens (`src/css/tokens.css`): Apple HIG for the base, iOS glass for
 * overlays, monochrome white on a dark page. Lyric rendering never reads these; they are for the
 * app's own chrome (controls, settings). CSS px are taken as dp and rem as 16sp.
 */

object SpicyColors {
    /** `--color-text-*`: white at 92/60/35/18%. */
    val TextPrimary = Color.White.copy(alpha = 0.92f)
    val TextSecondary = Color.White.copy(alpha = 0.6f)
    val TextTertiary = Color.White.copy(alpha = 0.35f)
    val TextQuaternary = Color.White.copy(alpha = 0.18f)
    val TextOnFill = Color.Black

    /** `--color-bg-elevated`, `--color-bg-overlay`. */
    val BgElevated = Color(28, 28, 32).copy(alpha = 0.88f)
    val BgOverlay = Color.Black.copy(alpha = 0.35f)

    /** `--hairline`, `--hairline-strong`. */
    val Hairline = Color.White.copy(alpha = 0.08f)
    val HairlineStrong = Color.White.copy(alpha = 0.14f)

    /** `--accent-tint-bg(-hover)`: the soft highlight for selected rows and fields. */
    val TintBg = Color.White.copy(alpha = 0.06f)
    val TintBgPressed = Color.White.copy(alpha = 0.14f)

    /** `--accent`: SL has no colour accent, only brighter white. */
    val Accent = Color.White.copy(alpha = 0.95f)

    /** `--color-status-*`: muted; tints an icon or one line of text, never a surface. */
    val StatusDanger = Color(255, 110, 110).copy(alpha = 0.78f)
    val StatusInfo = Color(120, 180, 255).copy(alpha = 0.78f)
    val StatusSuccess = Color(140, 220, 170).copy(alpha = 0.78f)
    val StatusWarning = Color(255, 195, 120).copy(alpha = 0.82f)
}

/**
 * `--material-regular-*` plus the `.ViewControl` edge and cast shadow. The blur comes from Haze
 * (see `LocalBackdrop`); the rest is drawn.
 */
object SpicyGlass {
    val Fill = Color.Black.copy(alpha = 0.10f)

    /** `:hover`/pressed: `background: rgba(255,255,255,.12)`. */
    val FillPressed = Color.White.copy(alpha = 0.12f)

    /** `inset 0 1px 0 rgba(255,255,255,.36)`, `inset 0 0 0 1px (.16)`, `inset 0 -1px 0 (.18)`. */
    val EdgeTop = Color.White.copy(alpha = 0.36f)
    val EdgeRing = Color.White.copy(alpha = 0.16f)
    val EdgeBottom = Color.White.copy(alpha = 0.18f)

    /** `0 10px 24px -8px rgba(8,10,18,.42)`. */
    val CastColor = Color(8, 10, 18).copy(alpha = 0.42f)
    val CastOffsetY = 10.dp
    val CastBlur = 24.dp
    val CastSpread = (-8).dp

    /**
     * `--material-regular-blur: blur(12px)` on what's behind. The fill and edges are drawn on
     * top by the glass itself; the tint is only for phones that can't blur (before Android 12).
     */
    val material = HazeStyle(
        // Haze paints this under the blurred copy; the page is black.
        backgroundColor = Color.Black,
        tints = emptyList(),
        blurRadius = 12.dp,
        noiseFactor = 0f,
        fallbackTint = HazeTint(Color.Black.copy(alpha = 0.3f)),
    )
}

/** `--space-1..6`: a 4dp grid. */
object SpicySpacing {
    val S1 = 4.dp
    val S2 = 8.dp
    val S3 = 12.dp
    val S4 = 16.dp
    val S5 = 20.dp
    val S6 = 24.dp
}

/** `--radius-*`. Pills use `CircleShape`. */
object SpicyRadii {
    val Sm = 8.dp
    val Md = 12.dp
    val Lg = 16.dp
}

object SpicyMotion {
    /** `--ease-standard`. */
    val Standard = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    /** The springy overshoot SL's ViewControls scale with. */
    val Overshoot = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
    const val FAST_MS = 150
    const val BASE_MS = 250
    const val SLOW_MS = 400

    /** `.ViewControls { transition: opacity 0.3s }`. */
    const val CONTROLS_FADE_MS = 300

    /**
     * The modal and its page slides, the toggle and the menus: `0.22s cubic-bezier(0.23, 1, 0.32, 1)`,
     * a quick ease-out.
     */
    val Modal = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
    const val MODAL_MS = 220
}

/** `--text-*` ramp in SL's font. */
object SpicyType {
    private val base = TextStyle(fontFamily = LyricsLayoutCalculator.spicyFontFamily, color = SpicyColors.TextPrimary)
    val Title = base.copy(fontSize = 17.6.sp, lineHeight = 1.25.em, fontWeight = FontWeight.Bold)
    val Headline = base.copy(fontSize = 15.32.sp, lineHeight = 1.3.em, fontWeight = FontWeight.SemiBold)
    val Body = base.copy(fontSize = 14.sp, lineHeight = 1.4.em, fontWeight = FontWeight.Normal)
    val Caption = base.copy(fontSize = 13.12.sp, lineHeight = 1.35.em, fontWeight = FontWeight.Normal)
    val Footnote = base.copy(fontSize = 12.16.sp, lineHeight = 1.3.em, fontWeight = FontWeight.Normal)
}
