package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult

class GestureRecognizerHelper(
    val context: Context,
    val gestureListener: GestureListener? = null
) {
    private var gestureRecognizer: GestureRecognizer? = null

    init {
        setupGestureRecognizer()
    }

    private fun setupGestureRecognizer() {
        val baseOptionsBuilder = BaseOptions.builder().setModelAssetPath("gesture_recognizer.task")
        baseOptionsBuilder.setDelegate(Delegate.CPU)
        val baseOptions = baseOptionsBuilder.build()

        val optionsBuilder = GestureRecognizer.GestureRecognizerOptions.builder()
            .setBaseOptions(baseOptions)
            .setMinHandDetectionConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener(this::returnLivestreamResult)
            .setErrorListener(this::returnLivestreamError)

        try {
            gestureRecognizer = GestureRecognizer.createFromOptions(context, optionsBuilder.build())
        } catch (e: Exception) {
            e.printStackTrace()
            gestureListener?.onError("Init Error: ${e.message}")
        }
    }

    fun recognizeLiveStream(imageProxy: ImageProxy, isFrontCamera: Boolean) {
        if (gestureRecognizer == null) {
            imageProxy.close()
            return
        }

        val frameTime = SystemClock.uptimeMillis()
        
        // Copy out RGB bits from the frame to a bitmap buffer
        val bitmapBuffer = Bitmap.createBitmap(
            imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888
        )
        try {
            bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer)
            imageProxy.close()
        } catch (e: Exception) {
            Log.e("GestureRecognizer", "Bitmap conversion failed", e)
            imageProxy.close()
            return
        }
        
        val matrix = Matrix().apply {
            postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
            if (isFrontCamera) {
                postScale(-1f, 1f, bitmapBuffer.width.toFloat(), bitmapBuffer.height.toFloat())
            }
        }
        val rotatedBitmap = Bitmap.createBitmap(
            bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height, matrix, true
        )

        val mpImage = BitmapImageBuilder(rotatedBitmap).build()
        try {
            gestureRecognizer?.recognizeAsync(mpImage, frameTime)
        } catch (e: Exception) {
            gestureListener?.onError("Inference Error: ${e.message}")
        }
    }

    private fun returnLivestreamResult(
        result: GestureRecognizerResult,
        input: MPImage
    ) {
        gestureListener?.onResults(result)
    }

    private fun returnLivestreamError(error: RuntimeException) {
        gestureListener?.onError(error.message ?: "An unknown error has occurred")
    }

    fun clear() {
        gestureRecognizer?.close()
        gestureRecognizer = null
    }

    interface GestureListener {
        fun onError(error: String)
        fun onResults(result: GestureRecognizerResult)
    }
}
