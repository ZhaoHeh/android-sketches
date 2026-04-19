package dev.hehe.sketch.feature.camerax.aruco

import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

class ArucoFrameAnalyzer(
    private val detector: OpenCvArucoDetector,
    private val onResult: (ArucoDetectionResult) -> Unit
) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        val startedAt = SystemClock.elapsedRealtimeNanos()
        try {
            val result = detector.detect(image).copy(
                processingTimeMs = (SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000
            )
            onResult(result)
        } catch (throwable: Throwable) {
            Log.e(TAG, "Failed to analyze arUco frame", throwable)
        } finally {
            image.close()
        }
    }

    private companion object {
        const val TAG = "ArucoFrameAnalyzer"
    }
}
