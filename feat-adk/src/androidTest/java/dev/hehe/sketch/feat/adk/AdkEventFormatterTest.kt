package dev.hehe.sketch.feat.adk

import com.google.adk.kt.events.Event
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.UsageMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdkEventFormatterTest {
    @Test
    fun visibleText_ignoresThoughtParts() {
        val event = Event(
            author = "agent",
            content = Content(
                role = Role.MODEL,
                parts = listOf(
                    Part(text = "hidden", thought = true),
                    Part(text = "hello "),
                    Part(text = "world")
                )
            )
        )

        assertEquals("hello world", AdkEventFormatter.visibleText(event))
    }

    @Test
    fun traceLines_exposesToolLoopAndTokenUsage() {
        val event = Event(
            author = "agent",
            content = Content(
                role = Role.MODEL,
                parts = listOf(
                    Part(
                        functionCall = FunctionCall(
                            name = QuickJsMcpEndpoint.TOOL_NAME,
                            args = mapOf("left" to 17, "right" to 23)
                        )
                    ),
                    Part(
                        functionResponse = FunctionResponse(
                            name = QuickJsMcpEndpoint.TOOL_NAME,
                            response = mapOf("product" to 391)
                        )
                    )
                )
            ),
            usageMetadata = UsageMetadata(
                promptTokenCount = 12,
                candidatesTokenCount = 4,
                totalTokenCount = 16
            )
        )

        val lines = AdkEventFormatter.traceLines(event)

        assertTrue(lines.any { it.startsWith("MODEL → TOOL") })
        assertTrue(lines.any { it.startsWith("TOOL → MODEL") })
        assertTrue(lines.any { it == "TOKENS  prompt=12, output=4, total=16" })
    }
}
