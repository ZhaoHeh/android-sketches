package dev.hehe.sketch.feat.quickjs

data class QuickJsExpectedOutcome(
    val success: Boolean,
    val failureKind: QuickJsFailureKind? = null,
    val containsText: String? = null
) {
    fun matches(result: QuickJsEvalResult): Boolean {
        if (success) {
            val value = (result as? QuickJsEvalResult.Success)?.value ?: return false
            return containsText == null || value.contains(containsText, ignoreCase = true)
        }
        val failure = result as? QuickJsEvalResult.Failure ?: return false
        return (failureKind == null || failure.kind == failureKind) &&
            (containsText == null ||
                failure.message.contains(containsText, ignoreCase = true) ||
                failure.stack?.contains(containsText, ignoreCase = true) == true)
    }
}

data class QuickJsValidationCase(
    val id: String,
    val title: String,
    val source: String,
    val options: QuickJsEvalOptions = QuickJsEvalOptions(),
    val expected: QuickJsExpectedOutcome,
    val includeInBatch: Boolean = true
)

object QuickJsValidationCases {
    val all: List<QuickJsValidationCase> = listOf(
        QuickJsValidationCase(
            id = "basic",
            title = "基础表达式与对象",
            source = """
                const numbers = [1, 2, 3, 4];
                ({
                  message: "Hello from QuickJS",
                  total: numbers.reduce((sum, value) => sum + value, 0)
                });
            """.trimIndent(),
            expected = QuickJsExpectedOutcome(success = true, containsText = "\"total\":10")
        ),
        QuickJsValidationCase(
            id = "unicode",
            title = "Unicode、emoji 与 NUL",
            source = "'你好 😀 ' + String.fromCharCode(0) + ' QuickJS';",
            expected = QuickJsExpectedOutcome(success = true, containsText = "QuickJS")
        ),
        QuickJsValidationCase(
            id = "console",
            title = "console.log 注入",
            source = """
                console.log("hello", { answer: 42 });
                "logged";
            """.trimIndent(),
            expected = QuickJsExpectedOutcome(success = true, containsText = "logged")
        ),
        QuickJsValidationCase(
            id = "delay_echo",
            title = "Promise 与 delayEcho",
            source = "android.delayEcho({ answer: 42 }, 100);",
            expected = QuickJsExpectedOutcome(success = true, containsText = "\"answer\":42")
        ),
        QuickJsValidationCase(
            id = "device_info",
            title = "Android 设备信息",
            source = "android.getDeviceInfo();",
            expected = QuickJsExpectedOutcome(success = true, containsText = "sdkInt")
        ),
        QuickJsValidationCase(
            id = "exception",
            title = "JS 异常与 stack",
            source = """
                function inner() { throw new Error("expected boom"); }
                function outer() { inner(); }
                outer();
            """.trimIndent(),
            expected = QuickJsExpectedOutcome(
                success = false,
                failureKind = QuickJsFailureKind.SCRIPT_ERROR,
                containsText = "expected boom"
            )
        ),
        QuickJsValidationCase(
            id = "unknown_host",
            title = "未知宿主方法 rejection",
            source = "android.invoke('missingMethod', {});",
            expected = QuickJsExpectedOutcome(
                success = false,
                failureKind = QuickJsFailureKind.HOST_ERROR,
                containsText = "METHOD_NOT_FOUND"
            )
        ),
        QuickJsValidationCase(
            id = "timeout",
            title = "死循环超时",
            source = "while (true) {}",
            options = QuickJsEvalOptions(timeoutMs = 400),
            expected = QuickJsExpectedOutcome(
                success = false,
                failureKind = QuickJsFailureKind.TIMEOUT
            )
        ),
        QuickJsValidationCase(
            id = "memory",
            title = "内存限制",
            source = """
                const values = [];
                while (true) values.push("x".repeat(1024));
            """.trimIndent(),
            options = QuickJsEvalOptions(
                timeoutMs = 2_000,
                memoryLimitBytes = 4 * QuickJsEvalOptions.MIB
            ),
            expected = QuickJsExpectedOutcome(
                success = false,
                failureKind = QuickJsFailureKind.SCRIPT_ERROR,
                containsText = "memory"
            )
        ),
        QuickJsValidationCase(
            id = "stack",
            title = "递归栈限制",
            source = "function recurse() { return recurse(); } recurse();",
            options = QuickJsEvalOptions(maxStackBytes = 128 * QuickJsEvalOptions.KIB),
            expected = QuickJsExpectedOutcome(
                success = false,
                failureKind = QuickJsFailureKind.SCRIPT_ERROR,
                containsText = "stack"
            )
        ),
        QuickJsValidationCase(
            id = "manual_cancel",
            title = "手动停止长任务",
            source = "android.delayEcho('finished', 10000);",
            options = QuickJsEvalOptions(timeoutMs = 30_000),
            expected = QuickJsExpectedOutcome(
                success = false,
                failureKind = QuickJsFailureKind.CANCELLED
            ),
            includeInBatch = false
        )
    )
}
