package dev.hehe.sketch.feat.gemma

import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class GemmaPlaygroundActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var modelStore: GemmaModelStore
    private lateinit var session: GemmaLocalSession

    private var modelJob: Job? = null
    private var generationJob: Job? = null
    private var downloading = false
    private var loading = false

    private lateinit var statusView: TextView
    private lateinit var progressView: TextView
    private lateinit var downloadProgress: ProgressBar
    private lateinit var downloadButton: Button
    private lateinit var cancelDownloadButton: Button
    private lateinit var deleteButton: Button
    private lateinit var loadButton: Button
    private lateinit var transcriptView: TextView
    private lateinit var metricsView: TextView
    private lateinit var promptEditor: EditText
    private lateinit var newConversationButton: Button
    private lateinit var stopButton: Button
    private lateinit var sendButton: Button

    private val transcript = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gemma_playground)
        modelStore = GemmaModelStore(applicationContext)
        session = GemmaLocalSession(applicationContext)
        bindViews()

        promptEditor.setText(savedInstanceState?.getString(STATE_PROMPT) ?: DEFAULT_PROMPT)
        downloadButton.setOnClickListener { startDownload() }
        cancelDownloadButton.setOnClickListener { modelJob?.cancel() }
        deleteButton.setOnClickListener { confirmDeleteModel() }
        loadButton.setOnClickListener {
            if (session.isLoaded()) unloadModel() else loadModel()
        }
        sendButton.setOnClickListener { sendMessage() }
        stopButton.setOnClickListener { stopGeneration() }
        newConversationButton.setOnClickListener { startNewConversation() }

        renderStoredProgress()
        renderControls()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PROMPT, promptEditor.text.toString())
    }

    override fun onDestroy() {
        modelJob?.cancel()
        stopGeneration()
        session.close()
        scope.cancel()
        super.onDestroy()
    }

    private fun startDownload() {
        if (modelJob != null) return
        downloading = true
        statusView.setText(R.string.gemma_status_downloading)
        renderControls()
        modelJob = scope.launch {
            try {
                val file = modelStore.download { progress ->
                    scope.launch { renderDownloadProgress(progress) }
                }
                downloading = false
                loading = true
                statusView.setText(R.string.gemma_status_loading)
                renderControls()
                val result = session.load(file)
                renderLoadResult(result)
            } catch (_: CancellationException) {
                statusView.setText(R.string.gemma_status_stopped)
            } catch (error: Throwable) {
                renderError(error)
            } finally {
                downloading = false
                loading = false
                modelJob = null
                renderStoredProgress()
                renderControls()
            }
        }
    }

    private fun loadModel() {
        if (modelJob != null || !modelStore.isModelReady()) return
        loading = true
        statusView.setText(R.string.gemma_status_loading)
        renderControls()
        modelJob = scope.launch {
            try {
                renderLoadResult(session.load(modelStore.modelFile))
            } catch (_: CancellationException) {
                statusView.setText(R.string.gemma_status_stopped)
            } catch (error: Throwable) {
                renderError(error)
            } finally {
                loading = false
                modelJob = null
                renderControls()
            }
        }
    }

    private fun unloadModel() {
        if (modelJob != null || generationJob != null) return
        loading = true
        renderControls()
        modelJob = scope.launch {
            try {
                session.unload()
                statusView.setText(R.string.gemma_status_ready)
            } catch (error: Throwable) {
                renderError(error)
            } finally {
                loading = false
                modelJob = null
                renderControls()
            }
        }
    }

    private fun deleteModel() {
        if (modelJob != null || generationJob != null) return
        loading = true
        renderControls()
        modelJob = scope.launch {
            try {
                session.unload()
                modelStore.deleteModel()
                transcript.clear()
                transcriptView.setText(R.string.gemma_transcript_empty)
                metricsView.setText(R.string.gemma_metrics_empty)
                statusView.setText(R.string.gemma_status_not_downloaded)
                Toast.makeText(this@GemmaPlaygroundActivity, R.string.gemma_model_deleted, Toast.LENGTH_SHORT).show()
            } catch (error: Throwable) {
                renderError(error)
            } finally {
                loading = false
                modelJob = null
                renderStoredProgress()
                renderControls()
            }
        }
    }

    private fun confirmDeleteModel() {
        if (modelJob != null || generationJob != null) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.gemma_delete_confirmation_title)
            .setMessage(R.string.gemma_delete_confirmation_message)
            .setNegativeButton(R.string.gemma_cancel) { dialog, _ -> dialog.dismiss() }
            .setPositiveButton(R.string.gemma_delete) { _, _ -> deleteModel() }
            .show()
    }

    private fun sendMessage() {
        if (!session.isLoaded() || generationJob != null) return
        val prompt = promptEditor.text.toString().trim()
        if (prompt.isEmpty()) {
            promptEditor.error = getString(R.string.gemma_prompt_required)
            return
        }
        hideKeyboard()
        appendTranscript("YOU", prompt)
        promptEditor.text.clear()
        statusView.setText(R.string.gemma_status_generating)
        renderControls()

        generationJob = scope.launch {
            val response = StringBuilder()
            try {
                session.send(prompt).collect { chunk ->
                    response.append(chunk)
                    renderLiveResponse(response.toString())
                }
                if (response.isNotEmpty()) appendTranscript("GEMMA", response.toString())
                renderLiveResponse("")
                renderMetrics()
                statusView.text = getString(
                    R.string.gemma_status_loaded,
                    session.backendName.orEmpty()
                )
            } catch (_: CancellationException) {
                if (response.isNotEmpty()) appendTranscript("GEMMA", response.toString())
                renderLiveResponse("")
                statusView.setText(R.string.gemma_status_stopped)
            } catch (error: Throwable) {
                renderLiveResponse("")
                renderError(error)
            } finally {
                generationJob = null
                renderControls()
            }
        }
    }

    private fun stopGeneration() {
        session.cancelGeneration()
        generationJob?.cancel()
    }

    private fun startNewConversation() {
        if (!session.isLoaded() || generationJob != null || modelJob != null) return
        modelJob = scope.launch {
            try {
                session.newConversation()
                transcript.clear()
                transcriptView.setText(R.string.gemma_transcript_empty)
                metricsView.setText(R.string.gemma_metrics_empty)
                statusView.text = getString(
                    R.string.gemma_status_loaded,
                    session.backendName.orEmpty()
                )
            } catch (error: Throwable) {
                renderError(error)
            } finally {
                modelJob = null
                renderControls()
            }
        }
        renderControls()
    }

    private fun renderDownloadProgress(progress: ModelDownloadProgress) {
        val percent = ((progress.downloadedBytes * 1000L) / progress.totalBytes)
            .coerceIn(0L, 1000L)
            .toInt()
        downloadProgress.progress = percent
        progressView.text = getString(
            R.string.gemma_progress,
            formatGiB(progress.downloadedBytes),
            formatGiB(progress.totalBytes),
            percent / 10
        )
        statusView.setText(
            if (progress.phase == ModelDownloadProgress.Phase.VERIFYING) {
                R.string.gemma_status_verifying
            } else {
                R.string.gemma_status_downloading
            }
        )
    }

    private fun renderStoredProgress() {
        val downloaded = modelStore.downloadedBytes()
        if (downloaded == 0L) {
            downloadProgress.progress = 0
            progressView.setText(R.string.gemma_progress_empty)
        } else {
            val percent = ((downloaded * 1000L) / GemmaModelSpec.SIZE_BYTES)
                .coerceIn(0L, 1000L)
                .toInt()
            downloadProgress.progress = percent
            progressView.text = getString(
                R.string.gemma_progress,
                formatGiB(downloaded),
                formatGiB(GemmaModelSpec.SIZE_BYTES),
                percent / 10
            )
        }
        if (modelJob == null && !session.isLoaded()) {
            statusView.setText(
                if (modelStore.isModelReady()) R.string.gemma_status_ready
                else R.string.gemma_status_not_downloaded
            )
        }
    }

    private fun renderLoadResult(result: GemmaLoadResult) {
        statusView.text = getString(R.string.gemma_status_loaded, result.backend)
        metricsView.text = result.gpuFailure?.let {
            getString(R.string.gemma_gpu_fallback, it)
        } ?: getString(
            R.string.gemma_metrics,
            result.initSeconds,
            0.0,
            0.0,
            0.0
        )
    }

    private fun renderMetrics() {
        val info = session.benchmarkInfo() ?: return
        metricsView.text = getString(
            R.string.gemma_metrics,
            info.initTimeInSecond,
            info.timeToFirstTokenInSecond,
            info.lastPrefillTokensPerSecond,
            info.lastDecodeTokensPerSecond
        )
    }

    private fun renderControls() {
        val modelReady = modelStore.isModelReady()
        val modelLoaded = session.isLoaded()
        val modelBusy = modelJob != null || downloading || loading
        val generating = generationJob != null

        downloadButton.visibility = if (!modelReady && !downloading) View.VISIBLE else View.GONE
        downloadButton.setText(
            if (modelStore.downloadedBytes() > 0L) R.string.gemma_resume_download
            else R.string.gemma_download
        )
        cancelDownloadButton.visibility = if (downloading) View.VISIBLE else View.GONE
        loadButton.visibility = if (modelReady && !modelBusy) View.VISIBLE else View.GONE
        loadButton.setText(if (modelLoaded) R.string.gemma_unload else R.string.gemma_load)
        deleteButton.isEnabled = !modelBusy && !generating && modelStore.downloadedBytes() > 0L
        sendButton.isEnabled = modelLoaded && !modelBusy && !generating
        promptEditor.isEnabled = modelLoaded && !modelBusy && !generating
        stopButton.isEnabled = generating
        newConversationButton.isEnabled = modelLoaded && !modelBusy && !generating
    }

    private fun appendTranscript(author: String, text: String) {
        if (transcript.isNotEmpty()) transcript.append("\n\n")
        transcript.append(author).append("\n").append(text.trim())
        transcriptView.text = transcript.toString()
    }

    private fun renderLiveResponse(text: String) {
        transcriptView.text = buildString {
            append(transcript)
            if (text.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("GEMMA\n").append(text)
            }
        }
    }

    private fun renderError(error: Throwable) {
        val message = error.message ?: error::class.simpleName.orEmpty()
        statusView.text = getString(R.string.gemma_status_failed, message)
    }

    private fun bindViews() {
        statusView = findViewById(R.id.statusView)
        progressView = findViewById(R.id.progressView)
        downloadProgress = findViewById(R.id.downloadProgress)
        downloadButton = findViewById(R.id.downloadButton)
        cancelDownloadButton = findViewById(R.id.cancelDownloadButton)
        deleteButton = findViewById(R.id.deleteButton)
        loadButton = findViewById(R.id.loadButton)
        transcriptView = findViewById(R.id.transcriptView)
        metricsView = findViewById(R.id.metricsView)
        promptEditor = findViewById(R.id.promptEditor)
        newConversationButton = findViewById(R.id.newConversationButton)
        stopButton = findViewById(R.id.stopButton)
        sendButton = findViewById(R.id.sendButton)
    }

    private fun hideKeyboard() {
        currentFocus?.let { view ->
            getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    private fun formatGiB(bytes: Long): String = "%.2f GiB".format(bytes / GIB.toDouble())

    private companion object {
        const val STATE_PROMPT = "prompt"
        const val GIB = 1024L * 1024L * 1024L
        const val DEFAULT_PROMPT = "用三句话介绍一下你自己，以及你目前是在云端还是本机运行。"
    }
}
