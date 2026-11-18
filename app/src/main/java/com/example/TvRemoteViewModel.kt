package com.example

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import remote.Remotemessage.RemoteKeyCode

class TvRemoteViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("tv_remote_prefs", Context.MODE_PRIVATE)

    private val _ipAddress = MutableStateFlow(prefs.getString("last_ip", "") ?: "")
    val ipAddress: StateFlow<String> = _ipAddress.asStateFlow()

    private val _connectionState = MutableStateFlow(TvConnectionManager.ConnectionState.DISCONNECTED.name)
    val connectionState: StateFlow<String> = _connectionState.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _pairingError = MutableStateFlow<String?>(null)
    val pairingError: StateFlow<String?> = _pairingError.asStateFlow()

    private val discoveryManager = TvDiscoveryManager(application)
    val discoveredDevices = discoveryManager.discoveredDevices

    private var collectionJob: Job? = null
    private var statusJob: Job? = null
    private var connectJob: Job? = null

    init {
        discoveryManager.startDiscovery()

        TvConnectionManagerInstance.manager?.let { manager ->
            _ipAddress.value = manager.host
            startCollecting(manager, application.applicationContext)
        } ?: run {
            if (_ipAddress.value.isNotBlank()) {
                connect(application.applicationContext)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        discoveryManager.stopDiscovery()
    }

    fun updateIpAddress(ip: String) {
        val trimmed = ip.trim()
        _ipAddress.value = trimmed
        prefs.edit().putString("last_ip", trimmed).apply()
    }

    fun sendCommand(command: TvCommand) {
        val keyCode = when (command) {
            TvCommand.UP -> RemoteKeyCode.KEYCODE_DPAD_UP_VALUE
            TvCommand.DOWN -> RemoteKeyCode.KEYCODE_DPAD_DOWN_VALUE
            TvCommand.LEFT -> RemoteKeyCode.KEYCODE_DPAD_LEFT_VALUE
            TvCommand.RIGHT -> RemoteKeyCode.KEYCODE_DPAD_RIGHT_VALUE
            TvCommand.OK -> RemoteKeyCode.KEYCODE_DPAD_CENTER_VALUE
            TvCommand.BACK -> RemoteKeyCode.KEYCODE_BACK_VALUE
            TvCommand.HOME -> RemoteKeyCode.KEYCODE_HOME_VALUE
            TvCommand.POWER -> RemoteKeyCode.KEYCODE_POWER_VALUE
            TvCommand.VOLUME_UP -> RemoteKeyCode.KEYCODE_VOLUME_UP_VALUE
            TvCommand.VOLUME_DOWN -> RemoteKeyCode.KEYCODE_VOLUME_DOWN_VALUE
            TvCommand.MUTE -> RemoteKeyCode.KEYCODE_VOLUME_MUTE_VALUE
            TvCommand.KEYBOARD -> -1
            TvCommand.VOICE -> RemoteKeyCode.KEYCODE_ASSIST_VALUE
        }

        if (keyCode != -1) {
            TvConnectionManagerInstance.manager?.sendKey(keyCode)
        }
    }

    fun sendKeyCode(keyCode: Int) {
        TvConnectionManagerInstance.manager?.sendKey(keyCode)
    }

    fun sendText(text: String) {
        val manager = TvConnectionManagerInstance.manager ?: return
        viewModelScope.launch {
            for (ch in text) {
                val keyCode = charToKeyCode(ch)
                if (keyCode != null) {
                    manager.sendKey(keyCode)
                    delay(40)
                }
            }
        }
    }

    private fun charToKeyCode(ch: Char): Int? {
        return when (ch) {
            in 'a'..'z' -> RemoteKeyCode.KEYCODE_A_VALUE + (ch - 'a')
            in 'A'..'Z' -> RemoteKeyCode.KEYCODE_A_VALUE + (ch - 'A')
            in '0'..'9' -> RemoteKeyCode.KEYCODE_0_VALUE + (ch - '0')
            ' ' -> RemoteKeyCode.KEYCODE_SPACE_VALUE
            '.' -> RemoteKeyCode.KEYCODE_PERIOD_VALUE
            ',' -> RemoteKeyCode.KEYCODE_COMMA_VALUE
            '-' -> RemoteKeyCode.KEYCODE_MINUS_VALUE
            '/' -> RemoteKeyCode.KEYCODE_SLASH_VALUE
            '@' -> RemoteKeyCode.KEYCODE_AT_VALUE
            '\n' -> RemoteKeyCode.KEYCODE_ENTER_VALUE
            else -> null
        }
    }

    private fun startCollecting(manager: TvConnectionManager, context: Context) {
        collectionJob?.cancel()
        collectionJob = viewModelScope.launch {
            manager.connectionState.collect { state ->
                _connectionState.value = state.name
                if (state == TvConnectionManager.ConnectionState.CONNECTED) {
                    val intent = Intent(context, TvConnectionService::class.java)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                } else if (state == TvConnectionManager.ConnectionState.DISCONNECTED || state == TvConnectionManager.ConnectionState.ERROR) {
                    val intent = Intent(context, TvConnectionService::class.java).apply { action = "STOP_SERVICE" }
                    context.startService(intent)
                }
            }
        }

        statusJob?.cancel()
        statusJob = viewModelScope.launch {
            manager.statusMessage.collect { msg ->
                _statusMessage.value = msg
            }
        }
    }

    private fun getOrCreateManager(context: Context): TvConnectionManager {
        val ip = _ipAddress.value
        val existing = TvConnectionManagerInstance.manager
        if (existing != null && existing.host == ip) {
            startCollecting(existing, context)
            return existing
        }
        existing?.disconnect()
        val created = TvConnectionManager(ip, context.applicationContext)
        TvConnectionManagerInstance.manager = created
        startCollecting(created, context)
        return created
    }

    fun connect(context: Context) {
        if (_ipAddress.value.isBlank()) return
        val appContext = context.applicationContext
        val manager = getOrCreateManager(appContext)

        if (manager.connectionState.value == TvConnectionManager.ConnectionState.CONNECTED ||
            manager.connectionState.value == TvConnectionManager.ConnectionState.CONNECTING ||
            manager.connectionState.value == TvConnectionManager.ConnectionState.PAIRING
        ) {
            return
        }

        _pairingError.value = null
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            try {
                manager.connectRemote()
            } catch (e: TvConnectionManager.PairingRequiredException) {
                manager.startPairing()
            } catch (e: Exception) {
                delay(800)
                try {
                    manager.connectRemote()
                } catch (e2: TvConnectionManager.PairingRequiredException) {
                    manager.startPairing()
                } catch (e2: Exception) {
                    manager.startPairing()
                }
            }
        }
    }

    fun pair(context: Context) {
        if (_ipAddress.value.isBlank()) return
        val appContext = context.applicationContext
        val manager = getOrCreateManager(appContext)
        _pairingError.value = null
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            manager.startPairing()
        }
    }

    fun disconnect(context: Context) {
        connectJob?.cancel()
        TvConnectionManagerInstance.manager?.disconnect()
        val intent = Intent(context, TvConnectionService::class.java).apply { action = "STOP_SERVICE" }
        context.startService(intent)
    }

    fun sendPairingCode(code: String) {
        val manager = TvConnectionManagerInstance.manager ?: return
        _pairingError.value = null
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            when (val result = manager.sendPairingCode(code)) {
                is TvConnectionManager.PairingResult.Success -> {
                    delay(400)
                    try {
                        manager.connectRemote()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                is TvConnectionManager.PairingResult.InvalidCode -> {
                    _pairingError.value = result.reason
                }
                is TvConnectionManager.PairingResult.Error -> {
                    _pairingError.value = result.message
                }
            }
        }
    }
}
