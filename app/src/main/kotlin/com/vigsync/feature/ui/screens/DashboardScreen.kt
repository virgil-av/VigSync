package com.vigsync.feature.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vigsync.core.models.MqttConnectionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = viewModel(),
    navController: androidx.navigation.NavController? = null
) {
    val connectionState by viewModel.connectionState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VigSync Client", fontWeight = FontWeight.Bold) },
                actions = {
                    ConnectionBadge(connectionState)
                    Spacer(modifier = Modifier.width(16.dp))
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Client Mode Active",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (connectionState == MqttConnectionState.CONNECTED) 
                        "Connected and ready to receive events." 
                        else "Configure MQTT settings to start receiving events.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ConnectionBadge(state: MqttConnectionState) {
    val color = when (state) {
        MqttConnectionState.CONNECTED -> androidx.compose.ui.graphics.Color(0xFF4CAF50)
        MqttConnectionState.CONNECTING -> androidx.compose.ui.graphics.Color(0xFFFF9800)
        else -> MaterialTheme.colorScheme.outline
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.Circle,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(10.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = state.name,
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}
