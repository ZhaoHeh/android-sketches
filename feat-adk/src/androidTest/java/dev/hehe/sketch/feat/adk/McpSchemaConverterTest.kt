package dev.hehe.sketch.feat.adk

import com.google.adk.kt.types.Type
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpSchemaConverterTest {
    @Test
    fun convertsSupportedObjectArrayAndEnumSchema() {
        val schema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "mode" to mapOf("type" to "string", "enum" to listOf("sum", "average")),
                "values" to mapOf("type" to "array", "items" to mapOf("type" to "number"))
            ),
            "required" to listOf("mode", "values")
        )

        val converted = McpSchemaConverter.toAdkSchema(schema).getOrThrow()

        assertEquals(Type.OBJECT, converted.type)
        assertEquals(listOf("sum", "average"), converted.properties?.get("mode")?.enum)
        assertEquals(Type.NUMBER, converted.properties?.get("values")?.items?.type)
    }

    @Test
    fun rejectsUnsupportedKeywordAndUnknownRequiredProperty() {
        assertTrue(
            McpSchemaConverter.toAdkSchema(mapOf("type" to "string", "pattern" to ".+"))
                .isFailure
        )
        assertTrue(
            McpSchemaConverter.toAdkSchema(
                McpSchemas.objectSchema(emptyMap(), required = listOf("missing"))
            ).isFailure
        )
    }

    @Test
    fun registryDetectsConflictsAndHonorsEnableState() = runBlocking {
        val first = DescriptorEndpoint("first", "same_tool")
        val second = DescriptorEndpoint("second", "same_tool")
        val duplicateRegistry = AgentCapabilityRegistry(listOf(first, second))
        assertTrue(duplicateRegistry.catalog().all { it.schemaError?.contains("Duplicate") == true })
        assertTrue(duplicateRegistry.exposedTools().isEmpty())

        val registry = AgentCapabilityRegistry(listOf(DescriptorEndpoint("only", "toggle_tool")))
        assertEquals(1, registry.exposedTools().size)
        registry.setEnabled("toggle_tool", false)
        assertFalse(registry.catalog().single().enabled)
        assertTrue(registry.exposedTools().isEmpty())
    }

    @Test
    fun resultConversionUsesMcpFieldNamesAndStructuredError() {
        val success = McpToolResult.success("done", mapOf("value" to 42)).toAdkResponse()
        val error = McpToolResult.error("FAILED", "nope").toAdkResponse()

        assertEquals(false, success["isError"])
        assertEquals(mapOf("value" to 42), success["structuredContent"])
        assertEquals(true, error["isError"])
        assertEquals("FAILED", error["errorCode"])
    }

    private class DescriptorEndpoint(
        override val id: String,
        private val toolName: String
    ) : McpEndpoint {
        override val displayName = id
        override suspend fun listTools() = listOf(
            McpToolDescriptor(toolName, "test", McpSchemas.objectSchema(emptyMap()))
        )
        override suspend fun callTool(
            name: String,
            arguments: Map<String, Any?>,
            context: McpCallContext
        ) = McpToolResult.success("ok")
    }
}
