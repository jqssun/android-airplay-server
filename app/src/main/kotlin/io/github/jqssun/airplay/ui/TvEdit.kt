package io.github.jqssun.airplay.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * A setting whose value is edited with a text field.
 *
 * On a tv the on screen keyboard covers the whole screen and the back button of the
 * remote leaves the app, so a text field the d-pad can walk into is a trap. The field
 * is therefore only composed while it is being edited: the d-pad reaches the value row,
 * ok opens the editor, back or escape closes it again and returns the focus to the row.
 *
 * @param onEditingChange tells the screen that an editor is open, so that it can keep
 * its own back handling out of the way.
 */
@Composable
fun EditableValue(
    label: String,
    value: String,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    digitsOnly: Boolean = false,
    maxDigits: Int = 6,
    range: IntRange? = null,
    onEditingChange: (Boolean) -> Unit = {},
) {
    var editing by remember { mutableStateOf(false) }
    var text by remember(value) { mutableStateOf(value) }
    var wasEditing by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val rowFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }

    val valid = range == null || text.toIntOrNull()?.let { it in range } == true

    fun close(commit: Boolean) {
        if (commit && valid && text != value) onCommit(text)
        editing = false
        text = value
        keyboard?.hide()
        onEditingChange(false)
    }

    // when the editor closes the focus goes back to the row it came from
    LaunchedEffect(editing) {
        if (wasEditing && !editing) rowFocus.requestFocusUntilLanded()
        wasEditing = editing
    }

    if (editing) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = if (digitsOnly) it.filter { c -> c.isDigit() }.take(maxDigits) else it
            },
            label = { Text(label) },
            supportingText = description?.let { { Text(it) } },
            singleLine = true,
            isError = !valid,
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { close(commit = true) }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp)
                .focusRequester(fieldFocus)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        // escape leaves the editor without changing anything
                        Key.Escape -> {
                            close(commit = false)
                            true
                        }
                        // the ok button of the remote applies the value
                        Key.Enter, Key.NumPadEnter -> {
                            close(commit = true)
                            true
                        }
                        else -> false
                    }
                }
        )
        LaunchedEffect(Unit) { fieldFocus.requestFocusUntilLanded() }
        // back closes the editor, it must not reach the activity
        BackHandler { close(commit = false) }
    } else {
        fun open() {
            text = value
            editing = true
            onEditingChange(true)
        }
        ListItem(
            modifier = modifier
                .dpadFocus(RectangleShape)
                .focusRequester(rowFocus)
                .onPreviewKeyEvent { event ->
                    // the ok button of the remote opens the editor, on every remote
                    if (event.type == KeyEventType.KeyDown &&
                        (event.key == Key.DirectionCenter ||
                            event.key == Key.Enter ||
                            event.key == Key.NumPadEnter)
                    ) {
                        open()
                        true
                    } else {
                        false
                    }
                }
                .clickable { open() },
            headlineContent = { Text(label) },
            supportingContent = {
                Column {
                    if (description != null) Text(description)
                    Text(value, style = MaterialTheme.typography.titleMedium)
                }
            }
        )
    }
}
