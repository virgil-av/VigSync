package com.vigsync.feature.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
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
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vigsync.data.local.EventEntity
import com.vigsync.core.models.SyncStatus
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsScreen(
    viewModel: EventsViewModel = viewModel()
) {
    val events by viewModel.events.collectAsState()
    val devices by viewModel.devices.collectAsState()
    val selectedDevice by viewModel.selectedDevice.collectAsState()
    val selectedType by viewModel.selectedType.collectAsState()
    
    var deviceMenuExpanded by remember { mutableStateOf(false) }
    var clearMenuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Event Timeline") },
                actions = {
                    // Per-Device Filter (Dropdown)
                    Box {
                        TextButton(onClick = { deviceMenuExpanded = true }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = selectedDevice ?: "All Devices",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 120.dp)
                                )
                                Icon(Icons.Default.Devices, contentDescription = null, modifier = Modifier.size(18.dp).padding(start = 4.dp))
                            }
                        }
                        DropdownMenu(
                            expanded = deviceMenuExpanded,
                            onDismissRequest = { deviceMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("All Devices") },
                                onClick = {
                                    viewModel.setSelectedDevice(null)
                                    deviceMenuExpanded = false
                                },
                                leadingIcon = { Icon(Icons.Default.AllInclusive, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            )
                            devices.forEach { deviceName ->
                                DropdownMenuItem(
                                    text = { Text(deviceName) },
                                    onClick = {
                                        viewModel.setSelectedDevice(deviceName)
                                        deviceMenuExpanded = false
                                    },
                                    leadingIcon = { Icon(Icons.Default.Smartphone, contentDescription = null, modifier = Modifier.size(18.dp)) }
                                )
                            }
                        }
                    }

                    // Clear Options
                    Box {
                        IconButton(onClick = { clearMenuExpanded = true }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Clear Options")
                        }
                        DropdownMenu(
                            expanded = clearMenuExpanded,
                            onDismissRequest = { clearMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Clear All Events") },
                                onClick = {
                                    viewModel.clearEvents()
                                    clearMenuExpanded = false
                                },
                                leadingIcon = { Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            // Per-Type Filter (Pill Buttons)
            EventTypeFilters(
                selectedType = selectedType,
                onTypeSelected = { viewModel.setSelectedType(it) }
            )

            if (events.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("No events captured yet", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.outline)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(events) { event ->
                        EventCard(event)
                    }
                }
            }
        }
    }
}

@Composable
fun EventTypeFilters(
    selectedType: String?,
    onTypeSelected: (String?) -> Unit
) {
    val types = listOf(
        null to "All",
        "CALL" to "Calls",
        "SMS" to "SMS",
        "NOTIFICATION" to "App Alerts"
    )

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(types) { (type, label) ->
            FilterChip(
                selected = selectedType == type,
                onClick = { onTypeSelected(type) },
                label = { Text(label) },
                leadingIcon = if (selectedType == type) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else null
            )
        }
    }
}

@Composable
fun EventCard(event: EventEntity) {
    val context = LocalContext.current
    val pm = context.packageManager
    val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val time = timeFormatter.format(Date(event.timestamp))

    var displayData = event.data
    var appLabel: String? = null
    var packageName: String? = null

    // Unified Pipe-Delimited Parsing
    if (event.data.contains("|")) {
        val parts = event.data.split("|", limit = 3)
        if (parts.size == 3) {
            packageName = parts[1]
            displayData = parts[2]
            
            // Extract app name from package instead of using the raw label
            appLabel = remember(packageName) {
                try {
                    val info = pm.getApplicationInfo(packageName, 0)
                    pm.getApplicationLabel(info).toString()
                } catch (_: Exception) {
                    parts[0] // fallback to the raw label if lookup fails
                }
            }
        }
    }

    // Generic Title Logic
    val displayTitle = when {
        event.type.contains("MISSED") -> "Missed Call"
        event.type.contains("CALL") -> {
            if (displayData.contains("Incoming", ignoreCase = true)) "Incoming Call"
            else if (displayData.contains("Outgoing", ignoreCase = true)) "Outgoing Call"
            else "Call"
        }
        event.type == "SMS" -> "SMS Message"
        event.type == "NOTIFICATION" -> appLabel ?: "App Alert"
        else -> event.type
    }
    
    val isMissedCall = event.type.contains("MISSED")
    
    val typeColor = when {
        event.type == "SYNC ERROR" -> MaterialTheme.colorScheme.error
        event.type == "SMS" -> Color(0xFF4CAF50)
        isMissedCall -> Color(0xFFF44336) // Red for Missed
        event.type.contains("CALL") -> Color(0xFF2196F3) // Blue for Active
        else -> Color(0xFF9C27B0) // Purple for Notifications
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // Device badge in top-right
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                shape = MaterialTheme.shapes.extraSmall,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            ) {
                Text(
                    text = event.sourceDevice ?: "Unknown",
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontSize = 9.sp
                )
            }

            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // App Icon or Type Icon
                    val appIcon: androidx.compose.ui.graphics.ImageBitmap? = remember(packageName) {
                        packageName?.let {
                            try {
                                pm.getApplicationIcon(it).toBitmap().asImageBitmap()
                            } catch (_: Exception) { null }
                        }
                    }

                    if (appIcon != null) {
                        androidx.compose.foundation.Image(
                            bitmap = appIcon,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp)
                        )
                    } else {
                        Icon(
                            imageVector = when {
                                event.type == "SMS" -> Icons.Default.Sms
                                isMissedCall -> Icons.AutoMirrored.Filled.PhoneMissed
                                event.type.contains("CALL") -> Icons.Default.Call
                                event.type == "SYNC ERROR" -> Icons.Default.SyncProblem
                                else -> Icons.Default.Notifications
                            },
                            contentDescription = null,
                            tint = typeColor,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = displayTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (appLabel != null || event.type == "SMS") MaterialTheme.colorScheme.onSurface else typeColor
                        )
                        // Show app label as subtitle if it's different from title
                        if (appLabel != null && displayTitle != appLabel) {
                            Text(
                                text = appLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                fontSize = 10.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = displayData,
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (event.errorMessage != null) {
                    Text(
                        text = event.errorMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = time,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val statusText = when (event.syncStatus) {
                            SyncStatus.SENT -> "Captured"
                            SyncStatus.RECEIVED -> "Synced"
                            SyncStatus.FAILED -> "Failed"
                            SyncStatus.PENDING -> "Pending"
                        }
                        
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                        
                        Icon(
                            imageVector = when (event.syncStatus) {
                                SyncStatus.SENT -> Icons.Default.CheckCircle
                                SyncStatus.RECEIVED -> Icons.Default.CloudDone
                                SyncStatus.FAILED -> Icons.Default.Error
                                SyncStatus.PENDING -> Icons.Default.Schedule
                            },
                            contentDescription = null,
                            tint = when (event.syncStatus) {
                                SyncStatus.SENT -> Color(0xFF4CAF50)
                                SyncStatus.RECEIVED -> Color(0xFF2196F3)
                                SyncStatus.FAILED -> MaterialTheme.colorScheme.error
                                SyncStatus.PENDING -> MaterialTheme.colorScheme.outline
                            },
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}
