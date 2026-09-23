package com.tx24.spicyplayer.latencytest

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import coil.Coil
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Size
import com.tx24.spicyplayer.lyrics.spicy.canvas.DynamicBackgroundView
import com.tx24.spicyplayer.lyrics.spicy.canvas.kawarp.KawarpBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** Uses the production background engines with artwork published by the remote session. */
@Composable
fun SpicySessionBackground(
    artwork: Bitmap?,
    artworkUri: String?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var softwareArtwork by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(artwork, artworkUri) {
        val loadedArtwork = artwork ?: artworkUri?.let { uri ->
            try {
                val request = ImageRequest.Builder(context)
                    .data(uri)
                    .size(Size(256, 256))
                    .allowHardware(false)
                    .build()
                ((Coil.imageLoader(context).execute(request) as? SuccessResult)
                    ?.drawable as? BitmapDrawable)?.bitmap
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }
        softwareArtwork = withContext(Dispatchers.Default) {
            loadedArtwork?.let { source ->
                val copy = if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    source.config == Bitmap.Config.HARDWARE
                ) {
                    source.copy(Bitmap.Config.ARGB_8888, false)
                } else source
                val longest = maxOf(copy.width, copy.height)
                if (longest <= 256) copy else Bitmap.createScaledBitmap(
                    copy,
                    (copy.width * 256f / longest).toInt().coerceAtLeast(1),
                    (copy.height * 256f / longest).toInt().coerceAtLeast(1),
                    true,
                )
            }
        }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        KawarpBackground(
            coverArtBitmap = softwareArtwork,
            modifier = modifier,
            isPlaying = isPlaying,
            blurIntensity = 60,
        )
    } else {
        DynamicBackgroundView(
            coverArtBitmap = softwareArtwork,
            modifier = modifier,
            blurIntensity = 60,
        )
    }
}
