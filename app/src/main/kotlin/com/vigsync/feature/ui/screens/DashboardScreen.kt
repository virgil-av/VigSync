package com.vigsync.feature.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vigsync.feature.ui.screens.DashboardViewModel
import com.vigsync.feature.ui.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = viewModel(),
    navController: androidx.navigation.NavController? = null
) {
    val isMonitoringActive: Boolean by viewModel.isSyncActive.collectAsState()
    val eventCounts by viewModel.deviceEventCounts.collectAsState()
    
    val shareCalls: Boolean by viewModel.shareCalls.collectAsState(initial = false)
    val shareSms: Boolean by viewModel.shareSms.collectAsState(initial = false)
    val shareNotifications: Boolean by viewModel.shareNotifications.collectAsState(initial = false)

    var showSharingDialog by remember { mutableStateOf<Boolean>(false) }
    var localShareCalls by remember { mutableStateOf<Boolean>(false) }
    var localShareSms by remember { mutableStateOf<Boolean>(false) }
    var localShareNotifications by remember { mutableStateOf<Boolean>(false) }

    var showStopConfirmation by remember { mutableStateOf(false) }
    var showResetConfirmation by remember { mutableStateOf(false) }
    var showInspectDialog by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf(false) }
    
    val context = LocalContext.current

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

    // Monitoring Options Dialog
    if (showSharingDialog) {
        MonitoringOptionsDialog(
            isActive = isMonitoringActive,
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
                    val pkgName = context.packageName
                    val flat = android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                    val enabled = flat?.contains(pkgName) == true
                    
                    if (enabled) {
                        localShareNotifications = true
                    } else {
                        context.startActivity(android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    }
                } else {
                    localShareNotifications = false
                }
            },
            onToggleMonitoring = {
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
            title = { Text("Stop Monitoring?") },
            text = { Text("This will pause local event monitoring. You can restart it anytime.") },
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
                    Text("Keep Monitoring")
                }
            }
        )
    }

    if (showResetConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            title = { Text("Reset Export File?") },
            text = { Text("This will clear all events from the text file. The local database history will remain untouched.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetExportFile()
                    showResetConfirmation = false
                }) {
                    Text("Reset", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showInspectDialog) {
        InspectLogDialog(
            viewModel = viewModel,
            onDismiss = { showInspectDialog = false }
        )
    }

    if (showQrDialog) {
        MyQrDialog(
            viewModel = viewModel,
            onDismiss = { showQrDialog = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VigSync Server", fontWeight = FontWeight.Bold) }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            MyDeviceCard(
                model = android.os.Build.MODEL,
                isActive = isMonitoringActive,
                onMonitorClick = { 
                    if (isMonitoringActive) {
                        showStopConfirmation = true
                    } else {
                        localShareCalls = shareCalls
                        localShareSms = shareSms
                        val flat = android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                        localShareNotifications = flat?.contains(context.packageName) == true
                        showSharingDialog = true 
                    }
                },
                onMyQrClick = { showQrDialog = true }
            )

            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = "Local Activity",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(12.dp))
            
            // Local Stats Card
            Card(
                modifier = Modifier.fillMaxWidth().clickable {
                    navController?.navigate(Screen.Events.route) {
                        popUpTo(navController.graph.startDestinationId) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        val localCount = eventCounts["Local Device"] ?: 0
                        Text("Recorded Events", fontWeight = FontWeight.Bold)
                        Text("$localCount events captured locally", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "External Integration",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(12.dp))

            // File Export Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Event Log File", fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Path Display with Copy Button
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.small,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = viewModel.exportFilePath,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("vigsync_log_path", viewModel.exportFilePath)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "Path copied to clipboard", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy Path",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { 
                                viewModel.loadFormattedLog()
                                showInspectDialog = true 
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Inspect")
                        }
                        
                        OutlinedButton(
                            onClick = { showResetConfirmation = true },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Reset")
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InspectLogDialog(
    viewModel: DashboardViewModel,
    onDismiss: () -> Unit
) {
    val logContent by viewModel.formattedLog.collectAsState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Log Inspector") },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Close")
                            }
                        },
                        actions = {
                            IconButton(onClick = { viewModel.loadFormattedLog() }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                            }
                        }
                    )
                }
            ) { padding ->
                Box(modifier = Modifier.padding(padding).fillMaxSize().background(Color(0xFF1E1E1E))) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        Text(
                            text = logContent,
                            color = Color(0xFFD4D4D4),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MyQrDialog(
    viewModel: DashboardViewModel,
    onDismiss: () -> Unit
) {
    val qrBitmap by viewModel.qrCode.collectAsState()
    
    LaunchedEffect(Unit) {
        viewModel.generateMyQr()
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Device Pairing Info", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                
                Surface(
                    modifier = Modifier.size(240.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (qrBitmap != null) {
                            Image(
                                bitmap = qrBitmap!!.asImageBitmap(), 
                                contentDescription = "My QR Code", 
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            CircularProgressIndicator()
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Scan this from another device to subscribe to this phone's events.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
fun MyDeviceCard(
    model: String,
    isActive: Boolean,
    onMonitorClick: () -> Unit,
    onMyQrClick: () -> Unit
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
                Surface(
                    color = if (isActive) Color(0xFF4CAF50).copy(alpha = 0.1f) else Color.Gray.copy(alpha = 0.1f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = if (isActive) "Monitoring Active" else "Monitoring Inactive",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isActive) Color(0xFF4CAF50) else Color.Gray,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                DeviceActionButton(
                    icon = Icons.Default.QrCode,
                    label = "My QR",
                    onClick = onMyQrClick
                )
                
                Spacer(modifier = Modifier.width(16.dp))
                
                DeviceActionButton(
                    icon = if (isActive) Icons.Default.Pause else Icons.Default.PlayArrow,
                    label = if (isActive) "Stop" else "Start",
                    isActive = isActive,
                    onClick = onMonitorClick
                )
            }
        }
    }
}

@Composable
fun MonitoringOptionsDialog(
    isActive: Boolean,
    calls: Boolean,
    sms: Boolean,
    notifications: Boolean,
    onCallsToggle: (Boolean) -> Unit,
    onSmsToggle: (Boolean) -> Unit,
    onNotificationsToggle: (Boolean) -> Unit,
    onToggleMonitoring: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Monitoring Configuration") },
        text = {
            Column {
                Text(
                    "Choose which events to record on this device.",
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
                        MonitoringOptionItem(
                            title = "Calls",
                            permissionInfo = "Requires Call Log & Phone state access.",
                            checked = calls,
                            onCheckedChange = onCallsToggle
                        )
                        
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                        
                        MonitoringOptionItem(
                            title = "SMS",
                            permissionInfo = "Requires SMS access.",
                            checked = sms,
                            onCheckedChange = onSmsToggle
                        )
                        
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                        
                        MonitoringOptionItem(
                            title = "App Alerts",
                            permissionInfo = "Requires Notification Access.",
                            checked = notifications,
                            onCheckedChange = onNotificationsToggle
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onToggleMonitoring,
                enabled = isActive || calls || sms || notifications,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isActive) Color(0xFFFF9800) else Color(0xFF4CAF50)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isActive) "Stop Monitoring" else "Start Monitoring")
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
fun MonitoringOptionItem(
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
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
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
