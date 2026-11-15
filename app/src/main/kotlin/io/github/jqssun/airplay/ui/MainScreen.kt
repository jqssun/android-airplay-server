package io.github.jqssun.airplay.ui

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn as AndroidxOptIn
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import kotlinx.coroutines.launch
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.produceState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.jqssun.airplay.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.service.AirPlayService.ServerState
import io.github.jqssun.airplay.ui.gestures.BrightnessState
import io.github.jqssun.airplay.ui.gestures.DoubleTapIndicator
import io.github.jqssun.airplay.ui.gestures.GestureInfoText
import io.github.jqssun.airplay.ui.gestures.SeekGestureState
import io.github.jqssun.airplay.ui.gestures.TapGestureState
import io.github.jqssun.airplay.ui.gestures.VerticalGesture
import io.github.jqssun.airplay.ui.gestures.VideoContentScale
import io.github.jqssun.airplay.ui.gestures.VerticalProgressIndicator
import io.github.jqssun.airplay.ui.gestures.VideoPlayerGestures
import io.github.jqssun.airplay.ui.gestures.VolumeAndBrightnessGestureState
import io.github.jqssun.airplay.ui.gestures.ServerVolumeState
import io.github.jqssun.airplay.ui.gestures.ZoomState
import io.github.jqssun.airplay.viewmodel.DebugInfo
import io.github.jqssun.airplay.viewmodel.LocalNetworkAccess
import io.github.jqssun.airplay.viewmodel.MainViewModel
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.ui.compose.material3.MiniController
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.pow
import kotlinx.coroutines.delay

private enum class Tab(val labelRes: Int, val icon: ImageVector) {
    OVERVIEW(R.string.tab_overview, Icons.Default.Cast),
    LOGS(R.string.tab_logs, Icons.AutoMirrored.Filled.Article),
    SETTINGS(R.string.tab_settings, Icons.Default.Settings)
}

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    isInPip: Boolean = false,
    onSurfaceAvailable: (android.view.Surface) -> Unit,
    onSurfaceDestroyed: (android.view.Surface) -> Unit,
    onPip: () -> Unit = {},
    onLocalNetwork: () -> Unit = {}
) {
    var tab by remember { mutableStateOf(Tab.OVERVIEW) }
    var fullscreen by remember { mutableStateOf(false) }
    // Apple Music TV-style: full-screen artwork while audio plays; Back returns to the main UI
    var musicFullscreen by remember { mutableStateOf(false) }
    val pin by viewModel.pinCode.collectAsState()
    val connections by viewModel.connectionCount.collectAsState()
    val audioOnly by viewModel.audioOnly.collectAsState()
    val videoPlaybackActive by viewModel.videoPlaybackActive.collectAsState()
    val videoSessionPending by viewModel.videoSessionPending.collectAsState()
    val mirroringActive by viewModel.mirroringActive.collectAsState()
    val autoFullscreen by viewModel.pref(Prefs.AUTO_FULLSCREEN).collectAsState()

    val musicActive = audioOnly && connections > 0
    LaunchedEffect(musicActive) {
        if (musicActive) {
            musicFullscreen = true
            tab = Tab.OVERVIEW
        } else {
            musicFullscreen = false
        }
    }

    // don't use movableContentOf: moving AndroidView across subcomposition boundaries makes it crash on reparent
    val video: @Composable () -> Unit = {
        val aspect by viewModel.videoAspect.collectAsState()
        VideoSurfaceView(
            onSurfaceAvailable = onSurfaceAvailable,
            onSurfaceDestroyed = onSurfaceDestroyed,
            aspectRatio = aspect
        )
    }

    // fullscreen only once mirroring reports a size: connections rise before the kind is known
    var prevMirroringActive by remember { mutableStateOf(false) }
    LaunchedEffect(mirroringActive, audioOnly, videoPlaybackActive, pin) {
        val justStarted = !prevMirroringActive && mirroringActive
        prevMirroringActive = mirroringActive
        if (justStarted && !audioOnly && !videoPlaybackActive && autoFullscreen && pin == null) {
            fullscreen = true
        }
    }

    // manual fullscreen over the idle preview is deliberate; only undo on disconnect
    LaunchedEffect(connections) {
        if (connections == 0) fullscreen = false
    }

    // leaving video playback must not fall through to a stale mirroring fullscreen
    LaunchedEffect(videoPlaybackActive) {
        if (videoPlaybackActive) fullscreen = false
    }

    val activity = LocalContext.current as? Activity
    val videoScreen = videoPlaybackActive || videoSessionPending
    LaunchedEffect(fullscreen, videoScreen, musicFullscreen) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (fullscreen || videoScreen || musicFullscreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    if (musicFullscreen && musicActive && !videoScreen) {
        if (isInPip) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                NowPlayingCoverArt(viewModel, fill = true)
            }
            return
        }
        FullscreenNowPlaying(
            viewModel = viewModel,
            onExit = { musicFullscreen = false }
        )
        return
    }

    if (videoScreen) {
        val videoPlaybackAspect by viewModel.videoPlaybackAspect.collectAsState()
        val playback: @Composable () -> Unit = {
            VideoSurfaceView(
                onSurfaceAvailable = { viewModel.onVideoPlaybackSurfaceAvailable(it) },
                onSurfaceDestroyed = { viewModel.onVideoPlaybackSurfaceDestroyed(it) },
                aspectRatio = videoPlaybackAspect
            )
        }
        if (isInPip) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                playback()
            }
            return
        }
        val overlayTick by viewModel.videoOverlayTick.collectAsState()
        val playing by viewModel.videoPlaying.collectAsState()
        val positionMs by viewModel.videoPositionMs.collectAsState()
        val durationMs by viewModel.videoDurationMs.collectAsState()
        val scrubPositionMs by viewModel.videoScrubPositionMs.collectAsState()
        val downloadProgress by viewModel.videoDownloadProgress.collectAsState()
        val scrubbing = scrubPositionMs != null
        val downloading = downloadProgress != null
        var overlayVisible by remember { mutableStateOf(false) }
        LaunchedEffect(overlayTick, playing, scrubbing, downloading, videoPlaybackActive) {
            // tick 0 = fresh session; a pending session or a running download pins the overlay
            if (!videoPlaybackActive) {
                overlayVisible = true
            } else if (overlayTick == 0L) {
                overlayVisible = false
            } else {
                overlayVisible = true
                if (playing && !scrubbing && !downloading) {
                    delay(VIDEO_OVERLAY_HIDE_MS)
                    overlayVisible = false
                }
            }
        }
        val gestureScope = rememberCoroutineScope()
        val tapGestureState = remember(viewModel) { TapGestureState(viewModel, gestureScope) }
        val seekGestureState = remember(viewModel) { SeekGestureState(viewModel) }
        val context = LocalContext.current
        val serverVolume = remember { ServerVolumeState(context) }
        val brightnessState = remember(activity) { activity?.window?.let { BrightnessState(it) } }
        val volumeAndBrightnessGestureState = remember(serverVolume, brightnessState) {
            VolumeAndBrightnessGestureState(serverVolume, brightnessState, gestureScope)
        }
        val zoomState = remember(viewModel) { ZoomState(viewModel, gestureScope) }
        var controlsLocked by remember { mutableStateOf(false) }
        DisposableEffect(serverVolume) { serverVolume.handleLifecycle(this) }
        LaunchedEffect(overlayTick) {
            if (overlayTick == 0L) {
                zoomState.reset()
                controlsLocked = false
            }
        }
        DisposableEffect(brightnessState) {
            onDispose { brightnessState?.clearOverride() }
        }
        // orientation follows the video aspect; manual rotate holds until the next video
        LaunchedEffect(videoPlaybackAspect) {
            activity?.requestedOrientation = if (videoPlaybackAspect < 1f) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }
        DisposableEffect(activity) {
            onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
        val videoPlaybackSize by viewModel.videoPlaybackSize.collectAsState()
        val buffering by viewModel.videoBuffering.collectAsState()
        val videoTitle by viewModel.videoTitle.collectAsState()
        val videoLocation by viewModel.videoLocation.collectAsState()
        val speed by viewModel.videoSpeed.collectAsState()
        val isPipSupported = remember {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        }
        var showSpeedSelector by remember { mutableStateOf(false) }
        BackHandler { viewModel.stopVideoPlayback() }

        val rootFocusRequester = remember { FocusRequester() }
        val playPauseFocusRequester = remember { FocusRequester() }
        val unlockFocusRequester = remember { FocusRequester() }
        var isPlayPauseFocused by remember { mutableStateOf(false) }
        var isUnlockFocused by remember { mutableStateOf(false) }
        LaunchedEffect(overlayVisible, controlsLocked, showSpeedSelector) {
            if (showSpeedSelector) return@LaunchedEffect
            if (!overlayVisible) {
                runCatching { rootFocusRequester.requestFocus() }
                return@LaunchedEffect
            }
            val locked = controlsLocked
            val target = if (locked) unlockFocusRequester else playPauseFocusRequester
            target.requestFocusUntilLanded(attempts = 20) { if (locked) isUnlockFocused else isPlayPauseFocused }
        }

        // dpad seeking (controls hidden): accumulate the skipped amount and briefly show it
        var dpadSeekOffsetMs by remember { mutableLongStateOf(0L) }
        var dpadSeekTargetMs by remember { mutableLongStateOf(0L) }
        var dpadSeekActive by remember { mutableStateOf(false) }
        var dpadSeekTick by remember { mutableIntStateOf(0) }
        LaunchedEffect(dpadSeekTick) {
            if (!dpadSeekActive) return@LaunchedEffect
            delay(1000)
            dpadSeekActive = false
        }
        val showDpadSeekFeedback: (Long) -> Unit = { deltaMs ->
            if (!dpadSeekActive) dpadSeekOffsetMs = 0L
            dpadSeekOffsetMs += deltaMs
            dpadSeekTargetMs = (dpadSeekTargetMs.takeIf { dpadSeekActive } ?: positionMs).plus(deltaMs)
                .coerceIn(0L, if (durationMs > 0) durationMs else Long.MAX_VALUE)
            dpadSeekActive = true
            dpadSeekTick++
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(rootFocusRequester)
                .focusable()
                .onPreviewKeyEvent { keyEvent ->
                    if (showSpeedSelector) {
                        false
                    } else {
                        handlePlayerKeyEvent(
                            keyEvent = keyEvent,
                            controlsVisible = overlayVisible,
                            controlsLocked = controlsLocked,
                            isPlayPauseFocused = isPlayPauseFocused,
                            seekIncrementMs = TapGestureState.SEEK_INCREMENT_MS,
                            viewModel = viewModel,
                            showControls = { viewModel.showVideoOverlay() },
                            unlockControls = {
                                controlsLocked = false
                                viewModel.showVideoOverlay()
                            },
                            onDpadSeek = showDpadSeekFeedback,
                        )
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            VideoContentFrame(
                aspect = videoPlaybackAspect,
                videoSizePx = videoPlaybackSize,
                contentScale = zoomState.contentScale,
                zoom = zoomState.zoom
            ) { sizeModifier ->
                VideoSurfaceView(
                    onSurfaceAvailable = { viewModel.onVideoPlaybackSurfaceAvailable(it) },
                    onSurfaceDestroyed = { viewModel.onVideoPlaybackSurfaceDestroyed(it) },
                    applyAspectRatio = false,
                    modifier = sizeModifier
                )
            }
            VideoPlayerGestures(
                enabled = videoPlaybackActive,
                locked = controlsLocked,
                onTap = {
                    // a pending session pins the overlay; taps must not unpin it
                    if (!videoPlaybackActive) return@VideoPlayerGestures
                    if (overlayVisible) overlayVisible = false else viewModel.showVideoOverlay()
                },
                tapGestureState = tapGestureState,
                seekGestureState = seekGestureState,
                volumeAndBrightnessGestureState = volumeAndBrightnessGestureState,
                zoomState = zoomState
            )
            androidx.compose.animation.AnimatedVisibility(
                visible = overlayVisible && videoPlaybackActive && !controlsLocked,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut()
            ) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))
            }
            if (buffering) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center).size(72.dp))
            }
            DoubleTapIndicator(tapGestureState = tapGestureState)
            val seekAmountMs = seekGestureState.seekAmountMs
            when {
                seekAmountMs != null -> GestureInfoText(
                    info = "${if (seekAmountMs < 0) "-" else "+"}${formatVideoTime(abs(seekAmountMs))}\n" +
                        "[${formatVideoTime(seekGestureState.targetPositionMs ?: 0)}]",
                    modifier = Modifier.align(Alignment.Center)
                )
                zoomState.isZooming -> GestureInfoText(
                    info = "${(zoomState.zoom * 100).toInt()}%",
                    modifier = Modifier.align(Alignment.Center)
                )
                zoomState.showContentScaleIndicator -> GestureInfoText(
                    info = stringResource(zoomState.contentScale.nameRes()),
                    modifier = Modifier.align(Alignment.Center)
                )
                else -> androidx.compose.animation.AnimatedVisibility(
                    visible = overlayVisible && videoPlaybackActive && !controlsLocked,
                    enter = androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut(),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    PlayPauseButton(
                        playing = playing,
                        onClick = { viewModel.toggleVideoPlayPause() },
                        modifier = Modifier
                            .focusRequester(playPauseFocusRequester)
                            .onFocusChanged { isPlayPauseFocused = it.hasFocus }
                    )
                }
            }
            DpadSeekIndicator(
                visible = dpadSeekActive && dpadSeekOffsetMs != 0L,
                offsetMs = dpadSeekOffsetMs,
                positionMs = dpadSeekTargetMs
            )
            androidx.compose.animation.AnimatedVisibility(
                visible = volumeAndBrightnessGestureState.activeGesture == VerticalGesture.VOLUME,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier.align(Alignment.CenterStart).padding(24.dp)
            ) {
                VerticalProgressIndicator(value = serverVolume.percentage, icon = Icons.AutoMirrored.Rounded.VolumeUp)
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = volumeAndBrightnessGestureState.activeGesture == VerticalGesture.BRIGHTNESS,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier.align(Alignment.CenterEnd).padding(24.dp)
            ) {
                VerticalProgressIndicator(value = brightnessState?.percentage ?: 0, icon = Icons.Rounded.BrightnessHigh)
            }
            if (controlsLocked) {
                if (overlayVisible) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .safeDrawingPadding()
                            .padding(top = 24.dp)
                    ) {
                        UnlockButton(
                            onClick = {
                                controlsLocked = false
                                viewModel.showVideoOverlay()
                            },
                            modifier = Modifier
                                .focusRequester(unlockFocusRequester)
                                .onFocusChanged { isUnlockFocused = it.hasFocus }
                        )
                    }
                }
            } else {
                androidx.compose.animation.AnimatedVisibility(
                    visible = overlayVisible,
                    enter = androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    VideoControlsTop(
                        title = videoTitle,
                        videoUrl = videoLocation,
                        downloadProgress = downloadProgress,
                        showDownload = durationMs > 0,
                        onBackClick = { viewModel.stopVideoPlayback() },
                        onSpeedClick = {
                            overlayVisible = false
                            showSpeedSelector = true
                        },
                        onDownloadClick = { viewModel.toggleVideoDownload() }
                    )
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = overlayVisible,
                    enter = androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    VideoControlsBottom(
                        positionMs = scrubPositionMs ?: positionMs,
                        durationMs = durationMs,
                        contentScale = zoomState.contentScale,
                        isPipSupported = isPipSupported,
                        onRotateClick = {
                            activity?.let {
                                it.requestedOrientation =
                                    if (it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                                    } else {
                                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                    }
                            }
                        },
                        onLockClick = {
                            viewModel.showVideoOverlay()
                            controlsLocked = true
                        },
                        onContentScaleClick = {
                            viewModel.showVideoOverlay()
                            zoomState.switchToNextContentScale()
                        },
                        onPipClick = onPip,
                        onSeek = { seekGestureState.onSeek(it) },
                        onSeekEnd = { seekGestureState.onSeekEnd() },
                        seekBarModifier = Modifier
                            .focusProperties { up = playPauseFocusRequester }
                            .dpadAdjust(
                                onLeft = {
                                    viewModel.seekVideoBy(-TapGestureState.SEEK_INCREMENT_MS)
                                    viewModel.showVideoOverlay()
                                },
                                onRight = {
                                    viewModel.seekVideoBy(TapGestureState.SEEK_INCREMENT_MS)
                                    viewModel.showVideoOverlay()
                                }
                            )
                    )
                }
            }
            val skipSilence by viewModel.videoSkipSilence.collectAsState()
            PlaybackSpeedSelector(
                show = showSpeedSelector,
                speed = speed,
                skipSilence = skipSilence,
                onSpeedChange = { viewModel.setVideoSpeed(it) },
                onSkipSilenceChange = { viewModel.setVideoSkipSilence(it) },
                onDismiss = { showSpeedSelector = false }
            )
        }
        return
    }

    // pip mode: show only the video surface
    if (isInPip) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            video()
        }
        return
    }

    // restore system bars when exiting pip back to non-fullscreen
    LaunchedEffect(isInPip) {
        if (!isInPip && !fullscreen) {
            val window = activity?.window ?: return@LaunchedEffect
            WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // exit fullscreen while a pin is being shown so the dialog isn't covered
    LaunchedEffect(pin) {
        if (pin != null) fullscreen = false
    }

    if (fullscreen) {
        BackHandler { fullscreen = false }
        FullscreenVideo(
            viewModel = viewModel,
            video = video,
            onExitFullscreen = { fullscreen = false },
            onPip = onPip
        )
    } else {
        Scaffold(
            bottomBar = {
                Column {
                    AudioMiniController(
                        viewModel,
                        visible = musicActive && !musicFullscreen && tab != Tab.OVERVIEW,
                        onClick = {
                            tab = Tab.OVERVIEW
                            musicFullscreen = true
                        }
                    )
                    NavigationBar {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = tab == t,
                                onClick = { tab = t },
                                icon = { Icon(t.icon, null) },
                                label = { Text(stringResource(t.labelRes)) },
                                modifier = Modifier.dpadFocus()
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
                TabContent(
                    tab, viewModel, video,
                    onFullscreen = { fullscreen = true }, onPip = onPip, onLocalNetwork = onLocalNetwork,
                    showAudioMode = musicActive,
                    onOpenMusicFullscreen = { musicFullscreen = true }
                )
            }
        }
    }

    // pin dialog
    if (pin != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissPin() },
            title = { Text(stringResource(R.string.dialog_pin_title)) },
            text = {
                Text(
                    text = pin!!,
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissPin() }) { Text(stringResource(R.string.btn_ok)) }
            }
        )
    }

}

@Composable
private fun TabContent(
    tab: Tab,
    viewModel: MainViewModel,
    video: @Composable () -> Unit,
    onFullscreen: () -> Unit,
    onPip: () -> Unit,
    onLocalNetwork: () -> Unit,
    showAudioMode: Boolean,
    onOpenMusicFullscreen: () -> Unit
) {
    when (tab) {
        Tab.OVERVIEW -> OverviewContent(
            viewModel, video,
            onFullscreen = onFullscreen, onPip = onPip, onLocalNetwork = onLocalNetwork, showAudioMode = showAudioMode,
            onOpenMusicFullscreen = onOpenMusicFullscreen
        )
        Tab.LOGS -> LogsScreen(viewModel)
        Tab.SETTINGS -> SettingsScreen(viewModel)
    }
}

@Composable
private fun OverviewContent(
    viewModel: MainViewModel,
    video: @Composable () -> Unit,
    onFullscreen: () -> Unit,
    onPip: () -> Unit,
    onLocalNetwork: () -> Unit,
    showAudioMode: Boolean = false,
    onOpenMusicFullscreen: () -> Unit = {}
) {
    val state by viewModel.serverState.collectAsState()
    val localNetwork by viewModel.localNetwork.collectAsState()
    val grant = state != ServerState.RUNNING && localNetwork != LocalNetworkAccess.GRANTED
    val connections by viewModel.connectionCount.collectAsState()
    val serverName by viewModel.pref(Prefs.SERVER_NAME).collectAsState()
    val videoResolution by viewModel.videoResolution.collectAsState()
    val idlePreview by viewModel.pref(Prefs.IDLE_PREVIEW).collectAsState()
    val mirroringActive by viewModel.mirroringActive.collectAsState()
    val videoPlaybackActive by viewModel.videoPlaybackActive.collectAsState()
    val debugEnabled by viewModel.pref(Prefs.DEBUG_ENABLED).collectAsState()
    val debugInfo by viewModel.debugInfo.collectAsState()
    val tv = isTv()
    val startFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (tv) startFocus.requestFocus()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // content area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            val connecting = state == ServerState.RUNNING && connections > 0 &&
                !mirroringActive && !videoPlaybackActive
            if (showAudioMode && state == ServerState.RUNNING && connections > 0) {
                // Compact overview while music continues in the background after Back
                MusicPlayingOverview(
                    viewModel = viewModel,
                    onOpenFullscreen = onOpenMusicFullscreen
                )
            } else {
                if (state == ServerState.RUNNING && (mirroringActive || idlePreview)) {
                    video()
                }
                if (state != ServerState.RUNNING || connections == 0) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = if (connections > 0) Icons.Default.CastConnected else Icons.Default.Cast,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = if (grant) stringResource(R.string.local_network_desc) else when (state) {
                                ServerState.STOPPED -> stringResource(R.string.server_stopped)
                                ServerState.RUNNING -> stringResource(R.string.waiting_for_connection)
                                ServerState.ERROR -> stringResource(R.string.error_starting_server)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                } else if (connecting) {
                    Text(
                        text = stringResource(R.string.waiting_for_playback),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                if (state == ServerState.RUNNING && mirroringActive) {
                    Row(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                        IconButton(onClick = onPip, modifier = Modifier.dpadFocus()) {
                            Icon(
                                painterResource(R.drawable.ic_pip), contentDescription = stringResource(R.string.cd_pip),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                        IconButton(onClick = onFullscreen, modifier = Modifier.dpadFocus()) {
                            Icon(
                                Icons.Default.Fullscreen, contentDescription = stringResource(R.string.cd_fullscreen),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
            if (debugEnabled && connections > 0) {
                DebugOverlay(debugInfo, Modifier.align(Alignment.TopStart).padding(8.dp))
            }
            var showRes by remember { mutableStateOf(false) }
            LaunchedEffect(videoResolution) {
                if (videoResolution.isNotEmpty() && !showAudioMode) {
                    showRes = true
                    delay(5000)
                    showRes = false
                }
            }
            if (!showAudioMode) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = showRes && connections > 0,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                ) {
                    Text(
                        text = videoResolution,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }

        // controls
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = serverName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(2.dp))
                        val statusColor by animateColorAsState(
                            if (grant) MaterialTheme.colorScheme.error else when (state) {
                                ServerState.RUNNING -> MaterialTheme.colorScheme.primary
                                ServerState.ERROR -> MaterialTheme.colorScheme.error
                                ServerState.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
                            }, label = "status"
                        )
                        Text(
                            text = if (grant) stringResource(R.string.local_network_label) else when (state) {
                                ServerState.RUNNING -> stringResource(R.string.connected_count, connections)
                                ServerState.ERROR -> stringResource(R.string.error_label)
                                ServerState.STOPPED -> stringResource(R.string.stopped_label)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = statusColor
                        )
                    }

                    val settings = localNetwork == LocalNetworkAccess.SETTINGS
                    val (icon, label, action) = when {
                        grant -> Triple(
                            if (settings) Icons.Default.Settings else Icons.Default.LockOpen,
                            if (settings) R.string.btn_open_settings else R.string.btn_allow,
                            onLocalNetwork
                        )
                        state == ServerState.RUNNING -> Triple(Icons.Default.Stop, R.string.btn_stop, viewModel::stopServer)
                        else -> Triple(Icons.Default.PlayArrow, R.string.btn_start, viewModel::startServer)
                    }
                    FilledTonalButton(
                        onClick = action,
                        modifier = Modifier.dpadFocus().focusRequester(startFocus)
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(label))
                    }
                }
            }
        }
    }
}

@Composable
private fun FullscreenVideo(
    viewModel: MainViewModel,
    video: @Composable () -> Unit,
    onExitFullscreen: () -> Unit,
    onPip: () -> Unit
) {
    val videoResolution by viewModel.videoResolution.collectAsState()
    val debugEnabled by viewModel.pref(Prefs.DEBUG_ENABLED).collectAsState()
    val debugInfo by viewModel.debugInfo.collectAsState()

    var controlsVisible by remember { mutableStateOf(true) }
    var tapTick by remember { mutableStateOf(0) }
    LaunchedEffect(tapTick) {
        controlsVisible = true
        delay(8000)
        controlsVisible = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { tapTick++ } },
        contentAlignment = Alignment.Center
    ) {
        video()
        androidx.compose.animation.AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            Row(modifier = Modifier.padding(8.dp)) {
                IconButton(onClick = onPip, modifier = Modifier.dpadFocus()) {
                    Icon(
                        painterResource(R.drawable.ic_pip), contentDescription = stringResource(R.string.cd_pip),
                        tint = Color.White.copy(alpha = 0.7f)
                    )
                }
                IconButton(onClick = onExitFullscreen, modifier = Modifier.dpadFocus()) {
                    Icon(
                        Icons.Default.FullscreenExit, contentDescription = stringResource(R.string.cd_exit_fullscreen),
                        tint = Color.White.copy(alpha = 0.7f)
                    )
                }
            }
        }
        if (debugEnabled) {
            DebugOverlay(debugInfo, Modifier.align(Alignment.TopStart).padding(8.dp))
        }
        var showRes by remember { mutableStateOf(false) }
        LaunchedEffect(videoResolution) {
            if (videoResolution.isNotEmpty()) {
                showRes = true
                delay(5000)
                showRes = false
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = showRes,
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
        ) {
            Text(
                text = videoResolution,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.6f)
            )
        }
    }
}

// Soft edge-to-edge scrim (accent) behind chrome that matches the title color.
@Composable
private fun MinimalAudioProgress(
    viewModel: MainViewModel,
    accent: Color,
    chrome: Color
) {
    val positionMs by viewModel.audioPositionMs.collectAsState()
    val durationMs by viewModel.audioDurationMs.collectAsState()
    val fraction = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val timeStyle = MaterialTheme.typography.labelSmall

    Box(modifier = Modifier.fillMaxSize()) {
        // Gradient follows the independently detected top-zone contrast.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to accent.copy(alpha = 0.55f),
                            0.45f to accent.copy(alpha = 0.28f),
                            1f to Color.Transparent
                        )
                    )
                )
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 48.dp, vertical = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(chrome.copy(alpha = 0.25f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction)
                        .background(chrome.copy(alpha = 0.95f))
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatAudioTime(positionMs),
                    style = timeStyle,
                    color = chrome.copy(alpha = 0.78f)
                )
                Text(
                    text = formatAudioTime(durationMs),
                    style = timeStyle,
                    color = chrome.copy(alpha = 0.78f)
                )
            }
        }
    }
}

@Composable
private fun AmbientProgress(
    viewModel: MainViewModel,
    color: Color,
    showTimes: Boolean,
    modifier: Modifier = Modifier
) {
    val positionMs by viewModel.audioPositionMs.collectAsState()
    val durationMs by viewModel.audioDurationMs.collectAsState()
    if (durationMs <= 0L) return
    val fraction = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color.copy(alpha = 0.2f))
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(color.copy(alpha = 0.75f))
            )
        }
        // fixed height so revealing the times doesn't move anything
        Box(Modifier.fillMaxWidth().height(24.dp)) {
            androidx.compose.animation.AnimatedVisibility(
                visible = showTimes,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val style = MaterialTheme.typography.labelMedium
                    Text(formatAudioTime(positionMs), style = style, color = color.copy(alpha = 0.7f))
                    Text(formatAudioTime(durationMs), style = style, color = color.copy(alpha = 0.7f))
                }
            }
        }
    }
}

@Composable
private fun NowPlayingClock(color: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val time by produceState(initialValue = "") {
        val fmt = android.text.format.DateFormat.getTimeFormat(context)
        while (true) {
            value = fmt.format(java.util.Date())
            delay(60_000 - System.currentTimeMillis() % 60_000)
        }
    }
    Text(
        text = time,
        style = MaterialTheme.typography.titleLarge,
        color = color,
        modifier = modifier
    )
}

// new content fades in over the old one, which stays opaque: no dip to the background mid-way
private fun overlayFade(ms: Int): ContentTransform =
    androidx.compose.animation.fadeIn(tween(ms)) togetherWith
        androidx.compose.animation.fadeOut(tween(durationMillis = 0, delayMillis = ms))

@Composable
private fun rememberStableBitmap(bitmap: Bitmap?): Bitmap? {
    val last = remember { arrayOfNulls<Bitmap>(1) }
    val prev = last[0]
    val stable = if (bitmap != null && prev != null && bitmap !== prev && bitmap.sameAs(prev)) prev else bitmap
    last[0] = stable
    return stable
}

// Apple TV-style press: the button dips and springs back instead of a ripple
@Composable
private fun TransportButton(
    onClick: () -> Unit,
    color: Color,
    modifier: Modifier = Modifier,
    pressEffect: Boolean = true,
    content: @Composable () -> Unit
) {
    val source = remember { MutableInteractionSource() }
    val scale = remember { Animatable(1f) }
    if (pressEffect) LaunchedEffect(source) {
        source.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> launch { scale.animateTo(PRESS_SCALE, tween(80)) }
                is PressInteraction.Release, is PressInteraction.Cancel -> launch {
                    // a quick tap still shows the full dip
                    if (scale.value > PRESS_SCALE + 0.01f) scale.animateTo(PRESS_SCALE, tween(80))
                    scale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 600f))
                }
            }
        }
    }
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(LocalContentColor provides color, content = content)
    }
}

private const val PRESS_SCALE = 0.88f

@Composable
private fun CoverArtShadow(
    accent: Color,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // Soft radial glow (smooth fade like the progress scrim), max ~25% opacity
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colorStops = arrayOf(
                            0.00f to accent.copy(alpha = 0.25f),
                            0.45f to accent.copy(alpha = 0.18f),
                            0.70f to accent.copy(alpha = 0.10f),
                            0.88f to accent.copy(alpha = 0.04f),
                            1.00f to Color.Transparent
                        )
                    )
                )
        )
        // Cover inset so the glow halo is visible around the edges
        Box(
            modifier = Modifier
                .fillMaxSize(0.86f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF2C2C2E)),
            content = content
        )
    }
}

private fun formatAudioTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = (ms / 1000).toInt()
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

/** Foreground and its opposite-color ambient scrim for one screen region. */
private data class ZoneContrast(val chrome: Color, val scrim: Color)

private data class NowPlayingContrast(
    val top: ZoneContrast,
    val middle: ZoneContrast,
    val bottom: ZoneContrast,
)

/**
 * Samples a rectangle expressed in viewport coordinates from the exact source
 * crop produced by ContentScale.Crop.
 */
private fun viewportRegionLuminances(
    bitmap: Bitmap,
    viewportAspect: Float,
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    columns: Int = 24,
    rows: Int = 16,
): FloatArray {
    val width = bitmap.width.coerceAtLeast(1)
    val height = bitmap.height.coerceAtLeast(1)
    val sourceAspect = width.toFloat() / height.toFloat()
    val safeViewportAspect = viewportAspect.coerceAtLeast(0.1f)

    var cropX = 0f
    var cropY = 0f
    var visibleWidth = 1f
    var visibleHeight = 1f
    if (sourceAspect > safeViewportAspect) {
        visibleWidth = safeViewportAspect / sourceAspect
        cropX = (1f - visibleWidth) / 2f
    } else {
        visibleHeight = sourceAspect / safeViewportAspect
        cropY = (1f - visibleHeight) / 2f
    }

    fun linearChannel(channel: Int): Double {
        val srgb = channel / 255.0
        return if (srgb <= 0.04045) {
            srgb / 12.92
        } else {
            ((srgb + 0.055) / 1.055).pow(2.4)
        }
    }

    return FloatArray(columns * rows) { index ->
        val column = index % columns
        val row = index / columns
        val viewportX = x0 + (x1 - x0) * ((column + 0.5f) / columns)
        val viewportY = y0 + (y1 - y0) * ((row + 0.5f) / rows)
        val sourceX = cropX + viewportX.coerceIn(0f, 1f) * visibleWidth
        val sourceY = cropY + viewportY.coerceIn(0f, 1f) * visibleHeight
        val pixelX = (sourceX * (width - 1)).toInt().coerceIn(0, width - 1)
        val pixelY = (sourceY * (height - 1)).toInt().coerceIn(0, height - 1)
        val pixel = bitmap.getPixel(pixelX, pixelY)
        val red = linearChannel((pixel shr 16) and 0xff)
        val green = linearChannel((pixel shr 8) and 0xff)
        val blue = linearChannel(pixel and 0xff)
        (0.2126 * red + 0.7152 * green + 0.0722 * blue).toFloat()
    }
}

// downscale + repeated box blur ≈ heavy gaussian; bilinear upscaling keeps it smooth
private fun ambientBackdrop(src: Bitmap): Bitmap {
    val w = 56
    val h = (w * src.height / src.width.coerceAtLeast(1)).coerceAtLeast(1)
    val small = Bitmap.createScaledBitmap(src, w, h, true)
    val px = IntArray(w * h)
    small.getPixels(px, 0, w, 0, 0, w, h)
    repeat(3) {
        boxBlur(px, w, h, 4, horizontal = true)
        boxBlur(px, w, h, 4, horizontal = false)
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
}

private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
    val lines = if (horizontal) h else w
    val len = if (horizontal) w else h
    val line = IntArray(len)
    val n = 2 * r + 1
    for (l in 0 until lines) {
        fun idx(i: Int) = if (horizontal) l * w + i else i * w + l
        for (i in 0 until len) line[i] = px[idx(i)]
        var rs = 0; var gs = 0; var bs = 0
        for (k in -r..r) {
            val c = line[k.coerceIn(0, len - 1)]
            rs += (c shr 16) and 0xff; gs += (c shr 8) and 0xff; bs += c and 0xff
        }
        for (i in 0 until len) {
            px[idx(i)] = (0xff shl 24) or ((rs / n) shl 16) or ((gs / n) shl 8) or (bs / n)
            val out = line[(i - r).coerceIn(0, len - 1)]
            val inc = line[(i + r + 1).coerceIn(0, len - 1)]
            rs += ((inc shr 16) and 0xff) - ((out shr 16) and 0xff)
            gs += ((inc shr 8) and 0xff) - ((out shr 8) and 0xff)
            bs += (inc and 0xff) - (out and 0xff)
        }
    }
}

/**
 * Strongly prefers white and switches to black only when the zone is predominantly
 * bright. The median drives the choice while the 10th percentile still gives
 * sizeable bright patches limited influence.
 */
private fun contrastFromSamples(luminances: FloatArray): ZoneContrast {
    if (luminances.isEmpty()) return ZoneContrast(Color.White, Color.Black)

    fun robustScore(ratios: FloatArray): Float {
        ratios.sort()
        val low = ratios[((ratios.lastIndex * 0.10f).toInt()).coerceIn(ratios.indices)]
        val median = ratios[ratios.size / 2]
        return low * 0.25f + median * 0.75f
    }

    val whiteRatios = FloatArray(luminances.size) { 1.05f / (luminances[it] + 0.05f) }
    return if (robustScore(whiteRatios) < 2.25f) {
        ZoneContrast(chrome = Color(0xFF101010), scrim = Color.White)
    } else {
        ZoneContrast(chrome = Color.White, scrim = Color.Black)
    }
}

private fun nowPlayingContrastFromCover(
    bitmap: Bitmap?,
    viewportAspect: Float,
    topEnd: Float,
    bottomStart: Float,
): NowPlayingContrast {
    val fallback = ZoneContrast(chrome = Color.White, scrim = Color.Black)
    if (bitmap == null) return NowPlayingContrast(fallback, fallback, fallback)

    val top = contrastFromSamples(
        viewportRegionLuminances(bitmap, viewportAspect, 0.02f, 0f, 0.98f, topEnd)
    )
    val middle = contrastFromSamples(
        viewportRegionLuminances(bitmap, viewportAspect, 0.46f, 0.36f, 0.98f, 0.64f)
    )
    val bottom = contrastFromSamples(
        viewportRegionLuminances(bitmap, viewportAspect, 0.02f, bottomStart, 0.98f, 1f)
    )
    return NowPlayingContrast(top = top, middle = middle, bottom = bottom)
}

@Composable
@AndroidxOptIn(UnstableApi::class)
private fun AudioMiniController(viewModel: MainViewModel, visible: Boolean, onClick: () -> Unit) {
    if (!visible) return
    val player = viewModel.dacpPlayer ?: return
    val context = LocalContext.current
    MiniController(
        player,
        bitmapLoader = remember { DataSourceBitmapLoader.Builder(context).build() },
        onClick = onClick
    )
}

@Composable
private fun MusicPlayingOverview(
    viewModel: MainViewModel,
    onOpenFullscreen: () -> Unit
) {
    val track by viewModel.trackInfo.collectAsState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .focusRequester(focus)
            .focusable()
            .clickable(onClick = onOpenFullscreen)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown &&
                    (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter)
                ) {
                    onOpenFullscreen()
                    true
                } else {
                    false
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        NowPlayingCoverArt(viewModel, fill = false, modifier = Modifier.fillMaxWidth(0.45f))
        Spacer(Modifier.height(16.dp))
        Text(
            text = track.title.ifEmpty { stringResource(R.string.unknown_track) },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (track.artist.isNotEmpty()) {
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.music_press_ok_fullscreen),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun NowPlayingCoverArt(
    viewModel: MainViewModel,
    fill: Boolean,
    modifier: Modifier = Modifier
) {
    val track by viewModel.trackInfo.collectAsState()
    Box(
        modifier = modifier
            .then(if (fill) Modifier.fillMaxSize() else Modifier.aspectRatio(1f))
            .clip(if (fill) RoundedCornerShape(0.dp) else RoundedCornerShape(12.dp))
            .background(Color(0xFF1C1C1E)),
        contentAlignment = Alignment.Center
    ) {
        if (track.coverArt != null) {
            Image(
                bitmap = track.coverArt!!.asImageBitmap(),
                contentDescription = stringResource(R.string.cd_cover_art),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(if (fill) 96.dp else 64.dp),
                tint = Color.White.copy(alpha = 0.35f)
            )
        }
    }
}

/**
 * Apple Music (tvOS)-style now playing: full-screen artwork, chrome hidden until Select/OK.
 * Back leaves this view and returns to the main tabs without stopping playback.
 */
@Composable
@AndroidxOptIn(UnstableApi::class)
private fun FullscreenNowPlaying(
    viewModel: MainViewModel,
    onExit: () -> Unit
) {
    val track by viewModel.trackInfo.collectAsState()
    val audioPlaying by viewModel.audioPlaying.collectAsState()
    var controlsVisible by remember { mutableStateOf(false) }
    var showTick by remember { mutableIntStateOf(0) }
    val rootFocus = remember { FocusRequester() }
    val playPauseFocus = remember { FocusRequester() }
    var playPauseFocused by remember { mutableStateOf(false) }

    val configuration = LocalConfiguration.current
    val viewportAspect = configuration.screenWidthDp.toFloat() /
        configuration.screenHeightDp.coerceAtLeast(1).toFloat()
    val topEnd = (72f / configuration.screenHeightDp.coerceAtLeast(1)).coerceIn(0.04f, 0.16f)
    val bottomStart = (1f - 104f / configuration.screenHeightDp.coerceAtLeast(1))
        .coerceIn(0.80f, 0.94f)
    // the sender resends the same artwork on play/pause: keep one instance so nothing re-animates
    val coverArt = rememberStableBitmap(track.coverArt)
    // Sampling is intentionally synchronous: it is small enough to finish within
    // the composition frame, keeping artwork and chrome color updates atomic.
    val contrast = remember(
        coverArt,
        viewportAspect,
        topEnd,
        bottomStart,
    ) {
        nowPlayingContrastFromCover(coverArt, viewportAspect, topEnd, bottomStart)
    }
    val top = contrast.top
    val middle = contrast.middle
    val bottom = contrast.bottom
    val artistColor = middle.chrome.copy(alpha = 0.78f)

    fun revealControls() {
        controlsVisible = true
        showTick++
    }

    fun bumpIdle() {
        if (controlsVisible) showTick++
    }

    // Auto-hide only while playing and idle; stay up while paused or while user keeps interacting
    LaunchedEffect(showTick, controlsVisible, audioPlaying) {
        if (!controlsVisible || !audioPlaying) return@LaunchedEffect
        delay(MUSIC_CONTROLS_HIDE_MS)
        controlsVisible = false
    }

    LaunchedEffect(controlsVisible) {
        if (controlsVisible) {
            playPauseFocus.requestFocusUntilLanded(attempts = 16) { playPauseFocused }
        } else {
            runCatching { rootFocus.requestFocus() }
        }
    }

    BackHandler {
        if (controlsVisible) controlsVisible = false else onExit()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .pointerInput(Unit) {
                detectTapGestures {
                    if (controlsVisible) controlsVisible = false else revealControls()
                }
            }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.MediaNext, Key.MediaSkipForward -> {
                        viewModel.audioNext()
                        revealControls()
                        true
                    }
                    Key.MediaPrevious, Key.MediaSkipBackward -> {
                        viewModel.audioPrev()
                        revealControls()
                        true
                    }
                    Key.MediaPlayPause -> {
                        viewModel.audioTogglePlayPause()
                        revealControls()
                        true
                    }
                    Key.MediaPlay -> {
                        if (!audioPlaying) viewModel.audioTogglePlayPause()
                        revealControls()
                        true
                    }
                    Key.MediaPause -> {
                        if (audioPlaying) viewModel.audioTogglePlayPause()
                        revealControls()
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter,
                    Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown -> {
                        if (!controlsVisible) {
                            revealControls()
                            true
                        } else {
                            bumpIdle()
                            false
                        }
                    }
                    Key.Back, Key.Escape -> {
                        if (controlsVisible) {
                            controlsVisible = false
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Ambient backdrop: software-blurred so it is smooth on every API level; slow zoom keeps it alive
        val drift by rememberInfiniteTransition(label = "drift").animateFloat(
            initialValue = 1f,
            targetValue = 1.12f,
            animationSpec = infiniteRepeatable(tween(30_000, easing = LinearEasing), RepeatMode.Reverse),
            label = "driftScale"
        )
        AnimatedContent(targetState = coverArt, transitionSpec = { overlayFade(800) }, label = "backdrop") { art ->
            val backdrop = remember(art) { art?.let(::ambientBackdrop)?.asImageBitmap() }
            if (backdrop != null) {
                Image(
                    bitmap = backdrop,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { scaleX = drift; scaleY = drift },
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High
                )
            } else {
                Box(Modifier.fillMaxSize())
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))

        // Always-on thin progress and clock while the chrome is hidden
        androidx.compose.animation.AnimatedVisibility(
            visible = !controlsVisible,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(Modifier.fillMaxSize()) {
                NowPlayingClock(
                    color = top.chrome.copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 32.dp, end = 48.dp)
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 0.dp)
        ) {
            // Top spacer keeps the middle band where it was; progress now lives under the title
            Spacer(Modifier.fillMaxWidth().height(72.dp))

            // Cover left, title/artist right — fixed middle band
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 96.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(56.dp)
            ) {
                // Apple Music-style pause: the cover shrinks back
                val coverScale by animateFloatAsState(
                    targetValue = if (audioPlaying) 1f else 0.84f,
                    animationSpec = tween(450),
                    label = "coverScale"
                )
                CoverArtShadow(
                    accent = middle.chrome,
                    modifier = Modifier
                        .fillMaxHeight(0.6f)
                        .aspectRatio(1f)
                        .graphicsLayer {
                            scaleX = coverScale
                            scaleY = coverScale
                        }
                ) {
                    AnimatedContent(targetState = coverArt, transitionSpec = { overlayFade(600) }, label = "cover") { art ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            if (art != null) {
                                Image(
                                    bitmap = art.asImageBitmap(),
                                    contentDescription = stringResource(R.string.cd_cover_art),
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(
                                    Icons.Default.MusicNote,
                                    contentDescription = null,
                                    modifier = Modifier.size(96.dp),
                                    tint = Color.White.copy(alpha = 0.35f)
                                )
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = track.title.ifEmpty { stringResource(R.string.unknown_track) },
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = middle.chrome,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (track.artist.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = track.artist,
                            style = MaterialTheme.typography.titleLarge,
                            color = artistColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    val details = listOf(track.album, track.genre).filter { it.isNotEmpty() }.joinToString(" · ")
                    if (details.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = details,
                            style = MaterialTheme.typography.titleMedium,
                            color = middle.chrome.copy(alpha = 0.55f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(28.dp))
                    AmbientProgress(
                        viewModel = viewModel,
                        color = middle.chrome,
                        showTimes = controlsVisible,
                        modifier = Modifier.fillMaxWidth(0.85f)
                    )
                }
            }

            // Fixed slot so transport fades in below without shifting cover/text
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(104.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = controlsVisible,
                    enter = androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut()
                ) {
                    Box(Modifier.fillMaxSize()) {
                        // Edge-to-edge gradient from the bottom up
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colorStops = arrayOf(
                                            0f to Color.Transparent,
                                            0.4f to bottom.scrim.copy(alpha = 0.28f),
                                            1f to bottom.scrim.copy(alpha = 0.55f)
                                        )
                                    )
                                )
                        )
                        CompositionLocalProvider(LocalContentColor provides bottom.chrome) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.Center)
                                    .padding(horizontal = 48.dp, vertical = 8.dp)
                            ) {
                                TransportButton(
                                    onClick = {
                                        bumpIdle()
                                        viewModel.audioPrev()
                                    },
                                    modifier = Modifier
                                        .size(56.dp)
                                        .dpadFocus(CircleShape, bottom.chrome)
                                        .onFocusChanged { if (it.hasFocus) bumpIdle() },
                                    color = bottom.chrome
                                ) {
                                    Icon(
                                        Icons.Default.SkipPrevious,
                                        contentDescription = stringResource(R.string.cd_previous),
                                        modifier = Modifier.size(36.dp)
                                    )
                                }
                                TransportButton(
                                    onClick = {
                                        bumpIdle()
                                        viewModel.audioTogglePlayPause()
                                    },
                                    modifier = Modifier
                                        .size(72.dp)
                                        .focusRequester(playPauseFocus)
                                        .onFocusChanged {
                                            playPauseFocused = it.hasFocus
                                            if (it.hasFocus) bumpIdle()
                                        }
                                        .dpadFocus(CircleShape, bottom.chrome),
                                    color = bottom.chrome,
                                    pressEffect = false
                                ) {
                                    Icon(
                                        if (audioPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                        contentDescription = stringResource(R.string.cd_play_pause),
                                        modifier = Modifier.size(44.dp)
                                    )
                                }
                                TransportButton(
                                    onClick = {
                                        bumpIdle()
                                        viewModel.audioNext()
                                    },
                                    modifier = Modifier
                                        .size(56.dp)
                                        .dpadFocus(CircleShape, bottom.chrome)
                                        .onFocusChanged { if (it.hasFocus) bumpIdle() },
                                    color = bottom.chrome
                                ) {
                                    Icon(
                                        Icons.Default.SkipNext,
                                        contentDescription = stringResource(R.string.cd_next),
                                        modifier = Modifier.size(36.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VolumeSlider(
    icon: Painter,
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onStep: (Int) -> Unit,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                onValueChangeFinished = onValueChangeFinished,
                modifier = Modifier
                    .semantics { contentDescription = label }
                    .dpadFocus()
                    .dpadAdjust(onLeft = { onStep(-1) }, onRight = { onStep(1) })
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.volume_value, (value * 100).roundToInt()),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 40.dp)
        )
    }
}

// sender has 16 volume steps above mute
private const val CLIENT_VOLUME_STEP = 1f / 16

private const val VIDEO_OVERLAY_HIDE_MS = 4000L
private const val MUSIC_CONTROLS_HIDE_MS = 5000L

@Composable
private fun DebugOverlay(info: DebugInfo, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        val style = MaterialTheme.typography.labelSmall
        val headingStyle = style.copy(fontWeight = FontWeight.Bold)
        val color = Color.White.copy(alpha = 0.9f)
        val headingColor = Color(0xFF80D8FF) // cyan
        val context = LocalContext.current
        debugOverlaySections(context, info).forEachIndexed { i, section ->
            if (i > 0) Spacer(Modifier.height(6.dp))
            Text(section.title.uppercase(), style = headingStyle, color = headingColor)
            section.lines.forEach { Text(it, style = style, color = color) }
        }
    }
}

private data class DebugSection(val title: String, val lines: List<String>)

private fun debugOverlaySections(context: Context, info: DebugInfo): List<DebugSection> = buildList {
    buildList {
        if (info.videoCodec.isNotEmpty()) {
            add(context.getString(R.string.debug_video, info.videoCodec, info.videoRes))
            add(context.getString(R.string.debug_fps_bitrate, info.videoFps, info.bitrateStr))
            add(context.getString(R.string.debug_frames_drops, info.videoFrames, info.droppedFrames))
            add(context.getString(R.string.debug_jitter, info.jitterStr))
        }
    }.takeIf { it.isNotEmpty() }?.let { add(DebugSection(context.getString(R.string.debug_section_video), it)) }

    buildList {
        if (info.audioCodec.isNotEmpty()) add(context.getString(R.string.debug_audio, info.audioCodec, info.serverVolume, info.clientVolume))
        info.audio?.let { a ->
            add(context.getString(R.string.debug_audio_buffer, a.backlogMs, a.tunedCushionMs))
            add(context.getString(R.string.debug_audio_glitch, a.trims, a.drops, a.silences, a.underruns, a.xrun))
            add(context.getString(R.string.debug_audio_decode, formatDecode(a.decodeMeanUs, a.decodeMaxUs, a.decodeHeld, a.decodeErrors)))
        }
    }.takeIf { it.isNotEmpty() }?.let { add(DebugSection(context.getString(R.string.debug_section_audio), it)) }

    add(DebugSection(context.getString(R.string.debug_section_network), listOf(context.getString(R.string.debug_clients, info.connections))))
}

// "mean/max ms held=N", or "held=N" before first latency window lands
private fun formatDecode(meanUs: Int, maxUs: Int, held: Int, errors: Int): String =
    (if (meanUs == 0) "held=$held"
    else "%.1f/%.1f ms held=%d".format(meanUs / 1000.0, maxUs / 1000.0, held)) + " errs=$errors"
