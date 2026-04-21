package dev.hehe.sketch.feat.cameraxaruco

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageButton
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
    private lateinit var detectionCountValueView: TextView
    private lateinit var recordingResolutionValueView: TextView
    private lateinit var openLatestVideoButton: ImageButton
    private lateinit var recordToggleButton: View
    private lateinit var recordOuterRingView: View
    private lateinit var recordInnerIndicatorView: View

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var detector: OpenCvArucoDetector? = null

    private var cameraSessionController: CameraSessionController? = null
    private lateinit var recordingController: RecordingController
    private var currentRecordingState = RecordingController.RecordingUiState()

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
        detectionCountValueView = findViewById(R.id.detectionCountValueView)
        recordingResolutionValueView = findViewById(R.id.recordingResolutionValueView)
        openLatestVideoButton = findViewById(R.id.openLatestVideoButton)
        recordToggleButton = findViewById(R.id.recordToggleButton)
        recordOuterRingView = findViewById(R.id.recordOuterRingView)
        recordInnerIndicatorView = findViewById(R.id.recordInnerIndicatorView)

        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        detectionCountValueView.text = getString(R.string.camera_aruco_marker_count_format, 0)
        recordingResolutionValueView.text = getString(R.string.camera_aruco_resolution_empty)
        applyMarkerStateColor(0)
        applyRecordingButtonStyle(false)
        openLatestVideoButton.isEnabled = false
        openLatestVideoButton.alpha = DISABLED_ACTION_ALPHA
    }

    private fun setupRecordingController() {
        recordingController = RecordingController(
            context = this,
            onStateChanged = { state -> runOnUiThread { renderRecordingState(state) } },
            onError = { message -> runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() } }
        )

        recordToggleButton.setOnClickListener {
            if (currentRecordingState.status == RecordingController.RecordingStatus.RECORDING) {
                recordingController.stopRecording()
            } else {
                recordingController.startRecording()
            }
        }
        openLatestVideoButton.setOnClickListener {
            val intent = recordingController.buildOpenLatestVideoIntent()
            if (intent == null) {
                Toast.makeText(this, R.string.camera_aruco_open_video_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            try {
                startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, R.string.camera_aruco_open_video_failed, Toast.LENGTH_SHORT).show()
            }
        }
        openLatestVideoButton.setOnLongClickListener {
            Toast.makeText(this, R.string.camera_aruco_open_video_hint, Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun observePreviewState() {
        previewView.previewStreamState.observe(this) { streamState ->
            if (streamState == PreviewView.StreamState.STREAMING) {
                previewStatusView.visibility = View.GONE
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
            previewStatusView.visibility = View.VISIBLE
            previewStatusView.text = getString(R.string.camera_aruco_status_request_permission)
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val readyDetector = detector ?: run {
            showFatalState(R.string.camera_aruco_status_opencv_error)
            return
        }
        previewStatusView.visibility = View.VISIBLE
        previewStatusView.text = getString(R.string.camera_aruco_status_waiting_preview)

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
                previewStatusView.visibility = View.VISIBLE
                previewStatusView.text = getString(R.string.camera_aruco_status_waiting_preview)
            },
            onError = {
                showFatalState(R.string.camera_aruco_status_camera_error)
            }
        ).also { it.start() }
    }

    private fun renderDetectionResult(result: ArucoDetectionResult) {
        val markerCount = result.markers.size
        detectionCountValueView.text = getString(R.string.camera_aruco_marker_count_format, markerCount)
        applyMarkerStateColor(markerCount)
    }

    private fun renderRecordingState(state: RecordingController.RecordingUiState) {
        currentRecordingState = state
        recordingResolutionValueView.text = state.resolutionLabel
        openLatestVideoButton.isEnabled = state.latestVideoAvailable
        openLatestVideoButton.alpha = if (state.latestVideoAvailable) ENABLED_ACTION_ALPHA else DISABLED_ACTION_ALPHA
        recordToggleButton.isEnabled = state.qualityLabel != "--" &&
            state.status != RecordingController.RecordingStatus.FINALIZING
        applyRecordingButtonStyle(state.status == RecordingController.RecordingStatus.RECORDING)

        if (state.status == RecordingController.RecordingStatus.AUTO_STOPPED) {
            Toast.makeText(this, R.string.camera_aruco_recording_auto_stopped, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showFatalState(messageRes: Int) {
        previewStatusView.visibility = View.VISIBLE
        previewStatusView.text = getString(messageRes)
    }

    private fun applyMarkerStateColor(markerCount: Int) {
        val textColor = if (markerCount > 0) {
            Color.parseColor("#6BCB77")
        } else {
            Color.parseColor("#E57373")
        }
        detectionCountValueView.setTextColor(textColor)
    }

    private fun applyRecordingButtonStyle(isRecording: Boolean) {
        val outer = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke(6, Color.WHITE)
            setColor(Color.TRANSPARENT)
        }
        recordOuterRingView.background = outer

        val inner = GradientDrawable().apply {
            shape = if (isRecording) GradientDrawable.RECTANGLE else GradientDrawable.OVAL
            setColor(Color.parseColor("#FF4D4F"))
            cornerRadius = if (isRecording) 16f else 999f
        }
        recordInnerIndicatorView.background = inner
    }

    private companion object {
        const val TAG = "CameraArucoActivity"
        const val ENABLED_ACTION_ALPHA = 1f
        const val DISABLED_ACTION_ALPHA = 0.38f
    }
}
