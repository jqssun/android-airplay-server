package io.github.jqssun.airplay.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.service.AirPlayService.ServerState
import io.github.jqssun.airplay.viewmodel.DebugInfo
import io.github.jqssun.airplay.viewmodel.MainViewModel
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.delay

private enum class Tab(val label: String) {
    OVERVIEW("Overview"), LOGS("Logs"), SETTINGS("Settings")
}

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    isInPip: Boolean = false,
    onSurfaceAvailable: (android.view.Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onPip: () -> Unit = {}
) {
    var tab by remember { mutableStateOf(Tab.OVERVIEW) }
    var fullscreen by remember { mutableStateOf(false) }
    val pin by viewModel.pinCode.collectAsState()
    val videoAspect by viewModel.videoAspect.collectAsState()
    val connections by viewModel.connectionCount.collectAsState()
    val audioOnly by viewModel.audioOnly.collectAsState()
    var showModePrompt by remember { mutableStateOf(false) }

    LaunchedEffect(audioOnly) {
        if (audioOnly) showModePrompt = true
    }

    val activity = LocalContext.current as? Activity
    LaunchedEffect(fullscreen) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (fullscreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // PiP mode: show only the video surface
    if (isInPip) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            MirroringView(
                onSurfaceAvailable = onSurfaceAvailable,
                onSurfaceDestroyed = onSurfaceDestroyed,
                aspectRatio = videoAspect
            )
        }
        return
    }

    // Restore system bars when exiting PiP back to non-fullscreen
    LaunchedEffect(isInPip) {
        if (!isInPip && !fullscreen) {
            val window = activity?.window ?: return@LaunchedEffect
            WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        }
    }

    if (fullscreen) {
        BackHandler { fullscreen = false }
        FullscreenVideo(
            viewModel = viewModel,
            onSurfaceAvailable = onSurfaceAvailable,
            onSurfaceDestroyed = onSurfaceDestroyed,
            aspectRatio = videoAspect,
            onExitFullscreen = { fullscreen = false },
            onPip = onPip
        )
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.OVERVIEW,
                    onClick = { tab = Tab.OVERVIEW },
                    icon = { Icon(Icons.Default.Cast, null) },
                    label = { Text(Tab.OVERVIEW.label) }
                )
                NavigationBarItem(
                    selected = tab == Tab.LOGS,
                    onClick = { tab = Tab.LOGS },
                    icon = { Icon(Icons.Default.Article, null) },
                    label = { Text(Tab.LOGS.label) }
                )
                NavigationBarItem(
                    selected = tab == Tab.SETTINGS,
                    onClick = { tab = Tab.SETTINGS },
                    icon = { Icon(Icons.Default.Settings, null) },
                    label = { Text(Tab.SETTINGS.label) }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (tab) {
                Tab.OVERVIEW -> OverviewContent(
                    viewModel, onSurfaceAvailable, onSurfaceDestroyed,
                    onFullscreen = { fullscreen = true },
                    onPip = onPip,
                    showAudioMode = audioOnly
                )
                Tab.LOGS -> LogsScreen(viewModel)
                Tab.SETTINGS -> SettingsScreen(viewModel)
            }
        }
    }

    // PIN dialog
    if (pin != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissPin() },
            title = { Text("AirPlay PIN") },
            text = {
                Text(
                    text = pin!!,
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissPin() }) { Text("OK") }
            }
        )
    }

    // Audio mode notification
    if (showModePrompt) {
        AlertDialog(
            onDismissRequest = { showModePrompt = false },
            title = { Text("Audio Mode") },
            text = { Text("Switching to Audio mode for track information and playback controls.") },
            confirmButton = {
                TextButton(onClick = { showModePrompt = false }) { Text("OK") }
            }
        )
    }
}

@Composable
private fun OverviewContent(
    viewModel: MainViewModel,
    onSurfaceAvailable: (android.view.Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onFullscreen: () -> Unit,
    onPip: () -> Unit,
    showAudioMode: Boolean = false
) {
    val state by viewModel.serverState.collectAsState()
    val connections by viewModel.connectionCount.collectAsState()
    val serverName by viewModel.serverName.collectAsState()
    val videoAspect by viewModel.videoAspect.collectAsState()
    val videoResolution by viewModel.videoResolution.collectAsState()
    val debugEnabled by viewModel.debugEnabled.collectAsState()
    val debugInfo by viewModel.debugInfo.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // Content area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (showAudioMode && state == ServerState.RUNNING && connections > 0) {
                NowPlayingContent(viewModel)
            } else {
                if (state == ServerState.RUNNING) {
                    MirroringView(
                        onSurfaceAvailable = onSurfaceAvailable,
                        onSurfaceDestroyed = onSurfaceDestroyed,
                        aspectRatio = videoAspect
                    )
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
                            text = when (state) {
                                ServerState.STOPPED -> "Server stopped"
                                ServerState.RUNNING -> "Waiting for connection..."
                                ServerState.ERROR -> "Error starting server"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
                if (state == ServerState.RUNNING && connections > 0) {
                    Row(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                        IconButton(onClick = onPip) {
                            Icon(
                                painterResource(R.drawable.ic_pip), contentDescription = "Picture-in-Picture",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                        IconButton(onClick = onFullscreen) {
                            Icon(
                                Icons.Default.Fullscreen, contentDescription = "Fullscreen",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
            if (debugEnabled && connections > 0 && !showAudioMode) {
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

        // Controls
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
                            when (state) {
                                ServerState.RUNNING -> MaterialTheme.colorScheme.primary
                                ServerState.ERROR -> MaterialTheme.colorScheme.error
                                ServerState.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
                            }, label = "status"
                        )
                        Text(
                            text = when (state) {
                                ServerState.RUNNING -> "$connections connected"
                                ServerState.ERROR -> "Error"
                                ServerState.STOPPED -> "Stopped"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = statusColor
                        )
                    }

                    FilledTonalButton(
                        onClick = {
                            if (state == ServerState.RUNNING) viewModel.stopServer()
                            else viewModel.startServer()
                        }
                    ) {
                        Icon(
                            imageVector = if (state == ServerState.RUNNING) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (state == ServerState.RUNNING) "Stop" else "Start")
                    }
                }
            }
        }
    }
}

@Composable
private fun FullscreenVideo(
    viewModel: MainViewModel,
    onSurfaceAvailable: (android.view.Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    aspectRatio: Float,
    onExitFullscreen: () -> Unit,
    onPip: () -> Unit
) {
    val videoResolution by viewModel.videoResolution.collectAsState()
    val debugEnabled by viewModel.debugEnabled.collectAsState()
    val debugInfo by viewModel.debugInfo.collectAsState()

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        MirroringView(
            onSurfaceAvailable = onSurfaceAvailable,
            onSurfaceDestroyed = onSurfaceDestroyed,
            aspectRatio = aspectRatio
        )
        Row(modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            IconButton(onClick = onPip) {
                Icon(
                    painterResource(R.drawable.ic_pip), contentDescription = "Picture-in-Picture",
                    tint = Color.White.copy(alpha = 0.7f)
                )
            }
            IconButton(onClick = onExitFullscreen) {
                Icon(
                    Icons.Default.FullscreenExit, contentDescription = "Exit fullscreen",
                    tint = Color.White.copy(alpha = 0.7f)
                )
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

@Composable
private fun NowPlayingContent(viewModel: MainViewModel) {
    val track by viewModel.trackInfo.collectAsState()
    val positionMs by viewModel.positionMs.collectAsState()
    val durationMs by viewModel.durationMs.collectAsState()
    val playing by viewModel.playing.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Cover art
        Box(
            modifier = Modifier
                .weight(1f, fill = false)
                .aspectRatio(1f)
                .fillMaxWidth(0.7f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            if (track.coverArt != null) {
                Image(
                    bitmap = track.coverArt!!.asImageBitmap(),
                    contentDescription = "Cover art",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // Track info
        Text(
            text = track.title.ifEmpty { "Unknown" },
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
        if (track.album.isNotEmpty()) {
            Text(
                text = track.album,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(16.dp))

        // Progress bar (read-only, seeking not supported by AirPlay receiver)
        if (durationMs > 0) {
            LinearProgressIndicator(
                progress = { (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(_formatTime(positionMs), style = MaterialTheme.typography.labelSmall)
                Text(_formatTime(durationMs), style = MaterialTheme.typography.labelSmall)
            }
        }

        Spacer(Modifier.height(8.dp))

        // Playback controls
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = { viewModel.dacpPrev() }) {
                Icon(Icons.Default.SkipPrevious, "Previous", modifier = Modifier.size(36.dp))
            }
            FilledIconButton(
                onClick = { viewModel.dacpPlayPause() },
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    "Play/Pause", modifier = Modifier.size(32.dp)
                )
            }
            IconButton(onClick = { viewModel.dacpNext() }) {
                Icon(Icons.Default.SkipNext, "Next", modifier = Modifier.size(36.dp))
            }
        }
    }
}

private fun _formatTime(ms: Long): String {
    val s = (ms / 1000).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun DebugOverlay(info: DebugInfo, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        val style = MaterialTheme.typography.labelSmall
        val color = Color.White.copy(alpha = 0.9f)

        if (info.videoCodec.isNotEmpty()) {
            Text("Video: ${info.videoCodec} ${info.videoRes}", style = style, color = color)
            Text("FPS: ${info.videoFps}  Bitrate: ${info.bitrateStr}", style = style, color = color)
            Text("Frames: ${info.videoFrames}", style = style, color = color)
        }
        if (info.audioCodec.isNotEmpty()) {
            Text("Audio: ${info.audioCodec}  Vol: ${info.audioVolume}%", style = style, color = color)
        }
        Text("Clients: ${info.connections}", style = style, color = color)
    }
}
