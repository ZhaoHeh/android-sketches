package dev.hehe.sketch.feat.quickjs

import androidx.annotation.Keep

@Keep
internal object QuickJsNative {
    init {
        System.loadLibrary("quickjs_bridge")
    }

    external fun create(
        hostBridge: QuickJsToHostBridge,
        timeoutMs: Long,
        memoryLimitBytes: Long,
        maxStackBytes: Long
    ): Long

    external fun eval(handle: Long, sourceUtf8: ByteArray): ByteArray
    external fun completeHostCall(
        handle: Long,
        callId: Long,
        success: Boolean,
        payloadUtf8: ByteArray
    ): Boolean

    external fun cancel(handle: Long)
    external fun destroy(handle: Long)
}

@Keep
internal class QuickJsToHostBridge(
    private val dispatchAction: (Long, ByteArray, ByteArray) -> Unit
) {
    @Keep
    fun dispatchRequest(callId: Long, methodUtf8: ByteArray, argsUtf8: ByteArray) {
        dispatchAction(callId, methodUtf8, argsUtf8)
    }
}
