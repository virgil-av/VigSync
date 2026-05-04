package com.vigsync.feature.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vigsync.feature.ui.components.QrScanner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = viewModel(),
    pairingViewModel: PairingViewModel = viewModel(),
    onNavigateToPairing: (Boolean) -> Unit = {} // Keep for compatibility if needed elsewhere
) {
    val devices by viewModel.pairedDevices.collectAsState()
    val isSyncActive by viewModel.isSyncActive.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState(initial = com.vigsync.core.mqtt.MqttConnectionStatus.DISCONNECTED)
    
    val shareCalls by viewModel.shareCalls.collectAsState(initial = true)
    val shareSms by viewModel.shareSms.collectAsState(initial = true)
    val shareNotifications by viewModel.shareNotifications.collectAsState(initial = true)

    var showSharingDialog by remember { mutableStateOf(false) }
    var showPairingDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf<com.vigsync.data.local.DeviceStatusEntity?>(null) }
    var renamingDevice by remember { mutableStateOf<com.vigsync.data.local.DeviceStatusEntity?>(null) }
    
    var deniedPermissions by remember { mutableStateOf<List<String>>(emptyList()) }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { permissions ->
            deniedPermissions = permissions.filter { !it.value }.keys.toList()
            if (deniedPermissions.isEmpty()) {
                viewModel.toggleSync(permissionsGranted = true)
            }
        }
    )

    // Sharing Options Dialog
    if (showSharingDialog) {
        SharingOptionsDialog(
            isSharing = isSyncActive,
            calls = shareCalls,
            sms = shareSms,
            notifications = shareNotifications,
            onUpdatePrefs = { c, s, n -> viewModel.updateSharingPreferences(c, s, n) },
            onToggleSharing = {
                if (!isSyncActive) {
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
                    viewModel.toggleSync()
                }
                showSharingDialog = false
            },
            onDismiss = { showSharingDialog = false }
        )
    }

    // Pairing Dialog
    if (showPairingDialog) {
        PairingDialog(
            viewModel = pairingViewModel,
            onDismiss = { showPairingDialog = false }
        )
    }

    // Rename Dialog
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

    // Delete Confirmation
    if (showDeleteConfirmation != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = null },
            title = { Text("Remove Device") },
            text = { Text("Are you sure you want to remove '${showDeleteConfirmation?.customLabel ?: showDeleteConfirmation?.name}'?") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.removeDevice(showDeleteConfirmation!!.deviceId)
                        showDeleteConfirmation = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VigSync", fontWeight = FontWeight.Bold) }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            // "My Device" Card - Full Width, Professional Look
            MyDeviceCard(
                model = android.os.Build.MODEL,
                connectionStatus = connectionStatus,
                isSharing = isSyncActive,
                onShareClick = { showSharingDialog = true }
            )

            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = "Synced Devices",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(12.dp))
            
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(devices) { device ->
                    DeviceGridCard(
                        device = device,
                        onRename = { renamingDevice = device },
                        onDelete = { showDeleteConfirmation = device }
                    )
                }
                
                item {
                    AddDeviceCard(onClick = { showPairingDialog = true })
                }
            }
        }
    }
}

@Composable
fun MyDeviceCard(
    model: String,
    connectionStatus: com.vigsync.core.mqtt.MqttConnectionStatus,
    isSharing: Boolean,
    onShareClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFF8F9FA), // Soft white
            contentColor = Color(0xFF212529)   // High contrast text
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "My Device",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        MqttStatusBadge(status = connectionStatus)
                    }
                    Text(
                        text = model,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray
                    )
                }
                
                // Share Button
                IconButton(
                    onClick = onShareClick,
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            if (isSharing) Color(0xFF4CAF50).copy(alpha = 0.1f) else Color.LightGray.copy(alpha = 0.2f),
                            MaterialTheme.shapes.medium
                        )
                ) {
                    Icon(
                        imageVector = if (isSharing) Icons.Default.CloudDone else Icons.Default.CloudOff,
                        contentDescription = "Share",
                        tint = if (isSharing) Color(0xFF4CAF50) else Color.Gray
                    )
                }
            }
            
            if (isSharing) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Currently sharing with synced devices",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF4CAF50),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun MqttStatusBadge(status: com.vigsync.core.mqtt.MqttConnectionStatus) {
    val (color, text) = when (status) {
        com.vigsync.core.mqtt.MqttConnectionStatus.CONNECTED -> Color(0xFF4CAF50) to "Online"
        com.vigsync.core.mqtt.MqttConnectionStatus.CONNECTING -> Color(0xFFFF9800) to "Connecting"
        com.vigsync.core.mqtt.MqttConnectionStatus.DISCONNECTED -> Color(0xFFF44336) to "Offline"
    }

    Surface(
        color = color.copy(alpha = 0.15f),
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(color, MaterialTheme.shapes.extraSmall)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun SharingOptionsDialog(
    isSharing: Boolean,
    calls: Boolean,
    sms: Boolean,
    notifications: Boolean,
    onUpdatePrefs: (Boolean, Boolean, Boolean) -> Unit,
    onToggleSharing: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isSharing) "Stop Sharing" else "Share this device") },
        text = {
            Column {
                Text(
                    "Choose what you would like to share with your other devices.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                SharingToggle("Calls", calls) { onUpdatePrefs(it, sms, notifications) }
                SharingToggle("Messages", sms) { onUpdatePrefs(calls, it, notifications) }
                SharingToggle("Notifications", notifications) { onUpdatePrefs(calls, sms, it) }
            }
        },
        confirmButton = {
            Button(onClick = onToggleSharing) {
                Text(if (isSharing) "Stop Sharing" else "Start Sharing")
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
fun SharingToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun PairingDialog(
    viewModel: PairingViewModel,
    onDismiss: () -> Unit
) {
    val qrBitmap by viewModel.qrCode.collectAsState()
    var isScanning by remember { mutableStateOf(false) }
    
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Pair Device", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                
                Surface(
                    modifier = Modifier.size(220.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isScanning) {
                            QrScanner { result ->
                                viewModel.onScanResult(result)
                                onDismiss()
                            }
                        } else if (qrBitmap != null) {
                            Image(bitmap = qrBitmap!!.asImageBitmap(), contentDescription = "My QR Code", modifier = Modifier.fillMaxSize())
                        } else {
                            CircularProgressIndicator()
                            LaunchedEffect(Unit) { viewModel.generateMyQr() }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { isScanning = false },
                        modifier = Modifier.weight(1f),
                        colors = if (!isScanning) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors()
                    ) {
                        Text("My QR", maxLines = 1)
                    }
                    Button(
                        onClick = { isScanning = true },
                        modifier = Modifier.weight(1f),
                        colors = if (isScanning) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors()
                    ) {
                        Text("Scan QR", maxLines = 1)
                    }
                }
                
                TextButton(onClick = onDismiss, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
fun AddDeviceCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp), // Consistent height with device cards
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Add Device",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
fun DeviceGridCard(
    device: com.vigsync.data.local.DeviceStatusEntity,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val isNew = !device.isOnline && (System.currentTimeMillis() - device.lastSeen < 60000)

    Card(
        modifier = Modifier.fillMaxWidth().height(160.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (device.isOnline) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = if (isNew) Icons.Default.Sync else Icons.Default.Smartphone,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp).rotate(if (isNew) animateRotation() else 0f),
                    tint = if (device.isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
                
                IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = device.customLabel ?: device.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Bold
            )
            
            if (isNew) {
                Text(
                    text = "Synchronizing...",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "May take a minute",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    fontSize = 10.sp
                )
            } else {
                Text(
                    text = if (device.isOnline) "Sync Active" else "Offline",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (device.isOnline) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.outline
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when {
                            device.batteryLevel > 80 -> Icons.Default.BatteryFull
                            device.batteryLevel > 20 -> Icons.Default.BatteryChargingFull
                            else -> Icons.Default.BatteryAlert
                        },
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${device.batteryLevel}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            TextButton(
                onClick = onRename,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.height(24.dp).align(Alignment.Start)
            ) {
                Text("Rename device", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
            }
        }
    }
}

@Composable
fun animateRotation(): Float {
    val infiniteTransition = rememberInfiniteTransition()
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )
    return angle
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
