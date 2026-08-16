package dev.hehe.sketch.feat.gemma

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.BenchmarkInfo
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File

internal data class GemmaLoadResult(
    val backend: String,
    val initSeconds: Double,
    val gpuFailure: String? = null
)

@OptIn(ExperimentalApi::class)
internal class GemmaLocalSession(context: Context) : AutoCloseable {
    private val cacheDir = File(context.cacheDir, "litertlm/gemma-4-e2b")

    @Volatile
    private var engine: Engine? = null

    @Volatile
    private var conversation: Conversation? = null

    @Volatile
    var backendName: String? = null
        private set

    suspend fun load(modelFile: File): GemmaLoadResult = withContext(Dispatchers.Default) {
        try {
            require(modelFile.isFile && modelFile.length() == GemmaModelSpec.SIZE_BYTES) {
                "模型文件不存在或大小不正确"
            }
            unloadInternal()
            cacheDir.mkdirs()

            val gpuResult = runCatching { initialize(modelFile, Backend.GPU(), "GPU") }
            val result = gpuResult.getOrNull() ?: run {
                val gpuFailure = gpuResult.exceptionOrNull()?.message
                    ?: gpuResult.exceptionOrNull()?.javaClass?.simpleName
                    ?: "未知错误"
                initialize(modelFile, Backend.CPU(), "CPU").copy(gpuFailure = gpuFailure)
            }
            currentCoroutineContext().ensureActive()
            result
        } catch (error: Throwable) {
            unloadInternal()
            throw error
        }
    }

    suspend fun newConversation() = withContext(Dispatchers.Default) {
        val activeEngine = engine ?: error("模型尚未加载")
        conversation?.close()
        conversation = activeEngine.createConversation(conversationConfig())
    }

    fun send(prompt: String): Flow<String> = flow {
        val activeConversation = conversation ?: error("模型尚未加载")
        activeConversation.sendMessageAsync(prompt).collect { message ->
            val text = message.textContent()
            if (text.isNotEmpty()) emit(text)
        }
    }

    fun cancelGeneration() {
        conversation?.cancelProcess()
    }

    fun benchmarkInfo(): BenchmarkInfo? = conversation?.getBenchmarkInfo()

    fun isLoaded(): Boolean = engine?.isInitialized() == true && conversation != null

    suspend fun unload() = withContext(Dispatchers.Default) {
        unloadInternal()
    }

    override fun close() {
        cancelGeneration()
        unloadInternal()
    }

    private fun initialize(modelFile: File, backend: Backend, name: String): GemmaLoadResult {
        var candidate: Engine? = null
        var candidateConversation: Conversation? = null
        try {
            ExperimentalFlags.enableBenchmark = true
            val activeEngine = Engine(
                EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = backend,
                    maxNumTokens = MAX_NUM_TOKENS,
                    cacheDir = cacheDir.absolutePath
                )
            )
            candidate = activeEngine
            activeEngine.initialize()
            val activeConversation = activeEngine.createConversation(conversationConfig())
            candidateConversation = activeConversation
            val initSeconds = activeConversation.getBenchmarkInfo().initTimeInSecond
            engine = activeEngine
            conversation = activeConversation
            backendName = name
            return GemmaLoadResult(
                backend = name,
                initSeconds = initSeconds
            )
        } catch (error: Throwable) {
            Log.e(TAG, "$name initialization failed", error)
            runCatching { candidateConversation?.close() }
            runCatching { candidate?.takeIf(Engine::isInitialized)?.close() }
            throw error
        }
    }

    private fun conversationConfig() = ConversationConfig(
        systemInstruction = Contents.of("你是一个完全在 Android 设备上离线运行的通用助手。"),
        samplerConfig = SamplerConfig(
            topK = 64,
            topP = 0.95,
            temperature = 1.0
        )
    )

    private fun unloadInternal() {
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null
        backendName = null
    }

    private fun Message.textContent(): String = contents.contents
        .mapNotNull { content -> (content as? Content.Text)?.text }
        .joinToString(separator = "")

    private companion object {
        const val TAG = "GemmaLocalSession"
        const val MAX_NUM_TOKENS = 4_096
    }
}
