package com.vigsync.feature.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = viewModel(),
    onNavigateToPairing: () -> Unit = {}
) {
    val devices by viewModel.pairedDevices.collectAsState()
    val isServiceRunning by viewModel.isServiceRunning.collectAsState()
    val isConnected by viewModel.connectionStatus.collectAsState(initial = false)
    
    var showServiceDialog by remember { mutableStateOf(false) }
    var deniedPermissions by remember { mutableStateOf<List<String>>(emptyList()) }

    var renamingDevice by remember { mutableStateOf<com.vigsync.data.local.DeviceStatusEntity?>(null) }

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

    if (renamingDevice != null) {
        RenameDeviceDialog(
            currentLabel = renamingDevice?.customLabel ?: renamingDevice?.name ?: "",
            onConfirm = { newName ->
                viewModel.renameDevice(renamingDevice!!.deviceId, newName)
                renamingDevice = null
            },
            onDismiss = { renamingDevice = null }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VigSync") },
                actions = {
                    IconButton(onClick = onNavigateToPairing) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = "Pair Device")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Main Service Card with MQTT Badge
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
                        Text("Start Syncing This Device", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        // MQTT Badge
                        Surface(
                            color = if (isConnected) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.extraSmall,
                            modifier = Modifier.height(20.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(if (isConnected) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error, shape = androidx.compose.foundation.shape.CircleShape)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    if (isConnected) "MQTT Connected" else "MQTT Disconnected",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isConnected) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
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

            Spacer(modifier = Modifier.height(24.dp))
            
            Text("Synced Devices", style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.Start), fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            
            if (devices.isEmpty()) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text("No remote devices paired", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(devices) { device ->
                        DeviceGridCard(
                            device = device,
                            onRename = { renamingDevice = device },
                            onDelete = { viewModel.removeDevice(device.deviceId) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceGridCard(
    device: com.vigsync.data.local.DeviceStatusEntity,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (device.isOnline) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.Smartphone,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = if (device.isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
                
                Row {
                    IconButton(onClick = onRename, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "Rename", modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = device.customLabel ?: device.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Bold
            )
            
            Text(
                text = if (device.isOnline) "Sync Active" else "Offline",
                style = MaterialTheme.typography.labelSmall,
                color = if (device.isOnline) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.outline
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when {
                        device.batteryLevel > 80 -> Icons.Default.BatteryFull
                        device.batteryLevel > 20 -> Icons.Default.BatteryChargingFull
                        else -> Icons.Default.BatteryAlert
                    },
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = if (device.batteryLevel < 20) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "${device.batteryLevel}%",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun RenameDeviceDialog(
    currentLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(currentLabel) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Device") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= 20) text = it },
                label = { Text("Custom Label") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(text) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
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
