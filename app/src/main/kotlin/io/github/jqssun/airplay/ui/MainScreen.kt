package io.github.jqssun.airplay.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.jqssun.airplay.service.AirPlayService.ServerState
import io.github.jqssun.airplay.viewmodel.DebugInfo
import io.github.jqssun.airplay.viewmodel.MainViewModel
import kotlinx.coroutines.delay

private enum class Tab(val label: String) {
    OVERVIEW("Overview"), LOGS("Logs"), SETTINGS("Settings")
}

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onSurfaceAvailable: (android.view.Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit
) {
    var tab by remember { mutableStateOf(Tab.OVERVIEW) }
    var fullscreen by remember { mutableStateOf(false) }
    val pin by viewModel.pinCode.collectAsState()
    val videoAspect by viewModel.videoAspect.collectAsState()
    val connections by viewModel.connectionCount.collectAsState()

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

    if (fullscreen) {
        BackHandler { fullscreen = false }
        FullscreenVideo(
            viewModel = viewModel,
            onSurfaceAvailable = onSurfaceAvailable,
            onSurfaceDestroyed = onSurfaceDestroyed,
            aspectRatio = videoAspect,
            onExitFullscreen = { fullscreen = false }
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
                    onFullscreen = { fullscreen = true }
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
}

@Composable
private fun OverviewContent(
    viewModel: MainViewModel,
    onSurfaceAvailable: (android.view.Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onFullscreen: () -> Unit
) {
    val state by viewModel.serverState.collectAsState()
    val connections by viewModel.connectionCount.collectAsState()
    val serverName by viewModel.serverName.collectAsState()
    val videoAspect by viewModel.videoAspect.collectAsState()
    val videoResolution by viewModel.videoResolution.collectAsState()
    val debugEnabled by viewModel.debugEnabled.collectAsState()
    val debugInfo by viewModel.debugInfo.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // Video area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
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
                IconButton(
                    onClick = onFullscreen,
                    modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                ) {
                    Icon(
                        Icons.Default.Fullscreen, contentDescription = "Fullscreen",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
            if (debugEnabled && connections > 0) {
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
    onExitFullscreen: () -> Unit
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
        IconButton(
            onClick = onExitFullscreen,
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
        ) {
            Icon(
                Icons.Default.FullscreenExit, contentDescription = "Exit fullscreen",
                tint = Color.White.copy(alpha = 0.7f)
            )
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
