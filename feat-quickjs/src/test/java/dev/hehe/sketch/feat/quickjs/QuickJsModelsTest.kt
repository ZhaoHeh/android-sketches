package dev.hehe.sketch.feat.quickjs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickJsModelsTest {
    @Test
    fun defaultOptionsAreValid() {
        assertNull(QuickJsEvalOptions().validationError())
    }

    @Test
    fun displayValuesConvertToBytes() {
        val options = QuickJsEvalOptions.fromDisplayValues(500, 8, 256)
        assertEquals(500, options.timeoutMs)
        assertEquals(8 * QuickJsEvalOptions.MIB, options.memoryLimitBytes)
        assertEquals(256 * QuickJsEvalOptions.KIB, options.maxStackBytes)
    }

    @Test
    fun optionBoundsAreRejected() {
        assertTrue(QuickJsEvalOptions(timeoutMs = 99).validationError()!!.contains("超时"))
        assertTrue(
            QuickJsEvalOptions(memoryLimitBytes = 3 * QuickJsEvalOptions.MIB)
                .validationError()!!.contains("内存")
        )
        assertTrue(
            QuickJsEvalOptions(maxStackBytes = 100 * QuickJsEvalOptions.KIB)
                .validationError()!!.contains("栈")
        )
    }

    @Test
    fun expectedOutcomesMatchResults() {
        val success = QuickJsEvalResult.Success("{\"answer\":42}", "object", emptyList(), 1, 10)
        val failure = QuickJsEvalResult.Failure(
            QuickJsFailureKind.TIMEOUT,
            "Execution timed out",
            null,
            emptyList(),
            400,
            10
        )
        assertTrue(QuickJsExpectedOutcome(true, containsText = "answer").matches(success))
        assertTrue(QuickJsExpectedOutcome(false, QuickJsFailureKind.TIMEOUT).matches(failure))
        assertFalse(QuickJsExpectedOutcome(true, containsText = "missing").matches(success))
    }

    @Test
    fun hostFailuresHaveStableJsonShape() {
        val failure = QuickJsHostResponse.Failure("METHOD_NOT_FOUND", "missing")
        assertEquals("METHOD_NOT_FOUND", failure.code)
        assertEquals("missing", failure.message)
    }
}
