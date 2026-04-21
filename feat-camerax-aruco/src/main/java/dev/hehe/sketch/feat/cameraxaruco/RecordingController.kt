package dev.hehe.sketch.feat.cameraxaruco

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraInfo
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

class RecordingController(
    private val context: Context,
    private val onStateChanged: (RecordingUiState) -> Unit,
    private val onError: (String) -> Unit
) {

    data class RecordingUiState(
        val status: RecordingStatus = RecordingStatus.IDLE,
        val qualityLabel: String = "--",
        val resolutionLabel: String = "--",
        val durationLabel: String = "00:00",
        val latestVideoAvailable: Boolean = false
    )

    enum class RecordingStatus {
        IDLE,
        RECORDING,
        FINALIZING,
        COMPLETED,
        AUTO_STOPPED,
        ERROR
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var videoCapture: VideoCapture<Recorder>? = null
    private var selectedQuality: Quality? = null
    private var selectedResolution: Size? = null
    private var recording: Recording? = null
    private var recordingStartedAtMs = 0L
    private var latestVideoFile: File? = loadPersistedLatestFile()
    private var autoStopped = false
    private var uiState = RecordingUiState(
        latestVideoAvailable = latestVideoFile?.exists() == true
    )

    private val updateDurationRunnable = object : Runnable {
        override fun run() {
            if (recording == null) {
                return
            }
            updateState { copy(durationLabel = formatDuration(SystemClock.elapsedRealtime() - recordingStartedAtMs)) }
            mainHandler.postDelayed(this, DURATION_UPDATE_INTERVAL_MS)
        }
    }

    private val autoStopRunnable = Runnable {
        if (recording != null) {
            autoStopped = true
            stopRecording()
        }
    }

    init {
        emitState()
    }

    fun attachSession(
        cameraInfo: CameraInfo,
        videoCapture: VideoCapture<Recorder>
    ) {
        this.videoCapture = videoCapture
        selectedQuality = resolveHighestQuality(cameraInfo)
        selectedResolution = selectedQuality?.let { QualitySelector.getResolution(cameraInfo, it) }
        updateState {
            copy(
                qualityLabel = selectedQuality?.toLabel() ?: "--",
                resolutionLabel = selectedResolution?.toLabel() ?: "--",
                latestVideoAvailable = latestVideoFile?.exists() == true
            )
        }
    }

    fun startRecording() {
        if (recording != null) {
            return
        }

        val capture = videoCapture
        if (capture == null) {
            onError(context.getString(R.string.camera_aruco_recording_not_ready))
            return
        }

        val outputFile = createLatestVideoFile()
        latestVideoFile?.takeIf { it.exists() }?.delete()
        latestVideoFile = outputFile
        persistLatestFile(outputFile)

        val outputOptions = FileOutputOptions.Builder(outputFile).build()
        val pendingRecording: PendingRecording = capture.output.prepareRecording(context, outputOptions)
        recordingStartedAtMs = SystemClock.elapsedRealtime()
        autoStopped = false

        recording = pendingRecording.start(ContextCompat.getMainExecutor(context)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    updateState {
                        copy(
                            status = RecordingStatus.RECORDING,
                            durationLabel = "00:00",
                            latestVideoAvailable = false
                        )
                    }
                    scheduleTimers()
                }

                is VideoRecordEvent.Finalize -> {
                    clearTimers()
                    recording?.close()
                    recording = null

                    if (event.hasError()) {
                        latestVideoFile?.delete()
                        latestVideoFile = null
                        persistLatestFile(null)
                        updateState {
                            copy(
                                status = RecordingStatus.ERROR,
                                durationLabel = "00:00",
                                latestVideoAvailable = false
                            )
                        }
                        onError(context.getString(R.string.camera_aruco_recording_failed))
                    } else {
                        updateState {
                            copy(
                                status = if (autoStopped) RecordingStatus.AUTO_STOPPED else RecordingStatus.COMPLETED,
                                latestVideoAvailable = latestVideoFile?.exists() == true
                            )
                        }
                    }
                }

                is VideoRecordEvent.Status -> {
                    updateState {
                        copy(durationLabel = formatDuration(event.recordingStats.recordedDurationNanos / 1_000_000))
                    }
                }
            }
        }
    }

    fun stopRecording() {
        updateState {
            if (recording == null) this else copy(status = RecordingStatus.FINALIZING)
        }
        clearTimers()
        recording?.stop()
    }

    fun hasLatestVideo(): Boolean = latestVideoFile?.exists() == true

    fun buildOpenLatestVideoIntent(): Intent? {
        val videoFile = latestVideoFile?.takeIf { it.exists() } ?: return null
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            videoFile
        )
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun emitState() {
        onStateChanged(uiState)
    }

    private fun updateState(transform: RecordingUiState.() -> RecordingUiState) {
        uiState = uiState.transform()
        emitState()
    }

    private fun resolveHighestQuality(cameraInfo: CameraInfo): Quality? {
        val supportedQualities = QualitySelector.getSupportedQualities(cameraInfo)
        return listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD)
            .firstOrNull { it in supportedQualities }
    }

    private fun createLatestVideoFile(): File {
        val externalRoot = context.getExternalFilesDir(null) ?: context.filesDir
        val videoDirectory = File(
            externalRoot,
            VIDEO_DIRECTORY_NAME
        ).apply { mkdirs() }
        return File(videoDirectory, LATEST_VIDEO_FILE_NAME)
    }

    private fun loadPersistedLatestFile(): File? {
        val storedPath = prefs.getString(KEY_LATEST_VIDEO_PATH, null) ?: return null
        return File(storedPath).takeIf { it.exists() }
    }

    private fun persistLatestFile(file: File?) {
        prefs.edit().putString(KEY_LATEST_VIDEO_PATH, file?.absolutePath).apply()
    }

    private fun scheduleTimers() {
        clearTimers()
        mainHandler.post(updateDurationRunnable)
        mainHandler.postDelayed(autoStopRunnable, MAX_RECORDING_DURATION_MS)
    }

    private fun clearTimers() {
        mainHandler.removeCallbacks(updateDurationRunnable)
        mainHandler.removeCallbacks(autoStopRunnable)
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs.coerceAtLeast(0L) / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    private fun Size.toLabel(): String = "${width} x ${height}"

    private fun Quality.toLabel(): String = when (this) {
        Quality.UHD -> "UHD"
        Quality.FHD -> "FHD"
        Quality.HD -> "HD"
        Quality.SD -> "SD"
        else -> toString()
    }

    private companion object {
        const val PREFS_NAME = "feat_camerax_aruco_recording"
        const val KEY_LATEST_VIDEO_PATH = "latest_video_path"
        const val VIDEO_DIRECTORY_NAME = "aruco-recordings"
        const val LATEST_VIDEO_FILE_NAME = "aruco-latest.mp4"
        const val DURATION_UPDATE_INTERVAL_MS = 1_000L
        const val MAX_RECORDING_DURATION_MS = 10L * 60L * 1000L
    }
}
