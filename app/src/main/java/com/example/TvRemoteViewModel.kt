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

class TvRemoteViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("tv_remote_prefs", Context.MODE_PRIVATE)
    
    private val _ipAddress = MutableStateFlow(prefs.getString("last_ip", "") ?: "")
    val ipAddress: StateFlow<String> = _ipAddress.asStateFlow()

    private val _connectionState = MutableStateFlow(TvConnectionManager.ConnectionState.DISCONNECTED.name)
    val connectionState: StateFlow<String> = _connectionState.asStateFlow()

    private val discoveryManager = TvDiscoveryManager(application)
    val discoveredDevices = discoveryManager.discoveredDevices

    private var collectionJob: Job? = null

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
        _ipAddress.value = ip
        prefs.edit().putString("last_ip", ip).apply()
    }

    fun sendCommand(command: TvCommand) {
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
            TvCommand.KEYBOARD -> -1 
            TvCommand.VOICE -> 219
        }
        
        if (keyCode != -1) {
            TvConnectionManagerInstance.manager?.sendKey(keyCode)
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
    }

    fun connect(context: Context) {
        if (_ipAddress.value.isBlank()) return
        
        if (TvConnectionManagerInstance.manager == null || TvConnectionManagerInstance.manager?.host != _ipAddress.value) {
            TvConnectionManagerInstance.manager?.disconnect()
            val manager = TvConnectionManager(_ipAddress.value, context)
            TvConnectionManagerInstance.manager = manager
            startCollecting(manager, context)
            
            viewModelScope.launch {
                try {
                    manager.connectRemote()
                } catch (e: Exception) {
                    try {
                        kotlinx.coroutines.delay(1000)
                        manager.connectRemote()
                    } catch (e2: Exception) {
                        manager.startPairing()
                    }
                }
            }
        } else {
            val manager = TvConnectionManagerInstance.manager!!
            startCollecting(manager, context)
            
            if (manager.connectionState.value != TvConnectionManager.ConnectionState.CONNECTED) {
                viewModelScope.launch {
                    try {
                        manager.connectRemote()
                    } catch (e: Exception) {
                        try {
                            kotlinx.coroutines.delay(1000)
                            manager.connectRemote()
                        } catch (e2: Exception) {
                            manager.startPairing()
                        }
                    }
                }
            }
        }
    }

    fun disconnect(context: Context) {
        TvConnectionManagerInstance.manager?.disconnect()
        val intent = Intent(context, TvConnectionService::class.java).apply { action = "STOP_SERVICE" }
        context.startService(intent)
    }

    fun sendPairingCode(code: String) {
        viewModelScope.launch {
            TvConnectionManagerInstance.manager?.sendPairingCode(code)
            delay(500)
            try {
                TvConnectionManagerInstance.manager?.connectRemote()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
