package com.tx24.spicyplayer.ui.background

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.tx24.spicyplayer.lyrics.spicy.canvas.DynamicBackgroundView
import com.tx24.spicyplayer.lyrics.spicy.canvas.kawarp.KawarpBackground
import com.tx24.spicyplayer.ui.nowplaying.rememberSessionArtwork

/** Uses the production background engines with artwork published by the remote session. */
@Composable
fun SpicySessionBackground(
    artwork: Bitmap?,
    artworkUri: String?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    /** False draws a still background (low performance mode). */
    animate: Boolean = true,
) {
    val softwareArtwork = rememberSessionArtwork(artwork, artworkUri, maxDimension = 256)?.bitmap

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        KawarpBackground(
            coverArtBitmap = softwareArtwork,
            modifier = modifier,
            isPlaying = isPlaying,
            animate = animate,
            blurIntensity = 60,
        )
    } else {
        DynamicBackgroundView(
            coverArtBitmap = softwareArtwork,
            modifier = modifier,
            blurIntensity = 60,
            animate = animate,
        )
    }
}
