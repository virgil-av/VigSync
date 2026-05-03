package com.vigsync.feature.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
fun DashboardScreen(viewModel: DashboardViewModel = viewModel()) {
    val events by viewModel.recentEvents.collectAsState(initial = emptyList())
    val devices by viewModel.pairedDevices.collectAsState()
    val isServiceRunning by viewModel.isServiceRunning.collectAsState(initial = false)
    val isConnected by viewModel.connectionStatus.collectAsState(initial = false)
    
    var showServiceDialog by remember { mutableStateOf(false) }
    var deniedPermissions by remember { mutableStateOf<List<String>>(emptyList()) }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { permissions ->
            deniedPermissions = permissions.filter { !it.value }.keys.toList()
            showServiceDialog = true
        }
    )

    if (showServiceDialog) {
        ServiceActivationDialog(
            deniedPermissions = deniedPermissions,
            onConfirm = {
                showServiceDialog = false
                viewModel.toggleService(permissionsGranted = true)
            },
            onDismiss = { showServiceDialog = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("VigSync Dashboard", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Observer Service", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (isServiceRunning) "Status: ACTIVE" else "Status: STOPPED",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isServiceRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
                Switch(
                    checked = isServiceRunning,
                    onCheckedChange = { checked ->
                        if (checked) {
                            val permissions = mutableListOf(
                                android.Manifest.permission.READ_SMS,
                                android.Manifest.permission.RECEIVE_SMS,
                                android.Manifest.permission.READ_CALL_LOG,
                                android.Manifest.permission.READ_PHONE_STATE,
                                android.Manifest.permission.READ_CONTACTS
                            )
                            if (android.os.Build.VERSION.SDK_INT >= 33) {
                                permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
                            }
                            permissionLauncher.launch(permissions.toTypedArray())
                        } else {
                            viewModel.toggleService()
                        }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isConnected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isConnected) "● MQTT Connected" else "○ MQTT Disconnected",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        
        Text("Paired Devices", style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.Start))
        
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(0.4f),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            if (devices.isEmpty()) {
                item {
                    Text("No remote devices paired", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                }
            } else {
                items(devices.size) { index ->
                    val device = devices[index]
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (device.isOnline) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(device.name, style = MaterialTheme.typography.titleMedium)
                                Text(if (device.isOnline) "Connected" else "Offline", style = MaterialTheme.typography.bodySmall)
                                val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(device.lastSeen))
                                Text("Updated: $time", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                            Text("${device.batteryLevel}%", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 8.dp))
                            IconButton(onClick = { viewModel.removeDevice(device.deviceId) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove Device", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        Text("Recent Activity", style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.Start))
        
        Box(modifier = Modifier.fillMaxWidth().weight(0.6f)) {
            if (events.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No recent activity", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(events.size) { index ->
                        val event = events[index]
                        DebugLogItem(event.message, event.status, event.timestamp)
                    }
                }
            }
        }
    }
}

@Composable
fun ServiceActivationDialog(
    deniedPermissions: List<String>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val enabledListeners = android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
    val hasNotificationAccess = enabledListeners.contains(context.packageName)

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.NotificationsActive, contentDescription = null) },
        title = { Text("Activate Observers") },
        text = {
            Column {
                Text("The Observer Service captures SMS, Calls, and Notifications from this phone to sync them with your paired devices.")
                
                if (deniedPermissions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Missing Permissions:", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                    }
                    deniedPermissions.forEach { permission ->
                        val label = permission.substringAfterLast(".")
                        Text("• $label will NOT be observed.", style = MaterialTheme.typography.bodySmall)
                    }
                }

                if (!hasNotificationAccess) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Notification Access is DISABLED. You will not receive alerts from WhatsApp, Mail, or other apps.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        },
                        modifier = Modifier.padding(top = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Enable Notification Access")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Start Service")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
