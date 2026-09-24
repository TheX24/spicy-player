package com.tx24.spicyplayer.lyrics.spicy.canvas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tx24.spicyplayer.lyrics.spicy.animation.SpringSimulation
import kotlin.math.abs

/**
 * One scroll position shared by the user and auto-scroll, like the reference's scroll container
 * (ScrollToActiveLine.ts):
 * - Touching the lyrics stops auto-scroll and hides the distance blur (HideLineBlur).
 * - Auto-scroll resumes, and the blur returns, once nothing has moved the lyrics by hand for
 *   [USER_SCROLL_COOLDOWN_MS] and the current line is at least partly on screen.
 * - After resuming it only moves when the current line changes, so a nudge is left alone.
 * - Jumps of over a second in the song snap straight to the line; shorter moves glide.
 */
internal class ScrollManager(
    private val autoSpring: SpringSimulation = SpringSimulation(0f, 1.5f, 1.0f),
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
    /** After resuming, stay put until the line changes (the reference doesn't re-centre). */
    private var holdUntilLineChange = false
    private var snapNext = true

    fun reset() {
        autoSpring.resetTo(0f)
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
            autoSpring.resetTo(y)
        }

        if (!hideLineBlur && targetY != null) {
            if (targetIndex != lastAutoTarget) {
                lastAutoTarget = targetIndex
                holdUntilLineChange = false
            }
            if (!holdUntilLineChange) {
                autoSpring.setGoal(targetY.coerceIn(bottom, top))
                y = autoSpring.step(dt)
            }
        }

        // Past either end: resist while dragging, spring back once released.
        if (!isUserScrolling) {
            val bound = y.coerceIn(bottom, top)
            if (y != bound && velocity == 0f) {
                y += (bound - y) * (dt * EDGE_RETURN_RATE).coerceAtMost(1f)
                if (abs(bound - y) < 0.5f) y = bound
                if (!hideLineBlur) autoSpring.resetTo(y)
            }
        }
        animScrollY = y
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

    private fun takeBackControl(targetIndex: Int?, y: Float) {
        autoSpring.resetTo(y)
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
    }
}
