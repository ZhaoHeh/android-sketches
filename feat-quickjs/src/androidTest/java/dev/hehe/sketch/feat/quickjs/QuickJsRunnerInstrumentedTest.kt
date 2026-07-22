package dev.hehe.sketch.feat.quickjs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class QuickJsRunnerInstrumentedTest {
    private lateinit var runner: QuickJsRunner

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        runner = QuickJsRunner(
            context = context,
            hostDispatcher = AndroidQuickJsHostDispatcher(context),
            resultExecutor = Executor(Runnable::run)
        )
    }

    @After
    fun tearDown() {
        runner.close()
    }

    @Test
    fun evaluatesUtf8AndEmbeddedNul() {
        val result = evaluate("'你好 😀 ' + String.fromCharCode(0) + ' QuickJS'")
        val success = result as QuickJsEvalResult.Success
        assertTrue(success.value.startsWith("你好 😀"))
        assertTrue(success.value.contains('\u0000'))
    }

    @Test
    fun resolvesAndroidPromiseAndCapturesConsole() {
        val result = evaluate(
            "console.log('before'); android.delayEcho({answer: 42}, 20);"
        ) as QuickJsEvalResult.Success
        assertTrue(result.value.contains("\"answer\":42"))
        assertEquals(listOf("before"), result.logs)
    }

    @Test
    fun interruptsInfiniteLoopAndRecovers() {
        val timeout = evaluate(
            "while (true) {}",
            QuickJsEvalOptions(timeoutMs = 200)
        ) as QuickJsEvalResult.Failure
        assertEquals(QuickJsFailureKind.TIMEOUT, timeout.kind)

        val recovered = evaluate("40 + 2") as QuickJsEvalResult.Success
        assertEquals("42", recovered.value)
    }

    @Test
    fun cancelsPendingHostCall() {
        val latch = CountDownLatch(1)
        var result: QuickJsEvalResult? = null
        val execution = runner.run(
            "android.delayEcho('late', 10000)",
            QuickJsEvalOptions(timeoutMs = 30_000)
        ) {
            result = it
            latch.countDown()
        } ?: error("runner was busy")
        Thread.sleep(50)
        execution.cancel()
        assertTrue(latch.await(3, TimeUnit.SECONDS))
        assertEquals(QuickJsFailureKind.CANCELLED, (result as QuickJsEvalResult.Failure).kind)
    }

    @Test
    fun supportsFiftyIsolatedRuns() {
        repeat(50) { index ->
            val result = evaluate("$index + 1") as QuickJsEvalResult.Success
            assertEquals((index + 1).toString(), result.value)
        }
    }

    private fun evaluate(
        source: String,
        options: QuickJsEvalOptions = QuickJsEvalOptions()
    ): QuickJsEvalResult {
        val latch = CountDownLatch(1)
        var result: QuickJsEvalResult? = null
        runner.run(source, options) {
            result = it
            latch.countDown()
        } ?: error("runner was busy")
        assertTrue("QuickJS execution timed out in test", latch.await(5, TimeUnit.SECONDS))
        return requireNotNull(result)
    }
}
