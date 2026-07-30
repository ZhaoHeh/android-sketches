package dev.hehe.sketch.feat.adk

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuickJsMcpEndpointInstrumentedTest {
    private lateinit var endpoint: QuickJsMcpEndpoint

    @Before
    fun setUp() {
        assumeTrue(Build.SUPPORTED_ABIS.contains("arm64-v8a"))
        endpoint = QuickJsMcpEndpoint(ApplicationProvider.getApplicationContext<Context>())
    }

    @Test
    fun executesJsonInputAndReturnsMetrics() = runBlocking {
        val result = endpoint.callTool(
            QuickJsMcpEndpoint.TOOL_NAME,
            mapOf(
                "code" to "input.values.reduce((sum, value) => sum + value, 0)",
                "input" to mapOf("values" to listOf(12.5, 8, 21.5, 5))
            ),
            McpCallContext("quickjs-success")
        )

        assertFalse(result.isError)
        assertEquals(true, result.structuredContent?.get("ok"))
        assertEquals(47L, result.structuredContent?.get("value"))
        assertTrue((result.structuredContent?.get("duration_ms") as Long) >= 0)
    }

    @Test
    fun reportsExceptionTimeoutAndDisabledAndroidBridge() = runBlocking {
        val exception = call("throw new Error('boom')")
        val timeout = call("while (true) {}")
        val memory = call("new ArrayBuffer(32 * 1024 * 1024)")
        val androidBridge = call("android.invoke('getDeviceInfo', {})")

        assertTrue(exception.isError)
        assertEquals("TIMEOUT", timeout.errorCode)
        assertTrue(memory.isError)
        assertTrue(androidBridge.isError)
        assertTrue(androidBridge.content.filterIsInstance<McpContent.Text>().single().text.contains("disabled"))
    }

    @Test
    fun enforcesSourceInputAndResultLimits() = runBlocking {
        val source = call(" ".repeat(16 * 1024 + 1))
        val input = endpoint.callTool(
            QuickJsMcpEndpoint.TOOL_NAME,
            mapOf("code" to "input", "input" to mapOf("text" to "x".repeat(33 * 1024))),
            McpCallContext("large-input")
        )
        val result = call("'x'.repeat(70 * 1024)")

        assertEquals("SOURCE_TOO_LARGE", source.errorCode)
        assertEquals("INPUT_TOO_LARGE", input.errorCode)
        assertEquals("RESULT_TOO_LARGE", result.errorCode)
    }

    @Test
    fun coroutineCancellationCancelsNativeExecutionAndRunnerCloses() = runBlocking {
        val job = launch { call("while (true) {}") }
        delay(50)
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)

        val recovered = call("40 + 2")
        assertFalse(recovered.isError)
        assertEquals("42", recovered.structuredContent?.get("value"))
    }

    private suspend fun call(code: String) = endpoint.callTool(
        QuickJsMcpEndpoint.TOOL_NAME,
        mapOf("code" to code),
        McpCallContext("quickjs-test")
    )
}
