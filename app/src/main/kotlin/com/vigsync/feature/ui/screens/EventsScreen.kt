package com.vigsync.feature.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.vigsync.core.models.EventDirection
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsScreen(
    viewModel: EventsViewModel = viewModel()
) {
    val events by viewModel.events.collectAsState()
    val selectedType by viewModel.selectedType.collectAsState()
    
    var expanded by remember { mutableStateOf(false) }
    var clearMenuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Event Timeline") },
                actions = {
                    Box(modifier = Modifier.padding(end = 8.dp)) {
                        TextButton(onClick = { expanded = true }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val filterText = when (selectedType) {
                                    "CALL", "VOIP MISSED CALL", "SYSTEM MISSED CALL", "VOIP ACTIVE CALL" -> "Calls"
                                    "SMS" -> "SMS"
                                    "NOTIFICATION" -> "App Alerts"
                                    else -> "All Events"
                                }
                                Text(
                                    text = filterText,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 120.dp)
                                )
                                Icon(Icons.Default.FilterList, contentDescription = null, modifier = Modifier.size(18.dp).padding(start = 4.dp))
                            }
                        }
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("All Events") },
                                onClick = {
                                    viewModel.setSelectedType(null)
                                    expanded = false
                                },
                                leadingIcon = { Icon(Icons.Default.AllInclusive, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            )
                            DropdownMenuItem(
                                text = { Text("Calls") },
                                onClick = {
                                    viewModel.setSelectedType("CALL") 
                                    expanded = false
                                },
                                leadingIcon = { Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            )
                            DropdownMenuItem(
                                text = { Text("SMS") },
                                onClick = {
                                    viewModel.setSelectedType("SMS")
                                    expanded = false
                                },
                                leadingIcon = { Icon(Icons.Default.Sms, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            )
                            DropdownMenuItem(
                                text = { Text("App Alerts") },
                                onClick = {
                                    viewModel.setSelectedType("NOTIFICATION")
                                    expanded = false
                                },
                                leadingIcon = { Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            )
                        }
                    }

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

    // Unified Pipe-Delimited Parsing
    if (event.data.contains("|")) {
        val parts = event.data.split("|", limit = 3)
        if (parts.size == 3) {
            appLabel = parts[0]
            packageName = parts[1]
            displayData = parts[2]
        }
    }

    // Special handling for VOIP tags if parsing failed or for specific labeling
    if (appLabel == null) {
        if (event.type.contains("VOIP")) {
            appLabel = "VoIP App"
        } else if (event.type == "SYSTEM MISSED CALL") {
            appLabel = "System Phone"
        }
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
                        text = appLabel ?: event.type,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (appLabel != null) MaterialTheme.colorScheme.onSurface else typeColor
                    )
                    if (packageName != null && packageName != "com.android.server.telecom") {
                        Text(
                            text = packageName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            fontSize = 10.sp
                        )
                    }
                }
                
                // Secondary Type Label
                if (event.type != "NOTIFICATION" && event.type != "SMS") {
                    Surface(
                        color = typeColor.copy(alpha = 0.1f),
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            text = event.type,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = typeColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
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
