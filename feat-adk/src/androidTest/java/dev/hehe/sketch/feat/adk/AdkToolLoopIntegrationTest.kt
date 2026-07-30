package dev.hehe.sketch.feat.adk

import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdkToolLoopIntegrationTest {
    @Test
    fun runner_executesAutomaticMcpTool() = runBlocking {
        val endpoint = RecordingEndpoint(McpApprovalPolicy.AUTO)
        val tools = AdkMcpToolset(AgentCapabilityRegistry(listOf(endpoint))).getTools()
        val model = TwoTurnModel(RecordingEndpoint.TOOL_NAME)
        val runner = InMemoryRunner(agent = LlmAgent(name = "test-agent", model = model, tools = tools))

        val events = runner.runAsync(
            userId = "user",
            sessionId = "automatic",
            newMessage = userMessage("calculate")
        ).toList()

        assertEquals(1, endpoint.calls)
        assertEquals(2, model.calls)
        assertTrue(events.any { it.functionCalls().any { call -> call.name == RecordingEndpoint.TOOL_NAME } })
        assertTrue(events.flatMap(EventExtensions::responses).any { it.response["isError"] == false })
    }

    @Test
    fun sideEffect_pausesThenResumesOriginalCallExactlyOnce() = runBlocking {
        val endpoint = RecordingEndpoint(McpApprovalPolicy.ALWAYS)
        val tools = AdkMcpToolset(AgentCapabilityRegistry(listOf(endpoint))).getTools()
        val model = TwoTurnModel(RecordingEndpoint.TOOL_NAME)
        val runner = InMemoryRunner(agent = LlmAgent(name = "test-agent", model = model, tools = tools))

        val firstEvents = runner.runAsync(
            userId = "user",
            sessionId = "approved",
            newMessage = userMessage("create it")
        ).toList()
        val pending = firstEvents.flatMap(PendingToolApproval::from).single()
        assertEquals(0, endpoint.calls)
        assertEquals(RecordingEndpoint.TOOL_NAME, pending.toolName)

        val resumed = runner.runAsync(
            userId = "user",
            sessionId = "approved",
            newMessage = confirmationResponse(pending, confirmed = true)
        ).toList()

        assertEquals(1, endpoint.calls)
        assertNotNull(resumed.flatMap(EventExtensions::responses).firstOrNull {
            it.name == RecordingEndpoint.TOOL_NAME
        })
        assertEquals(2, model.calls)
    }

    @Test
    fun rejectedSideEffect_neverCallsEndpoint() = runBlocking {
        val endpoint = RecordingEndpoint(McpApprovalPolicy.ALWAYS)
        val tools = AdkMcpToolset(AgentCapabilityRegistry(listOf(endpoint))).getTools()
        val model = TwoTurnModel(RecordingEndpoint.TOOL_NAME)
        val runner = InMemoryRunner(agent = LlmAgent(name = "test-agent", model = model, tools = tools))
        val pending = runner.runAsync(
            userId = "user",
            sessionId = "rejected",
            newMessage = userMessage("create it")
        )
            .toList()
            .flatMap(PendingToolApproval::from)
            .single()

        runner.runAsync(
            userId = "user",
            sessionId = "rejected",
            newMessage = confirmationResponse(pending, false)
        ).toList()

        assertEquals(0, endpoint.calls)
    }

    private fun userMessage(text: String) = Content(
        role = Role.USER,
        parts = listOf(Part(text = text))
    )

    private fun confirmationResponse(request: PendingToolApproval, confirmed: Boolean) = Content(
        role = Role.USER,
        parts = listOf(
            Part(
                functionResponse = FunctionResponse(
                    name = request.confirmationFunctionName,
                    id = request.confirmationCallId,
                    response = mapOf(
                        "confirmed" to confirmed,
                        "payload" to request.payload,
                        "hint" to request.hint
                    )
                )
            )
        )
    )

    private class TwoTurnModel(private val toolName: String) : Model {
        override val name: String = "scripted-model"
        var calls = 0

        override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> {
            calls += 1
            return if (calls == 1) {
                flowOf(
                    LlmResponse(
                        content = Content(
                            role = Role.MODEL,
                            parts = listOf(
                                Part(
                                    functionCall = FunctionCall(
                                        name = toolName,
                                        args = mapOf("value" to 7),
                                        id = "original-call-1"
                                    )
                                )
                            )
                        )
                    )
                )
            } else {
                flowOf(LlmResponse(content = Content(role = Role.MODEL, parts = listOf(Part(text = "done")))))
            }
        }
    }

    private class RecordingEndpoint(
        private val approval: McpApprovalPolicy
    ) : McpEndpoint {
        override val id = "recording"
        override val displayName = "Recording"
        var calls = 0

        override suspend fun listTools() = listOf(
            McpToolDescriptor(
                name = TOOL_NAME,
                description = "A deterministic generic test tool.",
                inputSchema = McpSchemas.objectSchema(
                    properties = mapOf("value" to McpSchemas.integer("input")),
                    required = listOf("value")
                ),
                approvalPolicy = approval
            )
        )

        override suspend fun callTool(
            name: String,
            arguments: Map<String, Any?>,
            context: McpCallContext
        ): McpToolResult {
            calls += 1
            return McpToolResult.success("ok", mapOf("ok" to true, "value" to arguments["value"]))
        }

        companion object {
            const val TOOL_NAME = "generic_test_tool"
        }
    }

    private object EventExtensions {
        fun responses(event: com.google.adk.kt.events.Event) = event.functionResponses()
    }
}
