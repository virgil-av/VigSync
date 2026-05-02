package com.vigsync.feature.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vigsync.data.local.EventEntity
import com.vigsync.core.models.SyncStatus
import com.vigsync.core.models.EventDirection
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsScreen(viewModel: EventsViewModel = viewModel()) {
    val events by viewModel.events.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Event Timeline") },
                actions = {
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
    val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val time = timeFormatter.format(Date(event.timestamp))
    
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
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when (event.type) {
                            "SMS" -> Icons.Default.Sms
                            "CALL" -> Icons.Default.Call
                            "SYNC ERROR" -> Icons.Default.SyncProblem
                            else -> Icons.Default.Notifications
                        },
                        contentDescription = null,
                        tint = typeColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = event.type,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = typeColor
                    )
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
                text = event.data,
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
