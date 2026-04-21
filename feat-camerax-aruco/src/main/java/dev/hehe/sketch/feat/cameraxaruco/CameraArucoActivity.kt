package dev.hehe.sketch.feat.cameraxaruco

import android.Manifest
import android.content.ActivityNotFoundException
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
import com.google.android.material.button.MaterialButton
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
    private lateinit var recordingStatusValueView: TextView
    private lateinit var recordingQualityValueView: TextView
    private lateinit var recordingResolutionValueView: TextView
    private lateinit var recordingDurationValueView: TextView
    private lateinit var startRecordingButton: MaterialButton
    private lateinit var stopRecordingButton: MaterialButton
    private lateinit var openLatestVideoButton: MaterialButton

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var detector: OpenCvArucoDetector? = null

    private var cameraSessionController: CameraSessionController? = null
    private lateinit var recordingController: RecordingController

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
        setupRecordingController()

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
        recordingController.stopRecording()
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
        recordingStatusValueView = findViewById(R.id.recordingStatusValueView)
        recordingQualityValueView = findViewById(R.id.recordingQualityValueView)
        recordingResolutionValueView = findViewById(R.id.recordingResolutionValueView)
        recordingDurationValueView = findViewById(R.id.recordingDurationValueView)
        startRecordingButton = findViewById(R.id.startRecordingButton)
        stopRecordingButton = findViewById(R.id.stopRecordingButton)
        openLatestVideoButton = findViewById(R.id.openLatestVideoButton)

        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        renderIdleState(getString(R.string.camera_aruco_status_initializing))
    }

    private fun setupRecordingController() {
        recordingController = RecordingController(
            context = this,
            onStateChanged = { state -> runOnUiThread { renderRecordingState(state) } },
            onError = { message -> runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() } }
        )

        startRecordingButton.setOnClickListener {
            recordingController.startRecording()
        }
        stopRecordingButton.setOnClickListener {
            recordingController.stopRecording()
        }
        openLatestVideoButton.setOnClickListener {
            val intent = recordingController.buildOpenLatestVideoIntent()
            if (intent == null) {
                Toast.makeText(this, R.string.camera_aruco_open_video_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            try {
                startActivity(intent)
            } catch (throwable: ActivityNotFoundException) {
                Toast.makeText(this, R.string.camera_aruco_open_video_failed, Toast.LENGTH_SHORT).show()
            }
        }
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
            onBound = { session ->
                recordingController.attachSession(session.cameraInfo, session.videoCapture)
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

    private fun renderRecordingState(state: RecordingController.RecordingUiState) {
        recordingStatusValueView.text = when (state.status) {
            RecordingController.RecordingStatus.IDLE -> getString(R.string.camera_aruco_recording_status_idle)
            RecordingController.RecordingStatus.RECORDING -> getString(R.string.camera_aruco_recording_status_recording)
            RecordingController.RecordingStatus.FINALIZING -> getString(R.string.camera_aruco_recording_status_finalizing)
            RecordingController.RecordingStatus.COMPLETED -> getString(R.string.camera_aruco_recording_status_completed)
            RecordingController.RecordingStatus.AUTO_STOPPED -> getString(R.string.camera_aruco_recording_status_auto_stopped)
            RecordingController.RecordingStatus.ERROR -> getString(R.string.camera_aruco_recording_status_error)
        }
        recordingStatusValueView.setTextColor(
            ContextCompat.getColor(
                this,
                when (state.status) {
                    RecordingController.RecordingStatus.RECORDING -> android.R.color.holo_red_light
                    RecordingController.RecordingStatus.COMPLETED -> android.R.color.holo_green_light
                    RecordingController.RecordingStatus.AUTO_STOPPED -> android.R.color.holo_orange_light
                    RecordingController.RecordingStatus.ERROR -> android.R.color.holo_red_dark
                    RecordingController.RecordingStatus.FINALIZING -> android.R.color.holo_blue_light
                    RecordingController.RecordingStatus.IDLE -> android.R.color.white
                }
            )
        )
        recordingQualityValueView.text = state.qualityLabel
        recordingResolutionValueView.text = state.resolutionLabel
        recordingDurationValueView.text = state.durationLabel
        startRecordingButton.isEnabled = state.qualityLabel != "--" &&
            state.status != RecordingController.RecordingStatus.RECORDING &&
            state.status != RecordingController.RecordingStatus.FINALIZING
        stopRecordingButton.isEnabled = state.status == RecordingController.RecordingStatus.RECORDING
        openLatestVideoButton.isEnabled = state.latestVideoAvailable
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
