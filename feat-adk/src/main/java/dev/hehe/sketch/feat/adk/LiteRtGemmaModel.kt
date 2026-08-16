package dev.hehe.sketch.feat.adk

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

internal class LiteRtGemmaModel(
    private val modelFile: File,
    private val cacheDir: File
) : Model, AutoCloseable {
    override val name: String = MODEL_NAME

    @Volatile
    private var engine: Engine? = null

    @Volatile
    private var activeConversation: Conversation? = null

    override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> = flow {
        require(request.contents.isNotEmpty()) { "ADK request has no messages" }
        val conversation = engine().createConversation(
            ConversationConfig(
                systemInstruction = LiteRtAdkMapper.systemInstruction(request),
                initialMessages = request.contents.dropLast(1).map(LiteRtAdkMapper::message),
                tools = LiteRtAdkMapper.tools(request),
                samplerConfig = SamplerConfig(
                    topK = request.config.topK ?: 64,
                    topP = (request.config.topP ?: 0.95f).toDouble(),
                    temperature = (request.config.temperature ?: 1.0f).toDouble()
                ),
                automaticToolCalling = false
            )
        )
        activeConversation = conversation
        try {
            val input = LiteRtAdkMapper.message(request.contents.last())
            if (stream) {
                val fullText = StringBuilder()
                val calls = mutableListOf<com.google.ai.edge.litertlm.ToolCall>()
                conversation.sendMessageAsync(input).collect { message ->
                    val text = message.contents.contents
                        .filterIsInstance<com.google.ai.edge.litertlm.Content.Text>()
                        .joinToString("") { it.text }
                    if (text.isNotEmpty()) {
                        fullText.append(text)
                        emit(
                            LiteRtAdkMapper.response(
                                LiteRtAdkMapper.finalMessage(text, emptyList()),
                                partial = true
                            )
                        )
                    }
                    calls += message.toolCalls
                }
                emit(
                    LiteRtAdkMapper.response(
                        LiteRtAdkMapper.finalMessage(fullText.toString(), calls),
                        partial = false
                    )
                )
            } else {
                emit(
                    LiteRtAdkMapper.response(
                        conversation.sendMessage(input),
                        partial = false
                    )
                )
            }
        } catch (cancelled: CancellationException) {
            conversation.cancelProcess()
            throw cancelled
        } finally {
            activeConversation = null
            conversation.close()
        }
    }.flowOn(Dispatchers.Default)

    override fun close() {
        activeConversation?.cancelProcess()
        synchronized(this) {
            engine?.close()
            engine = null
        }
    }

    @Synchronized
    private fun engine(): Engine {
        engine?.let { return it }
        require(modelFile.isFile) { "本地 Gemma 模型尚未下载" }
        cacheDir.mkdirs()
        val initialized = runCatching { initialize(Backend.GPU()) }
            .getOrElse { initialize(Backend.CPU()) }
        engine = initialized
        return initialized
    }

    private fun initialize(backend: Backend): Engine {
        val candidate = Engine(
            EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = backend,
                maxNumTokens = MAX_NUM_TOKENS,
                cacheDir = cacheDir.absolutePath
            )
        )
        return try {
            candidate.initialize()
            candidate
        } catch (error: Throwable) {
            runCatching { candidate.takeIf(Engine::isInitialized)?.close() }
            throw error
        }
    }

    companion object {
        const val MODEL_NAME = "gemma-4-e2b-local"
        private const val MAX_NUM_TOKENS = 4_096
    }
}
