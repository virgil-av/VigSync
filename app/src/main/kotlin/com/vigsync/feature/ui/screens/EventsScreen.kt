package com.vigsync.feature.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vigsync.data.local.EventEntity
import com.vigsync.core.models.SyncStatus
import com.vigsync.core.models.EventDirection
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsScreen(
    viewModel: EventsViewModel = viewModel(),
    deviceName: String? = null
) {
    val events by viewModel.events.collectAsState()
    val devices by viewModel.devices.collectAsState()
    val selectedDevice by viewModel.selectedDevice.collectAsState()
    
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(deviceName) {
        if (deviceName != null) {
            viewModel.setSelectedDevice(deviceName)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Event Timeline") },
                actions = {
                    Box(modifier = Modifier.padding(end = 8.dp)) {
                        TextButton(onClick = { expanded = true }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = selectedDevice ?: "All Devices",
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 120.dp)
                                )
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                            }
                        }
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("All Devices") },
                                onClick = {
                                    viewModel.setSelectedDevice(null)
                                    expanded = false
                                },
                                leadingIcon = { Icon(Icons.Default.Devices, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            )
                            devices.forEach { device ->
                                DropdownMenuItem(
                                    text = { Text(device) },
                                    onClick = {
                                        viewModel.setSelectedDevice(device)
                                        expanded = false
                                    },
                                    leadingIcon = { Icon(Icons.Default.PhoneAndroid, contentDescription = null, modifier = Modifier.size(18.dp)) }
                                )
                            }
                        }
                    }

                    IconButton(onClick = { viewModel.clearEvents() }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear All")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (events.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("No events captured yet", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.outline)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
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

@Composable
fun EventCard(event: EventEntity) {
    val context = LocalContext.current
    val pm = context.packageManager
    val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val time = timeFormatter.format(Date(event.timestamp))

    var displayData = event.data
    var appLabel: String? = null
    var packageName: String? = null

    if (event.type == "NOTIFICATION" && event.data.contains("|")) {
        val parts = event.data.split("|", limit = 3)
        if (parts.size == 3) {
            appLabel = parts[0]
            packageName = parts[1]
            displayData = parts[2]
        }
    } else if (event.type == "CALL" && event.data.startsWith("WhatsApp Call")) {
        appLabel = "WhatsApp"
        packageName = "com.whatsapp"
    }
    
    val typeColor = when (event.type) {
        "CALL" -> Color(0xFF2196F3) // Blue
        "SMS" -> Color(0xFF4CAF50)  // Green
        "SYNC ERROR" -> MaterialTheme.colorScheme.error
        else -> Color(0xFF9C27B0)   // Purple for Notifications
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
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
                        imageVector = when (event.type) {
                            "SMS" -> Icons.Default.Sms
                            "CALL" -> Icons.Default.Call
                            "SYNC ERROR" -> Icons.Default.SyncProblem
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
                        text = appLabel ?: event.type,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (appLabel != null) MaterialTheme.colorScheme.onSurface else typeColor
                    )
                    if (packageName != null) {
                        Text(
                            text = packageName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            fontSize = 10.sp
                        )
                    }
                }
                
                if (event.sourceDevice != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            text = event.sourceDevice,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
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
                        SyncStatus.SENT -> "Sent"
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
