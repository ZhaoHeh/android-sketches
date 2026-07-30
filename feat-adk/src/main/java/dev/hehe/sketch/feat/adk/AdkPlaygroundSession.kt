package dev.hehe.sketch.feat.adk

import android.content.Context

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.StreamingMode
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal class AdkPlaygroundSession(context: Context) : AutoCloseable {
    private val sessionService = InMemorySessionService()
    private val registry = AgentCapabilityRegistry(
        listOf(
            QuickJsMcpEndpoint(context.applicationContext),
            AndroidSystemMcpEndpoint(context.applicationContext)
        )
    )
    private val mcpToolset = AdkMcpToolset(registry)
    private var runnerConfig: RunnerConfig? = null
    private var runner: InMemoryRunner? = null
    private var sessionNumber = 1

    fun run(
        apiKey: String,
        modelName: String,
        prompt: String,
        streaming: Boolean
    ): Flow<Event> = flow {
        val config = RunnerConfig(apiKey = apiKey, modelName = modelName)
        val activeRunner = if (runner == null || runnerConfig != config) {
            createRunner(config).also {
                runner = it
                runnerConfig = config
            }
        } else {
            checkNotNull(runner)
        }

        emitAll(activeRunner.runAsync(
            userId = USER_ID,
            sessionId = "$SESSION_PREFIX-$sessionNumber",
            newMessage = Content(
                role = Role.USER,
                parts = listOf(Part(text = prompt))
            ),
            runConfig = RunConfig(
                streamingMode = if (streaming) StreamingMode.SSE else StreamingMode.NONE,
                maxLlmCalls = 6
            )
        ))
    }

    fun resumeConfirmation(
        apiKey: String,
        modelName: String,
        request: PendingToolApproval,
        confirmed: Boolean,
        streaming: Boolean
    ): Flow<Event> = flow {
        val config = RunnerConfig(apiKey = apiKey, modelName = modelName)
        val activeRunner = if (runner == null || runnerConfig != config) {
            createRunner(config).also {
                runner = it
                runnerConfig = config
            }
        } else {
            checkNotNull(runner)
        }
        emitAll(activeRunner.runAsync(
            userId = USER_ID,
            sessionId = "$SESSION_PREFIX-$sessionNumber",
            newMessage = Content(
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
            ),
            runConfig = RunConfig(
                streamingMode = if (streaming) StreamingMode.SSE else StreamingMode.NONE,
                maxLlmCalls = 6
            )
        ))
    }

    suspend fun capabilityCatalog(): List<CapabilityEntry> = registry.catalog()

    fun setToolEnabled(name: String, enabled: Boolean) {
        registry.setEnabled(name, enabled)
        runner = null
        runnerConfig = null
    }

    fun startNewSession(): Int {
        sessionNumber += 1
        registry.resetSession()
        return sessionNumber
    }

    fun clearCredentials() {
        runner = null
        runnerConfig = null
    }

    override fun close() {
        clearCredentials()
        registry.close()
    }

    private suspend fun createRunner(config: RunnerConfig): InMemoryRunner {
        // ADK 0.6.0 resolves confirmation resumes from LlmAgent.tools, not toolsets.
        // Materialize this snapshot and rebuild it whenever the capability catalog changes.
        val tools = mcpToolset.getTools()
        val agent = LlmAgent(
            name = AGENT_NAME,
            description = "A general Android agent backed by local MCP-style capabilities.",
            model = Gemini(name = config.modelName, apiKey = config.apiKey),
            instruction = Instruction.Provider {
                val now = ZonedDateTime.now()
                val catalog = registry.catalog().joinToString("\n") { entry ->
                    val state = if (entry.exposedToAgent) "available" else "unavailable"
                    "- ${entry.descriptor.name}: ${entry.descriptor.description} [$state]"
                }
                Content.fromText(
                    text = """
                        你是一个运行在 Android 手机上的通用 Agent。
                        根据用户任务自主选择工具；不要为了展示工具而调用无关工具。
                        工具不足或不可用时明确说明，不得声称已完成未执行的操作。
                        时间含义不明确时先追问；当前时间是 ${now.format(DateTimeFormatter.ISO_ZONED_DATE_TIME)}，时区是 ${now.zone.id}。
                        日历和计时器等副作用操作必须等待用户逐次确认。成功后不得重复调用同一操作。
                        QuickJS 是纯计算沙箱；需要处理或统计 JSON 数据时可以生成 JavaScript 并调用它。

                        当前能力：
                        $catalog
                    """.trimIndent(),
                    role = Role.SYSTEM
                )
            },
            tools = tools,
            maxSteps = 8
        )
        return InMemoryRunner(
            agent = agent,
            appName = APP_NAME,
            sessionService = sessionService
        )
    }

    private data class RunnerConfig(
        val apiKey: String,
        val modelName: String
    )

    companion object {
        const val AGENT_NAME = "android_adk_agent"
        private const val APP_NAME = "android-sketches-adk"
        private const val USER_ID = "local-user"
        private const val SESSION_PREFIX = "adk-playground"
    }
}
