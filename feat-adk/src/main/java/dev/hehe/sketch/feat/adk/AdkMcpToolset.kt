package dev.hehe.sketch.feat.adk

import com.google.adk.kt.agents.ReadonlyContext
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.tools.Toolset
import com.google.adk.kt.types.FunctionDeclaration

internal class AdkMcpToolset(
    private val registry: AgentCapabilityRegistry
) : Toolset {
    override suspend fun getTools(readonlyContext: ReadonlyContext?): List<BaseTool> =
        registry.exposedTools().map(::AdkMcpTool)
}

internal class AdkMcpTool(
    private val registration: RegisteredMcpTool
) : BaseTool(
    name = registration.descriptor.name,
    description = registration.descriptor.description,
    customMetadata = mapOf(
        "mcp.endpoint" to registration.endpoint.id,
        "mcp.readOnlyHint" to registration.descriptor.annotations.readOnlyHint,
        "mcp.destructiveHint" to registration.descriptor.annotations.destructiveHint,
        "mcp.idempotentHint" to registration.descriptor.annotations.idempotentHint,
        "mcp.openWorldHint" to registration.descriptor.annotations.openWorldHint
    )
) {
    override fun declaration(): FunctionDeclaration = FunctionDeclaration(
        name = name,
        description = description,
        parameters = McpSchemaConverter.toAdkSchema(registration.descriptor.inputSchema).getOrThrow()
    )

    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any {
        if (registration.descriptor.approvalPolicy == McpApprovalPolicy.ALWAYS) {
            val confirmation = context.toolConfirmation
            if (confirmation == null) {
                context.requestConfirmation(
                    hint =
                        "${registration.descriptor.description}\n" +
                            "该操作会产生 Android 系统副作用；是否允许本次调用？",
                    payload = mapOf(
                        "endpoint" to registration.endpoint.displayName,
                        "tool" to name,
                        "arguments" to args
                    )
                )
                context.actions.skipSummarization = true
                return mapOf(
                    "error" to
                        "This tool call requires confirmation, please approve or reject."
                )
            }
            if (!confirmation.confirmed) {
                return mapOf("error" to "This tool call is rejected.")
            }
        }
        return registration.endpoint.callTool(
            name = name,
            arguments = args,
            context = McpCallContext(functionCallId = context.functionCallId)
        ).toAdkResponse()
    }
}
