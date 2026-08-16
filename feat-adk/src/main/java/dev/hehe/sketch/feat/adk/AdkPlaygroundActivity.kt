package dev.hehe.sketch.feat.adk

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import com.google.adk.kt.events.Event
import androidx.core.view.isVisible

class AdkPlaygroundActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: AdkPlaygroundSession
    private var activeRun: Job? = null
    private var running = false
    private var awaitingCalendarPermission: PendingToolApproval? = null
    private val pendingApprovals = linkedMapOf<String, PendingToolApproval>()

    private lateinit var configBody: View
    private lateinit var configToggle: Button
    private lateinit var apiKeyEditor: EditText
    private lateinit var modelEditor: EditText
    private lateinit var localModelCheck: CheckBox
    private lateinit var apiKeyLayout: View
    private lateinit var apiKeyWarning: View
    private lateinit var modelLayout: View
    private lateinit var localModelStatus: TextView
    private lateinit var promptEditor: EditText
    private lateinit var streamingCheck: CheckBox
    private lateinit var sendButton: Button
    private lateinit var stopButton: Button
    private lateinit var newSessionButton: Button
    private lateinit var statusView: TextView
    private lateinit var transcriptView: TextView
    private lateinit var traceView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var toolsContainer: LinearLayout
    private lateinit var pendingSection: View
    private lateinit var pendingContainer: LinearLayout

    private val transcript = StringBuilder()
    private val trace = StringBuilder()

    private val calendarPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val request = awaitingCalendarPermission ?: return@registerForActivityResult
        awaitingCalendarPermission = null
        appendTrace("PERMISSION  calendar result=${hasCalendarPermissions()}")
        resumeApproval(request, confirmed = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = AdkPlaygroundSession(applicationContext)
        setContentView(R.layout.activity_adk_playground)
        bindViews()

        modelEditor.setText(savedInstanceState?.getString(STATE_MODEL) ?: DEFAULT_MODEL)
        localModelCheck.isChecked = savedInstanceState?.getBoolean(STATE_LOCAL_MODEL) ?: false
        promptEditor.setText(savedInstanceState?.getString(STATE_PROMPT) ?: DEFAULT_PROMPT)
        streamingCheck.isChecked = savedInstanceState?.getBoolean(STATE_STREAMING) ?: true

        configToggle.setOnClickListener {
            configBody.visibility = if (configBody.isVisible) View.GONE else View.VISIBLE
        }
        localModelCheck.setOnCheckedChangeListener { _, _ ->
            session.clearCredentials()
            renderModelConfig()
        }
        sendButton.setOnClickListener { runAgent() }
        stopButton.setOnClickListener { stopRun() }
        newSessionButton.setOnClickListener { startNewSession() }
        findViewById<Button>(R.id.exampleQuickJs).setOnClickListener {
            promptEditor.setText(R.string.adk_example_quickjs)
        }
        findViewById<Button>(R.id.exampleCalendar).setOnClickListener {
            promptEditor.setText(R.string.adk_example_calendar)
        }
        findViewById<Button>(R.id.exampleTimer).setOnClickListener {
            promptEditor.setText(R.string.adk_example_timer)
        }
        renderIntro()
        renderModelConfig()
        refreshCapabilityCatalog()
        renderPendingApprovals()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_MODEL, modelEditor.text.toString())
        outState.putBoolean(STATE_LOCAL_MODEL, localModelCheck.isChecked)
        outState.putString(STATE_PROMPT, promptEditor.text.toString())
        outState.putBoolean(STATE_STREAMING, streamingCheck.isChecked)
    }

    override fun onResume() {
        super.onResume()
        if (::session.isInitialized && ::localModelStatus.isInitialized) renderModelConfig()
    }

    override fun onDestroy() {
        activeRun?.cancel()
        session.close()
        scope.cancel()
        super.onDestroy()
    }

    private fun runAgent() {
        if (pendingApprovals.isNotEmpty()) {
            statusView.setText(R.string.adk_status_approval_required)
            return
        }
        val config = validatedConfig() ?: return
        val prompt = promptEditor.text.toString().trim()
        if (prompt.isBlank()) {
            promptEditor.error = getString(R.string.adk_prompt_required)
            return
        }
        hideKeyboard()
        appendTranscript("YOU", prompt)
        collectRun(session.run(config.modelConfig, prompt, streamingCheck.isChecked))
    }

    private fun resumeApproval(request: PendingToolApproval, confirmed: Boolean) {
        val config = validatedConfig() ?: return
        pendingApprovals.remove(request.confirmationCallId)
        renderPendingApprovals()
        appendTrace("APPROVAL  ${request.toolName} ${if (confirmed) "allowed" else "rejected"}")
        collectRun(
            session.resumeConfirmation(
                modelConfig = config.modelConfig,
                request = request,
                confirmed = confirmed,
                streaming = streamingCheck.isChecked
            )
        )
    }

    private fun approve(request: PendingToolApproval) {
        if (request.needsCalendarPermission && !hasCalendarPermissions()) {
            awaitingCalendarPermission = request
            appendTrace("PERMISSION  requesting calendar access after approval")
            renderPendingApprovals()
            calendarPermissionLauncher.launch(
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            )
        } else {
            resumeApproval(request, confirmed = true)
        }
    }

    private fun collectRun(events: Flow<Event>) {
        running = true
        setRunning(true)
        statusView.setText(R.string.adk_status_running)
        var streamedText = ""
        var finalText = ""
        activeRun = scope.launch {
            try {
                events.collect { event ->
                    AdkEventFormatter.traceLines(event).forEach(::appendTrace)
                    PendingToolApproval.from(event).forEach { approval ->
                        pendingApprovals[approval.confirmationCallId] = approval
                    }
                    renderPendingApprovals()
                    val text = AdkEventFormatter.visibleText(event)
                    if (text.isNotBlank() && event.author == AdkPlaygroundSession.AGENT_NAME) {
                        if (event.partial) {
                            streamedText += text
                            renderLiveResponse(streamedText)
                        } else {
                            finalText = text
                            renderLiveResponse(finalText)
                        }
                    }
                }
                val answer = finalText.ifBlank { streamedText }.trim()
                if (answer.isNotBlank()) appendTranscript("AGENT", answer)
                renderLiveResponse("")
                statusView.setText(
                    if (pendingApprovals.isEmpty()) R.string.adk_status_complete
                    else R.string.adk_status_approval_required
                )
            } catch (_: CancellationException) {
                renderLiveResponse("")
                appendTrace("CANCELLED  current ADK run")
                statusView.setText(R.string.adk_status_stopped)
            } catch (error: Exception) {
                renderLiveResponse("")
                appendTrace("FAILED  ${error.message ?: error::class.simpleName}")
                statusView.text = getString(
                    R.string.adk_status_failed,
                    error.message ?: error::class.simpleName.orEmpty()
                )
            } finally {
                running = false
                activeRun = null
                setRunning(false)
                renderPendingApprovals()
            }
        }
    }

    private fun stopRun() {
        activeRun?.cancel()
    }

    private fun startNewSession() {
        if (activeRun != null) return
        pendingApprovals.clear()
        awaitingCalendarPermission = null
        val number = session.startNewSession()
        transcript.clear()
        trace.clear()
        renderIntro()
        renderPendingApprovals()
        appendTrace(getString(R.string.adk_trace_new_session, number))
        statusView.setText(R.string.adk_status_idle)
    }

    private fun refreshCapabilityCatalog() {
        scope.launch {
            val entries = session.capabilityCatalog()
            toolsContainer.removeAllViews()
            entries.forEach { entry ->
                val check = CheckBox(this@AdkPlaygroundActivity).apply {
                    val risk = if (entry.descriptor.approvalPolicy == McpApprovalPolicy.ALWAYS) {
                        getString(R.string.adk_risk_confirmation)
                    } else {
                        getString(R.string.adk_risk_read_only)
                    }
                    val availability = when {
                        entry.schemaError != null -> entry.schemaError
                        !entry.descriptor.available -> entry.descriptor.unavailableReason
                        else -> getString(R.string.adk_available)
                    }
                    text = "${entry.endpointName} · ${entry.descriptor.name}\n" +
                        "${entry.descriptor.description}\n$risk · $availability"
                    isChecked = entry.enabled
                    isEnabled = entry.descriptor.available && entry.schemaError == null && !running && pendingApprovals.isEmpty()
                    setTextColor(ContextCompat.getColor(context, R.color.adk_text_secondary))
                    setOnCheckedChangeListener { _, enabled ->
                        session.setToolEnabled(entry.descriptor.name, enabled)
                        appendTrace("CATALOG  ${entry.descriptor.name} enabled=$enabled")
                        refreshCapabilityCatalog()
                    }
                }
                toolsContainer.addView(check)
            }
        }
    }

    private fun renderPendingApprovals() {
        pendingSection.visibility = if (pendingApprovals.isEmpty()) View.GONE else View.VISIBLE
        pendingContainer.removeAllViews()
        pendingApprovals.values.forEach { request ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 16, 20, 16)
                setBackgroundResource(R.drawable.bg_adk_status)
            }
            card.addView(TextView(this).apply {
                text = "${request.toolName}\n${request.hint.orEmpty()}\n参数：${request.arguments}"
                setTextColor(ContextCompat.getColor(context, R.color.adk_text_primary))
            })
            card.addView(LinearLayout(this).apply {
                gravity = android.view.Gravity.END
                addView(Button(this@AdkPlaygroundActivity).apply {
                    text = getString(R.string.adk_reject)
                    isEnabled = !running && awaitingCalendarPermission == null
                    setOnClickListener { resumeApproval(request, confirmed = false) }
                })
                addView(Button(this@AdkPlaygroundActivity).apply {
                    text = getString(R.string.adk_allow)
                    isEnabled = !running && awaitingCalendarPermission == null
                    setOnClickListener { approve(request) }
                })
            })
            pendingContainer.addView(card)
        }
        setRunning(running)
    }

    private fun validatedConfig(): AgentConfig? {
        if (localModelCheck.isChecked) {
            if (!session.isLocalModelReady()) {
                localModelStatus.setText(R.string.adk_local_model_required)
                return null
            }
            return AgentConfig(AdkModelConfig.LocalGemma)
        }
        val apiKey = apiKeyEditor.text.toString().trim()
        val model = modelEditor.text.toString().trim()
        if (apiKey.isBlank()) {
            apiKeyEditor.error = getString(R.string.adk_api_key_required)
            return null
        }
        if (model.isBlank()) {
            modelEditor.error = getString(R.string.adk_model_required)
            return null
        }
        return AgentConfig(AdkModelConfig.Cloud(apiKey, model))
    }

    private fun renderModelConfig() {
        val local = localModelCheck.isChecked
        apiKeyLayout.visibility = if (local) View.GONE else View.VISIBLE
        apiKeyWarning.visibility = if (local) View.GONE else View.VISIBLE
        modelLayout.visibility = if (local) View.GONE else View.VISIBLE
        localModelStatus.visibility = if (local) View.VISIBLE else View.GONE
        if (local) {
            val ready = session.isLocalModelReady()
            localModelStatus.setText(
                if (ready) R.string.adk_local_model_ready else R.string.adk_local_model_missing
            )
            localModelStatus.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (ready) R.color.adk_primary else R.color.adk_warning
                )
            )
        }
    }

    private fun hasCalendarPermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun appendTranscript(author: String, text: String) {
        if (transcript.isNotEmpty()) transcript.append("\n\n")
        transcript.append(author).append("\n").append(text)
        transcriptView.text = transcript
        scrollToBottom()
    }

    private fun appendTrace(line: String) {
        if (trace.isNotEmpty()) trace.append('\n')
        trace.append(line)
        traceView.text = trace
        scrollToBottom()
    }

    private fun renderLiveResponse(text: String) {
        val base = transcript.toString()
        transcriptView.text = if (text.isBlank()) base else "$base\n\nAGENT · STREAMING\n$text"
        scrollToBottom()
    }

    private fun renderIntro() {
        transcript.append(getString(R.string.adk_transcript_intro))
        transcriptView.text = transcript
        traceView.text = trace
    }

    private fun setRunning(running: Boolean) {
        val hasPending = pendingApprovals.isNotEmpty()
        sendButton.isEnabled = !running && !hasPending
        stopButton.isEnabled = running
        newSessionButton.isEnabled = !running
        apiKeyEditor.isEnabled = !running && !hasPending
        modelEditor.isEnabled = !running && !hasPending
        localModelCheck.isEnabled = !running && !hasPending
        promptEditor.isEnabled = !running && !hasPending
        streamingCheck.isEnabled = !running && !hasPending
    }

    private fun scrollToBottom() {
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun hideKeyboard() {
        currentFocus?.let { view ->
            getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    private fun bindViews() {
        configBody = findViewById(R.id.configBody)
        configToggle = findViewById(R.id.configToggle)
        apiKeyEditor = findViewById(R.id.apiKeyEditor)
        modelEditor = findViewById(R.id.modelEditor)
        localModelCheck = findViewById(R.id.localModelCheck)
        apiKeyLayout = findViewById(R.id.apiKeyLayout)
        apiKeyWarning = findViewById(R.id.apiKeyWarning)
        modelLayout = findViewById(R.id.modelLayout)
        localModelStatus = findViewById(R.id.localModelStatus)
        promptEditor = findViewById(R.id.promptEditor)
        streamingCheck = findViewById(R.id.streamingCheck)
        sendButton = findViewById(R.id.sendButton)
        stopButton = findViewById(R.id.stopButton)
        newSessionButton = findViewById(R.id.newSessionButton)
        statusView = findViewById(R.id.statusView)
        transcriptView = findViewById(R.id.transcriptView)
        traceView = findViewById(R.id.traceView)
        scrollView = findViewById(R.id.scrollView)
        toolsContainer = findViewById(R.id.toolsContainer)
        pendingSection = findViewById(R.id.pendingSection)
        pendingContainer = findViewById(R.id.pendingContainer)
    }

    private data class AgentConfig(val modelConfig: AdkModelConfig)

    private companion object {
        const val DEFAULT_MODEL = "gemini-3.1-flash-lite-preview"
        const val DEFAULT_PROMPT = "用 JavaScript 计算订单 [12.5, 8, 21.5, 5] 的总额、平均值和最大值。"
        const val STATE_MODEL = "model"
        const val STATE_LOCAL_MODEL = "local_model"
        const val STATE_PROMPT = "prompt"
        const val STATE_STREAMING = "streaming"
    }
}
