package com.example

import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

enum class TvCommand {
    UP, DOWN, LEFT, RIGHT, OK,
    BACK, HOME, POWER,
    VOLUME_UP, VOLUME_DOWN, MUTE,
    KEYBOARD, VOICE
}


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                TvRemoteApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvRemoteApp(viewModel: TvRemoteViewModel = viewModel()) {
    val connectionState by viewModel.connectionState.collectAsState()
    val ipAddress by viewModel.ipAddress.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState(initial = emptyList())
    val context = LocalContext.current
    var showIpDialog by remember { mutableStateOf(false) }
    var showPairingDialog by remember { mutableStateOf(false) }
    var showGestureScreen by remember { mutableStateOf(false) }

    LaunchedEffect(connectionState) {
        if (connectionState == "PAIRING") {
            showPairingDialog = true
        } else if (connectionState == "CONNECTED" || connectionState == "ERROR" || connectionState == "DISCONNECTED") {
            showPairingDialog = false
        }
    }

    if (showIpDialog) {
        var tempIp by remember { mutableStateOf(ipAddress) }
        AlertDialog(
            onDismissRequest = { showIpDialog = false },
            title = { Text("Connect to TV") },
            text = {
                Column {
                    OutlinedTextField(
                        value = tempIp,
                        onValueChange = { tempIp = it },
                        label = { Text("TV IP Address") },
                        singleLine = true
                    )
                    
                    if (discoveredDevices.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Discovered TVs:", style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        discoveredDevices.forEach { device ->
                            Surface(
                                onClick = {
                                    viewModel.updateIpAddress(device.ip)
                                    viewModel.connect(context)
                                    showIpDialog = false
                                },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(device.name, fontWeight = FontWeight.Bold)
                                    Text(device.ip, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateIpAddress(tempIp)
                    viewModel.connect(context)
                    showIpDialog = false
                }) { Text("Connect") }
            },
            dismissButton = {
                TextButton(onClick = { showIpDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showPairingDialog) {
        var pairingCode by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Pairing") },
            text = {
                OutlinedTextField(
                    value = pairingCode,
                    onValueChange = { pairingCode = it },
                    label = { Text("Enter 6-digit code shown on TV") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.sendPairingCode(pairingCode)
                }) { Text("Pair") }
            }
        )
    }

    val vibrator = remember {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    val onCommand: (TvCommand) -> Unit = { command ->
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(50)
        }
        viewModel.sendCommand(command)
    }

    if (showGestureScreen) {
        GestureScreen(viewModel = viewModel, onBack = { showGestureScreen = false })
    } else {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TV Remote", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                actions = {
                    if (connectionState == "CONNECTED") {
                        IconButton(onClick = { viewModel.disconnect(context) }) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Disconnect"
                            )
                        }
                    }
                    IconButton(onClick = { showGestureScreen = true }) {
                                        Icon(
                                            imageVector = Icons.Rounded.PanTool,
                                            contentDescription = "Gesture Control"
                                        )
                                    }
                    IconButton(onClick = { showIpDialog = true }) {
                        Icon(
                            imageVector = Icons.Rounded.Cast,
                            contentDescription = "Connect to TV",
                            tint = if (connectionState == "CONNECTED") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            // Connection Status
            Text(
                text = if (connectionState == "CONNECTED") "Connected to $ipAddress" else "Status: $connectionState",
                style = MaterialTheme.typography.labelLarge,
                color = if (connectionState == "CONNECTED") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // Top Controls (Power & Keyboard)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                RemoteButton(
                    icon = Icons.Rounded.PowerSettingsNew,
                    contentDescription = "Power",
                    onClick = { onCommand(TvCommand.POWER) },
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
                RemoteButton(
                    icon = Icons.Rounded.Keyboard,
                    contentDescription = "Keyboard",
                    onClick = { onCommand(TvCommand.KEYBOARD) }
                )
            }

            // D-Pad
            DPad(onCommand = onCommand)

            // Center Controls (Back, Home, Voice)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                RemoteButton(
                    icon = Icons.Rounded.ArrowBack,
                    contentDescription = "Back",
                    onClick = { onCommand(TvCommand.BACK) }
                )
                RemoteButton(
                    icon = Icons.Rounded.Home,
                    contentDescription = "Home",
                    onClick = { onCommand(TvCommand.HOME) },
                    size = 72.dp
                )
                RemoteButton(
                    icon = Icons.Rounded.Mic,
                    contentDescription = "Voice",
                    onClick = { onCommand(TvCommand.VOICE) }
                )
            }

            // Bottom Controls (Volume)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                VolumeControl(onCommand = onCommand)
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
    }

@Composable
fun DPad(onCommand: (TvCommand) -> Unit) {
    Box(
        modifier = Modifier
            .size(260.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center
    ) {
        // Up
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(0.4f)
                .fillMaxHeight(0.3f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onCommand(TvCommand.UP) },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Up", modifier = Modifier.size(40.dp))
        }
        // Down
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(0.4f)
                .fillMaxHeight(0.3f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onCommand(TvCommand.DOWN) },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Down", modifier = Modifier.size(40.dp))
        }
        // Left
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight(0.4f)
                .fillMaxWidth(0.3f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onCommand(TvCommand.LEFT) },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.KeyboardArrowLeft, contentDescription = "Left", modifier = Modifier.size(40.dp))
        }
        // Right
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(0.4f)
                .fillMaxWidth(0.3f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onCommand(TvCommand.RIGHT) },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.KeyboardArrowRight, contentDescription = "Right", modifier = Modifier.size(40.dp))
        }
        
        // Center (OK)
        Box(
            modifier = Modifier
                .size(100.dp)
                .shadow(8.dp, CircleShape)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable { onCommand(TvCommand.OK) },
            contentAlignment = Alignment.Center
        ) {
            Text(
                "OK",
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge
            )
        }
    }
}

@Composable
fun VolumeControl(onCommand: (TvCommand) -> Unit) {
    Surface(
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.width(200.dp)
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onCommand(TvCommand.VOLUME_DOWN) }) {
                Icon(Icons.AutoMirrored.Rounded.VolumeDown, contentDescription = "Volume Down")
            }
            IconButton(onClick = { onCommand(TvCommand.MUTE) }) {
                Icon(Icons.AutoMirrored.Rounded.VolumeOff, contentDescription = "Mute")
            }
            IconButton(onClick = { onCommand(TvCommand.VOLUME_UP) }) {
                Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = "Volume Up")
            }
        }
    }
}

@Composable
fun RemoteButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 64.dp,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        modifier = modifier.size(size)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(size / 2.5f))
        }
    }
}
