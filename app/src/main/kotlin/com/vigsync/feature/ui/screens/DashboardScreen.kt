package com.vigsync.feature.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vigsync.feature.ui.components.QrScanner
import java.text.SimpleDateFormat
import java.util.*
import com.vigsync.feature.ui.screens.DashboardViewModel

enum class PairingTab { MY_QR, SCAN_QR }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = viewModel(),
    pairingViewModel: PairingViewModel = viewModel(),
    navController: androidx.navigation.NavController? = null
) {
    val devices: List<com.vigsync.data.local.DeviceStatusEntity> by viewModel.pairedDevices.collectAsState(initial = emptyList())
    val isSyncActive: Boolean by viewModel.isSyncActive.collectAsState()
    val connectionStatus: com.vigsync.core.mqtt.MqttConnectionStatus by viewModel.connectionStatus.collectAsState(initial = com.vigsync.core.mqtt.MqttConnectionStatus.DISCONNECTED)
    
    val shareCalls: Boolean by viewModel.shareCalls.collectAsState(initial = false)
    val shareSms: Boolean by viewModel.shareSms.collectAsState(initial = false)
    val shareNotifications: Boolean by viewModel.shareNotifications.collectAsState(initial = false)

    var showSharingDialog by remember { mutableStateOf<Boolean>(false) }
    var localShareCalls by remember { mutableStateOf<Boolean>(false) }
    var localShareSms by remember { mutableStateOf<Boolean>(false) }
    var localShareNotifications by remember { mutableStateOf<Boolean>(false) }

    var showPairingDialog by remember { mutableStateOf<PairingTab?>(null) }
    var showDeleteConfirmation by remember { mutableStateOf<com.vigsync.data.local.DeviceStatusEntity?>(null) }
    var renamingDevice by remember { mutableStateOf<com.vigsync.data.local.DeviceStatusEntity?>(null) }
    var showStopConfirmation by remember { mutableStateOf(false) }
    
    val context = androidx.compose.ui.platform.LocalContext.current

    val callPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { permissions ->
            localShareCalls = permissions.values.all { it }
        }
    )

    val smsPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { permissions ->
            localShareSms = permissions.values.all { it }
        }
    )

    // Sharing Options Dialog
    if (showSharingDialog) {
        SharingOptionsDialog(
            isSharing = isSyncActive,
            calls = localShareCalls,
            sms = localShareSms,
            notifications = localShareNotifications,
            onCallsToggle = { checked ->
                if (checked) {
                    callPermissionLauncher.launch(arrayOf(
                        android.Manifest.permission.READ_CALL_LOG,
                        android.Manifest.permission.READ_PHONE_STATE,
                        android.Manifest.permission.READ_CONTACTS
                    ))
                } else {
                    localShareCalls = false
                }
            },
            onSmsToggle = { checked ->
                if (checked) {
                    smsPermissionLauncher.launch(arrayOf(
                        android.Manifest.permission.READ_SMS,
                        android.Manifest.permission.RECEIVE_SMS
                    ))
                } else {
                    localShareSms = false
                }
            },
            onNotificationsToggle = { checked ->
                if (checked) {
                    // Check if notification service is already enabled
                    val pkgName = context.packageName
                    val flat = android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                    val enabled = flat?.contains(pkgName) == true
                    
                    if (enabled) {
                        localShareNotifications = true
                    } else {
                        // Redirect to settings
                        context.startActivity(android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    }
                } else {
                    localShareNotifications = false
                }
            },
            onToggleSharing = {
                viewModel.updateSharingPreferences(localShareCalls, localShareSms, localShareNotifications)
                viewModel.toggleSync()
                showSharingDialog = false
            },
            onDismiss = { showSharingDialog = false }
        )
    }

    if (showStopConfirmation) {
        AlertDialog(
            onDismissRequest = { showStopConfirmation = false },
            title = { Text("Stop Synchronization?") },
            text = { Text("This will pause event synchronization across your devices. You can restart it anytime.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.toggleSync()
                    showStopConfirmation = false
                }) {
                    Text("Stop", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirmation = false }) {
                    Text("Keep Syncing")
                }
            }
        )
    }

    // Effect to check notification permission when returning to app
    androidx.compose.runtime.DisposableEffect(showSharingDialog) {
        if (showSharingDialog) {
            val pkgName = context.packageName
            val flat = android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            localShareNotifications = flat?.contains(pkgName) == true
        }
        onDispose {}
    }

    // Pairing Dialog
    if (showPairingDialog != null) {
        PairingDialog(
            viewModel = pairingViewModel,
            initialTab = showPairingDialog!!,
            onDismiss = { showPairingDialog = null }
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
            // "My Device" Card
            MyDeviceCard(
                model = android.os.Build.MODEL,
                connectionStatus = connectionStatus,
                isSharing = isSyncActive,
                lastSeen = System.currentTimeMillis(),
                onShareClick = { 
                    if (isSyncActive) {
                        showStopConfirmation = true
                    } else {
                        localShareCalls = shareCalls
                        localShareSms = shareSms
                        // Check notification listener status accurately
                        val flat = android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                        localShareNotifications = flat?.contains(context.packageName) == true
                        showSharingDialog = true 
                    }
                },
                onMyQrClick = { showPairingDialog = PairingTab.MY_QR },
                onRetryClick = { viewModel.retryConnection() }
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
                    val eventCount by viewModel.getEventCount(device.name).collectAsState(initial = 0)
                    DeviceGridCard(
                        device = device,
                        eventCount = eventCount,
                        onRename = { renamingDevice = device },
                        onDelete = { showDeleteConfirmation = device },
                        onClick = {
                            if (eventCount > 0) {
                                navController?.navigate(com.vigsync.feature.ui.Screen.Events.createRoute(device.name)) {
                                    // Ensure we switch to the Events tab properly
                                    popUpTo(com.vigsync.feature.ui.Screen.Dashboard.baseRoute) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        }
                    )
                }
                
                item {
                    AddDeviceCard(onClick = { showPairingDialog = PairingTab.SCAN_QR })
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
    lastSeen: Long,
    onShareClick: () -> Unit,
    onMyQrClick: () -> Unit,
    onRetryClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFF8F9FA),
            contentColor = Color(0xFF212529)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "My Device",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = model,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MqttStatusBadgeInline(status = connectionStatus, onRetry = onRetryClick)
                    if (isSharing) {
                        Spacer(modifier = Modifier.width(8.dp))
                        HeartbeatText(lastSeen = lastSeen)
                    }
                }
            }
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                // My QR Button
                DeviceActionButton(
                    icon = Icons.Default.QrCode,
                    label = "My QR",
                    onClick = onMyQrClick
                )
                
                Spacer(modifier = Modifier.width(16.dp))
                
                // Share Button (Dynamic Icon and Label)
                DeviceActionButton(
                    icon = Icons.Default.Sync,
                    label = if (isSharing) "Stop Sync" else "Start sync",
                    isActive = isSharing,
                    onClick = onShareClick
                )
            }
        }
    }
}

@Composable
fun MqttStatusBadgeInline(
    status: com.vigsync.core.mqtt.MqttConnectionStatus,
    onRetry: () -> Unit = {}
) {
    val (color, text, icon) = when (status) {
        com.vigsync.core.mqtt.MqttConnectionStatus.CONNECTED -> Triple(Color(0xFF4CAF50), "Online", Icons.Default.Cloud)
        com.vigsync.core.mqtt.MqttConnectionStatus.CONNECTING -> Triple(Color(0xFFFF9800), "Connecting", Icons.Default.CloudQueue)
        com.vigsync.core.mqtt.MqttConnectionStatus.RECONNECTING -> Triple(Color(0xFF2196F3), "Reconnecting", Icons.Default.CloudQueue)
        com.vigsync.core.mqtt.MqttConnectionStatus.DISCONNECTED -> Triple(Color(0xFFF44336), "Offline", Icons.Default.CloudOff)
    }

    Surface(
        color = color.copy(alpha = 0.1f),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.height(IntrinsicSize.Min).clickable(enabled = status == com.vigsync.core.mqtt.MqttConnectionStatus.DISCONNECTED) { onRetry() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (status == com.vigsync.core.mqtt.MqttConnectionStatus.DISCONNECTED) "Offline (Retry)" else text,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp
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
    onCallsToggle: (Boolean) -> Unit,
    onSmsToggle: (Boolean) -> Unit,
    onNotificationsToggle: (Boolean) -> Unit,
    onToggleSharing: () -> Unit,
    onDismiss: () -> Unit
) {
    var showStopConfirmationInDialog by remember { mutableStateOf(false) }

    if (showStopConfirmationInDialog) {
        AlertDialog(
            onDismissRequest = { showStopConfirmationInDialog = false },
            title = { Text("Stop Synchronization?") },
            text = { Text("This will pause event synchronization across your devices. You can restart it anytime.") },
            confirmButton = {
                TextButton(onClick = {
                    onToggleSharing()
                    showStopConfirmationInDialog = false
                    onDismiss()
                }) {
                    Text("Stop", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirmationInDialog = false }) {
                    Text("Keep Syncing")
                }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sync Configuration") },
        text = {
            Column {
                Text(
                    "Choose what you would like to share with your other devices.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Sync settings",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        SharingOptionItem(
                            title = "Calls",
                            permissionInfo = "Requires Call Log & Phone state access.",
                            checked = calls,
                            onCheckedChange = onCallsToggle
                        )
                        
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 12.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                        )
                        
                        SharingOptionItem(
                            title = "SMS",
                            permissionInfo = "Requires SMS access.",
                            checked = sms,
                            onCheckedChange = onSmsToggle
                        )
                        
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 12.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                        )
                        
                        SharingOptionItem(
                            title = "App Alerts",
                            permissionInfo = "Requires Special Notification Access.",
                            checked = notifications,
                            onCheckedChange = onNotificationsToggle
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isSharing) {
                        showStopConfirmationInDialog = true
                    } else {
                        onToggleSharing()
                    }
                },
                enabled = isSharing || calls || sms || notifications,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSharing) Color(0xFFFF9800) else Color(0xFF4CAF50)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isSharing) "Stop Sync" else "Start Sync")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun SharingOptionItem(
    title: String,
    permissionInfo: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = permissionInfo,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF4CAF50),
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF4CAF50)
            )
        )
    }
}

@Composable
fun DeviceActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isActive: Boolean = false,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(44.dp)
                .background(
                    if (isActive) Color(0xFF4CAF50).copy(alpha = 0.1f) else Color.LightGray.copy(alpha = 0.2f),
                    MaterialTheme.shapes.medium
                )
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isActive) Color(0xFF4CAF50) else Color.Gray,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            color = Color.Gray
        )
    }
}

@Composable
fun HeartbeatText(lastSeen: Long) {
    val diff = (System.currentTimeMillis() - lastSeen) / 1000
    val color = when {
        diff < 90 -> Color(0xFF4CAF50)
        diff < 180 -> Color(0xFFFF9800)
        else -> Color(0xFFF44336)
    }
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    Text(
        text = sdf.format(Date(lastSeen)),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontSize = 10.sp
    )
}

@Composable
fun PairingDialog(
    viewModel: PairingViewModel,
    initialTab: PairingTab,
    onDismiss: () -> Unit
) {
    val qrBitmap by viewModel.qrCode.collectAsState()
    var currentTab by remember { mutableStateOf(initialTab) }
    
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
                        if (currentTab == PairingTab.SCAN_QR) {
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
                        onClick = { currentTab = PairingTab.MY_QR },
                        modifier = Modifier.weight(1f),
                        colors = if (currentTab == PairingTab.MY_QR) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors()
                    ) {
                        Text("My QR", maxLines = 1)
                    }
                    Button(
                        onClick = { currentTab = PairingTab.SCAN_QR },
                        modifier = Modifier.weight(1f),
                        colors = if (currentTab == PairingTab.SCAN_QR) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors()
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
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .height(170.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = Color(0xFFF8F9FA).copy(alpha = 0.5f)
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
    eventCount: Int,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit = {}
) {
    // Show Synchronizing if it's never been online AND was added in the last 120 seconds
    val pairingTime = device.pairingTimestamp ?: 0L
    val timeSincePairing = System.currentTimeMillis() - pairingTime
    val isSynchronizing = !device.isOnline && pairingTime > 0 && timeSincePairing < 120000
    
    var showMenu by remember { mutableStateOf(false) }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth().height(170.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (device.isOnline) Color(0xFFE8F5E9) else Color(0xFFF8F9FA)
        ),
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = device.customLabel ?: device.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                
                Box {
                    IconButton(onClick = { showMenu = true }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu", modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Rename device") },
                            onClick = { showMenu = false; onRename() },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        )
                        DropdownMenuItem(
                            text = { Text("Remove device", color = MaterialTheme.colorScheme.error) },
                            onClick = { showMenu = false; onDelete() },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) }
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            
            if (isSynchronizing) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Text(
                        text = "Synchronizing...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "It may take up to 2 minutes. If not, check if the other phone is online and connected to MQTT.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 8.sp,
                        lineHeight = 10.sp
                    )
                }
            } else {
                Text("Last sync:", style = MaterialTheme.typography.labelSmall, color = Color.Gray, fontSize = 9.sp)
                HeartbeatText(lastSeen = device.lastSeen)
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Battery
                val batteryColor = when {
                    device.batteryLevel > 60 -> Color(0xFF4CAF50)
                    device.batteryLevel > 20 -> Color(0xFFFF9800)
                    else -> Color(0xFFF44336)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when {
                            device.batteryLevel > 80 -> Icons.Default.BatteryFull
                            device.batteryLevel > 20 -> Icons.Default.BatteryChargingFull
                            else -> Icons.Default.BatteryAlert
                        },
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = batteryColor
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${device.batteryLevel}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = batteryColor,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                
                // Events Count
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.List,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = Color.Gray
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "$eventCount events",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                }
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
