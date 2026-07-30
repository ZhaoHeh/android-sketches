package dev.hehe.sketch.feat.adk

import android.content.Context
import android.os.Build
import dev.hehe.sketch.feat.quickjs.QuickJsEvalOptions
import dev.hehe.sketch.feat.quickjs.QuickJsEvalResult
import dev.hehe.sketch.feat.quickjs.QuickJsHostCall
import dev.hehe.sketch.feat.quickjs.QuickJsHostDispatcher
import dev.hehe.sketch.feat.quickjs.QuickJsHostRequest
import dev.hehe.sketch.feat.quickjs.QuickJsHostResponse
import dev.hehe.sketch.feat.quickjs.QuickJsRunner
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal class QuickJsMcpEndpoint(
    context: Context
) : McpEndpoint {
    private val appContext = context.applicationContext

    override val id: String = "quickjs"
    override val displayName: String = "QuickJS 沙箱"

    override suspend fun listTools(): List<McpToolDescriptor> {
        val supported = Build.SUPPORTED_ABIS.contains(SUPPORTED_ABI)
        return listOf(
            McpToolDescriptor(
                name = TOOL_NAME,
                description =
                    "在隔离的 QuickJS 中执行纯 JavaScript。代码应把最终值作为最后一个表达式返回，" +
                        "异步代码可返回 Promise；输入数据可通过全局常量 input 读取。",
                inputSchema = McpSchemas.objectSchema(
                    properties = mapOf(
                        "code" to McpSchemas.string(
                            "要执行的 JavaScript 源码，最后一个表达式必须可 JSON 序列化。"
                        ),
                        "input" to McpSchemas.objectSchema(
                            properties = emptyMap(),
                            description = "供脚本通过全局常量 input 读取的可选 JSON 对象。"
                        )
                    ),
                    required = listOf("code")
                ),
                annotations = McpToolAnnotations(
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false
                ),
                approvalPolicy = McpApprovalPolicy.AUTO,
                available = supported,
                unavailableReason = if (supported) null else "当前仅支持 $SUPPORTED_ABI"
            )
        )
    }

    override suspend fun callTool(
        name: String,
        arguments: Map<String, Any?>,
        context: McpCallContext
    ): McpToolResult {
        if (name != TOOL_NAME) return McpToolResult.error("METHOD_NOT_FOUND", "未知工具：$name")
        if (!Build.SUPPORTED_ABIS.contains(SUPPORTED_ABI)) {
            return McpToolResult.error("UNSUPPORTED_ABI", "QuickJS 当前仅支持 $SUPPORTED_ABI")
        }

        val code = arguments["code"] as? String
            ?: return McpToolResult.error("INVALID_ARGUMENT", "code 必须是字符串")
        if (code.toByteArray(Charsets.UTF_8).size > MAX_SOURCE_BYTES) {
            return McpToolResult.error(
                "SOURCE_TOO_LARGE",
                "JavaScript 源码不能超过 ${MAX_SOURCE_BYTES / 1024} KiB"
            )
        }

        val input = arguments["input"]
        if (input != null && input !is Map<*, *>) {
            return McpToolResult.error("INVALID_ARGUMENT", "input 必须是 JSON 对象")
        }
        val inputJson = try {
            encodeJson(input ?: emptyMap<String, Any?>())
        } catch (error: IllegalArgumentException) {
            return McpToolResult.error("INVALID_ARGUMENT", error.message ?: "input 不是合法 JSON")
        }
        if (inputJson.toByteArray(Charsets.UTF_8).size > MAX_INPUT_BYTES) {
            return McpToolResult.error(
                "INPUT_TOO_LARGE",
                "input 不能超过 ${MAX_INPUT_BYTES / 1024} KiB"
            )
        }

        val source = "const input = JSON.parse(${JSONObject.quote(inputJson)});\n$code"
        return execute(source)
    }

    private suspend fun execute(source: String): McpToolResult = suspendCancellableCoroutine { cont ->
        val cleaned = AtomicBoolean(false)
        val runner = QuickJsRunner(
            context = appContext,
            hostDispatcher = DisabledAndroidHostDispatcher,
            resultExecutor = DirectExecutor
        )
        fun cleanup() {
            if (cleaned.compareAndSet(false, true)) runner.close()
        }

        val execution = runner.run(source, EXECUTION_OPTIONS) { result ->
            val response = result.toMcpResult()
            cleanup()
            if (cont.isActive) cont.resume(response)
        }
        if (execution == null) {
            cleanup()
            cont.resume(McpToolResult.error("RUNNER_BUSY", "QuickJS 执行器正忙"))
            return@suspendCancellableCoroutine
        }
        cont.invokeOnCancellation {
            execution.cancel()
            cleanup()
        }
    }

    private fun QuickJsEvalResult.toMcpResult(): McpToolResult = when (this) {
        is QuickJsEvalResult.Success -> {
            if (value.toByteArray(Charsets.UTF_8).size > MAX_RESULT_BYTES) {
                McpToolResult.error(
                    "RESULT_TOO_LARGE",
                    "QuickJS 结果不能超过 ${MAX_RESULT_BYTES / 1024} KiB"
                )
            } else {
                val structured = mapOf(
                    "ok" to true,
                    "value" to decodeQuickJsValue(value, valueType),
                    "value_type" to valueType,
                    "logs" to logs,
                    "duration_ms" to durationMs,
                    "memory_used_bytes" to memoryUsedBytes
                )
                if (encodeJson(structured).toByteArray(Charsets.UTF_8).size > MAX_RESULT_BYTES) {
                    McpToolResult.error(
                        "RESULT_TOO_LARGE",
                        "QuickJS 完整结果不能超过 ${MAX_RESULT_BYTES / 1024} KiB"
                    )
                } else {
                    McpToolResult.success(
                        text = "QuickJS 执行成功，结果：$value",
                        structuredContent = structured
                    )
                }
            }
        }
        is QuickJsEvalResult.Failure -> McpToolResult(
            content = listOf(McpContent.Text("QuickJS 执行失败：${kind.name} $message")),
            structuredContent = mapOf(
                "ok" to false,
                "logs" to logs,
                "duration_ms" to durationMs,
                "memory_used_bytes" to memoryUsedBytes,
                "error" to mapOf(
                    "kind" to kind.name,
                    "message" to message,
                    "stack" to stack?.take(MAX_STACK_CHARS)
                )
            ),
            isError = true,
            errorCode = kind.name
        )
    }

    private fun encodeJson(value: Any?): String = when (val wrapped = jsonValue(value)) {
        null, JSONObject.NULL -> "null"
        is JSONObject, is JSONArray -> wrapped.toString()
        is String -> JSONObject.quote(wrapped)
        is Number, is Boolean -> wrapped.toString()
        else -> JSONObject.quote(wrapped.toString())
    }

    private fun decodeQuickJsValue(value: String, valueType: String): Any? = when (valueType) {
        "object" -> runCatching { kotlinJsonValue(JSONTokener(value).nextValue()) }.getOrDefault(value)
        "number" -> value.toLongOrNull() ?: value.toDoubleOrNull() ?: value
        "boolean" -> value.toBooleanStrictOrNull() ?: value
        "null" -> null
        else -> value
    }

    private fun kotlinJsonValue(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> buildMap {
            value.keys().forEach { key -> put(key, kotlinJsonValue(value.get(key))) }
        }
        is JSONArray -> buildList {
            for (index in 0 until value.length()) add(kotlinJsonValue(value.get(index)))
        }
        else -> value
    }

    private fun jsonValue(value: Any?): Any? = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().apply {
            value.forEach { (key, item) ->
                require(key is String) { "JSON object keys must be strings" }
                put(key, jsonValue(item))
            }
        }
        is Iterable<*> -> JSONArray().apply { value.forEach { put(jsonValue(it)) } }
        is Array<*> -> JSONArray().apply { value.forEach { put(jsonValue(it)) } }
        is String, is Number, is Boolean, is JSONObject, is JSONArray -> value
        else -> value.toString()
    }

    private object DisabledAndroidHostDispatcher : QuickJsHostDispatcher {
        override fun dispatch(
            request: QuickJsHostRequest,
            complete: (QuickJsHostResponse) -> Unit
        ): QuickJsHostCall {
            complete(
                QuickJsHostResponse.Failure(
                    code = "HOST_METHOD_DISABLED",
                    message = "android.invoke is disabled in the Agent QuickJS sandbox"
                )
            )
            return QuickJsHostCall {}
        }
    }

    private object DirectExecutor : Executor {
        override fun execute(command: Runnable) = command.run()
    }

    companion object {
        const val TOOL_NAME = "quickjs_execute"
        private const val SUPPORTED_ABI = "arm64-v8a"
        private const val MAX_SOURCE_BYTES = 16 * 1024
        private const val MAX_INPUT_BYTES = 32 * 1024
        private const val MAX_RESULT_BYTES = 64 * 1024
        private const val MAX_STACK_CHARS = 2 * 1024
        private val EXECUTION_OPTIONS = QuickJsEvalOptions(
            timeoutMs = 2_000,
            memoryLimitBytes = 16 * QuickJsEvalOptions.MIB,
            maxStackBytes = 512 * QuickJsEvalOptions.KIB
        )
    }
}
