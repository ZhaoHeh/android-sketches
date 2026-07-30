package dev.hehe.sketch.feat.adk

internal class AgentCapabilityRegistry(
    private val endpoints: List<McpEndpoint>
) : AutoCloseable {
    private val enabledTools = mutableMapOf<String, Boolean>()

    suspend fun catalog(): List<CapabilityEntry> {
        val entries = endpoints.flatMap { endpoint ->
            endpoint.listTools().map { descriptor ->
                CapabilityEntry(
                    endpointId = endpoint.id,
                    endpointName = endpoint.displayName,
                    descriptor = descriptor,
                    enabled = enabledTools[descriptor.name] ?: true,
                    schemaError = McpSchemaConverter.toAdkSchema(descriptor.inputSchema)
                        .exceptionOrNull()
                        ?.message
                )
            }
        }
        val duplicates = entries.groupBy { it.descriptor.name }.filterValues { it.size > 1 }.keys
        return entries.map { entry ->
            if (entry.descriptor.name in duplicates) {
                entry.copy(schemaError = "Duplicate MCP tool name: ${entry.descriptor.name}")
            } else {
                entry
            }
        }
    }

    suspend fun exposedTools(): List<RegisteredMcpTool> = catalog()
        .filter(CapabilityEntry::exposedToAgent)
        .map { entry ->
            RegisteredMcpTool(
                endpoint = endpoints.first { it.id == entry.endpointId },
                descriptor = entry.descriptor
            )
        }

    fun setEnabled(toolName: String, enabled: Boolean) {
        enabledTools[toolName] = enabled
    }

    fun resetSession() {
        endpoints.forEach(McpEndpoint::resetSession)
    }

    override fun close() {
        endpoints.forEach(McpEndpoint::close)
    }
}

internal data class RegisteredMcpTool(
    val endpoint: McpEndpoint,
    val descriptor: McpToolDescriptor
)
