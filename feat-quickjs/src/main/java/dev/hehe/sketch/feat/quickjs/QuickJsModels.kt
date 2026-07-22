package dev.hehe.sketch.feat.quickjs

import org.json.JSONObject

data class QuickJsEvalOptions(
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    val memoryLimitBytes: Long = DEFAULT_MEMORY_MIB * MIB,
    val maxStackBytes: Long = DEFAULT_STACK_KIB * KIB
) {
    fun validationError(): String? = when {
        timeoutMs !in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS ->
            "超时需在 $MIN_TIMEOUT_MS–$MAX_TIMEOUT_MS ms 之间"
        memoryLimitBytes !in MIN_MEMORY_MIB * MIB..MAX_MEMORY_MIB * MIB ->
            "内存需在 $MIN_MEMORY_MIB–$MAX_MEMORY_MIB MiB 之间"
        maxStackBytes !in MIN_STACK_KIB * KIB..MAX_STACK_KIB * KIB ->
            "栈需在 $MIN_STACK_KIB–$MAX_STACK_KIB KiB 之间"
        else -> null
    }

    companion object {
        const val KIB = 1024L
        const val MIB = 1024L * 1024L
        const val DEFAULT_TIMEOUT_MS = 2_000L
        const val DEFAULT_MEMORY_MIB = 16L
        const val DEFAULT_STACK_KIB = 512L
        const val MIN_TIMEOUT_MS = 100L
        const val MAX_TIMEOUT_MS = 30_000L
        const val MIN_MEMORY_MIB = 4L
        const val MAX_MEMORY_MIB = 256L
        const val MIN_STACK_KIB = 128L
        const val MAX_STACK_KIB = 4_096L

        fun fromDisplayValues(timeoutMs: Long, memoryMib: Long, stackKib: Long) =
            QuickJsEvalOptions(timeoutMs, memoryMib * MIB, stackKib * KIB)
    }
}

enum class QuickJsFailureKind {
    SCRIPT_ERROR,
    HOST_ERROR,
    TIMEOUT,
    CANCELLED,
    UNRESOLVED_PROMISE,
    ENGINE_ERROR
}

sealed class QuickJsEvalResult {
    abstract val logs: List<String>
    abstract val durationMs: Long
    abstract val memoryUsedBytes: Long

    data class Success(
        val value: String,
        val valueType: String,
        override val logs: List<String>,
        override val durationMs: Long,
        override val memoryUsedBytes: Long
    ) : QuickJsEvalResult()

    data class Failure(
        val kind: QuickJsFailureKind,
        val message: String,
        val stack: String?,
        override val logs: List<String>,
        override val durationMs: Long,
        override val memoryUsedBytes: Long
    ) : QuickJsEvalResult()

    companion object {
        internal fun decode(payload: ByteArray): QuickJsEvalResult {
            val json = JSONObject(payload.toString(Charsets.UTF_8))
            val logsJson = json.getJSONArray("logs")
            val logs = buildList {
                for (index in 0 until logsJson.length()) add(logsJson.getString(index))
            }
            val durationMs = json.optLong("durationMs")
            val memoryUsedBytes = json.optLong("memoryUsedBytes")
            return if (json.getBoolean("ok")) {
                Success(
                    value = json.optString("value"),
                    valueType = json.optString("valueType", "unknown"),
                    logs = logs,
                    durationMs = durationMs,
                    memoryUsedBytes = memoryUsedBytes
                )
            } else {
                Failure(
                    kind = runCatching {
                        QuickJsFailureKind.valueOf(json.getString("kind"))
                    }.getOrDefault(QuickJsFailureKind.ENGINE_ERROR),
                    message = json.optString("message", "QuickJS failed"),
                    stack = json.optString("stack").takeIf { it.isNotBlank() },
                    logs = logs,
                    durationMs = durationMs,
                    memoryUsedBytes = memoryUsedBytes
                )
            }
        }

        internal fun engineFailure(error: Throwable) = Failure(
            kind = QuickJsFailureKind.ENGINE_ERROR,
            message = error.message ?: error.javaClass.simpleName,
            stack = null,
            logs = emptyList(),
            durationMs = 0,
            memoryUsedBytes = 0
        )
    }
}
