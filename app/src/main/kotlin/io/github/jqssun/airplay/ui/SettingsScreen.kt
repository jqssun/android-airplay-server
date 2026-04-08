package io.github.jqssun.airplay.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.jqssun.airplay.viewmodel.MainViewModel
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val serverName by viewModel.serverName.collectAsState()
    val h265Enabled by viewModel.h265Enabled.collectAsState()
    val alacEnabled by viewModel.alacEnabled.collectAsState()
    val aacEnabled by viewModel.aacEnabled.collectAsState()
    val resolution by viewModel.resolution.collectAsState()
    val maxFps by viewModel.maxFps.collectAsState()
    val overscanned by viewModel.overscanned.collectAsState()
    val requirePin by viewModel.requirePin.collectAsState()
    val allowNewConn by viewModel.allowNewConn.collectAsState()
    val autoStart by viewModel.autoStart.collectAsState()
    val audioLatencyMs by viewModel.audioLatencyMs.collectAsState()
    val debugEnabled by viewModel.debugEnabled.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp)
    ) {
        SectionHeader("Server")

        var nameText by remember(serverName) { mutableStateOf(serverName) }
        ListItem(
            headlineContent = { Text("Server name") },
            supportingContent = { Text("Name shown to AirPlay clients") }
        )
        OutlinedTextField(
            value = nameText,
            onValueChange = { nameText = it },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
            trailingIcon = {
                if (nameText != serverName) {
                    TextButton(onClick = { viewModel.setServerName(nameText) }) {
                        Text("Save")
                    }
                }
            }
        )

        SettingSwitch(
            title = "Start server automatically",
            description = "Start the AirPlay server when the app launches",
            checked = autoStart,
            onCheckedChange = { viewModel.setAutoStart(it) }
        )

        SectionHeader("Connection")

        SettingSwitch(
            title = "Require PIN (Beta)",
            description = "Require 4-digit PIN for each new connection",
            checked = requirePin,
            onCheckedChange = { viewModel.setRequirePin(it) }
        )

        SettingSwitch(
            title = "Allow new connections",
            description = "Drop current client when a new one connects",
            checked = allowNewConn,
            onCheckedChange = { viewModel.setAllowNewConn(it) }
        )

        SectionHeader("Display")

        SettingChipField(
            title = "Resolution",
            description = "Video resolution advertised to clients",
            value = resolution,
            presets = listOf("auto" to "Auto", "1280x720" to "1280x720", "1920x1080" to "1920x1080", "3840x2160" to "3840x2160"),
            placeholder = "[W]x[H]",
            onValueChange = { viewModel.setResolution(it) }
        )

        SettingChipField(
            title = "Max FPS",
            description = "Maximum frame rate advertised to clients",
            value = maxFps.toString(),
            presets = listOf("24" to "24", "30" to "30", "60" to "60", "120" to "120"),
            placeholder = "[FPS]",
            keyboard = KeyboardType.Number,
            onValueChange = { it.toIntOrNull()?.let { v -> viewModel.setMaxFps(v) } }
        )

        SettingSwitch(
            title = "Overscanned",
            description = "Add pixel boundary for full-screen overscan displays",
            checked = overscanned,
            onCheckedChange = { viewModel.setOverscanned(it) }
        )

        SectionHeader("Audio")

        SettingSwitch(
            title = "Override audio delay",
            description = "Set a custom audio latency reported to client",
            checked = audioLatencyMs >= 0,
            onCheckedChange = { viewModel.setAudioLatencyMs(if (it) 250 else -1) }
        )

        if (audioLatencyMs >= 0) {
            var sliderVal by remember(audioLatencyMs) { mutableFloatStateOf(audioLatencyMs.toFloat()) }
            ListItem(
                headlineContent = {
                    Slider(
                        value = sliderVal,
                        onValueChange = { sliderVal = it },
                        onValueChangeFinished = { viewModel.setAudioLatencyMs(sliderVal.roundToInt()) },
                        valueRange = 0f..1000f,
                        steps = 19
                    )
                },
                trailingContent = { Text("${sliderVal.roundToInt()}ms") }
            )
        }

        SectionHeader("Decode")

        SettingSwitch(
            title = "H.265 (HEVC)",
            description = "Enable H.265 video decoding if device supports it",
            checked = h265Enabled,
            onCheckedChange = { viewModel.setH265Enabled(it) }
        )

        SettingSwitch(
            title = "ALAC audio",
            description = "Apple Lossless codec for AirPlay music streaming",
            checked = alacEnabled,
            onCheckedChange = { viewModel.setAlacEnabled(it) }
        )

        SettingSwitch(
            title = "AAC audio",
            description = "AAC-ELD / AAC-LC codec for screen mirroring audio",
            checked = aacEnabled,
            onCheckedChange = { viewModel.setAacEnabled(it) }
        )

        SectionHeader("Debug")

        SettingSwitch(
            title = "Show debug overlay",
            description = "Display stats (bitrate, FPS, codec) over video",
            checked = debugEnabled,
            onCheckedChange = { viewModel.setDebugEnabled(it) }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingChipField(
    title: String,
    description: String,
    value: String,
    presets: List<Pair<String, String>>,
    placeholder: String,
    keyboard: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit
) {
    val isPreset = presets.any { it.first == value }
    var editing by remember { mutableStateOf(false) }
    var text by remember(value) { mutableStateOf(if (isPreset) "" else value) }
    val focus = LocalFocusManager.current

    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Column {
                Text(description)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { (key, label) ->
                        FilterChip(
                            selected = value == key && !editing,
                            onClick = {
                                editing = false
                                text = ""
                                onValueChange(key)
                            },
                            label = { Text(label) }
                        )
                    }
                    FilterChip(
                        selected = !isPreset || editing,
                        onClick = { editing = true },
                        label = { Text("Custom") }
                    )
                }
                if (editing || !isPreset) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        placeholder = { Text(placeholder) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = keyboard,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = {
                            if (text.isNotBlank()) {
                                onValueChange(text)
                                editing = false
                            }
                            focus.clearFocus()
                        }),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    )
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) }
    )
}
