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
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.viewmodel.MainViewModel
import androidx.compose.ui.res.stringResource
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val serverName by viewModel.serverName.collectAsState()
    val h265Enabled by viewModel.h265Enabled.collectAsState()
    val alacEnabled by viewModel.alacEnabled.collectAsState()
    val aacEnabled by viewModel.aacEnabled.collectAsState()
    val resolution by viewModel.resolution.collectAsState()
    val idlePreview by viewModel.idlePreview.collectAsState()
    val autoFullscreen by viewModel.autoFullscreen.collectAsState()
    val autoAudioMode by viewModel.autoAudioMode.collectAsState()
    val maxFps by viewModel.maxFps.collectAsState()
    val overscanned by viewModel.overscanned.collectAsState()
    val requirePin by viewModel.requirePin.collectAsState()
    val allowNewConn by viewModel.allowNewConn.collectAsState()
    val autoStart by viewModel.autoStart.collectAsState()
    val serverPort by viewModel.serverPort.collectAsState()
    val audioLatencyMs by viewModel.audioLatencyMs.collectAsState()
    val swAlacEnabled by viewModel.swAlacEnabled.collectAsState()
    val debugEnabled by viewModel.debugEnabled.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp)
    ) {
        SectionHeader(stringResource(R.string.section_server))

        var nameText by remember(serverName) { mutableStateOf(serverName) }
        ListItem(
            headlineContent = { Text(stringResource(R.string.setting_server_name)) },
            supportingContent = { Text(stringResource(R.string.setting_server_name_desc)) }
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
                        Text(stringResource(R.string.btn_save))
                    }
                }
            }
        )

        var portText by remember(serverPort) { mutableStateOf(serverPort.toString()) }
        val focus = LocalFocusManager.current
        ListItem(
            headlineContent = { Text(stringResource(R.string.setting_server_port)) },
            supportingContent = { Text(stringResource(R.string.setting_server_port_desc)) }
        )
        OutlinedTextField(
            value = portText,
            onValueChange = { portText = it.filter { c -> c.isDigit() }.take(5) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
            isError = portText.toIntOrNull()?.let { it !in 1..65535 } ?: true,
            trailingIcon = {
                val port = portText.toIntOrNull()
                if (portText != serverPort.toString() && port != null && port in 1..65535) {
                    TextButton(onClick = { viewModel.setServerPort(port) }) { Text(stringResource(R.string.btn_save)) }
                }
            }
        )

        SettingSwitch(
            title = stringResource(R.string.setting_auto_start),
            description = stringResource(R.string.setting_auto_start_desc),
            checked = autoStart,
            onCheckedChange = { viewModel.setAutoStart(it) }
        )

        SectionHeader(stringResource(R.string.section_connection))

        SettingSwitch(
            title = stringResource(R.string.setting_require_pin),
            description = stringResource(R.string.setting_require_pin_desc),
            checked = requirePin,
            onCheckedChange = { viewModel.setRequirePin(it) }
        )

        SettingSwitch(
            title = stringResource(R.string.setting_allow_new_conn),
            description = stringResource(R.string.setting_allow_new_conn_desc),
            checked = allowNewConn,
            onCheckedChange = { viewModel.setAllowNewConn(it) }
        )

        SectionHeader(stringResource(R.string.section_display))

        SettingSwitch(
            title = stringResource(R.string.setting_idle_preview),
            description = stringResource(R.string.setting_idle_preview_desc),
            checked = idlePreview,
            onCheckedChange = { viewModel.setIdlePreview(it) }
        )

        SettingSwitch(
            title = stringResource(R.string.setting_auto_fullscreen),
            description = stringResource(R.string.setting_auto_fullscreen_desc),
            checked = autoFullscreen,
            onCheckedChange = { viewModel.setAutoFullscreen(it) }
        )

        SettingSwitch(
            title = stringResource(R.string.setting_auto_audio_mode),
            description = stringResource(R.string.setting_auto_audio_mode_desc),
            checked = autoAudioMode,
            onCheckedChange = { viewModel.setAutoAudioMode(it) }
        )

        SettingChipField(
            title = stringResource(R.string.setting_resolution),
            description = stringResource(R.string.setting_resolution_desc),
            value = resolution,
            presets = listOf("auto" to stringResource(R.string.setting_resolution_auto), "1280x720" to "1280x720", "1920x1080" to "1920x1080", "3840x2160" to "3840x2160"),
            placeholder = stringResource(R.string.setting_resolution_placeholder),
            onValueChange = { viewModel.setResolution(it) }
        )

        SettingChipField(
            title = stringResource(R.string.setting_max_fps),
            description = stringResource(R.string.setting_max_fps_desc),
            value = maxFps.toString(),
            presets = listOf("24" to "24", "30" to "30", "60" to "60", "120" to "120"),
            placeholder = stringResource(R.string.setting_max_fps_placeholder),
            keyboard = KeyboardType.Number,
            onValueChange = { it.toIntOrNull()?.let { v -> viewModel.setMaxFps(v) } }
        )

        SettingSwitch(
            title = stringResource(R.string.setting_overscanned),
            description = stringResource(R.string.setting_overscanned_desc),
            checked = overscanned,
            onCheckedChange = { viewModel.setOverscanned(it) }
        )

        SectionHeader(stringResource(R.string.section_audio))

        SettingSwitch(
            title = stringResource(R.string.setting_audio_delay),
            description = stringResource(R.string.setting_audio_delay_desc),
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
                trailingContent = { Text(stringResource(R.string.audio_delay_value, sliderVal.roundToInt())) }
            )
        }

        SectionHeader(stringResource(R.string.section_decode))

        SettingSwitch(
            title = stringResource(R.string.setting_h265),
            description = stringResource(R.string.setting_h265_desc),
            checked = h265Enabled,
            onCheckedChange = { viewModel.setH265Enabled(it) }
        )

        SettingSwitch(
            title = stringResource(R.string.setting_alac),
            description = stringResource(R.string.setting_alac_desc),
            checked = alacEnabled,
            onCheckedChange = { viewModel.setAlacEnabled(it) }
        )

        SettingSwitch(
            title = stringResource(R.string.setting_sw_alac),
            description = stringResource(R.string.setting_sw_alac_desc),
            checked = swAlacEnabled,
            onCheckedChange = { viewModel.setSwAlacEnabled(it) }
        )

        SettingSwitch(
            title = stringResource(R.string.setting_aac),
            description = stringResource(R.string.setting_aac_desc),
            checked = aacEnabled,
            onCheckedChange = { viewModel.setAacEnabled(it) }
        )

        SectionHeader(stringResource(R.string.section_debug))

        SettingSwitch(
            title = stringResource(R.string.setting_debug_overlay),
            description = stringResource(R.string.setting_debug_overlay_desc),
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
                        label = { Text(stringResource(R.string.chip_custom)) }
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
