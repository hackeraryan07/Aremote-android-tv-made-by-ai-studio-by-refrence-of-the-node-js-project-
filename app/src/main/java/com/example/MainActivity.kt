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

class TvRemoteViewModel : ViewModel() {
    // Conceptual IP address of the TV
    private val tvConnectionManager = TvConnectionManager("192.168.1.100")
    
    val connectionState: StateFlow<String> = tvConnectionManager.connectionState
        .map { it.name }
        .stateIn(viewModelScope, SharingStarted.Lazily, TvConnectionManager.ConnectionState.DISCONNECTED.name)

    fun sendCommand(command: TvCommand) {
        // Map UI commands to Android TV KeyCodes
        // Equivalent to RemoteKeyCode in Node.js reference
        val keyCode = when(command) {
            TvCommand.UP -> 19
            TvCommand.DOWN -> 20
            TvCommand.LEFT -> 21
            TvCommand.RIGHT -> 22
            TvCommand.OK -> 23
            TvCommand.BACK -> 4
            TvCommand.HOME -> 3
            TvCommand.POWER -> 26
            TvCommand.VOLUME_UP -> 24
            TvCommand.VOLUME_DOWN -> 25
            TvCommand.MUTE -> 164
            TvCommand.KEYBOARD -> -1 // Special handling for string injection
            TvCommand.VOICE -> 219
        }
        
        if (keyCode != -1) {
            tvConnectionManager.sendKey(keyCode)
        }
    }

    fun connect() {
        viewModelScope.launch {
            // Initiate the pairing sequence
            tvConnectionManager.startPairing()
            
            // In a real implementation, you would prompt the user for the code here.
            // For mock purposes, we pretend the code was entered:
            delay(1000) 
            tvConnectionManager.sendPairingCode("123456")
            tvConnectionManager.connectRemote()
        }
    }
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
    val context = LocalContext.current
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TV Remote", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                actions = {
                    IconButton(onClick = { viewModel.connect() }) {
                        Icon(
                            imageVector = Icons.Rounded.Cast,
                            contentDescription = "Connect to TV",
                            tint = if (connectionState == "Connected") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
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
                text = connectionState,
                style = MaterialTheme.typography.labelLarge,
                color = if (connectionState == "Connected") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
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
