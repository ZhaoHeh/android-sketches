package dev.hehe.sketch.feat.cameraxaruco

import android.content.Context
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService

class CameraSessionController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val analysisExecutor: ExecutorService,
    private val analyzer: ImageAnalysis.Analyzer,
    private val onBound: (SessionBinding) -> Unit,
    private val onError: (Throwable) -> Unit
) {

    data class SessionBinding(
        val cameraInfo: CameraInfo,
        val videoCapture: VideoCapture<Recorder>
    )

    private var cameraProvider: ProcessCameraProvider? = null

    fun start() {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                try {
                    cameraProvider = providerFuture.get()
                    bindUseCases()
                } catch (throwable: Throwable) {
                    onError(throwable)
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    fun stop() {
        cameraProvider?.unbindAll()
    }

    private fun bindUseCases() {
        val provider = cameraProvider ?: return
        val targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0

        val preview = Preview.Builder()
            .setTargetRotation(targetRotation)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val analysis = ImageAnalysis.Builder()
            .setTargetRotation(targetRotation)
            .setTargetResolution(ANALYSIS_TARGET_SIZE)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { it.setAnalyzer(analysisExecutor, analyzer) }

        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.fromOrderedList(
                    PREFERRED_QUALITIES,
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                )
            )
            .build()
        val videoCapture = VideoCapture.withOutput(recorder)

        provider.unbindAll()
        val camera = provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            analysis,
            videoCapture
        )
        onBound(
            SessionBinding(
                cameraInfo = camera.cameraInfo,
                videoCapture = videoCapture
            )
        )
    }

    private companion object {
        val ANALYSIS_TARGET_SIZE = Size(1280, 720)
        val PREFERRED_QUALITIES = listOf(
            Quality.UHD,
            Quality.FHD,
            Quality.HD,
            Quality.SD
        )
    }
}
