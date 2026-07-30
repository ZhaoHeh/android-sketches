package dev.hehe.sketch.feat.adk

import com.google.adk.kt.events.Event
import com.google.adk.kt.types.FunctionCall

internal data class PendingToolApproval(
    val confirmationCallId: String,
    val confirmationFunctionName: String,
    val originalCallId: String?,
    val toolName: String,
    val arguments: Map<String, Any>,
    val hint: String?,
    val payload: Any?
) {
    val needsCalendarPermission: Boolean
        get() = toolName == AndroidSystemMcpEndpoint.CALENDAR_TOOL_NAME

    companion object {
        fun from(event: Event): List<PendingToolApproval> = event.functionCalls().mapNotNull { call ->
            if (call.name != FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME) return@mapNotNull null
            val original = call.args[FunctionCall.ORIGINAL_FUNCTION_CALL_KEY] as? Map<*, *>
                ?: return@mapNotNull null
            val confirmation = call.args[FunctionCall.TOOL_CONFIRMATION_KEY] as? Map<*, *>
            val name = original[FunctionCall.NAME_KEY] as? String ?: return@mapNotNull null
            @Suppress("UNCHECKED_CAST")
            val arguments = (original[FunctionCall.ARGS_KEY] as? Map<String, Any>).orEmpty()
            PendingToolApproval(
                confirmationCallId = call.id ?: return@mapNotNull null,
                confirmationFunctionName = call.name.orEmpty(),
                originalCallId = original[FunctionCall.ID_KEY] as? String,
                toolName = name,
                arguments = arguments,
                hint = confirmation?.get("hint") as? String,
                payload = confirmation?.get("payload")
            )
        }
    }
}
