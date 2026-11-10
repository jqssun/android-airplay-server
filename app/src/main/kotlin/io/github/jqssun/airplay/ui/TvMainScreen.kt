package io.github.jqssun.airplay.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.jqssun.airplay.service.AirPlayService.ServerState
import io.github.jqssun.airplay.viewmodel.MainViewModel

/**
 * TV-optimized main screen with sidebar navigation
 * Follows Amazon Fire TV and Android TV design guidelines for 10-foot UI
 */
@Composable
fun TvMainScreen(
    viewModel: MainViewModel,
    isInPip: Boolean = false,
    onSurfaceAvailable: (android.view.Surface) -> Unit,
    onSurfaceDestroyed: (android.view.Surface) -> Unit,
    onPip: () -> Unit = {},
    onLocalNetwork: () -> Unit = {}
) {
    var selectedTab by remember { mutableStateOf(TvTab.HOME) }
    var fullscreen by remember { mutableStateOf(false) }
    val state by viewModel.serverState.collectAsState()
    val pin by viewModel.pinCode.collectAsState()
    val mirroringActive by viewModel.mirroringActive.collectAsState()
    val audioOnly by viewModel.audioOnly.collectAsState()
    val autoFullscreen by viewModel.pref(io.github.jqssun.airplay.Prefs.AUTO_FULLSCREEN).collectAsState()
    val connections by viewModel.connectionCount.collectAsState()
    val videoPlaybackActive by viewModel.videoPlaybackActive.collectAsState()
    val videoSessionPending by viewModel.videoSessionPending.collectAsState()
    
    // Auto-fullscreen when mirroring starts (like original MainScreen)
    var prevMirroringActive by remember { mutableStateOf(false) }
    LaunchedEffect(mirroringActive, audioOnly, pin) {
        val justStarted = !prevMirroringActive && mirroringActive
        prevMirroringActive = mirroringActive
        if (justStarted && !audioOnly && autoFullscreen && pin == null) {
            fullscreen = true
        }
    }
    
    // Exit fullscreen when disconnecting
    LaunchedEffect(connections) {
        if (connections == 0) fullscreen = false
    }
    
    // Exit fullscreen when video playback starts (avoid being stuck in fullscreen)
    LaunchedEffect(videoPlaybackActive, videoSessionPending) {
        if (videoPlaybackActive || videoSessionPending) fullscreen = false
    }
    
    // Video playback screen (fullscreen, use existing implementation)
    if (videoPlaybackActive || videoSessionPending || isInPip) {
        // Delegate to existing video playback UI - it's already optimized
        MainScreen(
            viewModel = viewModel,
            isInPip = isInPip,
            onSurfaceAvailable = onSurfaceAvailable,
            onSurfaceDestroyed = onSurfaceDestroyed,
            onPip = onPip,
            onLocalNetwork = onLocalNetwork
        )
        return
    }
    
    // Fullscreen mirroring mode
    if (fullscreen && mirroringActive) {
        // Allow Back button to exit fullscreen
        BackHandler { fullscreen = false }
        
        val currentAspect = viewModel.videoAspect.collectAsState().value
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black),
            contentAlignment = Alignment.Center
        ) {
            // Use appropriate sizing based on aspect ratio
            VideoSurfaceView(
                onSurfaceAvailable = onSurfaceAvailable,
                onSurfaceDestroyed = onSurfaceDestroyed,
                aspectRatio = currentAspect,
                modifier = if (currentAspect < 1f) {
                    // Portrait: fill height, pillarbox sides
                    Modifier.fillMaxHeight()
                } else {
                    // Landscape: fill width, letterbox top/bottom
                    Modifier.fillMaxWidth()
                }
            )
            
            // Exit fullscreen button - auto-focused so D-pad OK closes fullscreen immediately
            val exitFocus = remember { androidx.compose.ui.focus.FocusRequester() }
            LaunchedEffect(Unit) { exitFocus.requestFocus() }
            IconButton(
                onClick = { fullscreen = false },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .focusRequester(exitFocus)
                    .dpadFocusNoScale(androidx.compose.foundation.shape.CircleShape)
            ) {
                Icon(
                    androidx.compose.material.icons.Icons.Default.FullscreenExit,
                    contentDescription = "Exit fullscreen",
                    tint = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        
        // PIN dialog in fullscreen
        if (pin != null) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissPin() },
                title = { Text(androidx.compose.ui.res.stringResource(io.github.jqssun.airplay.R.string.dialog_pin_title)) },
                text = {
                    Text(
                        text = pin!!,
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissPin() }) { 
                        Text(androidx.compose.ui.res.stringResource(io.github.jqssun.airplay.R.string.btn_ok)) 
                    }
                }
            )
        }
        return
    }
    
    // TV Layout: Sidebar + Content
    // Add TV overscan padding: 48dp on sides, 27dp top/bottom (5% of 1080p)
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 48.dp, end = 48.dp, top = 27.dp, bottom = 27.dp)
    ) {
        // Sidebar navigation (200dp fixed width - enough for text)
        TvSidebar(
            selectedTab = selectedTab,
            serverState = state,
            onTabSelect = { selectedTab = it },
            onStartStop = {
                if (state == ServerState.RUNNING) viewModel.stopServer()
                else viewModel.startServer()
            },
            onLocalNetwork = onLocalNetwork,
            modifier = Modifier
                .width(200.dp)
                .fillMaxHeight()
        )
        
        // Content area (flexible width)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(16.dp)
        ) {
            when (selectedTab) {
                TvTab.HOME -> TvHomeScreen(
                    viewModel = viewModel,
                    onSurfaceAvailable = onSurfaceAvailable,
                    onSurfaceDestroyed = onSurfaceDestroyed,
                    onPip = onPip,
                    onFullscreen = { fullscreen = true }
                )
                TvTab.LOGS -> LogsScreen(viewModel)
                TvTab.SETTINGS -> TvSettingsScreen(viewModel)
            }
        }
    }
    
    // PIN dialog - CRITICAL: must be shown on top of everything
    if (pin != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissPin() },
            title = { Text(androidx.compose.ui.res.stringResource(io.github.jqssun.airplay.R.string.dialog_pin_title)) },
            text = {
                Text(
                    text = pin!!,
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissPin() }) { 
                    Text(androidx.compose.ui.res.stringResource(io.github.jqssun.airplay.R.string.btn_ok)) 
                }
            }
        )
    }
}
