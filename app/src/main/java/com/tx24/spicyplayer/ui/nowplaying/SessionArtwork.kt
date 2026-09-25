package com.tx24.spicyplayer.ui.nowplaying

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import coil.Coil
import com.tx24.spicyplayer.R
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Size
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Cover art published by the remote session, as a software bitmap no larger than the size asked
 * for, plus a small fingerprint of its pixels. Players often republish the same cover as a new
 * Bitmap on every metadata update, so the fingerprint (not the object) says whether it changed.
 */
class SessionArtwork(val bitmap: Bitmap, val fingerprint: Int)

/**
 * Loads the session's cover: its bitmap when it sent one, else its artwork URI through Coil.
 * When there is none, the app logo stands in, but only after [FALLBACK_GRACE_MS]: players often
 * publish a new song's metadata a moment before its cover, and the logo flashing up in between
 * would be noise.
 */
@Composable
fun rememberSessionArtwork(artwork: Bitmap?, artworkUri: String?, maxDimension: Int): SessionArtwork? {
    val context = LocalContext.current
    var loaded by remember { mutableStateOf<SessionArtwork?>(null) }
    LaunchedEffect(artwork, artworkUri, maxDimension) {
        val cover = loadSessionArtwork(context, artwork, artworkUri, maxDimension)
        if (cover != null) {
            loaded = cover
            return@LaunchedEffect
        }
        delay(FALLBACK_GRACE_MS)
        loaded = fallbackArtwork(context)
    }
    return loaded
}

private const val FALLBACK_GRACE_MS = 3_000L

@Volatile private var fallback: SessionArtwork? = null

/** The app logo on its gradient, cropped to the launcher icon's visible square. */
private suspend fun fallbackArtwork(context: Context): SessionArtwork =
    fallback ?: withContext(Dispatchers.Default) {
        val options = BitmapFactory.Options().apply { inScaled = false }
        val bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.fallback_cover, options)
        SessionArtwork(bitmap, fingerprint(bitmap)).also { fallback = it }
    }

suspend fun loadSessionArtwork(
    context: Context,
    artwork: Bitmap?,
    artworkUri: String?,
    maxDimension: Int,
): SessionArtwork? {
    val source = artwork ?: artworkUri?.let { uri ->
        try {
            val request = ImageRequest.Builder(context)
                .data(uri)
                .size(Size(maxDimension, maxDimension))
                .allowHardware(false)
                .build()
            ((Coil.imageLoader(context).execute(request) as? SuccessResult)
                ?.drawable as? BitmapDrawable)?.bitmap
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    } ?: return null
    return withContext(Dispatchers.Default) {
        val software = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            source.config == Bitmap.Config.HARDWARE
        ) {
            source.copy(Bitmap.Config.ARGB_8888, false)
        } else source
        val longest = maxOf(software.width, software.height)
        val sized = if (longest <= maxDimension) software else Bitmap.createScaledBitmap(
            software,
            (software.width * maxDimension.toFloat() / longest).toInt().coerceAtLeast(1),
            (software.height * maxDimension.toFloat() / longest).toInt().coerceAtLeast(1),
            true,
        )
        SessionArtwork(sized, fingerprint(sized))
    }
}

/** Hash of an 8x8 downscale: equal for the same cover at any size, different for another cover. */
private fun fingerprint(bitmap: Bitmap): Int {
    val tiny = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
    val pixels = IntArray(64)
    tiny.getPixels(pixels, 0, 8, 0, 0, 8, 8)
    if (tiny !== bitmap) tiny.recycle()
    // Drop the low bits of each channel so scaler rounding at different source sizes still matches.
    return pixels.fold(17) { hash, pixel -> hash * 31 + (pixel and 0x00F0F0F0) }
}
