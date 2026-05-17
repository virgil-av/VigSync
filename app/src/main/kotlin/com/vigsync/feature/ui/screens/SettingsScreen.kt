package com.vigsync.feature.ui.screens

import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vigsync.core.models.MqttConnectionState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.ui.graphics.Color
import com.vigsync.data.local.MqttLogEntity
import com.vigsync.data.local.SystemLogEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

enum class SettingsSection { MQTT_CONFIG, PUSH_NOTIFICATIONS, MQTT_DEBUG }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel(),
    openMqttConfig: Boolean = false
) {
    var activeDialog by remember { mutableStateOf<SettingsSection?>(null) }

    LaunchedEffect(openMqttConfig) {
        if (openMqttConfig) {
            activeDialog = SettingsSection.MQTT_CONFIG
        }
    }

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
                title = "MQTT Settings",
                subtitle = "Broker and Connection configuration",
                icon = Icons.Default.Dns,
                onClick = { activeDialog = SettingsSection.MQTT_CONFIG }
            )

            SettingsMenuItem(
                title = "Push Notifications",
                subtitle = "Local alerts for incoming events",
                icon = Icons.Default.NotificationsActive,
                onClick = { activeDialog = SettingsSection.PUSH_NOTIFICATIONS }
            )

            SettingsMenuItem(
                title = "MQTT Debugging",
                subtitle = "View message logs and system events",
                icon = Icons.AutoMirrored.Filled.Notes,
                onClick = { activeDialog = SettingsSection.MQTT_DEBUG }
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
                Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
                                SettingsSection.MQTT_CONFIG -> "MQTT Settings"
                                SettingsSection.PUSH_NOTIFICATIONS -> "Push Notifications"
                                SettingsSection.MQTT_DEBUG -> "MQTT Debugging"
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
                        SettingsSection.MQTT_CONFIG -> MqttConfigTab(viewModel)
                        SettingsSection.PUSH_NOTIFICATIONS -> PushNotificationsTab(viewModel)
                        SettingsSection.MQTT_DEBUG -> MqttDebugTab()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MqttConfigTab(viewModel: SettingsViewModel) {
    val savedUrl by viewModel.brokerUrl.collectAsState()
    val savedPort by viewModel.brokerPort.collectAsState()
    val savedUser by viewModel.brokerUser.collectAsState()
    val savedPass by viewModel.brokerPass.collectAsState()
    val savedTls by viewModel.useTls.collectAsState()
    val savedVersion by viewModel.mqttVersion.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()

    var url by remember(savedUrl) { mutableStateOf(savedUrl) }
    var port by remember(savedPort) { mutableStateOf(savedPort) }
    var user by remember(savedUser) { mutableStateOf(savedUser) }
    var pass by remember(savedPass) { mutableStateOf(savedPass) }
    var tls by remember(savedTls) { mutableStateOf(savedTls) }
    var version by remember(savedVersion) { mutableStateOf(savedVersion) }

    var securityExpanded by remember { mutableStateOf(false) }
    var versionExpanded by remember { mutableStateOf(false) }

    // Sync local state with saved values (Crucial for JSON import detection)
    LaunchedEffect(savedTls, savedPort, savedVersion) {
        tls = savedTls
        port = savedPort
        version = savedVersion
    }

    val noAutoCorrect = KeyboardOptions(
        autoCorrect = false,
        imeAction = ImeAction.Next
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        JsonImportCard(onImport = { viewModel.importFromJson(it) })

        Text("Broker Configuration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Broker URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = noAutoCorrect
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = port,
                onValueChange = { port = it },
                label = { Text("Port") },
                modifier = Modifier.weight(0.25f),
                singleLine = true,
                keyboardOptions = noAutoCorrect.copy(keyboardType = KeyboardType.Number)
            )

            ExposedDropdownMenuBox(
                expanded = securityExpanded,
                onExpandedChange = { securityExpanded = !securityExpanded },
                modifier = Modifier.weight(0.4f)
            ) {
                OutlinedTextField(
                    value = if (tls) "SSL/TLS" else "None",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Security") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = securityExpanded) },
                    modifier = Modifier.menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = securityExpanded,
                    onDismissRequest = { securityExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("None") },
                        onClick = {
                            tls = false
                            port = "1883"
                            securityExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("SSL/TLS") },
                        onClick = {
                            tls = true
                            port = "8883"
                            securityExpanded = false
                        }
                    )
                }
            }

            ExposedDropdownMenuBox(
                expanded = versionExpanded,
                onExpandedChange = { versionExpanded = !versionExpanded },
                modifier = Modifier.weight(0.35f)
            ) {
                OutlinedTextField(
                    value = if (version == "5") "v5" else "v3",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Version") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = versionExpanded) },
                    modifier = Modifier.menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = versionExpanded,
                    onDismissRequest = { versionExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("v5") },
                        onClick = {
                            version = "5"
                            versionExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("v3") },
                        onClick = {
                            version = "3"
                            versionExpanded = false
                        }
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("Username") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = noAutoCorrect
            )
            OutlinedTextField(
                value = pass,
                onValueChange = { pass = it },
                label = { Text("Password") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = noAutoCorrect.copy(imeAction = ImeAction.Done)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Status Indicator
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            when (connectionState) {
                MqttConnectionState.CONNECTING -> {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Connecting...", style = MaterialTheme.typography.bodyMedium)
                }
                MqttConnectionState.CONNECTED -> {
                    Surface(
                        color = Color(0xFF4CAF50),
                        shape = androidx.compose.foundation.shape.CircleShape,
                        modifier = Modifier.size(12.dp)
                    ) {}
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Connected", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                }
                MqttConnectionState.DISCONNECTED, MqttConnectionState.IDLE -> {
                    Surface(
                        color = MaterialTheme.colorScheme.outline,
                        shape = androidx.compose.foundation.shape.CircleShape,
                        modifier = Modifier.size(12.dp)
                    ) {}
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Not Connected", color = MaterialTheme.colorScheme.outline)
                }
                MqttConnectionState.ERROR -> {
                    Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Connection Error", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        if (connectionState == MqttConnectionState.ERROR) {
            Text(
                text = "Troubleshooting: Ensure the server is reachable or try switching to MQTT v3.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        val isSslEnabled = tls || port == "8883"
        val isAuthMissing = isSslEnabled && (user.isBlank() || pass.isBlank())

        if (isSslEnabled) {
            Text(
                text = "SSL/TLS requires a Username and Password.",
                style = MaterialTheme.typography.labelSmall,
                color = if (isAuthMissing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        Button(
            onClick = { 
                viewModel.updateAndConnect(url, port, user, pass, tls, version)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = connectionState != MqttConnectionState.CONNECTING && 
                      connectionState != MqttConnectionState.CONNECTED &&
                      !isAuthMissing
        ) {
            Icon(Icons.Default.CloudSync, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Save & Connect")
        }

        if (connectionState == MqttConnectionState.CONNECTED || connectionState == MqttConnectionState.CONNECTING) {
            OutlinedButton(
                onClick = { viewModel.disconnect() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.CloudOff, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Disconnect")
            }
        }
    }
}

@Composable
fun JsonImportCard(onImport: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var jsonText by remember { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Input, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Import from JSON", fontWeight = FontWeight.Bold)
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = jsonText,
                    onValueChange = { jsonText = it },
                    label = { Text("Paste configuration JSON here") },
                    modifier = Modifier.fillMaxWidth().height(150.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(autoCorrect = false)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        onImport(jsonText)
                        jsonText = ""
                        expanded = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = jsonText.isNotBlank()
                ) {
                    Text("Load Configuration")
                }
            }
        }
    }
}

@Composable
fun PushNotificationsTab(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val calls by viewModel.notifCalls.collectAsState()
    val sms by viewModel.notifSms.collectAsState()
    val other by viewModel.notifOther.collectAsState()

    var hasNotifPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasNotifPermission = isGranted
        if (!isGranted) {
            Toast.makeText(context, "Notification permission is required for alerts", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Notification Preferences", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Receive local alerts on this device when events are synced from your paired devices.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotifPermission) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Permission Required", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                        Text("Please grant notification permission to receive alerts.", style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                        Text("Grant")
                    }
                }
            }
        }

        HorizontalDivider()

        NotificationToggleItem(
            label = "Call Notifications",
            checked = calls,
            onCheckedChange = { viewModel.updateNotifSettings(it, sms, other) }
        )

        NotificationToggleItem(
            label = "SMS Notifications",
            checked = sms,
            onCheckedChange = { viewModel.updateNotifSettings(calls, it, other) }
        )

        NotificationToggleItem(
            label = "App Alert Notifications",
            checked = other,
            onCheckedChange = { viewModel.updateNotifSettings(calls, sms, it) }
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { 
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotifPermission) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    viewModel.sendTestNotification()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Notifications, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Send Test Notification")
        }
    }
}

@Composable
fun NotificationToggleItem(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun MqttDebugTab(viewModel: DebugViewModel = viewModel()) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Logs", "System")

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

        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                0 -> MqttLogsList(viewModel)
                1 -> SystemLogsList(viewModel)
            }
        }
    }
}

@Composable
fun MqttLogsList(viewModel: DebugViewModel) {
    val logs by viewModel.mqttLogs.collectAsState()
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }

    Box(modifier = Modifier.fillMaxSize()) {
        if (logs.isEmpty()) {
            EmptyDebugView("No MQTT logs yet")
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { viewModel.clearMqttLogs() }) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear")
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(logs) { log ->
                        MqttLogItem(log, timeFormatter)
                    }
                }
            }
        }
    }
}

@Composable
fun SystemLogsList(viewModel: DebugViewModel) {
    val logs by viewModel.systemLogs.collectAsState()
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize()) {
        if (logs.isEmpty()) {
            EmptyDebugView("No system logs yet")
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        val logText = logs.joinToString("\n") { log ->
                            "${timeFormatter.format(Date(log.timestamp))} | ${log.event} | ${log.details ?: ""}"
                        }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("VigSync System Logs", logText)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy Log")
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    TextButton(onClick = { viewModel.clearSystemLogs() }) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear")
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(logs) { log ->
                        SystemLogItem(log, timeFormatter)
                    }
                }
            }
        }
    }
}

@Composable
fun MqttLogItem(log: MqttLogEntity, formatter: SimpleDateFormat) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (log.isIncoming) Icons.Default.CallReceived else Icons.Default.CallMade,
                        contentDescription = null,
                        tint = if (log.isIncoming) Color(0xFF4CAF50) else Color(0xFF2196F3),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (log.isIncoming) "Received" else "Sent",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (log.isIncoming) Color(0xFF4CAF50) else Color(0xFF2196F3)
                    )
                }
                Text(
                    text = formatter.format(Date(log.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            
            // Topic shown in Body
            Text(
                text = log.topic,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
            
            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = log.payload,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )
        }
    }
}

@Composable
fun SystemLogItem(log: SystemLogEntity, formatter: SimpleDateFormat) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (log.isError) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f) 
                             else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = log.event,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (log.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = formatter.format(Date(log.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            if (!log.details.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = log.details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun EmptyDebugView(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = message, color = MaterialTheme.colorScheme.outline)
    }
}
