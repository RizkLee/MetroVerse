/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.artwork

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.metrolist.music.LocalAnimatedArtworkVideoCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "ArtworkVideo"

internal fun computeTextureScaleMatrix(
    viewWidth: Int,
    viewHeight: Int,
    videoWidth: Int,
    videoHeight: Int,
    pixelWidthHeightRatio: Float = 1f,
    centerCrop: Boolean = true,
): Matrix {
    val matrix = Matrix()
    if (viewWidth <= 0 || viewHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) {
        return matrix
    }
    val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()
    val videoAspect = (videoWidth.toFloat() * pixelWidthHeightRatio) / videoHeight.toFloat()
    if (videoAspect <= 0f || viewAspect <= 0f) {
        return matrix
    }
    val (scaleX, scaleY) =
        if (centerCrop) {
            if (videoAspect > viewAspect) {
                (videoAspect / viewAspect) to 1f
            } else {
                1f to (viewAspect / videoAspect)
            }
        } else {
            if (videoAspect > viewAspect) {
                1f to (viewAspect / videoAspect)
            } else {
                (videoAspect / viewAspect) to 1f
            }
        }
    matrix.setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f)
    return matrix
}

@OptIn(UnstableApi::class)
@Composable
fun AnimatedCoverVideo(
    url: String,
    priority: ArtworkVideoPriority,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    playing: Boolean = true,
    contentScaleCrop: Boolean = true,
    onError: (() -> Unit)? = null,
    onReadyChanged: ((Boolean) -> Unit)? = null,
) {
    val appContext = LocalContext.current.applicationContext
    val videoCache = LocalAnimatedArtworkVideoCache.current
    val currentOnError by rememberUpdatedState(onError)
    val currentOnReadyChanged by rememberUpdatedState(onReadyChanged)
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val lifecycleActive = lifecycleState.isAtLeast(Lifecycle.State.STARTED)

    val ownerId = remember { "artwork_video_${UUID.randomUUID()}" }
    DisposableEffect(ownerId, active, lifecycleActive, priority) {
        if (active && lifecycleActive) {
            ArtworkVideoCoordinator.acquire(ownerId, priority)
        } else {
            ArtworkVideoCoordinator.release(ownerId)
        }
        onDispose {
            ArtworkVideoCoordinator.release(ownerId)
        }
    }

    val activeOwner by ArtworkVideoCoordinator.activeOwnerId.collectAsStateWithLifecycle()
    val shouldRunSession = active && lifecycleActive && activeOwner == ownerId

    if (!shouldRunSession) return

    val session = remember(url, shouldRunSession) { ArtworkVideoSession() }
    var hasRenderedFrame by remember(session) { mutableStateOf(false) }
    var videoWidth by remember(session) { mutableIntStateOf(0) }
    var videoHeight by remember(session) { mutableIntStateOf(0) }
    var videoPixelRatio by remember(session) { mutableFloatStateOf(1f) }
    val alpha by animateFloatAsState(
        targetValue = if (hasRenderedFrame) 1f else 0f,
        animationSpec = tween(durationMillis = 250),
        label = "artworkVideoFade",
    )

    LaunchedEffect(session, url, videoCache) {
        session.prepare(
            context = appContext,
            url = url,
            videoCache = videoCache,
            initialPlaying = playing,
            onReady = {
                hasRenderedFrame = true
                currentOnReadyChanged?.invoke(true)
            },
            onVideoSize = { width, height, ratio ->
                videoWidth = width
                videoHeight = height
                videoPixelRatio = ratio
            },
            onError = {
                hasRenderedFrame = false
                currentOnReadyChanged?.invoke(false)
                currentOnError?.invoke()
            },
        )
    }

    LaunchedEffect(session, playing) {
        session.setPlaying(playing)
    }

    DisposableEffect(session) {
        onDispose {
            hasRenderedFrame = false
            currentOnReadyChanged?.invoke(false)
            session.release()
        }
    }

    key(session) {
        AndroidView(
            factory = { viewContext ->
                TextureView(viewContext).apply {
                    isOpaque = false
                    surfaceTextureListener =
                        object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(
                                texture: SurfaceTexture,
                                width: Int,
                                height: Int,
                            ) {
                                setTransform(
                                    computeTextureScaleMatrix(
                                        viewWidth = width,
                                        viewHeight = height,
                                        videoWidth = videoWidth,
                                        videoHeight = videoHeight,
                                        pixelWidthHeightRatio = videoPixelRatio,
                                        centerCrop = contentScaleCrop,
                                    ),
                                )
                                session.attachSurface(Surface(texture))
                            }

                            override fun onSurfaceTextureSizeChanged(
                                texture: SurfaceTexture,
                                width: Int,
                                height: Int,
                            ) {
                                setTransform(
                                    computeTextureScaleMatrix(
                                        viewWidth = width,
                                        viewHeight = height,
                                        videoWidth = videoWidth,
                                        videoHeight = videoHeight,
                                        pixelWidthHeightRatio = videoPixelRatio,
                                        centerCrop = contentScaleCrop,
                                    ),
                                )
                            }

                            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                                session.onFrameRendered()
                            }

                            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                // Keep SurfaceTexture alive until ExoPlayer detaches on its playback looper.
                                session.detachSurface(texture)
                                return false
                            }
                        }
                }
            },
            update = { textureView ->
                if (textureView.width > 0 && textureView.height > 0) {
                    textureView.setTransform(
                        computeTextureScaleMatrix(
                            viewWidth = textureView.width,
                            viewHeight = textureView.height,
                            videoWidth = videoWidth,
                            videoHeight = videoHeight,
                            pixelWidthHeightRatio = videoPixelRatio,
                            centerCrop = contentScaleCrop,
                        ),
                    )
                }
            },
            modifier =
                modifier
                    .clipToBounds()
                    .graphicsLayer { this.alpha = alpha },
        )
    }
}

private object ArtworkVideoThread {
    val handler: Handler = Handler(HandlerThread("ArtworkVideo").apply { start() }.looper)
    val dispatcher = handler.asCoroutineDispatcher()
}

@OptIn(UnstableApi::class)
private class ArtworkVideoSession {
    private val handler = ArtworkVideoThread.handler
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + ArtworkVideoThread.dispatcher)
    private val renderedFrames = AtomicLong()
    private val watchdog = ArtworkVideoWatchdog()
    private var player: ExoPlayer? = null
    private var surface: Surface? = null
    private val surfaceAttached = MutableStateFlow(false)
    private var playing = false
    private var released = false

    @Volatile
    private var disposed = false

    fun prepare(
        context: Context,
        url: String,
        videoCache: AnimatedArtworkVideoCache?,
        initialPlaying: Boolean,
        onReady: () -> Unit,
        onVideoSize: (Int, Int, Float) -> Unit,
        onError: () -> Unit,
    ) {
        fun notify(action: () -> Unit) {
            mainHandler.post { if (!disposed) action() }
        }
        handler.post {
            if (disposed || released) return@post
            if (player != null) return@post
            playing = initialPlaying
            try {
                val upstreamFactory =
                    OkHttpDataSource.Factory { request ->
                        ArtworkNetworkClient.current().newCall(request)
                    }
                val dataSourceFactory = videoCache?.streamingFactory(upstreamFactory) ?: upstreamFactory
                val mediaSourceFactory = DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory)
                val selector =
                    DefaultTrackSelector(context).apply {
                        parameters =
                            buildUponParameters()
                                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                .setTrackTypeDisabled(C.TRACK_TYPE_METADATA, true)
                                .build()
                    }
                val loadControl =
                    DefaultLoadControl
                        .Builder()
                        .setBufferDurationsMs(10_000, 30_000, 1_000, 2_000)
                        // Artwork shares the heap with the audio player and image cache.
                        .setTargetBufferBytes(8 * 1024 * 1024)
                        .setPrioritizeTimeOverSizeThresholds(false)
                        .build()
                val videoPlayer =
                    ExoPlayer
                        .Builder(context)
                        .setLooper(handler.looper)
                        .setAudioAttributes(AudioAttributes.DEFAULT, false)
                        .setTrackSelector(selector)
                        .setLoadControl(loadControl)
                        .setMediaSourceFactory(mediaSourceFactory)
                        .build()
                player = videoPlayer

                val clockPlaying = MutableStateFlow(playing)

                videoPlayer.addListener(
                    object : Player.Listener {
                        override fun onPlayWhenReadyChanged(
                            playWhenReady: Boolean,
                            reason: Int,
                        ) {
                            clockPlaying.value = playWhenReady
                        }

                        override fun onRenderedFirstFrame() {
                            notify(onReady)
                        }

                        override fun onVideoSizeChanged(videoSize: VideoSize) {
                            if (videoSize.width > 0 && videoSize.height > 0) {
                                notify {
                                    onVideoSize(videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio)
                                }
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            Timber.tag(TAG).w("Artwork video playback failed: ${error.errorCodeName}")
                            releasePlayer()
                            notify(onError)
                        }
                    },
                )

                videoPlayer.volume = 0f
                videoPlayer.repeatMode = Player.REPEAT_MODE_ALL
                videoPlayer.setVideoSurface(surface)
                videoPlayer.playWhenReady = playing
                videoPlayer.setMediaItem(MediaItem.fromUri(url))
                videoPlayer.prepare()

                scope.launch {
                    watchdog.observe(
                        active = combine(clockPlaying, surfaceAttached) { requested, attached -> requested && attached },
                        timeMs = SystemClock::elapsedRealtime,
                        renderedFrames = renderedFrames::get,
                        onStalled = {
                            Timber.tag(TAG).w("Artwork video stopped rendering frames; falling back")
                            releasePlayer()
                            notify(onError)
                        },
                    )
                }
            } catch (error: Exception) {
                Timber.tag(TAG).w("Artwork video preparation failed (${error::class.simpleName})")
                releasePlayer()
                notify(onError)
            }
        }
    }

    fun onFrameRendered() {
        renderedFrames.incrementAndGet()
    }

    fun setPlaying(value: Boolean) {
        handler.post {
            if (disposed || released) return@post
            if (playing != value) watchdog.reset()
            playing = value
            player?.playWhenReady = value
        }
    }

    fun attachSurface(value: Surface) {
        handler.post {
            if (disposed || released) {
                value.release()
            } else {
                watchdog.reset()
                player?.setVideoSurface(value)
                surface?.release()
                surface = value
                surfaceAttached.value = true
            }
        }
    }

    fun detachSurface(texture: SurfaceTexture) {
        handler.post {
            watchdog.reset()
            player?.clearVideoSurface()
            surface?.release()
            surface = null
            surfaceAttached.value = false
            texture.release()
        }
    }

    fun release() {
        disposed = true
        handler.post {
            released = true
            releasePlayer()
            surface?.release()
            surface = null
        }
    }

    private fun releasePlayer() {
        scope.cancel()
        player?.release()
        player = null
    }
}
