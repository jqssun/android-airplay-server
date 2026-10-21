package io.github.jqssun.airplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.viewmodel.MainViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(viewModel: MainViewModel) {
    val logs by viewModel.logs.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // follow the tail only while the reader is already at the end; otherwise
    // jumping back to a line they wanted to read would yank the list away
    val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
    val atEnd = logs.isEmpty() || lastVisible >= logs.size - 1

    LaunchedEffect(logs.size) {
        if (atEnd && logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_logs)) },
                actions = {
                    IconButton(onClick = viewModel::exportLogs, modifier = Modifier.dpadFocus()) {
                        Icon(Icons.Outlined.Share, stringResource(R.string.btn_export))
                    }
                    IconButton(onClick = viewModel::clearLogs, modifier = Modifier.dpadFocus()) {
                        Icon(Icons.Outlined.Delete, stringResource(R.string.btn_clear))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            SelectionContainer(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(logs) {
                        Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    }
                }
            }
            if (!atEnd) {
                FloatingActionButton(
                    onClick = {
                        scope.launch { listState.animateScrollToItem(logs.size - 1) }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                        .dpadFocus(),
                ) {
                    Icon(Icons.Default.ArrowDownward, stringResource(R.string.btn_jump_latest))
                }
            }
        }
    }
}
