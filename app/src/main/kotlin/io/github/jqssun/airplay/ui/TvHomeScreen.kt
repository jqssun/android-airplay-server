package io.github.jqssun.airplay.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.jqssun.airplay.Prefs
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.service.AirPlayService.ServerState
import io.github.jqssun.airplay.viewmodel.MainViewModel
import kotlinx.coroutines.delay

/**
 * TV Home Screen with hero card and info cards
 * - Large video preview/mirroring area
 * - Server and connection info cards below
 */
@Composable
fun TvHomeScreen(
    viewModel: MainViewModel,
    onSurfaceAvailable: (android.view.Surface) -> Unit,
    onSurfaceDestroyed: (android.view.Surface) -> Unit,
    onPip: () -> Unit,
    onFullscreen: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state by viewModel.serverState.collectAsState()
    val connections by viewModel.connectionCount.collectAsState()
    val mirroringActive by viewModel.mirroringActive.collectAsState()
    val audioOnly by viewModel.audioOnly.collectAsState()
    val idlePreview by viewModel.pref(Prefs.IDLE_PREVIEW).collectAsState()
    
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Hero card: Video/Mirroring preview
        TvHeroCard(
            viewModel = viewModel,
            state = state,
            connections = connections,
            mirroringActive = mirroringActive,
            audioOnly = audioOnly,
            idlePreview = idlePreview,
            onSurfaceAvailable = onSurfaceAvailable,
            onSurfaceDestroyed = onSurfaceDestroyed,
            onPip = onPip,
            onFullscreen = onFullscreen,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
        
        // Info cards row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Server info card
            TvInfoCard(
                title = stringResource(R.string.section_server),
                icon = Icons.Default.Dns,
                content = {
                    val serverName by viewModel.pref(Prefs.SERVER_NAME).collectAsState()
                    val port by viewModel.pref(Prefs.SERVER_PORT).collectAsState()
                    TvInfoRow(stringResource(R.string.setting_server_name), serverName)
                    TvInfoRow(stringResource(R.string.setting_server_port), port.toString())
                },
                modifier = Modifier.weight(1f)
            )
            
            // Connection info card
            TvInfoCard(
                title = stringResource(R.string.section_connection),
                icon = Icons.Default.Link,
                content = {
                    val statusText = when (state) {
                        ServerState.RUNNING -> stringResource(R.string.connected_count, connections)
                        ServerState.STOPPED -> stringResource(R.string.stopped_label)
                        ServerState.ERROR -> stringResource(R.string.error_label)
                    }
                    TvInfoRow(stringResource(R.string.status), statusText)
                    if (mirroringActive) {
                        val videoRes by viewModel.videoResolution.collectAsState()
                        TvInfoRow(stringResource(R.string.resolution), videoRes.ifEmpty { "-" })
                    }
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Hero card with video preview or idle state
 */
@Composable
fun TvHeroCard(
    viewModel: MainViewModel,
    state: ServerState,
    connections: Int,
    mirroringActive: Boolean,
    audioOnly: Boolean,
    idlePreview: Boolean,
    onSurfaceAvailable: (android.view.Surface) -> Unit,
    onSurfaceDestroyed: (android.view.Surface) -> Unit,
    onPip: () -> Unit,
    onFullscreen: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                // Audio-only mode: Show Now Playing UI with cover art
                audioOnly && state == ServerState.RUNNING && connections > 0 -> {
                    TvAudioOnlyContent(viewModel)
                }
                
                // Video mirroring active or idle preview
                state == ServerState.RUNNING && (mirroringActive || idlePreview) -> {
                    val aspect by viewModel.videoAspect.collectAsState()
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center
                    ) {
                        VideoSurfaceView(
                            onSurfaceAvailable = onSurfaceAvailable,
                            onSurfaceDestroyed = onSurfaceDestroyed,
                            aspectRatio = aspect
                        )
                    }
                    
                    // Floating controls (only when mirroring active)
                    if (mirroringActive) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IconButton(
                                onClick = onFullscreen,
                                modifier = Modifier.tvFocusBorder(CircleShape)
                            ) {
                                Icon(
                                    Icons.Default.Fullscreen,
                                    contentDescription = stringResource(R.string.cd_fullscreen),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                
                // Idle state
                else -> {
                    TvIdleState(state, connections)
                }
            }
            
            // Resolution indicator
            if (!audioOnly) {
                val videoResolution by viewModel.videoResolution.collectAsState()
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
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                    ) {
                        Text(
                            text = videoResolution,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
            
            // Debug overlay (optional, when debug mode enabled)
            val debugEnabled by viewModel.pref(Prefs.DEBUG_ENABLED).collectAsState()
            if (debugEnabled && connections > 0) {
                val debugInfo by viewModel.debugInfo.collectAsState()
                DebugOverlay(debugInfo, Modifier.align(Alignment.TopStart).padding(12.dp))
            }
        }
    }
}

/**
 * Idle state with instructions
 */
@Composable
fun TvIdleState(state: ServerState, connections: Int) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = if (connections > 0) Icons.Default.CastConnected else Icons.Default.Cast,
            contentDescription = null,
            modifier = Modifier.size(120.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        )
        
        Spacer(Modifier.height(24.dp))
        
        Text(
            text = when (state) {
                ServerState.STOPPED -> stringResource(R.string.server_stopped)
                ServerState.RUNNING -> if (connections > 0) 
                    stringResource(R.string.waiting_for_playback)
                    else stringResource(R.string.waiting_for_connection)
                ServerState.ERROR -> stringResource(R.string.error_starting_server)
            },
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        
        Spacer(Modifier.height(16.dp))
        
        Text(
            text = stringResource(R.string.airplay_instructions),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 48.dp)
        )
    }
}

/**
 * Info card component for server/connection details
 */
@Composable
fun TvInfoCard(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.height(140.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            
            Spacer(Modifier.height(12.dp))
            
            content()
        }
    }
}

/**
 * Info row with label and value
 */
@Composable
fun TvInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}


/**
 * Audio-only content with cover art and track info
 * Optimized for TV 10-foot UI
 */
@Composable
fun TvAudioOnlyContent(viewModel: MainViewModel) {
    val track by viewModel.trackInfo.collectAsState()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Cover art
        Box(
            modifier = Modifier
                .size(300.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
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
                    modifier = Modifier.size(120.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                )
            }
        }
        
        Spacer(Modifier.height(32.dp))
        
        // Track info
        Text(
            text = track.title.ifEmpty { stringResource(R.string.unknown_track) },
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        
        if (track.artist.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = track.artist,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        
        if (track.album.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = track.album,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}
