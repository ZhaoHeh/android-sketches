package dev.hehe.sketch.feat.adk

import android.content.Context

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.StreamingMode
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.models.Model
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import dev.hehe.sketch.feat.gemma.GemmaModelStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal sealed interface AdkModelConfig {
    data class Cloud(val apiKey: String, val modelName: String) : AdkModelConfig
    data object LocalGemma : AdkModelConfig
}

internal class AdkPlaygroundSession(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val sessionService = InMemorySessionService()
    private val gemmaStore = GemmaModelStore(appContext)
    private val registry = AgentCapabilityRegistry(
        listOf(
            QuickJsMcpEndpoint(context.applicationContext),
            AndroidSystemMcpEndpoint(context.applicationContext)
        )
    )
    private val mcpToolset = AdkMcpToolset(registry)
    private var runnerConfig: AdkModelConfig? = null
    private var runner: InMemoryRunner? = null
    private var localModel: LiteRtGemmaModel? = null
    private var sessionNumber = 1

    fun run(
        modelConfig: AdkModelConfig,
        prompt: String,
        streaming: Boolean
    ): Flow<Event> = flow {
        val activeRunner = runnerFor(modelConfig)

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
        modelConfig: AdkModelConfig,
        request: PendingToolApproval,
        confirmed: Boolean,
        streaming: Boolean
    ): Flow<Event> = flow {
        val activeRunner = runnerFor(modelConfig)
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

    fun isLocalModelReady(): Boolean = gemmaStore.isModelReady()

    fun setToolEnabled(name: String, enabled: Boolean) {
        registry.setEnabled(name, enabled)
        clearRunner()
    }

    fun startNewSession(): Int {
        sessionNumber += 1
        registry.resetSession()
        return sessionNumber
    }

    fun clearCredentials() {
        clearRunner()
    }

    private fun clearRunner() {
        runner = null
        runnerConfig = null
        localModel?.close()
        localModel = null
    }

    override fun close() {
        clearCredentials()
        registry.close()
    }

    private suspend fun runnerFor(config: AdkModelConfig): InMemoryRunner {
        if (runner != null && runnerConfig == config) return checkNotNull(runner)
        clearRunner()
        return createRunner(config).also {
            runner = it
            runnerConfig = config
        }
    }

    private suspend fun createRunner(config: AdkModelConfig): InMemoryRunner {
        // ADK 0.6.0 resolves confirmation resumes from LlmAgent.tools, not toolsets.
        // Materialize this snapshot and rebuild it whenever the capability catalog changes.
        val tools = mcpToolset.getTools()
        val model: Model = when (config) {
            is AdkModelConfig.Cloud -> Gemini(name = config.modelName, apiKey = config.apiKey)
            AdkModelConfig.LocalGemma -> {
                check(gemmaStore.isModelReady()) { "请先在 feat-gemma 下载 Gemma 4 E2B 模型" }
                LiteRtGemmaModel(
                    modelFile = gemmaStore.modelFile,
                    cacheDir = java.io.File(appContext.cacheDir, "litertlm/adk-gemma-4-e2b")
                ).also { localModel = it }
            }
        }
        val agent = LlmAgent(
            name = AGENT_NAME,
            description = "A general Android agent backed by local MCP-style capabilities.",
            model = model,
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

    companion object {
        const val AGENT_NAME = "android_adk_agent"
        private const val APP_NAME = "android-sketches-adk"
        private const val USER_ID = "local-user"
        private const val SESSION_PREFIX = "adk-playground"
    }
}
