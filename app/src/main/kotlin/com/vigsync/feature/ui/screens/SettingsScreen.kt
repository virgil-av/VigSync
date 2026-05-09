package com.vigsync.feature.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

enum class SettingsSection { SERVER_CONFIG, PERMISSIONS, MONITORING_CONTROLS, DETECTED_APPS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    var activeDialog by remember { mutableStateOf<SettingsSection?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Settings") })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            
            SettingsMenuItem(
                title = "Server Configuration",
                subtitle = "MQTT Broker and Topic settings for Raspberry Pi",
                icon = Icons.Default.Dns,
                onClick = { activeDialog = SettingsSection.SERVER_CONFIG }
            )

            SettingsMenuItem(
                title = "System Permissions",
                subtitle = "SMS, Calls, and Background access",
                icon = Icons.Default.Security,
                onClick = { activeDialog = SettingsSection.PERMISSIONS }
            )

            SettingsMenuItem(
                title = "Monitoring Controls",
                subtitle = "Select which events to record locally",
                icon = Icons.Default.SettingsSuggest,
                onClick = { activeDialog = SettingsSection.MONITORING_CONTROLS }
            )
            
            SettingsMenuItem(
                title = "Detected Apps",
                subtitle = "Manage monitoring for discovered apps",
                icon = Icons.Default.Apps,
                onClick = { activeDialog = SettingsSection.DETECTED_APPS }
            )
        }
    }

    if (activeDialog != null) {
        SettingsDialog(
            section = activeDialog!!,
            viewModel = viewModel,
            onDismiss = { activeDialog = null }
        )
    }
}

@Composable
fun SettingsMenuItem(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 20.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    section: SettingsSection,
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit
) {
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
                        title = { 
                            Text(when(section) {
                                SettingsSection.SERVER_CONFIG -> "Server Configuration"
                                SettingsSection.PERMISSIONS -> "System Permissions"
                                SettingsSection.MONITORING_CONTROLS -> "Monitoring Controls"
                                SettingsSection.DETECTED_APPS -> "Detected Apps"
                            })
                        },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Default.Close, contentDescription = "Close")
                            }
                        }
                    )
                }
            ) { padding ->
                Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                    when (section) {
                        SettingsSection.SERVER_CONFIG -> ServerConfigTab(viewModel)
                        SettingsSection.PERMISSIONS -> PermissionsSettingsTab()
                        SettingsSection.MONITORING_CONTROLS -> MonitoringControlsTab(viewModel)
                        SettingsSection.DETECTED_APPS -> DetectedAppsTab(viewModel)
                    }
                }
            }
        }
    }
}

@Composable
fun ServerConfigTab(viewModel: SettingsViewModel) {
    val savedUrl by viewModel.brokerUrl.collectAsState()
    val savedPort by viewModel.brokerPort.collectAsState()
    val savedUser by viewModel.brokerUser.collectAsState()
    val savedPass by viewModel.brokerPass.collectAsState()
    val savedPrefix by viewModel.topicPrefix.collectAsState()

    var url by remember(savedUrl) { mutableStateOf(savedUrl) }
    var port by remember(savedPort) { mutableStateOf(savedPort) }
    var user by remember(savedUser) { mutableStateOf(savedUser) }
    var pass by remember(savedPass) { mutableStateOf(savedPass) }
    var prefix by remember(savedPrefix) { mutableStateOf(savedPrefix) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("MQTT Broker Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Broker URL (e.g. broker.hivemq.com)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = port,
            onValueChange = { port = it },
            label = { Text("Broker Port (default 1883)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("Username (Optional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text("Password (Optional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text("Topic Definition", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        
        OutlinedTextField(
            value = prefix,
            onValueChange = { prefix = it },
            label = { Text("Topic Prefix (e.g. vigsync)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            supportingText = { Text("Final topic: $prefix/devices/[device_id]") }
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { viewModel.saveServerConfig(url, port, user, pass, prefix) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Save, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Save & Generate Config File")
        }
        
        Text(
            "This will create 'vigsync_server_config.json' in your Documents folder for the Raspberry Pi to consume.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
fun PermissionsSettingsTab() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    var hasSms by remember { mutableStateOf(false) }
    var hasCall by remember { mutableStateOf(false) }
    var hasPhone by remember { mutableStateOf(false) }
    var hasNotif by remember { mutableStateOf(false) }
    var hasListener by remember { mutableStateOf(false) }
    var isBatteryUnrestricted by remember { mutableStateOf(false) }

    fun updateStates() {
        hasSms = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        hasCall = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALL_LOG) == android.content.pm.PackageManager.PERMISSION_GRANTED
        hasPhone = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        hasNotif = if (android.os.Build.VERSION.SDK_INT >= 33) {
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
        
        val enabledListeners = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        hasListener = enabledListeners.contains(context.packageName)
        
        val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        isBatteryUnrestricted = powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                updateStates()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { updateStates() }

    LaunchedEffect(Unit) {
        updateStates()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("System Permissions", style = MaterialTheme.typography.titleLarge)
        Text("Tap to request permissions", style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.height(16.dp))

        PermissionItem("SMS Capture", hasSms) {
            permissionLauncher.launch(android.Manifest.permission.READ_SMS)
        }
        PermissionItem("Call Log Access", hasCall) {
            permissionLauncher.launch(android.Manifest.permission.READ_CALL_LOG)
        }
        PermissionItem("Phone Status", hasPhone) {
            permissionLauncher.launch(android.Manifest.permission.READ_PHONE_STATE)
        }
        PermissionItem("Notification Listener", hasListener) {
            val intent = android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            PermissionItem("Post Notifications", hasNotif) {
                permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        
        Text("Background Reliability", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isBatteryUnrestricted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Battery Optimization", style = MaterialTheme.typography.titleMedium)
                Text(
                    "The app needs 'Unrestricted' battery access to maintain reliable monitoring while the screen is off.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (!isBatteryUnrestricted) {
                    Button(
                        onClick = {
                            val intent = android.content.Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        },
                        modifier = Modifier.padding(top = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF4CAF50))
                    ) {
                        Text("Open App Info to set Unrestricted")
                    }
                } else {
                    Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Already Unrestricted", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
fun MonitoringControlsTab(viewModel: SettingsViewModel) {
    val calls by viewModel.notifCalls.collectAsState()
    val sms by viewModel.notifSms.collectAsState()
    val other by viewModel.notifOther.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Monitoring Controls", style = MaterialTheme.typography.titleLarge)
        Text(
            "Select which types of events should be recorded and stored locally on this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider()

        PermissionItem(
            label = "Record Calls",
            granted = calls,
            onClick = { viewModel.updateNotifSettings(!calls, sms, other) }
        )

        PermissionItem(
            label = "Record SMS",
            granted = sms,
            onClick = { viewModel.updateNotifSettings(calls, !sms, other) }
        )

        PermissionItem(
            label = "Record App Alerts",
            granted = other,
            onClick = { viewModel.updateNotifSettings(calls, sms, !other) }
        )
    }
}

@Composable
fun DetectedAppsTab(viewModel: SettingsViewModel) {
    val discoveredApps by viewModel.discoveredApps.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Monitored Apps", style = MaterialTheme.typography.titleLarge)
        Text(
            "Apps that have triggered a notification are listed here. You can selectively disable their monitoring.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        
        if (discoveredApps.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("No apps discovered yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }

        discoveredApps.forEach { app ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    val bitmap = remember(app.icon) { app.icon?.toBitmap() }
                    if (bitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.size(32.dp)
                        )
                    } else {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(32.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(app.name, style = MaterialTheme.typography.bodyLarge)
                        Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
                Switch(
                    checked = app.isEnabled,
                    onCheckedChange = { viewModel.toggleAppSync(app.packageName, it) }
                )
            }
        }
    }
}

@Composable
fun PermissionItem(label: String, granted: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label)
        Icon(
            if (granted) Icons.Default.CheckCircle else Icons.Default.Cancel,
            contentDescription = if (granted) "Granted" else "Denied",
            tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
    }
}
