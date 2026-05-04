package com.vigsync.feature.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vigsync.feature.ui.components.QrScanner
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(
    viewModel: PairingViewModel = viewModel(),
    onPairingSuccess: () -> Unit = {}
) {
    val qrBitmap by viewModel.qrCode.collectAsState()
    var isScanning by remember { mutableStateOf(false) }
    var pairedDeviceName by remember { mutableStateOf<String?>(null) }
    var showSuccessDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.pairingComplete.collectLatest { deviceName ->
            pairedDeviceName = deviceName
            showSuccessDialog = true
        }
    }

    if (showSuccessDialog) {
        AlertDialog(
            onDismissRequest = { /* Prevent dismiss */ },
            title = { Text("Pairing Successful") },
            text = { Text("Device '$pairedDeviceName' has been added to your dashboard.") },
            confirmButton = {
                Button(onClick = {
                    showSuccessDialog = false
                    onPairingSuccess()
                }) {
                    Text("Go to Dashboard")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Pair Device") })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                modifier = Modifier.size(300.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.medium
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isScanning) {
                        QrScanner { result ->
                            viewModel.onScanResult(result)
                            isScanning = false
                        }
                    } else if (qrBitmap != null) {
                        Image(bitmap = qrBitmap!!.asImageBitmap(), contentDescription = "My QR Code", modifier = Modifier.fillMaxSize())
                    } else {
                        Text("Ready to pair")
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = { 
                    isScanning = false
                    viewModel.generateMyQr() 
                }) {
                    Text("Show My QR")
                }
                Button(onClick = { isScanning = true }) {
                    Text("Scan QR")
                }
            }
        }
    }
}
