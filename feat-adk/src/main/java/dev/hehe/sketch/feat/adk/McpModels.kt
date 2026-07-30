package dev.hehe.sketch.feat.adk

/**
 * Transport-neutral subset of MCP used by the Android experiment. The shapes intentionally follow
 * MCP tools/list and tools/call so a remote endpoint can be added without changing the Agent layer.
 */
internal interface McpEndpoint : AutoCloseable {
    val id: String
    val displayName: String

    suspend fun listTools(): List<McpToolDescriptor>

    suspend fun callTool(
        name: String,
        arguments: Map<String, Any?>,
        context: McpCallContext
    ): McpToolResult

    fun resetSession() = Unit

    override fun close() = Unit
}

internal data class McpToolDescriptor(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any?>,
    val annotations: McpToolAnnotations = McpToolAnnotations(),
    val approvalPolicy: McpApprovalPolicy = McpApprovalPolicy.AUTO,
    val available: Boolean = true,
    val unavailableReason: String? = null
)

internal data class McpToolAnnotations(
    val readOnlyHint: Boolean = false,
    val destructiveHint: Boolean = false,
    val idempotentHint: Boolean = false,
    val openWorldHint: Boolean = true
)

internal enum class McpApprovalPolicy {
    AUTO,
    ALWAYS
}

internal data class McpCallContext(
    val functionCallId: String?
)

internal sealed interface McpContent {
    data class Text(val text: String) : McpContent
}

internal data class McpToolResult(
    val content: List<McpContent> = emptyList(),
    val structuredContent: Map<String, Any?>? = null,
    val isError: Boolean = false,
    val errorCode: String? = null
) {
    fun toAdkResponse(): Map<String, Any?> = buildMap {
        put(
            "content",
            content.map { item ->
                when (item) {
                    is McpContent.Text -> mapOf("type" to "text", "text" to item.text)
                }
            }
        )
        structuredContent?.let { put("structuredContent", it) }
        put("isError", isError)
        errorCode?.let { put("errorCode", it) }
    }

    companion object {
        fun success(
            text: String,
            structuredContent: Map<String, Any?>? = null
        ) = McpToolResult(
            content = listOf(McpContent.Text(text)),
            structuredContent = structuredContent
        )

        fun error(code: String, message: String) = McpToolResult(
            content = listOf(McpContent.Text(message)),
            structuredContent = mapOf(
                "ok" to false,
                "error" to mapOf("code" to code, "message" to message)
            ),
            isError = true,
            errorCode = code
        )
    }
}

internal data class CapabilityEntry(
    val endpointId: String,
    val endpointName: String,
    val descriptor: McpToolDescriptor,
    val enabled: Boolean,
    val schemaError: String? = null
) {
    val exposedToAgent: Boolean
        get() = enabled && descriptor.available && schemaError == null
}
