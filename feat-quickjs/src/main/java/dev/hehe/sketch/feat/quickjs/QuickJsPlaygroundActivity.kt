package dev.hehe.sketch.feat.quickjs

import android.os.Bundle
import android.os.Build
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class QuickJsPlaygroundActivity : AppCompatActivity() {
    private lateinit var runner: QuickJsRunner
    private var execution: QuickJsExecution? = null
    private var batchRunning = false
    private var destroyed = false

    private lateinit var caseSpinner: Spinner
    private lateinit var sourceEditor: EditText
    private lateinit var timeoutEditor: EditText
    private lateinit var memoryEditor: EditText
    private lateinit var stackEditor: EditText
    private lateinit var resetButton: Button
    private lateinit var runButton: Button
    private lateinit var stopButton: Button
    private lateinit var runAllButton: Button
    private lateinit var statusView: TextView
    private lateinit var resultView: TextView
    private lateinit var consoleView: TextView
    private lateinit var metricsView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quickjs_playground)
        bindViews()
        runner = QuickJsRunner(this)

        val cases = QuickJsValidationCases.all
        caseSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            cases.map(QuickJsValidationCase::title)
        )
        caseSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (savedInstanceState == null || sourceEditor.text.isEmpty()) loadCase(cases[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        resetButton.setOnClickListener { applyOptions(QuickJsEvalOptions()) }
        runButton.setOnClickListener { runSource() }
        stopButton.setOnClickListener {
            batchRunning = false
            execution?.cancel()
            statusView.text = getString(R.string.quickjs_status_stopping)
        }
        runAllButton.setOnClickListener { runAllCases() }

        if (savedInstanceState != null) {
            sourceEditor.setText(savedInstanceState.getString(STATE_SOURCE).orEmpty())
            timeoutEditor.setText(savedInstanceState.getString(STATE_TIMEOUT))
            memoryEditor.setText(savedInstanceState.getString(STATE_MEMORY))
            stackEditor.setText(savedInstanceState.getString(STATE_STACK))
            caseSpinner.setSelection(savedInstanceState.getInt(STATE_CASE_INDEX, 0))
        } else {
            loadCase(cases.first())
        }

        if (!Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
            runButton.isEnabled = false
            runAllButton.isEnabled = false
            statusView.text = getString(R.string.quickjs_status_unsupported_abi)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SOURCE, sourceEditor.text.toString())
        outState.putString(STATE_TIMEOUT, timeoutEditor.text.toString())
        outState.putString(STATE_MEMORY, memoryEditor.text.toString())
        outState.putString(STATE_STACK, stackEditor.text.toString())
        outState.putInt(STATE_CASE_INDEX, caseSpinner.selectedItemPosition)
    }

    override fun onDestroy() {
        destroyed = true
        execution?.cancel()
        runner.close()
        super.onDestroy()
    }

    private fun runSource() {
        val options = readOptions() ?: return
        setRunning(true)
        statusView.text = getString(R.string.quickjs_status_running)
        resultView.text = ""
        consoleView.text = ""
        metricsView.text = ""
        execution = runner.run(sourceEditor.text.toString(), options) { result ->
            if (destroyed) return@run
            execution = null
            setRunning(false)
            renderResult(result)
        }
        if (execution == null) {
            setRunning(false)
            statusView.text = getString(R.string.quickjs_status_busy)
        }
    }

    private fun runAllCases() {
        val cases = QuickJsValidationCases.all.filter(QuickJsValidationCase::includeInBatch)
        batchRunning = true
        setRunning(true)
        resultView.text = ""
        consoleView.text = ""
        metricsView.text = ""
        runBatchCase(cases, 0, mutableListOf())
    }

    private fun runBatchCase(
        cases: List<QuickJsValidationCase>,
        index: Int,
        report: MutableList<String>
    ) {
        if (destroyed || !batchRunning) return
        if (index >= cases.size) {
            batchRunning = false
            execution = null
            setRunning(false)
            val passed = report.count { it.startsWith("✓") }
            statusView.text = getString(R.string.quickjs_status_batch_complete, passed, report.size)
            resultView.text = report.joinToString("\n")
            return
        }

        val case = cases[index]
        statusView.text = getString(
            R.string.quickjs_status_batch_progress,
            index + 1,
            cases.size,
            case.title
        )
        execution = runner.run(case.source, case.options) { result ->
            if (destroyed) return@run
            if (!batchRunning) {
                execution = null
                setRunning(false)
                statusView.text = getString(R.string.quickjs_status_batch_stopped)
                return@run
            }
            val passed = case.expected.matches(result)
            report += "${if (passed) "✓" else "✗"} ${case.title}${failureSummary(result, passed)}"
            execution = null
            runBatchCase(cases, index + 1, report)
        }
        if (execution == null) {
            report += "✗ ${case.title}（执行器忙）"
            runBatchCase(cases, index + 1, report)
        }
    }

    private fun failureSummary(result: QuickJsEvalResult, passed: Boolean): String {
        if (passed) return ""
        return when (result) {
            is QuickJsEvalResult.Success -> "：得到 ${result.value}"
            is QuickJsEvalResult.Failure -> "：${result.kind} ${result.message}"
        }
    }

    private fun renderResult(result: QuickJsEvalResult) {
        when (result) {
            is QuickJsEvalResult.Success -> {
                statusView.text = getString(R.string.quickjs_status_success, result.valueType)
                resultView.text = result.value
            }
            is QuickJsEvalResult.Failure -> {
                statusView.text = getString(R.string.quickjs_status_failure, result.kind)
                resultView.text = buildString {
                    append(result.message)
                    result.stack?.let { append("\n").append(it) }
                }
            }
        }
        consoleView.text = result.logs.ifEmpty { listOf(getString(R.string.quickjs_no_logs)) }
            .joinToString("\n")
        metricsView.text = getString(
            R.string.quickjs_metrics,
            result.durationMs,
            formatBytes(result.memoryUsedBytes)
        )
    }

    private fun loadCase(case: QuickJsValidationCase) {
        sourceEditor.setText(case.source)
        applyOptions(case.options)
        statusView.text = getString(R.string.quickjs_status_idle)
    }

    private fun applyOptions(options: QuickJsEvalOptions) {
        timeoutEditor.setText(options.timeoutMs.toString())
        memoryEditor.setText((options.memoryLimitBytes / QuickJsEvalOptions.MIB).toString())
        stackEditor.setText((options.maxStackBytes / QuickJsEvalOptions.KIB).toString())
    }

    private fun readOptions(): QuickJsEvalOptions? {
        val timeout = timeoutEditor.text.toString().toLongOrNull()
        val memory = memoryEditor.text.toString().toLongOrNull()
        val stack = stackEditor.text.toString().toLongOrNull()
        if (timeout == null || memory == null || stack == null) {
            Toast.makeText(this, R.string.quickjs_invalid_options, Toast.LENGTH_SHORT).show()
            return null
        }
        val options = QuickJsEvalOptions.fromDisplayValues(timeout, memory, stack)
        options.validationError()?.let {
            Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
            return null
        }
        return options
    }

    private fun setRunning(running: Boolean) {
        runButton.isEnabled = !running
        runAllButton.isEnabled = !running
        resetButton.isEnabled = !running
        caseSpinner.isEnabled = !running
        stopButton.isEnabled = running
        sourceEditor.isEnabled = !running
        timeoutEditor.isEnabled = !running
        memoryEditor.isEnabled = !running
        stackEditor.isEnabled = !running
    }

    private fun bindViews() {
        caseSpinner = findViewById(R.id.caseSpinner)
        sourceEditor = findViewById(R.id.sourceEditor)
        timeoutEditor = findViewById(R.id.timeoutEditor)
        memoryEditor = findViewById(R.id.memoryEditor)
        stackEditor = findViewById(R.id.stackEditor)
        resetButton = findViewById(R.id.resetButton)
        runButton = findViewById(R.id.runButton)
        stopButton = findViewById(R.id.stopButton)
        runAllButton = findViewById(R.id.runAllButton)
        statusView = findViewById(R.id.statusView)
        resultView = findViewById(R.id.resultView)
        consoleView = findViewById(R.id.consoleView)
        metricsView = findViewById(R.id.metricsView)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= QuickJsEvalOptions.MIB -> "%.2f MiB".format(bytes.toDouble() / QuickJsEvalOptions.MIB)
        bytes >= QuickJsEvalOptions.KIB -> "%.1f KiB".format(bytes.toDouble() / QuickJsEvalOptions.KIB)
        else -> "$bytes B"
    }

    companion object {
        private const val STATE_SOURCE = "source"
        private const val STATE_TIMEOUT = "timeout"
        private const val STATE_MEMORY = "memory"
        private const val STATE_STACK = "stack"
        private const val STATE_CASE_INDEX = "case_index"
    }
}
