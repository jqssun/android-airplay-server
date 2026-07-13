package io.github.jqssun.airplay.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.jqssun.airplay.R
import kotlin.math.round

// portrait slides up from the bottom, landscape in from the end
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BoxScope.PlaybackSpeedSelector(
    show: Boolean,
    speed: Float,
    skipSilence: Boolean,
    onSpeedChange: (Float) -> Unit,
    onSkipSilenceChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hapticFeedback = LocalHapticFeedback.current
    val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(show) {
        if (show) focusRequester.requestFocusUntilLanded(attempts = 5)
    }

    if (show) {
        BackHandler(onBack = onDismiss)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
        )
    }
    AnimatedVisibility(
        modifier = Modifier.align(if (portrait) Alignment.BottomCenter else Alignment.CenterEnd),
        visible = show,
        enter = if (portrait) slideInVertically { it } else slideInHorizontally { it },
        exit = if (portrait) slideOutVertically { it } else slideOutHorizontally { it },
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            modifier = modifier.then(
                if (portrait) Modifier.fillMaxWidth().fillMaxHeight(0.45f)
                else Modifier.fillMaxWidth(0.45f).fillMaxHeight()
            ),
        ) {
            val endPadding = WindowInsets.safeDrawing.asPaddingValues()
                .calculateEndPadding(LocalLayoutDirection.current)
            Column(
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .focusGroup()
                    .padding(top = 24.dp)
                    .padding(end = endPadding)
            ) {
                Text(
                    modifier = Modifier.padding(horizontal = 24.dp),
                    text = stringResource(R.string.select_playback_speed),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(modifier = Modifier.size(8.dp))
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 24.dp)
                        .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalIconButton(
                            onClick = { onSpeedChange((speed - STEP).coerceAtLeast(MIN_SPEED)) },
                            modifier = Modifier.focusRing()
                        ) {
                            Icon(Icons.Rounded.Remove, contentDescription = null)
                        }
                        Text(
                            text = (round(speed * 100) / 100).toString(),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                        FilledTonalIconButton(
                            onClick = { onSpeedChange((speed + STEP).coerceAtMost(MAX_SPEED)) },
                            modifier = Modifier.focusRing()
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Slider(
                            value = speed,
                            valueRange = MIN_SPEED..MAX_SPEED,
                            steps = ((MAX_SPEED - MIN_SPEED) / STEP).toInt() - 1,
                            onValueChange = {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSpeedChange(it)
                            },
                            modifier = Modifier.weight(1f).focusRing(MaterialTheme.shapes.small),
                        )
                        IconButton(onClick = { onSpeedChange(1f) }, modifier = Modifier.focusRing()) {
                            Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.cd_reset_speed))
                        }
                    }
                    FlowRow(
                        maxItemsInEachRow = 5,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PRESETS.forEach { preset ->
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .focusRing(CircleShape)
                                    .border(width = 1.dp, color = LocalContentColor.current, shape = CircleShape)
                                    .clickable { onSpeedChange(preset) }
                                    .padding(8.dp)
                                    .weight(1f),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = preset.toString(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .focusRing(RoundedCornerShape(4.dp))
                            .toggleable(value = skipSilence, onValueChange = onSkipSilenceChange)
                            .fillMaxWidth()
                            .padding(8.dp)
                            .semantics(mergeDescendants = true) {},
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.skip_silence),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = skipSilence, onCheckedChange = null)
                    }
                }
            }
        }
    }
}

private const val MIN_SPEED = 0.2f
private const val MAX_SPEED = 4f
private const val STEP = 0.1f
private val PRESETS = listOf(0.2f, 0.5f, 0.75f, 1.0f, 1.5f, 2.0f, 2.5f, 3.0f, 3.5f, 4.0f)
