package dev.hehe.sketch.feat.quickjs

import android.content.Context
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class QuickJsExecution internal constructor(
    private val cancelAction: () -> Unit
) {
    fun cancel() = cancelAction()
}

class QuickJsRunner(
    context: Context,
    private val hostDispatcher: QuickJsHostDispatcher = AndroidQuickJsHostDispatcher(context),
    private val resultExecutor: Executor = ContextCompat.getMainExecutor(context),
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
) : Closeable {
    private val lock = Any()
    private var active: ExecutionState? = null
    private var closed = false

    fun run(
        source: String,
        options: QuickJsEvalOptions = QuickJsEvalOptions(),
        onResult: (QuickJsEvalResult) -> Unit
    ): QuickJsExecution? {
        require(options.validationError() == null) { options.validationError().orEmpty() }

        val state = synchronized(lock) {
            if (closed || active != null) return null
            ExecutionState().also { active = it }
        }

        val execution = QuickJsExecution { cancel(state) }
        worker.execute {
            execute(state, source, options, onResult)
        }
        return execution
    }

    override fun close() {
        val state = synchronized(lock) {
            if (closed) return
            closed = true
            active
        }
        state?.let(::cancel)
        worker.shutdownNow()
        (hostDispatcher as? Closeable)?.close()
    }

    private fun execute(
        state: ExecutionState,
        source: String,
        options: QuickJsEvalOptions,
        onResult: (QuickJsEvalResult) -> Unit
    ) {
        var handle = 0L
        val result = runCatching {
            val callbacks = NativeCallbacks { callId, method, args ->
                dispatchHostCall(state, callId, method, args)
            }
            handle = QuickJsNative.create(
                callbacks,
                options.timeoutMs,
                options.memoryLimitBytes,
                options.maxStackBytes
            )
            check(handle != 0L) { "Failed to create native QuickJS session" }

            synchronized(lock) {
                state.nativeHandle = handle
                if (state.cancelled.get()) QuickJsNative.cancel(handle)
            }

            QuickJsEvalResult.decode(
                QuickJsNative.eval(handle, source.toByteArray(Charsets.UTF_8))
            )
        }.getOrElse(QuickJsEvalResult::engineFailure)

        val shouldDeliver = synchronized(lock) {
            state.cancelHostCalls()
            if (handle != 0L) {
                QuickJsNative.destroy(handle)
                state.nativeHandle = 0L
            }
            if (active === state) active = null
            !closed
        }

        if (shouldDeliver) resultExecutor.execute deliver@{
            synchronized(lock) {
                if (closed) return@deliver
            }
            onResult(result)
        }
    }

    private fun dispatchHostCall(
        state: ExecutionState,
        callId: Long,
        methodUtf8: ByteArray,
        argsUtf8: ByteArray
    ) {
        val completed = AtomicBoolean(false)
        val request = QuickJsHostRequest(
            callId = callId,
            method = methodUtf8.toString(Charsets.UTF_8),
            argsJson = argsUtf8.toString(Charsets.UTF_8)
        )
        val hostCall = hostDispatcher.dispatch(request) { response ->
            completed.set(true)
            synchronized(lock) {
                state.hostCalls.remove(callId)
                val handle = state.nativeHandle
                if (active !== state || state.cancelled.get() || handle == 0L) return@synchronized
                val success = response is QuickJsHostResponse.Success
                val payload = when (response) {
                    is QuickJsHostResponse.Success -> response.valueJson
                    is QuickJsHostResponse.Failure -> response.toJson()
                }
                QuickJsNative.completeHostCall(
                    handle,
                    callId,
                    success,
                    payload.toByteArray(Charsets.UTF_8)
                )
            }
        }

        synchronized(lock) {
            if (!completed.get() && active === state && !state.cancelled.get()) {
                state.hostCalls[callId] = hostCall
            } else {
                hostCall.cancel()
            }
        }
    }

    private fun cancel(state: ExecutionState) {
        if (!state.cancelled.compareAndSet(false, true)) return
        synchronized(lock) {
            state.cancelHostCalls()
            if (state.nativeHandle != 0L) QuickJsNative.cancel(state.nativeHandle)
        }
    }

    private class ExecutionState {
        val cancelled = AtomicBoolean(false)
        var nativeHandle: Long = 0L
        val hostCalls = mutableMapOf<Long, QuickJsHostCall>()

        fun cancelHostCalls() {
            hostCalls.values.forEach(QuickJsHostCall::cancel)
            hostCalls.clear()
        }
    }
}
