package com.tx24.spicyplayer.ui.background

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import com.tx24.spicyplayer.ui.nowplaying.SessionArtwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** `cubic-bezier(0.66, 0, 0.34, 1)` over 850 ms: the still background's change. */
private val StillChangeEasing = CubicBezierEasing(0.66f, 0f, 0.34f, 1f)
private const val STILL_CHANGE_MS = 850

/**
 * A still picture filling the page: cropped to cover it, `brightness(0.55) contrast(1.05)
 * saturate(1.7)`, blurred by [blurDp] and drawn at 1.25× so the blur's soft edges fall outside.
 * A new picture slides in from the right as the old one slides out to the left. [image] null keeps
 * the one shown (nothing, at first).
 *
 * The blur needs Android 12; older phones show the picture sharp.
 */
@Composable
fun StillImageBackground(image: SessionArtwork?, blurDp: Int, modifier: Modifier = Modifier) {
    var current by remember { mutableStateOf<StillPicture?>(null) }
    var incoming by remember { mutableStateOf<StillPicture?>(null) }
    val slide = remember { Animatable(0f) }

    val latestImage by rememberUpdatedState(image)
    LaunchedEffect(Unit) {
        // A null in between (still looking up) doesn't interrupt a change under way; a newer
        // picture does, and the one that was sliding in counts as shown.
        snapshotFlow { latestImage }.filterNotNull().distinctUntilChangedBy { it.fingerprint }.collectLatest { next ->
            incoming?.let { current = it; incoming = null }
            slide.snapTo(0f)
            val picture = withContext(Dispatchers.Default) { StillPicture(next.fingerprint, filtered(next.bitmap).asImageBitmap()) }
            val shown = current
            if (shown == null) {
                current = picture
                return@collectLatest
            }
            if (shown.fingerprint == picture.fingerprint) return@collectLatest
            incoming = picture
            slide.animateTo(1f, tween(STILL_CHANGE_MS, easing = StillChangeEasing))
            current = picture
            incoming = null
            slide.snapTo(0f)
        }
    }

    val blurPx = with(LocalDensity.current) { blurDp.coerceAtLeast(0) * density }
    Box(modifier.fillMaxSize().clipToBounds().background(Color.Black)) {
        current?.let { StillLayer(it.bitmap, blurPx) { size -> -slide.value * size } }
        incoming?.let { StillLayer(it.bitmap, blurPx) { size -> (1f - slide.value) * size } }
    }
}

private class StillPicture(val fingerprint: Int, val bitmap: ImageBitmap)

@Composable
private fun StillLayer(bitmap: ImageBitmap, blurPx: Float, offsetX: (Float) -> Float) {
    Image(
        bitmap,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationX = offsetX(size.width)
                scaleX = 1.25f
                scaleY = 1.25f
                if (blurPx > 0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val radius = cssBlurToRadius(blurPx)
                    renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
                }
            },
    )
}

/** CSS blur lengths are Gaussian sigmas; Android's blur radius maps to sigma = radius × 0.57735 + 0.5. */
private fun cssBlurToRadius(sigmaPx: Float) = ((sigmaPx - 0.5f) / 0.57735f).coerceAtLeast(0.1f)

/**
 * `brightness(0.55) contrast(1.05) saturate(1.7)`, each step clamped to 0..1 as the browser's
 * filter chain does, on a copy no bigger than 1280 px.
 */
private fun filtered(source: Bitmap): Bitmap {
    val longest = maxOf(source.width, source.height)
    val scaled = if (longest <= MAX_STILL_PX) source else Bitmap.createScaledBitmap(
        source,
        (source.width * MAX_STILL_PX.toFloat() / longest).toInt().coerceAtLeast(1),
        (source.height * MAX_STILL_PX.toFloat() / longest).toInt().coerceAtLeast(1),
        true,
    )
    val w = scaled.width
    val h = scaled.height
    val pixels = IntArray(w * h)
    scaled.getPixels(pixels, 0, w, 0, 0, w, h)
    for (i in pixels.indices) {
        val p = pixels[i]
        var r = ((p shr 16) and 0xFF) / 255f
        var g = ((p shr 8) and 0xFF) / 255f
        var b = (p and 0xFF) / 255f
        r = (r * 0.55f).coerceIn(0f, 1f); g = (g * 0.55f).coerceIn(0f, 1f); b = (b * 0.55f).coerceIn(0f, 1f)
        r = ((r - 0.5f) * 1.05f + 0.5f).coerceIn(0f, 1f)
        g = ((g - 0.5f) * 1.05f + 0.5f).coerceIn(0f, 1f)
        b = ((b - 0.5f) * 1.05f + 0.5f).coerceIn(0f, 1f)
        // The filter spec's saturate matrix.
        val s = 1.7f
        val nr = (0.213f + 0.787f * s) * r + (0.715f - 0.715f * s) * g + (0.072f - 0.072f * s) * b
        val ng = (0.213f - 0.213f * s) * r + (0.715f + 0.285f * s) * g + (0.072f - 0.072f * s) * b
        val nb = (0.213f - 0.213f * s) * r + (0.715f - 0.715f * s) * g + (0.072f + 0.928f * s) * b
        pixels[i] = (0xFF shl 24) or
            ((nr.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 16) or
            ((ng.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 8) or
            (nb.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
}

private const val MAX_STILL_PX = 1280

/**
 * The cover's colours as a plain page: its lighter shade over all of it, its darker shade over
 * the top 90% fading out downwards, and a light black shade rising from the bottom. The colours
 * move to a new song's over 1.5 s; with no cover the page is black.
 */
@Composable
fun ColorBackground(cover: SessionArtwork?, modifier: Modifier = Modifier) {
    val colors by produceState<CoverColors?>(null, cover?.fingerprint) {
        val bitmap = cover?.bitmap ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val small = Bitmap.createScaledBitmap(bitmap, 48, 48, true)
            val pixels = IntArray(48 * 48)
            small.getPixels(pixels, 0, 48, 0, 0, 48, 48)
            CoverColors.of(pixels)
        }
    }
    val base by animateColorAsState(colors?.let { Color(it.minContrast) } ?: Color.Black, tween(COLOR_CHANGE_MS), label = "colorBase")
    val top by animateColorAsState(colors?.let { Color(it.highContrast) } ?: Color.Black, tween(COLOR_CHANGE_MS), label = "colorTop")
    Canvas(modifier.fillMaxSize()) {
        drawRect(base)
        // `height: 90%`, `mask-image: linear-gradient(to top, transparent, black 145%)`.
        val band = size.height * 0.9f
        drawRect(
            Brush.verticalGradient(
                0f to top.copy(alpha = top.alpha * (1f / 1.45f)),
                1f to top.copy(alpha = 0f),
                startY = 0f,
                endY = band,
            ),
            topLeft = Offset.Zero,
            size = Size(size.width, band),
        )
        // `opacity: 0.3; linear-gradient(to top, black, transparent)`.
        drawRect(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.3f)))
    }
}

private const val COLOR_CHANGE_MS = 1_500
