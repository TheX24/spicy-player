package com.tx24.spicyplayer.ui.background

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.tx24.spicyplayer.lyrics.spicy.canvas.DynamicBackgroundView
import com.tx24.spicyplayer.lyrics.spicy.canvas.kawarp.KawarpBackground
import com.tx24.spicyplayer.ui.nowplaying.SessionArtwork
import com.tx24.spicyplayer.ui.nowplaying.loadSessionArtwork
import com.tx24.spicyplayer.ui.nowplaying.rememberSessionArtwork
import com.tx24.spicyplayer.ui.settings.BackgroundType

/**
 * The page's background, of the chosen [type], from the artwork the remote session publishes.
 *
 * The artist-header types show [artistHeaderUrl], or the cover when the artist has none; while
 * [artistHeaderPending] they keep what they show. [speed] is how fast the moving background
 * should go while playing (null: the normal speed).
 */
@Composable
fun SpicySessionBackground(
    artwork: Bitmap?,
    artworkUri: String?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    type: BackgroundType = BackgroundType.Default,
    /** False holds the moving backgrounds still (low performance mode, or the setting). */
    animate: Boolean = true,
    blurDp: Int = 0,
    artistHeaderUrl: String? = null,
    artistHeaderPending: Boolean = false,
    speed: (() -> Float?)? = null,
    /** Cover Art only: the record's animated cover, played over the still one. */
    motionCoverUrl: String? = null,
    onMotionCoverFailed: () -> Unit = {},
    /** False when no player is around: the logo then stands in at once. */
    hasSession: Boolean = true,
) {
    when (type) {
        BackgroundType.Default, BackgroundType.Legacy -> {
            val softwareArtwork = rememberSessionArtwork(artwork, artworkUri, maxDimension = 256, hasSession = hasSession)?.bitmap
            if (type == BackgroundType.Legacy) {
                LegacyBackground(coverArtBitmap = softwareArtwork, modifier = modifier, animate = animate)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                KawarpBackground(
                    coverArtBitmap = softwareArtwork,
                    modifier = modifier,
                    isPlaying = isPlaying,
                    animate = animate,
                    blurIntensity = 60,
                    speed = speed,
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
        BackgroundType.Color -> {
            ColorBackground(rememberSessionArtwork(artwork, artworkUri, maxDimension = 256, hasSession = hasSession), modifier)
        }
        BackgroundType.CoverArt -> {
            Box(modifier) {
                StillImageBackground(rememberSessionArtwork(artwork, artworkUri, maxDimension = STILL_COVER_PX, hasSession = hasSession), blurDp)
                if (motionCoverUrl != null) MotionImageBackground(motionCoverUrl, blurDp, onMotionCoverFailed)
            }
        }
        BackgroundType.Auto, BackgroundType.ArtistHeader -> {
            val cover = rememberSessionArtwork(artwork, artworkUri, maxDimension = STILL_COVER_PX, hasSession = hasSession)
            val context = LocalContext.current
            // Which header URL has loaded, and what it gave (null: it failed, so the cover shows).
            val header by produceState<Pair<String, SessionArtwork?>?>(null, artistHeaderUrl) {
                val url = artistHeaderUrl ?: return@produceState
                value = url to loadSessionArtwork(context, null, url, STILL_HEADER_PX)
            }
            val loaded = header?.takeIf { it.first == artistHeaderUrl }
            val image = when {
                artistHeaderPending -> null
                artistHeaderUrl != null && loaded == null -> null
                else -> loaded?.second ?: cover
            }
            StillImageBackground(image, blurDp, modifier)
        }
    }
}

private const val STILL_COVER_PX = 1024
private const val STILL_HEADER_PX = 1600
