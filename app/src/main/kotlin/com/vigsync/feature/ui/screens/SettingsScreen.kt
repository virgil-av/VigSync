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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("MQTT", "Permissions")

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) }
                )
            }
        }

        when (selectedTab) {
            0 -> MqttSettingsTab(viewModel)
            1 -> PermissionsSettingsTab()
        }
    }
}

@Composable
fun MqttSettingsTab(viewModel: SettingsViewModel) {
    val savedUrl by viewModel.brokerUrl.collectAsState()
    val savedPort by viewModel.brokerPort.collectAsState()
    val savedUser by viewModel.brokerUsername.collectAsState()
    val savedPass by viewModel.brokerPassword.collectAsState()
    val savedTls by viewModel.useTls.collectAsState()

    var url by remember(savedUrl) { mutableStateOf(savedUrl) }
    var port by remember(savedPort) { mutableStateOf(savedPort) }
    var user by remember(savedUser) { mutableStateOf(savedUser ?: "") }
    var pass by remember(savedPass) { mutableStateOf(savedPass ?: "") }
    var tls by remember(savedTls) { mutableStateOf(savedTls) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Broker Configuration", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Broker URL") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = port,
            onValueChange = { port = it },
            label = { Text("Port") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("Username (Optional)") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text("Password (Optional)") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = tls, onCheckedChange = { tls = it })
            Text("Use SSL/TLS Encryption")
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { viewModel.saveMqttConfig(url, port, user, pass, tls) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save MQTT Changes")
        }
    }
}

@Composable
fun PermissionsSettingsTab() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    var hasSms by remember { mutableStateOf(false) }
    var hasCall by remember { mutableStateOf(false) }
    var hasPhone by remember { mutableStateOf(false) }
    var hasCamera by remember { mutableStateOf(false) }
    var hasNotif by remember { mutableStateOf(false) }
    var hasListener by remember { mutableStateOf(false) }
    var isBatteryUnrestricted by remember { mutableStateOf(false) }

    fun updateStates() {
        hasSms = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        hasCall = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALL_LOG) == android.content.pm.PackageManager.PERMISSION_GRANTED
        hasPhone = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        hasCamera = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        hasNotif = if (android.os.Build.VERSION.SDK_INT >= 33) {
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
        
        val enabledListeners = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        hasListener = enabledListeners.contains(context.packageName)
        
        val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        isBatteryUnrestricted = powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    // Lifecycle observer to refresh when returning to the app
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
        PermissionItem("Camera (QR Scanning)", hasCamera) {
            permissionLauncher.launch(android.Manifest.permission.CAMERA)
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
                    "The app needs 'Unrestricted' battery access to maintain the MQTT connection and capture events while the screen is off.",
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
                        modifier = Modifier.padding(top = 8.dp)
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

        Spacer(modifier = Modifier.height(16.dp))
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (hasListener) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (hasListener) Icons.Default.CheckCircle else Icons.Default.Error,
                        contentDescription = null,
                        tint = if (hasListener) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Notification Listener", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    "Required to capture WhatsApp/Telegram/Email messages.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (!hasListener) {
                    Button(
                        onClick = {
                            val intent = android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        },
                        modifier = Modifier.padding(top = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Grant Access")
                    }
                }
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
