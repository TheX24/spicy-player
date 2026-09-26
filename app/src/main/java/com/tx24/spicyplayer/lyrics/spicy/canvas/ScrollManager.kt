package com.tx24.spicyplayer.lyrics.spicy.canvas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.CubicBezierEasing
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One scroll position shared by the user and auto-scroll:
 * - Touching the lyrics stops auto-scroll and hides the distance blur.
 * - Auto-scroll resumes, and the blur returns, once nothing has moved the lyrics by hand for
 *   [USER_SCROLL_COOLDOWN_MS] and the current line is at least partly on screen.
 * - After resuming it only moves when the current line changes, so a nudge is left alone.
 * - Jumps of over a second in the song snap straight to the line; shorter moves glide.
 *
 * Glides copy CSS's `scroll-behavior: smooth`, i.e. desktop Chromium's programmatic
 * smooth scroll (cc ScrollOffsetAnimationCurve, M143+): cubic-bezier(0.4, 0, 0, 1) over
 * sqrt(distance in px) / 60 seconds, at most 1.5s. The curve is front-loaded (~86% of the move
 * by halfway, against 50% for ease-in-out), so the line lands early and then settles softly.
 */
internal class ScrollManager(
    /**
     * Our px per desktop CSS px. Glide durations are measured in those px, and like the effect
     * sizes they scale with the lyric text (56px lyrics on desktop), not screen density.
     */
    var pxPerReferencePx: Float = 1f,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    /** Offset added to the content: 0 puts the first line at the anchor, negative scrolls down. */
    var animScrollY by mutableFloatStateOf(0f)
        private set

    /** A finger is on the lyrics. */
    var isUserScrolling by mutableStateOf(false)
        private set

    /** The user has taken over and auto-scroll hasn't resumed yet: no distance blur. */
    var hideLineBlur by mutableStateOf(false)
        private set

    private var velocity = 0f
    private var lastDragTimeMs = 0L
    private var lastUserMoveMs = 0L
    /** Line index auto-scroll last went to; a different target starts a new glide. */
    private var lastAutoTarget: Int? = null
    /** After resuming, stay put until the line changes (no re-centring). */
    private var holdUntilLineChange = false
    private var snapNext = true

    // Current glide: from glideFrom to glideTo over glideDuration seconds.
    private var glideFrom = 0f
    private var glideTo = 0f
    private var glideElapsed = 0f
    private var glideDuration = 0f

    fun reset() {
        settle(0f)
        animScrollY = 0f
        isUserScrolling = false
        hideLineBlur = false
        velocity = 0f
        lastDragTimeMs = 0L
        lastUserMoveMs = 0L
        lastAutoTarget = null
        holdUntilLineChange = false
        snapNext = true
    }

    /**
     * @param targetIndex the line to follow, or null when there is nothing to follow (static).
     * @param targetY the scroll offset that centres [targetIndex].
     * @param snap jump instead of gliding (seek, first frame).
     * @param targetVisiblePx how much of the target line is on screen right now.
     */
    fun updateScroll(
        dt: Float,
        totalContentHeight: Float,
        targetIndex: Int?,
        targetY: Float?,
        snap: Boolean = false,
        targetVisiblePx: Float = Float.POSITIVE_INFINITY,
    ) {
        val now = monotonicNowMs()
        val top = 0f
        val bottom = -totalContentHeight.coerceAtLeast(0f)
        var y = animScrollY

        if (targetY != null && (snap || snapNext)) {
            y = targetY.coerceIn(bottom, top)
            takeBackControl(targetIndex, y)
            snapNext = false
            animScrollY = y
            return
        }

        when {
            isUserScrolling -> lastUserMoveMs = now
            abs(velocity) > MIN_FLING_VELOCITY -> {
                // Fling: counts as the user still scrolling, so auto-scroll never yanks it.
                y += velocity * dt
                velocity *= ScrollPolicyController.flingDecayMultiplier(dt)
                if (y > top + OVERSCROLL_PX || y < bottom - OVERSCROLL_PX) velocity = 0f
                lastUserMoveMs = now
            }
            else -> velocity = 0f
        }

        if (hideLineBlur && !isUserScrolling && velocity == 0f &&
            now - lastUserMoveMs > USER_SCROLL_COOLDOWN_MS && targetVisiblePx >= MIN_VISIBLE_PX
        ) {
            // Resume where the user left it; the next line change glides from here.
            hideLineBlur = false
            holdUntilLineChange = true
            settle(y)
        }

        if (!hideLineBlur && targetY != null) {
            if (targetIndex != lastAutoTarget) {
                lastAutoTarget = targetIndex
                holdUntilLineChange = false
            }
            if (!holdUntilLineChange) y = glide(y, targetY.coerceIn(bottom, top), dt)
        }

        // Past either end: resist while dragging, spring back once released.
        if (!isUserScrolling) {
            val bound = y.coerceIn(bottom, top)
            if (y != bound && velocity == 0f) {
                y += (bound - y) * (dt * EDGE_RETURN_RATE).coerceAtMost(1f)
                if (abs(bound - y) < 0.5f) y = bound
                if (!hideLineBlur) settle(y)
            }
        }
        animScrollY = y
    }

    /**
     * The same lyrics were laid out again (romanized, resized): every line moved, so the next
     * frame jumps to the target's new place instead of gliding there, and the line being sung
     * stays where it was on screen.
     */
    fun onRelayout() {
        snapNext = true
    }

    fun onDragStart() {
        isUserScrolling = true
        hideLineBlur = true
        velocity = 0f
        lastDragTimeMs = monotonicNowMs()
        lastUserMoveMs = lastDragTimeMs
    }

    fun onDrag(dy: Float, totalContentHeight: Float) {
        val now = monotonicNowMs()
        val dtSec = (now - lastDragTimeMs) / 1000f
        if (dtSec > 0) velocity = velocity * 0.4f + (dy / dtSec) * 0.6f
        lastDragTimeMs = now
        lastUserMoveMs = now
        val bottom = -totalContentHeight.coerceAtLeast(0f)
        val outside = animScrollY > 0f || animScrollY < bottom
        animScrollY += if (outside) dy * 0.5f else dy
    }

    fun onDragEnd() {
        isUserScrolling = false
        // A finger resting still before lifting shouldn't fling.
        if (monotonicNowMs() - lastDragTimeMs > 80L) velocity = 0f
    }

    /**
     * The user tapped a line to seek: hand control back to auto-scroll. The jump itself is
     * decided once the player reports the new position (over 1s snaps, shorter glides).
     */
    fun onSeek() {
        takeBackControl(targetIndex = null, y = animScrollY)
    }

    /** Steps the glide toward [goal], starting a new one from [y] when the goal moves. */
    private fun glide(y: Float, goal: Float, dt: Float): Float {
        if (abs(goal - glideTo) > 0.5f) {
            glideFrom = y
            glideTo = goal
            glideElapsed = 0f
            val referencePx = abs(goal - y) / pxPerReferencePx.coerceAtLeast(0.01f)
            glideDuration = minOf(sqrt(referencePx), MAX_GLIDE_FRAMES) / 60f
        }
        if (glideElapsed >= glideDuration) return glideTo
        glideElapsed += dt
        val t = (glideElapsed / glideDuration).coerceIn(0f, 1f)
        return glideFrom + (glideTo - glideFrom) * GLIDE_EASING.transform(t)
    }

    /** Stops any glide with the view resting at [y]. */
    private fun settle(y: Float) {
        glideFrom = y
        glideTo = y
        glideElapsed = 0f
        glideDuration = 0f
    }

    private fun takeBackControl(targetIndex: Int?, y: Float) {
        settle(y)
        velocity = 0f
        hideLineBlur = false
        holdUntilLineChange = false
        lastAutoTarget = targetIndex
    }

    private fun monotonicNowMs(): Long = clockMs()

    private companion object {
        /** Reference USER_SCROLL_COOLDOWN. */
        const val USER_SCROLL_COOLDOWN_MS = 750L
        /** Reference: the line counts as in view when at least 5px of it is visible. */
        const val MIN_VISIBLE_PX = 5f
        const val MIN_FLING_VELOCITY = 10f
        const val OVERSCROLL_PX = 60f
        const val EDGE_RETURN_RATE = 12f
        /**
         * Desktop Chromium's kProgrammaticScrollAnimationOverride defaults (cc/base/features.cc):
         * max_animation_duration 1500ms (90 frames of 1/60s) and the curve below. Older builds
         * used ease-in-out capped at 0.2s, which starts slow and trails the line change.
         */
        const val MAX_GLIDE_FRAMES = 90f
        val GLIDE_EASING = CubicBezierEasing(0.4f, 0f, 0f, 1f)
    }
}
