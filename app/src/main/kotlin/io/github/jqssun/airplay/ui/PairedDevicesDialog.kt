package io.github.jqssun.airplay.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.pairing.PairingManager.PairedDevice

/**
 * Dialog showing paired devices with ability to remove them
 */
@Composable
fun PairedDevicesDialog(
    devices: List<PairedDevice>,
    onDismiss: () -> Unit,
    onRemoveDevice: (String) -> Unit,
    onRemoveAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    var deviceToRemove by remember { mutableStateOf<PairedDevice?>(null) }
    var showRemoveAllConfirm by remember { mutableStateOf(false) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Default.Devices, contentDescription = null)
                Text(stringResource(R.string.paired_devices_title))
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (devices.isEmpty()) {
                    Text(
                        text = stringResource(R.string.no_paired_devices),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                } else {
                    Text(
                        text = stringResource(R.string.paired_devices_subtitle, devices.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(devices, key = { it.id }) { device ->
                            PairedDeviceItem(
                                device = device,
                                onRemove = { deviceToRemove = device }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (devices.isNotEmpty()) {
                TextButton(
                    onClick = { showRemoveAllConfirm = true },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.remove_all_devices))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_close))
            }
        },
        modifier = modifier
    )
    
    // Confirm remove single device
    if (deviceToRemove != null) {
        AlertDialog(
            onDismissRequest = { deviceToRemove = null },
            title = { Text(stringResource(R.string.remove_device_title)) },
            text = {
                Text(stringResource(R.string.remove_device_message, deviceToRemove!!.name))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemoveDevice(deviceToRemove!!.id)
                        deviceToRemove = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.btn_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { deviceToRemove = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }
    
    // Confirm remove all
    if (showRemoveAllConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveAllConfirm = false },
            title = { Text(stringResource(R.string.remove_all_devices_title)) },
            text = {
                Text(stringResource(R.string.remove_all_devices_message, devices.size))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemoveAll()
                        showRemoveAllConfirm = false
                        onDismiss()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.btn_remove_all))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveAllConfirm = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }
}

/**
 * Single paired device item
 */
@Composable
fun PairedDeviceItem(
    device: PairedDevice,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(R.string.last_seen, device.lastSeenFormatted()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            IconButton(
                onClick = onRemove,
                modifier = Modifier.dpadFocus()
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.cd_remove_device),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
