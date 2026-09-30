package com.tx24.spicyplayer.ui.nowplaying

import android.content.Context
import android.graphics.Paint
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import com.tx24.spicyplayer.network.motion.MotionCoverFinder
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/** What the animated cover is looked up by. */
data class MotionCoverQuery(val artist: String, val album: String, val title: String)

/**
 * The record's animated cover stream, or null while there is none (setting off, still searching,
 * or the album has no animation). Resets to null the moment the record changes.
 */
@Composable
fun rememberMotionCoverUrl(enabled: Boolean, query: MotionCoverQuery): String? {
    val context = LocalContext.current
    val artist = leadArtist(query.artist)
    val key = MotionCoverFinder.key(artist, query.album, query.title)
    return produceState<String?>(null, enabled, key) {
        value = null
        if (!enabled || key == null) return@produceState
        value = MotionCovers.finder(context).find(artist, query.album, query.title)
    }.value
}

/**
 * Loops [url] muted, filling its box. Nothing shows until the first frame has been drawn, then it
 * fades in over whatever is beneath (the still cover); [visible] false fades it back out without
 * stopping it. A stream that fails to play calls [onFailed].
 *
 * A TextureView rather than a SurfaceView, so the cover's rounded clip and alpha apply to it.
 */
@OptIn(UnstableApi::class)
@Composable
fun MotionCoverVideo(
    url: String,
    visible: Boolean,
    onFailed: () -> Unit,
    modifier: Modifier = Modifier,
    /** Drawn through this paint, e.g. a colour filter. */
    layerPaint: Paint? = null,
) {
    val context = LocalContext.current
    val latestOnFailed by rememberUpdatedState(onFailed)
    var firstFrame by remember(url) { mutableStateOf(false) }
    val player = remember(url) { MotionCovers.player(context, url) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(player) {
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                firstFrame = true
            }

            override fun onPlayerError(error: PlaybackException) {
                latestOnFailed()
            }
        })
        onDispose { player.release() }
    }
    DisposableEffect(player, lifecycle) {
        // Only loops while the app is on screen.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> player.play()
                Lifecycle.Event.ON_STOP -> player.pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val alpha by animateFloatAsState(
        targetValue = if (firstFrame && visible) 1f else 0f,
        animationSpec = tween(FADE_MS),
        label = "motionCoverAlpha",
    )
    AndroidView(
        factory = { TextureView(it) },
        update = {
            player.setVideoTextureView(it)
            it.setLayerPaint(layerPaint)
        },
        modifier = modifier.graphicsLayer { this.alpha = alpha },
    )
}

/**
 * The first credited artist: the session joins several with ", ", and Apple files the record
 * under its lead.
 */
internal fun leadArtist(artists: String): String =
    artists.split(", ").first().split(Regex("\\s+(?:feat\\.?|ft\\.?|featuring)\\s+", RegexOption.IGNORE_CASE))
        .first().trim()

private const val FADE_MS = 600

/** The process-wide finder and stream cache, and the players built on them. */
@OptIn(UnstableApi::class)
private object MotionCovers {
    @Volatile private var finder: MotionCoverFinder? = null
    @Volatile private var cache: SimpleCache? = null

    fun finder(context: Context): MotionCoverFinder = finder ?: synchronized(this) {
        finder ?: MotionCoverFinder(
            client = OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build(),
            memoFile = File(context.cacheDir, "motion/known.json"),
        ).also { finder = it }
    }

    private fun cache(context: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(
            File(context.cacheDir, "motion/streams"),
            LeastRecentlyUsedCacheEvictor(CACHE_BYTES),
            StandaloneDatabaseProvider(context.applicationContext),
        ).also { cache = it }
    }

    fun player(context: Context, url: String): ExoPlayer {
        val upstream = DefaultHttpDataSource.Factory().setUserAgent(BROWSER_UA)
        val source = CacheDataSource.Factory()
            .setCache(cache(context))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(HlsMediaSource.Factory(source))
            .build()
            .apply {
                // Never asks for audio focus (the default), so the music app is left alone.
                volume = 0f
                repeatMode = Player.REPEAT_MODE_ONE
                // Apple offers up to 1080² at 10 Mbit/s. One steady rendition this side of the cap
                // keeps a loop to a few MB and caches cleanly.
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .setMaxVideoSize(MAX_VIDEO_PX, MAX_VIDEO_PX)
                    .setMaxVideoBitrate(MAX_VIDEO_BITRATE)
                    .setForceHighestSupportedBitrate(true)
                    .build()
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                playWhenReady = true
            }
    }

    private const val CACHE_BYTES = 256L shl 20
    private const val MAX_VIDEO_PX = 1080
    private const val MAX_VIDEO_BITRATE = 3_600_000
    private const val BROWSER_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
}

/** Forgets a remembered stream that failed, so the next song change searches afresh. */
internal suspend fun forgetMotionCover(context: Context, query: MotionCoverQuery) {
    MotionCovers.finder(context).forget(leadArtist(query.artist), query.album, query.title)
}
