package com.example

import android.Manifest
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun GestureScreen(
    viewModel: TvRemoteViewModel,
    onBack: () -> Unit
) {
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var recognizedGesture by remember { mutableStateOf("Waiting for gesture...") }
    var lastGestureTime by remember { mutableStateOf(0L) }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    val gestureListener = remember {
        object : GestureRecognizerHelper.GestureListener {
            override fun onError(error: String) {
                Log.e("GestureScreen", "Error: $error")
            }

            override fun onResults(result: GestureRecognizerResult) {
                if (result.gestures().isNotEmpty() && result.gestures()[0].isNotEmpty()) {
                    val gesture = result.gestures()[0][0].categoryName()
                    val score = result.gestures()[0][0].score()
                    if (score > 0.6f && gesture != "None") {
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastGestureTime > 1500) { // 1.5 second debounce
                            recognizedGesture = gesture
                            lastGestureTime = currentTime

                            when (gesture) {
                                "Thumb_Up" -> viewModel.sendCommand(TvCommand.UP)
                                "Thumb_Down" -> viewModel.sendCommand(TvCommand.DOWN)
                                "Pointing_Up" -> viewModel.sendCommand(TvCommand.VOLUME_UP)
                                "Closed_Fist" -> viewModel.sendCommand(TvCommand.VOLUME_DOWN)
                                "Victory" -> viewModel.sendCommand(TvCommand.OK)
                                "ILoveYou" -> viewModel.sendCommand(TvCommand.HOME)
                                "Open_Palm" -> viewModel.sendCommand(TvCommand.BACK)
                            }
                        }
                    }
                }
            }
        }
    }

    val gestureRecognizerHelper = remember { GestureRecognizerHelper(context, gestureListener) }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            gestureRecognizerHelper.clear()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gesture Control") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (cameraPermissionState.status.isGranted) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color.Black)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            val previewView = PreviewView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                scaleType = PreviewView.ScaleType.FILL_CENTER
                            }

                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                            cameraProviderFuture.addListener({
                                val cameraProvider = cameraProviderFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }

                                val imageAnalysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                                    .build()
                                    .also {
                                        it.setAnalyzer(cameraExecutor) { imageProxy ->
                                            gestureRecognizerHelper.recognizeLiveStream(
                                                imageProxy = imageProxy,
                                                isFrontCamera = true
                                            )
                                        }
                                    }

                                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                                try {
                                    cameraProvider.unbindAll()
                                    cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        cameraSelector,
                                        preview,
                                        imageAnalysis
                                    )
                                } catch (exc: Exception) {
                                    Log.e("GestureScreen", "Use case binding failed", exc)
                                }
                            }, ContextCompat.getMainExecutor(ctx))

                            previewView
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    
                    // Gesture Overlay Text
                    Text(
                        text = recognizedGesture,
                        color = Color.White,
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 32.dp)
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(16.dp)
                    )
                }
                
                // Instructions
                Card(
                    modifier = Modifier.padding(16.dp).fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Gesture Guide:", style = MaterialTheme.typography.titleMedium)
                        Text("👍 Thumb Up -> UP")
                        Text("👎 Thumb Down -> DOWN")
                        Text("☝️ Pointing Up -> VOL UP")
                        Text("✊ Closed Fist -> VOL DOWN")
                        Text("✌️ Victory -> OK")
                        Text("✋ Open Palm -> BACK")
                        Text("🤟 I Love You -> HOME")
                    }
                }
            } else {
                Button(onClick = { cameraPermissionState.launchPermissionRequest() }) {
                    Text("Request Camera Permission")
                }
            }
        }
    }
}
