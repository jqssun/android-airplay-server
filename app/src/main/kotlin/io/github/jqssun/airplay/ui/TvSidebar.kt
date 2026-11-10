package io.github.jqssun.airplay.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.service.AirPlayService.ServerState
import io.github.jqssun.airplay.viewmodel.LocalNetworkAccess
import io.github.jqssun.airplay.viewmodel.MainViewModel

/**
 * TV navigation tabs
 */
enum class TvTab(val labelRes: Int, val icon: ImageVector) {
    HOME(R.string.tab_overview, Icons.Default.Cast),
    LOGS(R.string.tab_logs, Icons.AutoMirrored.Filled.Article),
    SETTINGS(R.string.tab_settings, Icons.Default.Settings)
}

/**
 * TV Sidebar navigation with vertical tabs and action button
 * - Logo + status indicator at top
 * - Tab items in middle
 * - Start/Stop button at bottom
 */
@Composable
fun TvSidebar(
    selectedTab: TvTab,
    serverState: ServerState,
    onTabSelect: (TvTab) -> Unit,
    onStartStop: () -> Unit,
    onLocalNetwork: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Logo + Server status indicator
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Cast,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            val statusColor = when (serverState) {
                ServerState.RUNNING -> MaterialTheme.colorScheme.primary
                ServerState.ERROR -> MaterialTheme.colorScheme.error
                ServerState.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            }
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
        }
        
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
        
        // Navigation items
        TvTab.entries.forEach { tab ->
            TvSidebarItem(
                label = stringResource(tab.labelRes),
                icon = tab.icon,
                selected = selectedTab == tab,
                onClick = { onTabSelect(tab) }
            )
        }
        
        Spacer(Modifier.weight(1f))
        
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
        
        // Start/Stop button
        TvActionButton(
            label = if (serverState == ServerState.RUNNING) 
                stringResource(R.string.btn_stop) 
                else stringResource(R.string.btn_start),
            icon = if (serverState == ServerState.RUNNING) 
                Icons.Default.Stop 
                else Icons.Default.PlayArrow,
            onClick = onStartStop,
            primary = true
        )
    }
}

/**
 * Sidebar navigation item with focus animation
 */
@Composable
fun TvSidebarItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.02f else 1.0f,
        label = "sidebarItemScale"
    )
    val bgColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer 
        else MaterialTheme.colorScheme.surface,
        label = "sidebarItemBg"
    )
    
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)  // Reduced from 72dp
            .padding(horizontal = 8.dp, vertical = 4.dp)  // More compact
            .graphicsLayer { 
                scaleX = scale
                scaleY = scale
            }
            .tvFocusBorder(
                shape = RoundedCornerShape(8.dp),  // Reduced from 12dp
                scaleOnFocus = false  // Disable scale to avoid clipping
            ),
        shape = RoundedCornerShape(8.dp),
        color = bgColor
    ) {
        Row(
            modifier = Modifier.padding(12.dp),  // Reduced from 16dp
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Icon(
                icon, 
                contentDescription = null,
                modifier = Modifier.size(24.dp),  // Reduced from 28dp
                tint = if (selected) 
                    MaterialTheme.colorScheme.onPrimaryContainer 
                    else MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,  // Changed from titleMedium
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) 
                    MaterialTheme.colorScheme.onPrimaryContainer 
                    else MaterialTheme.colorScheme.onSurface,
                maxLines = 1  // Prevent wrapping
            )
        }
    }
}

/**
 * Action button for sidebar (Start/Stop server)
 */
@Composable
fun TvActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    primary: Boolean = false,
    modifier: Modifier = Modifier
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = 8.dp)
            .tvFocusBorder(
                shape = RoundedCornerShape(12.dp),
                scaleOnFocus = true
            ),
        colors = if (primary) ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ) else ButtonDefaults.filledTonalButtonColors()
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium
        )
    }
}
