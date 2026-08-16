package dev.hehe.sketch.feat.adk

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.types.Content as AdkContent
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.FunctionCall as AdkFunctionCall
import com.google.adk.kt.types.Part as AdkPart
import com.google.adk.kt.types.Role as AdkRole
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.google.ai.edge.litertlm.Content as LiteContent
import com.google.ai.edge.litertlm.Contents as LiteContents
import com.google.ai.edge.litertlm.Message as LiteMessage
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ToolCall as LiteToolCall
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import com.google.gson.Gson

internal object LiteRtAdkMapper {
    fun tools(request: LlmRequest): List<ToolProvider> =
        request.config.tools.orEmpty()
            .flatMap { it.functionDeclarations.orEmpty() }
            .map { declaration ->
                tool(object : OpenApiTool {
                    override fun getToolDescriptionJsonString(): String = adkToolDescription(declaration)

                    override fun execute(paramsJsonString: String): String =
                        error("ADK owns tool execution")
                })
            }

    fun message(content: AdkContent): LiteMessage {
        val text = content.parts.orEmpty().mapNotNull { part ->
            part.text?.let(LiteContent::Text)
        }
        val calls = content.parts.orEmpty().mapNotNull { part ->
            part.functionCall?.let { call -> LiteToolCall(call.name, call.args) }
        }
        val responses = content.parts.orEmpty().mapNotNull { part ->
            part.functionResponse?.let { response ->
                LiteContent.ToolResponse(response.name, response.response)
            }
        }

        return when {
            responses.isNotEmpty() -> LiteMessage.tool(LiteContents.of(responses))
            content.role == AdkRole.MODEL -> LiteMessage.model(LiteContents.of(text), calls)
            content.role == AdkRole.SYSTEM -> LiteMessage.system(LiteContents.of(text))
            else -> LiteMessage.user(LiteContents.of(text))
        }
    }

    fun response(message: LiteMessage, partial: Boolean): LlmResponse {
        val parts = buildList {
            message.contents.contents.forEach { content ->
                if (content is LiteContent.Text && content.text.isNotEmpty()) {
                    add(AdkPart(text = content.text))
                }
            }
            message.toolCalls.forEach { call ->
                add(
                    AdkPart(
                        functionCall = AdkFunctionCall(
                            name = call.name,
                            args = call.arguments,
                            id = "gemma-${java.util.UUID.randomUUID()}"
                        )
                    )
                )
            }
        }
        return LlmResponse(
            content = AdkContent(role = AdkRole.MODEL, parts = parts),
            partial = partial,
            modelVersion = LiteRtGemmaModel.MODEL_NAME
        )
    }

    fun finalMessage(text: String, calls: List<LiteToolCall>): LiteMessage =
        LiteMessage.model(
            contents = LiteContents.of(text),
            toolCalls = calls
        )

    fun systemInstruction(request: LlmRequest): LiteContents? {
        val text = request.config.systemInstruction
            ?.parts
            .orEmpty()
            .mapNotNull { it.text }
            .joinToString("")
        return text.takeIf(String::isNotBlank)?.let(LiteContents::of)
    }

}

internal fun adkToolDescription(declaration: FunctionDeclaration): String = Gson().toJson(
    mapOf(
        "name" to declaration.name,
        "description" to declaration.description,
        "parameters" to declaration.parameters.toJsonSchema()
    )
)

private fun Schema?.toJsonSchema(): Map<String, Any?> {
    if (this == null) return mapOf("type" to "object", "properties" to emptyMap<String, Any?>())
    return buildMap {
        put("type", type.toJsonType())
        properties?.let { values ->
            put("properties", values.mapValues { (_, schema) -> schema.toJsonSchema() })
        }
        items?.let { put("items", it.toJsonSchema()) }
        required?.let { put("required", it) }
        description?.let { put("description", it) }
        enum?.let { put("enum", it) }
    }
}

private fun Type?.toJsonType(): String = when (this) {
    Type.STRING -> "string"
    Type.NUMBER -> "number"
    Type.INTEGER -> "integer"
    Type.BOOLEAN -> "boolean"
    Type.ARRAY -> "array"
    Type.OBJECT, null -> "object"
    else -> error("Unsupported ADK schema type: $this")
}
