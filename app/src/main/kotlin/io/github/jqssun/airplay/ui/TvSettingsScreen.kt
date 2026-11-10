package io.github.jqssun.airplay.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jqssun.airplay.viewmodel.MainViewModel

/**
 * TV-optimized settings screen
 * Wraps existing SettingsScreen with padding to avoid clipped focus borders
 */
@Composable
fun TvSettingsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    // Add more padding to avoid focus border clipping (16dp each side)
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)  // Increased horizontal padding
    ) {
        SettingsScreen(viewModel)
    }
}
