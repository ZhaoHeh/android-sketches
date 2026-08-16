package dev.hehe.sketch.feat.adk

import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class LiteRtAdkMapperTest {
    @Test
    fun convertsAdkFunctionDeclarationToLiteRtToolJson() {
        val json = JsonParser.parseString(
            adkToolDescription(
                FunctionDeclaration(
                    name = "quickjs_execute",
                    description = "Run JavaScript",
                    parameters = Schema(
                        type = Type.OBJECT,
                        properties = mapOf(
                            "code" to Schema(type = Type.STRING, description = "JavaScript source")
                        ),
                        required = listOf("code")
                    )
                )
            )
        ).asJsonObject

        assertEquals("quickjs_execute", json["name"].asString)
        assertEquals("object", json["parameters"].asJsonObject["type"].asString)
        assertEquals(
            "string",
            json["parameters"].asJsonObject["properties"]
                .asJsonObject["code"].asJsonObject["type"].asString
        )
        assertEquals("code", json["parameters"].asJsonObject["required"].asJsonArray[0].asString)
    }
}
