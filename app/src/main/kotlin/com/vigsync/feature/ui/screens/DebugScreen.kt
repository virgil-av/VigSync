package com.vigsync.feature.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vigsync.core.mqtt.LogEntry
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(viewModel: DebugViewModel = viewModel()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                actions = {
                    IconButton(onClick = { viewModel.clearLogs() }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear Logs")
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            DebugScreenContent(viewModel)
        }
    }
}

@Composable
fun DebugScreenContent(viewModel: DebugViewModel) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("MQTT", "App Logs", "Storage")

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
            0 -> LogList(viewModel.mqttLogs)
            1 -> LogList(viewModel.appLogs)
            2 -> StorageList(viewModel)
        }
    }
}

@Composable
fun StorageList(viewModel: DebugViewModel) {
    val rawMessages by viewModel.rawMessages.collectAsState()
    val deviceStatus by viewModel.deviceStatus.collectAsState()
    val clipboardManager = LocalClipboardManager.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("Cold Storage (Latest Device Status)", style = MaterialTheme.typography.titleMedium)
        }
        
        items(deviceStatus) { status ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${status.name} (${status.deviceId})", style = MaterialTheme.typography.labelMedium)
                        Text("Battery: ${status.batteryLevel}% | Online: ${status.isOnline}", style = MaterialTheme.typography.bodySmall)
                        Text("Last Updated: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(status.lastSeen))}", style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(onClick = { 
                        clipboardManager.setText(AnnotatedString("Device: ${status.name}\nID: ${status.deviceId}\nBattery: ${status.batteryLevel}%\nOnline: ${status.isOnline}\nLast Seen: ${status.lastSeen}"))
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy Status", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text("Hot Storage (Recent Raw Packets)", style = MaterialTheme.typography.titleMedium)
        }

        items(rawMessages) { msg ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (msg.isProcessed) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (msg.isProcessed) "PROCESSED" else "NEW", style = MaterialTheme.typography.labelSmall)
                            Text(SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(msg.timestamp)), style = MaterialTheme.typography.labelSmall)
                        }
                        Text(msg.topic, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(msg.payload.take(50) + if (msg.payload.length > 50) "..." else "", style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { 
                        clipboardManager.setText(AnnotatedString("Topic: ${msg.topic}\nTimestamp: ${msg.timestamp}\nPayload: ${msg.payload}"))
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy Payload", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun LogList(logsFlow: kotlinx.coroutines.flow.StateFlow<List<LogEntry>>) {
    val logs by logsFlow.collectAsState()
    
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(logs) { log ->
            DebugLogItem(log.message, log.status, log.timestamp)
        }
    }
}

@Composable
fun DebugLogItem(message: String, status: String, timestamp: Long) {
    val timeFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    val time = timeFormatter.format(Date(timestamp))
    val clipboardManager = LocalClipboardManager.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (status) {
                "ERROR" -> MaterialTheme.colorScheme.errorContainer
                "SUCCESS" -> Color(0xFFE8F5E9)
                "TRACE" -> Color(0xFFFFF3E0)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(status, style = MaterialTheme.typography.labelSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text(time, style = MaterialTheme.typography.labelSmall)
                }
                Text(message, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = { 
                clipboardManager.setText(AnnotatedString("Status: $status\nTime: $time\nMessage: $message"))
            }) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy Log", modifier = Modifier.size(18.dp))
            }
        }
    }
}
