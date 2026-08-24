package io.github.jqssun.airplay.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.jqssun.airplay.Prefs
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.viewmodel.MainViewModel
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val serverName by viewModel.serverName.collectAsState()
    val h265Enabled by viewModel.h265Enabled.collectAsState()
    val enforceSdr by viewModel.enforceSdr.collectAsState()
    val alacEnabled by viewModel.alacEnabled.collectAsState()
    val aacEnabled by viewModel.aacEnabled.collectAsState()
    val resolution by viewModel.resolution.collectAsState()
    val idlePreview by viewModel.idlePreview.collectAsState()
    val autoFullscreen by viewModel.autoFullscreen.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()
    val advertiseVideo by viewModel.advertiseVideo.collectAsState()
    val advertiseAudio by viewModel.advertiseAudio.collectAsState()
    val launchOnConnect by viewModel.launchOnConnect.collectAsState()
    val returnToPreviousApp by viewModel.returnToPreviousApp.collectAsState()
    val maxFps by viewModel.maxFps.collectAsState()
    val overscanned by viewModel.overscanned.collectAsState()
    val requirePin by viewModel.requirePin.collectAsState()
    val allowNewConn by viewModel.allowNewConn.collectAsState()
    val autoStart by viewModel.autoStart.collectAsState()
    val bootAutoStart by viewModel.bootAutoStart.collectAsState()
    val runInBackground by viewModel.runInBackground.collectAsState()
    val serverPort by viewModel.serverPort.collectAsState()
    val audioLatencyMs by viewModel.audioLatencyMs.collectAsState()
    val forceSwAlac by viewModel.forceSwAlac.collectAsState()
    val debugEnabled by viewModel.debugEnabled.collectAsState()
    val developerOptions by viewModel.developerOptions.collectAsState()
    val keyAllowFrameDrop by viewModel.keyAllowFrameDrop.collectAsState()
    val realtimeDecoderPriority by viewModel.realtimeDecoderPriority.collectAsState()
    val lowLatency by viewModel.lowLatency.collectAsState()
    val operatingRate by viewModel.operatingRate.collectAsState()
    val scheduledOutputBufferRelease by viewModel.scheduledOutputBufferRelease.collectAsState()
    val benchmarkLog by viewModel.benchmarkLog.collectAsState()
    val audioAutoBuffer by viewModel.audioAutoBuffer.collectAsState()
    val audioCushionMs by viewModel.audioCushionMs.collectAsState()
    val audioAdaptiveStep by viewModel.audioAdaptiveStep.collectAsState()
    val oboeBufferFrames by viewModel.oboeBufferFrames.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp)
    ) {
        SectionHeader(stringResource(R.string.section_server))

        SettingTextField(
            label = stringResource(R.string.setting_server_name),
            value = serverName,
            onCommit = { viewModel.setServerName(it) }
        )

        SettingTextField(
            label = stringResource(R.string.setting_server_port),
            value = serverPort.toString(),
            onCommit = { viewModel.setServerPort(it.toInt()) },
            range = 1..65535
        )

        SettingSwitch(
            title = stringResource(R.string.setting_boot_auto_start),
            description = stringResource(R.string.setting_boot_auto_start_desc),
            checked = bootAutoStart,
            onCheckedChange = { viewModel.setBootAutoStart(it) }
        )

        SettingSwitch(
            title = stringResource(R.string.setting_run_in_background),
            description = stringResource(R.string.setting_run_in_background_desc),
            checked = runInBackground,
            onCheckedChange = { viewModel.setRunInBackground(it) }
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

        val ctx = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        var hasOverlayPermission by remember { mutableStateOf(canAutoLaunch(ctx)) }
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) hasOverlayPermission = canAutoLaunch(ctx)
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        val needsOverlayPermission = launchOnConnect && !hasOverlayPermission
        ListItem(
            modifier = Modifier
                .dpadFocus(RectangleShape)
                .toggleable(
                    value = launchOnConnect,
                    role = Role.Switch,
                    onValueChange = {
                        viewModel.setLaunchOnConnect(it)
                        if (it && !canAutoLaunch(ctx)) ctx.startActivity(_overlayIntent(ctx))
                    }
                ),
            headlineContent = { Text(stringResource(R.string.setting_launch_on_connect)) },
            supportingContent = {
                Text(stringResource(
                    if (needsOverlayPermission) R.string.setting_launch_on_connect_no_permission
                    else R.string.setting_launch_on_connect_desc
                ))
            },
            trailingContent = {
                Switch(checked = launchOnConnect, onCheckedChange = null)
            }
        )

        SettingSwitch(
            title = stringResource(R.string.setting_return_to_previous_app),
            description = stringResource(R.string.setting_return_to_previous_app_desc),
            checked = returnToPreviousApp,
            onCheckedChange = { viewModel.setReturnToPreviousApp(it) }
        )

        SectionHeader(stringResource(R.string.section_display))

        SettingSwitch(
            title = stringResource(R.string.setting_auto_fullscreen),
            description = stringResource(R.string.setting_auto_fullscreen_desc),
            checked = autoFullscreen,
            onCheckedChange = { viewModel.setAutoFullscreen(it) }
        )

        SettingResolution(
            value = resolution,
            onValueChange = { viewModel.setResolution(it) }
        )

        SettingChipField(
            title = stringResource(R.string.setting_max_fps),
            description = stringResource(R.string.setting_max_fps_desc),
            value = maxFps.toString(),
            presets = listOf("24" to "24", "30" to "30", "60" to "60", "120" to "120"),
            placeholder = stringResource(R.string.setting_max_fps_placeholder),
            keyboardType = KeyboardType.Number,
            onValueChange = { it.toIntOrNull()?.let { v -> viewModel.setMaxFps(v) } }
        )

        SettingSwitch(
            title = stringResource(R.string.setting_overscanned),
            description = stringResource(R.string.setting_overscanned_desc),
            checked = overscanned,
            onCheckedChange = { viewModel.setOverscanned(it) }
        )

        SectionHeader(stringResource(R.string.section_decode))

        SettingSwitch(
            title = stringResource(R.string.setting_h265),
            description = stringResource(R.string.setting_h265_desc),
            checked = h265Enabled,
            onCheckedChange = { viewModel.setH265Enabled(it) }
        )

        SettingSwitch(
            title = stringResource(R.string.setting_sw_alac),
            description = stringResource(R.string.setting_sw_alac_desc),
            checked = forceSwAlac,
            onCheckedChange = { viewModel.setForceSwAlac(it) }
        )

        SectionHeader(stringResource(R.string.section_developer))

        SettingSwitch(
            title = stringResource(R.string.setting_developer_options),
            description = stringResource(R.string.setting_developer_options_desc),
            checked = developerOptions,
            onCheckedChange = { viewModel.setDeveloperOptions(it) }
        )

        if (developerOptions) {
            SettingSwitch(
                title = stringResource(R.string.setting_auto_start),
                description = stringResource(R.string.setting_auto_start_desc),
                checked = autoStart,
                onCheckedChange = { viewModel.setAutoStart(it) }
            )

            SettingSwitch(
                title = stringResource(R.string.setting_keep_screen_on),
                description = stringResource(R.string.setting_keep_screen_on_desc),
                checked = keepScreenOn,
                onCheckedChange = { viewModel.setKeepScreenOn(it) }
            )

            SettingSwitch(
                title = stringResource(R.string.setting_idle_preview),
                description = stringResource(R.string.setting_idle_preview_desc),
                checked = idlePreview,
                onCheckedChange = { viewModel.setIdlePreview(it) }
            )

            SettingSwitch(
                title = stringResource(R.string.setting_advertise_video),
                description = stringResource(R.string.setting_advertise_video_desc),
                checked = advertiseVideo,
                onCheckedChange = { viewModel.setAdvertiseVideo(it) }
            )

            SettingSwitch(
                title = stringResource(R.string.setting_advertise_audio),
                description = stringResource(R.string.setting_advertise_audio_desc),
                checked = advertiseAudio,
                onCheckedChange = { viewModel.setAdvertiseAudio(it) }
            )

            SettingSwitch(
                title = stringResource(R.string.setting_alac),
                description = stringResource(R.string.setting_alac_desc),
                checked = alacEnabled,
                onCheckedChange = { viewModel.setAlacEnabled(it) }
            )

            SettingSwitch(
                title = stringResource(R.string.setting_aac),
                description = stringResource(R.string.setting_aac_desc),
                checked = aacEnabled,
                onCheckedChange = { viewModel.setAacEnabled(it) }
            )

            SettingSwitch(
                title = stringResource(R.string.setting_key_allow_frame_drop),
                description = stringResource(R.string.setting_key_allow_frame_drop_desc),
                checked = keyAllowFrameDrop,
                onCheckedChange = { viewModel.setKeyAllowFrameDrop(it) }
            )

            SettingSwitch(
                title = stringResource(R.string.setting_enforce_sdr),
                description = stringResource(R.string.setting_enforce_sdr_desc),
                checked = enforceSdr,
                onCheckedChange = { viewModel.setEnforceSdr(it) }
            )

            SettingSwitch(
                title = stringResource(R.string.setting_realtime_decoder_priority),
                description = stringResource(R.string.setting_realtime_decoder_priority_desc),
                checked = realtimeDecoderPriority,
                onCheckedChange = { viewModel.setRealtimeDecoderPriority(it) }
            )

            SettingChips(
                title = stringResource(R.string.setting_operating_rate),
                description = stringResource(R.string.setting_operating_rate_desc),
                value = operatingRate,
                options = listOf(
                    Prefs.AUTO to stringResource(R.string.chip_auto),
                    Prefs.ON to stringResource(R.string.chip_on),
                    Prefs.OFF to stringResource(R.string.chip_off),
                ),
                onValueChange = { viewModel.setOperatingRate(it) }
            )

            SettingSwitch(
                title = stringResource(R.string.setting_low_latency),
                description = stringResource(R.string.setting_low_latency_desc),
                checked = lowLatency,
                onCheckedChange = { viewModel.setLowLatency(it) }
            )

            SettingSwitch(
                title = stringResource(R.string.setting_scheduled_output_buffer_release),
                description = stringResource(R.string.setting_scheduled_output_buffer_release_desc),
                checked = scheduledOutputBufferRelease,
                onCheckedChange = { viewModel.setScheduledOutputBufferRelease(it) }
            )

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
                            steps = 19,
                            modifier = Modifier.dpadFocus().dpadAdjust(
                                onLeft = { viewModel.setAudioLatencyMs((audioLatencyMs - 50).coerceIn(0, 1000)) },
                                onRight = { viewModel.setAudioLatencyMs((audioLatencyMs + 50).coerceIn(0, 1000)) }
                            )
                        )
                    },
                    trailingContent = { Text(stringResource(R.string.audio_delay_value, sliderVal.roundToInt())) }
                )
            }

            SettingSwitch(
                title = stringResource(R.string.setting_audio_auto_buffer),
                description = stringResource(R.string.setting_audio_auto_buffer_desc),
                checked = audioAutoBuffer,
                onCheckedChange = { viewModel.setAudioAutoBuffer(it) }
            )

            if (audioAutoBuffer) {
                val maxStep = Prefs.ADAPTIVE_PERCENTILES.size - 1
                var stepVal by remember(audioAdaptiveStep) { mutableFloatStateOf(audioAdaptiveStep.toFloat()) }
                ListItem(
                    headlineContent = {
                        Slider(
                            value = stepVal,
                            onValueChange = { stepVal = it },
                            onValueChangeFinished = { viewModel.setAudioAdaptiveStep(stepVal.roundToInt()) },
                            valueRange = 0f..maxStep.toFloat(),
                            steps = maxStep - 1,
                            modifier = Modifier.dpadFocus().dpadAdjust(
                                onLeft = { viewModel.setAudioAdaptiveStep((audioAdaptiveStep - 1).coerceIn(0, maxStep)) },
                                onRight = { viewModel.setAudioAdaptiveStep((audioAdaptiveStep + 1).coerceIn(0, maxStep)) }
                            )
                        )
                    },
                    trailingContent = {
                        val step = stepVal.roundToInt().coerceIn(0, maxStep)
                        Text(stringResource(R.string.audio_adaptive_value,
                            Prefs.ADAPTIVE_PERCENTILES[step],
                            stringArrayResource(R.array.audio_adaptive_step_names)[step]))
                    }
                )
            } else {
                SettingTextField(
                    label = stringResource(R.string.setting_audio_cushion_ms),
                    value = audioCushionMs.toString(),
                    onCommit = { viewModel.setAudioCushionMs(it.toInt()) },
                    description = stringResource(R.string.setting_audio_cushion_ms_desc),
                    range = 1..1000
                )
            }

            SettingTextField(
                label = stringResource(R.string.setting_oboe_buffer_frames),
                value = oboeBufferFrames.toString(),
                onCommit = { viewModel.setOboeBufferFrames(it.toInt()) },
                description = stringResource(R.string.setting_oboe_buffer_frames_desc),
                range = 0..8192
            )


            SettingSwitch(
                title = stringResource(R.string.setting_debug_overlay),
                description = stringResource(R.string.setting_debug_overlay_desc),
                checked = debugEnabled,
                onCheckedChange = { viewModel.setDebugEnabled(it) }
            )

            SettingSwitch(
                title = stringResource(R.string.setting_benchmark_log),
                description = stringResource(R.string.setting_benchmark_log_desc),
                checked = benchmarkLog,
                onCheckedChange = { viewModel.setBenchmarkLog(it) }
            )
        }
    }
}

private fun canAutoLaunch(ctx: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(ctx)

private fun _overlayIntent(ctx: Context): Intent =
    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

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
private fun SettingResolution(
    value: String,
    onValueChange: (String) -> Unit
) {
    val presets = listOf(
        Prefs.AUTO to stringResource(R.string.chip_auto),
        "1280x720" to "1280x720",
        "1920x1080" to "1920x1080",
        "3840x2160" to "3840x2160"
    )
    val devicePresets = listOf(
        "portrait" to stringResource(R.string.chip_device_portrait),
        "landscape" to stringResource(R.string.chip_device_landscape)
    )
    val isPreset = (presets + devicePresets).any { it.first == value }
    var editing by remember { mutableStateOf(false) }
    val parts = if (!isPreset && value.contains("x")) value.split("x", limit = 2) else listOf("", "")
    var width by remember(value) { mutableStateOf(if (isPreset) "" else parts[0]) }
    var height by remember(value) { mutableStateOf(if (isPreset) "" else parts.getOrElse(1) { "" }) }

    ListItem(
        headlineContent = { Text(stringResource(R.string.setting_resolution)) },
        supportingContent = {
            Column {
                Text(stringResource(R.string.setting_resolution_desc))
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    @Composable
                    fun presetChip(key: String, label: String) = FilterChip(
                        selected = value == key && !editing,
                        onClick = {
                            editing = false
                            width = ""; height = ""
                            onValueChange(key)
                        },
                        label = { Text(label) },
                        modifier = Modifier.dpadFocus()
                    )
                    presets.forEach { (key, label) -> presetChip(key, label) }
                    FilterChip(
                        selected = !isPreset || editing,
                        onClick = { editing = true },
                        label = { Text(stringResource(R.string.chip_custom)) },
                        modifier = Modifier.dpadFocus()
                    )
                    devicePresets.forEach { (key, label) -> presetChip(key, label) }
                }
                if (editing || !isPreset) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = width,
                            onValueChange = { width = it.filter { c -> c.isDigit() }.take(5) },
                            singleLine = true,
                            label = { Text(stringResource(R.string.setting_resolution_width)) },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Next
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = height,
                            onValueChange = { height = it.filter { c -> c.isDigit() }.take(5) },
                            singleLine = true,
                            label = { Text(stringResource(R.string.setting_resolution_height)) },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = doneAndHide {
                                val w = width.toIntOrNull()
                                val h = height.toIntOrNull()
                                if (w != null && w > 0 && h != null && h > 0) {
                                    onValueChange("${w}x${h}")
                                    editing = false
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun doneAndHide(commit: () -> Unit): KeyboardActions {
    val keyboard = LocalSoftwareKeyboardController.current
    return KeyboardActions(onDone = { commit(); keyboard?.hide() })
}

@Composable
private fun SettingTextField(
    label: String,
    value: String,
    onCommit: (String) -> Unit,
    description: String? = null,
    range: IntRange? = null
) {
    var text by remember(value) { mutableStateOf(value) }
    val valid = range == null || text.toIntOrNull()?.let { it in range } == true
    fun save() {
        if (valid && text != value) onCommit(text)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = if (range == null) it else it.filter { c -> c.isDigit() }.take(6) },
        label = { Text(label) },
        supportingText = description?.let { { Text(it) } },
        singleLine = true,
        isError = !valid,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (range == null) KeyboardType.Text else KeyboardType.Number,
            imeAction = ImeAction.Done
        ),
        keyboardActions = doneAndHide(::save),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 8.dp)
            .onFocusChanged { if (!it.isFocused) save() }
    )
}

@Composable
private fun SettingChipField(
    title: String,
    description: String,
    value: String,
    presets: List<Pair<String, String>>,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit
) {
    val isPreset = presets.any { it.first == value }
    var editing by remember { mutableStateOf(false) }
    var text by remember(value) { mutableStateOf(if (isPreset) "" else value) }

    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Column {
                Text(description)
                Spacer(Modifier.height(8.dp))
                ChipRow(if (editing) "" else value, presets, { editing = false; text = ""; onValueChange(it) }) {
                    FilterChip(
                        selected = !isPreset || editing,
                        onClick = { editing = true },
                        label = { Text(stringResource(R.string.chip_custom)) },
                        modifier = Modifier.dpadFocus()
                    )
                }
                if (editing || !isPreset) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        label = { Text(placeholder) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = keyboardType,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = doneAndHide {
                            if (text.isNotBlank()) {
                                onValueChange(text)
                                editing = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    trailing: @Composable () -> Unit = {}
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (key, label) ->
            FilterChip(
                selected = value == key,
                onClick = { onSelect(key) },
                label = { Text(label) },
                modifier = Modifier.dpadFocus()
            )
        }
        trailing()
    }
}

@Composable
private fun SettingChips(
    title: String,
    description: String,
    value: String,
    options: List<Pair<String, String>>,
    onValueChange: (String) -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Column {
                Text(description)
                Spacer(Modifier.height(8.dp))
                ChipRow(value, options, onValueChange)
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
        modifier = Modifier
            .dpadFocus(RectangleShape)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) }
    )
}
