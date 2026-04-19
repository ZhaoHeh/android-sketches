package dev.hehe.sketch.feat.cameraxaruco

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import org.opencv.android.OpenCVLoader
import java.util.concurrent.Executors

class CameraArucoActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: ArucoOverlayView
    private lateinit var previewStatusView: TextView
    private lateinit var detectionStatusValueView: TextView
    private lateinit var detectionCountValueView: TextView
    private lateinit var detectionIdsValueView: TextView
    private lateinit var detectionLatencyValueView: TextView

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var detector: OpenCvArucoDetector? = null

    private var cameraSessionController: CameraSessionController? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            showFatalState(R.string.camera_aruco_status_permission_denied)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_aruco)
        title = getString(R.string.camera_aruco_title)

        bindViews()
        observePreviewState()

        if (!OpenCVLoader.initLocal()) {
            showFatalState(R.string.camera_aruco_status_opencv_error)
            Toast.makeText(this, R.string.camera_aruco_status_opencv_error, Toast.LENGTH_LONG).show()
            return
        }

        detector = try {
            OpenCvArucoDetector()
        } catch (throwable: Throwable) {
            Log.e(TAG, "Failed to create arUco detector", throwable)
            showFatalState(R.string.camera_aruco_status_opencv_error)
            Toast.makeText(this, R.string.camera_aruco_status_opencv_error, Toast.LENGTH_LONG).show()
            return
        }

        ensureCameraPermissionAndStart()
    }

    override fun onDestroy() {
        cameraSessionController?.stop()
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    private fun bindViews() {
        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        previewStatusView = findViewById(R.id.previewStatusView)
        detectionStatusValueView = findViewById(R.id.detectionStatusValueView)
        detectionCountValueView = findViewById(R.id.detectionCountValueView)
        detectionIdsValueView = findViewById(R.id.detectionIdsValueView)
        detectionLatencyValueView = findViewById(R.id.detectionLatencyValueView)
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        renderIdleState(getString(R.string.camera_aruco_status_initializing))
    }

    private fun observePreviewState() {
        previewView.previewStreamState.observe(this) { streamState ->
            if (streamState == PreviewView.StreamState.STREAMING) {
                previewStatusView.visibility = View.GONE
                if (detectionCountValueView.text.toString() == "0") {
                    detectionStatusValueView.text = getString(R.string.camera_aruco_status_detecting)
                }
            } else {
                previewStatusView.visibility = View.VISIBLE
                previewStatusView.text = getString(R.string.camera_aruco_status_waiting_preview)
            }
        }
    }

    private fun ensureCameraPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            renderIdleState(getString(R.string.camera_aruco_status_request_permission))
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val readyDetector = detector ?: run {
            showFatalState(R.string.camera_aruco_status_opencv_error)
            return
        }
        renderIdleState(getString(R.string.camera_aruco_status_waiting_preview))
        val analyzer = ArucoFrameAnalyzer(readyDetector) { result ->
            runOnUiThread {
                overlayView.submitResult(result)
                renderDetectionResult(result)
            }
        }

        cameraSessionController = CameraSessionController(
            context = this,
            lifecycleOwner = this,
            previewView = previewView,
            analysisExecutor = analysisExecutor,
            analyzer = analyzer,
            onBound = {
                renderIdleState(getString(R.string.camera_aruco_status_waiting_preview))
            },
            onError = {
                showFatalState(R.string.camera_aruco_status_camera_error)
            }
        ).also { it.start() }
    }

    private fun renderIdleState(status: String) {
        overlayView.clear()
        previewStatusView.visibility = View.VISIBLE
        previewStatusView.text = status
        detectionStatusValueView.text = status
        detectionCountValueView.text = getString(R.string.camera_aruco_count_format, 0)
        detectionIdsValueView.text = getString(R.string.camera_aruco_ids_empty)
        detectionLatencyValueView.text = getString(R.string.camera_aruco_latency_empty)
    }

    private fun renderDetectionResult(result: ArucoDetectionResult) {
        val hasMarkers = result.markers.isNotEmpty()
        detectionStatusValueView.text = if (hasMarkers) {
            getString(R.string.camera_aruco_status_detecting)
        } else {
            getString(R.string.camera_aruco_status_no_marker)
        }
        detectionCountValueView.text = getString(
            R.string.camera_aruco_count_format,
            result.markers.size
        )
        detectionIdsValueView.text = if (result.markerIds.isEmpty()) {
            getString(R.string.camera_aruco_ids_empty)
        } else {
            result.markerIds.joinToString(", ")
        }
        detectionLatencyValueView.text = getString(
            R.string.camera_aruco_latency_format,
            result.processingTimeMs
        )
    }

    private fun showFatalState(messageRes: Int) {
        renderIdleState(getString(messageRes))
        previewStatusView.visibility = View.VISIBLE
        previewStatusView.text = getString(messageRes)
    }

    private companion object {
        const val TAG = "CameraArucoActivity"
    }
}
