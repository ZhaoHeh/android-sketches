package dev.hehe.sketch.feat.adk

import com.google.adk.kt.events.Event
import com.google.adk.kt.types.FunctionCall

internal object AdkEventFormatter {
    fun visibleText(event: Event): String =
        event.content
            ?.parts
            .orEmpty()
            .filter { it.thought != true }
            .mapNotNull { it.text }
            .joinToString("")

    fun traceLines(event: Event): List<String> = buildList {
        event.functionCalls().forEach { call ->
            if (call.name == FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME) {
                add("APPROVAL PAUSED  ${call.id.orEmpty()}")
            } else {
                add("MODEL → TOOL  ${call.name} ${call.args}")
            }
        }
        event.functionResponses().forEach { response ->
            if (response.name == FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME) {
                add("APPROVAL → ADK  ${response.response["confirmed"]}")
            } else {
                add("TOOL → MODEL  ${response.name} ${response.response}")
                if (response.name == QuickJsMcpEndpoint.TOOL_NAME) {
                    val metrics = response.response["structuredContent"] as? Map<*, *>
                    add(
                        "QUICKJS  duration=${metrics?.get("duration_ms") ?: "?"}ms, " +
                            "memory=${metrics?.get("memory_used_bytes") ?: "?"}B"
                    )
                }
            }
        }
        event.errorMessage?.let { add("ERROR  ${event.errorCode.orEmpty()} $it".trim()) }
        event.usageMetadata?.let { usage ->
            add(
                "TOKENS  prompt=${usage.promptTokenCount ?: "?"}, " +
                    "output=${usage.candidatesTokenCount ?: "?"}, " +
                    "total=${usage.totalTokenCount ?: "?"}"
            )
        }
        if (event.turnComplete) add("TURN COMPLETE")
    }
}
