package com.vigsync.feature.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.vigsync.core.models.MqttConnectionState
import com.vigsync.data.local.PairedDeviceEntity
import com.vigsync.feature.ui.Screen
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = viewModel(),
    navController: androidx.navigation.NavController? = null
) {
    val context = LocalContext.current
    val connectionState by viewModel.connectionState.collectAsState()
    val devices by viewModel.pairedDevices.collectAsState()
    val syncingDevices by viewModel.syncingDevices.collectAsState()
    
    var showScanner by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.pairingError.collect { error ->
            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            showScanner = true
        } else {
            Toast.makeText(context, "Camera permission is required to scan QR codes", Toast.LENGTH_SHORT).show()
        }
    }

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
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Add Device Card
            item {
                AddDeviceCard(onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        showScanner = true
                    } else {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                })
            }

            // Paired Devices
            items(devices) { device ->
                DeviceCard(
                    device = device,
                    isSyncing = syncingDevices.contains(device.deviceId),
                    onDelete = { viewModel.removeDevice(device) }
                )
            }
        }
    }

    if (showScanner) {
        QRScannerDialog(
            onDismiss = { showScanner = false },
            onResult = { result ->
                viewModel.pairDevice(result) {
                    navController?.navigate(Screen.Settings.route + "?openMqttConfig=true") {
                        popUpTo(Screen.Dashboard.route) { inclusive = true }
                        launchSingleTop = true
                    }
                }
                showScanner = false
            }
        )
    }
}

@Composable
fun AddDeviceCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Add Device",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun DeviceCard(
    device: PairedDeviceEntity,
    isSyncing: Boolean,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSyncing) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                             else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            IconButton(
                onClick = { showDeleteConfirm = true },
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Remove", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (isSyncing) {
                    SyncingAnimation()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Syncing...",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary,
                        fontSize = 14.sp
                    )
                    Text(
                        "Wait 2 minutes",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Icon(
                        Icons.Default.Smartphone,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = if (device.isOnline) Color(0xFF4CAF50) else Color.Gray
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        device.deviceName,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        fontSize = 14.sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            modifier = Modifier.size(8.dp),
                            shape = CircleShape,
                            color = if (device.isOnline) Color(0xFF4CAF50) else Color.Gray
                        ) {}
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (device.isOnline) "Online" else "Offline",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Remove Device?") },
            text = { Text("Are you sure you want to remove ${device.deviceName}?") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteConfirm = false
                }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun SyncingAnimation() {
    val infiniteTransition = rememberInfiniteTransition()
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    Icon(
        Icons.Default.Sync,
        contentDescription = null,
        modifier = Modifier
            .size(32.dp)
            .rotate(rotation),
        tint = MaterialTheme.colorScheme.secondary
    )
}

@Composable
fun ConnectionBadge(state: MqttConnectionState) {
    val color = when (state) {
        MqttConnectionState.CONNECTED -> Color(0xFF4CAF50)
        MqttConnectionState.CONNECTING -> Color(0xFFFF9800)
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

@Composable
fun QRScannerDialog(onDismiss: () -> Unit, onResult: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    var isScanning by remember { mutableStateOf(true) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { previewView ->
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()
                            
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }

                            val scanner = BarcodeScanning.getClient(
                                BarcodeScannerOptions.Builder()
                                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                                    .build()
                            )

                            val imageAnalyzer = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                                .also {
                                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                                        if (!isScanning) {
                                            imageProxy.close()
                                            return@setAnalyzer
                                        }

                                        val mediaImage = imageProxy.image
                                        if (mediaImage != null) {
                                            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                                            scanner.process(image)
                                                .addOnSuccessListener { barcodes ->
                                                    for (barcode in barcodes) {
                                                        barcode.rawValue?.let { result ->
                                                            if (isScanning) {
                                                                isScanning = false
                                                                cameraProvider.unbindAll()
                                                                onResult(result)
                                                            }
                                                        }
                                                    }
                                                }
                                                .addOnCompleteListener { imageProxy.close() }
                                        }
                                    }
                                }

                            try {
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    imageAnalyzer
                                )
                            } catch (exc: Exception) {
                                Log.e("QRScanner", "Use case binding failed", exc)
                            }
                        }, ContextCompat.getMainExecutor(context))
                    }
                )

                // Overlay
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f))
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(250.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .background(Color.Transparent)
                                .border(2.dp, Color.White, RoundedCornerShape(24.dp))
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            "Scan Device QR Code",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .padding(16.dp)
                        .align(Alignment.TopStart)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }
    }
}
