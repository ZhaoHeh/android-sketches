package dev.hehe.sketch.feat.adk

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AndroidSystemMcpEndpointTest {
    @Test
    fun validatesArgumentsBeforeCallingBackends() = runBlocking {
        var calendarCalls = 0
        var timerCalls = 0
        val endpoint = AndroidSystemMcpEndpoint(
            calendarBackend = CalendarEventBackend {
                calendarCalls += 1
                McpToolResult.success("created")
            },
            timerBackend = TimerBackend {
                timerCalls += 1
                McpToolResult.success("started")
            }
        )

        val invalidTime = endpoint.callTool(
            AndroidSystemMcpEndpoint.CALENDAR_TOOL_NAME,
            mapOf("title" to "sync", "start_time" to "tomorrow", "duration_minutes" to 30),
            McpCallContext("calendar-invalid")
        )
        val invalidTimer = endpoint.callTool(
            AndroidSystemMcpEndpoint.TIMER_TOOL_NAME,
            mapOf("duration_seconds" to 0),
            McpCallContext("timer-invalid")
        )

        assertEquals("INVALID_ARGUMENT", invalidTime.errorCode)
        assertEquals("INVALID_ARGUMENT", invalidTimer.errorCode)
        assertEquals(0, calendarCalls)
        assertEquals(0, timerCalls)
    }

    @Test
    fun sameFunctionCallIdReturnsCachedResult() = runBlocking {
        var calls = 0
        val endpoint = AndroidSystemMcpEndpoint(
            calendarBackend = CalendarEventBackend { McpToolResult.success("unused") },
            timerBackend = TimerBackend {
                calls += 1
                McpToolResult.success("started-$calls")
            }
        )
        val context = McpCallContext("stable-call-id")

        val first = endpoint.callTool(
            AndroidSystemMcpEndpoint.TIMER_TOOL_NAME,
            mapOf("duration_seconds" to 90),
            context
        )
        val second = endpoint.callTool(
            AndroidSystemMcpEndpoint.TIMER_TOOL_NAME,
            mapOf("duration_seconds" to 90),
            context
        )

        assertSame(first, second)
        assertEquals(1, calls)
    }

    @Test
    fun backendFailuresAreReturnedWithoutBeingRewritten() = runBlocking {
        val endpoint = AndroidSystemMcpEndpoint(
            calendarBackend = CalendarEventBackend {
                McpToolResult.error("NO_WRITABLE_CALENDAR", "none")
            },
            timerBackend = TimerBackend {
                McpToolResult.error("HANDLER_NOT_FOUND", "none")
            }
        )

        val calendar = endpoint.callTool(
            AndroidSystemMcpEndpoint.CALENDAR_TOOL_NAME,
            mapOf(
                "title" to "sync",
                "start_time" to "2026-07-30T15:00:00+08:00",
                "duration_minutes" to 30
            ),
            McpCallContext("calendar-failure")
        )
        val timer = endpoint.callTool(
            AndroidSystemMcpEndpoint.TIMER_TOOL_NAME,
            mapOf("duration_seconds" to 90),
            McpCallContext("timer-failure")
        )

        assertEquals("NO_WRITABLE_CALENDAR", calendar.errorCode)
        assertEquals("HANDLER_NOT_FOUND", timer.errorCode)
    }
}
