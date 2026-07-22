package dev.hehe.sketch.feat.quickjs

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class QuickJsHostRequest(
    val callId: Long,
    val method: String,
    val argsJson: String
)

sealed class QuickJsHostResponse {
    data class Success(val valueJson: String) : QuickJsHostResponse()
    data class Failure(val code: String, val message: String) : QuickJsHostResponse() {
        internal fun toJson(): String = JSONObject()
            .put("code", code)
            .put("message", message)
            .toString()
    }
}

fun interface QuickJsHostCall {
    fun cancel()
}

interface QuickJsHostDispatcher {
    fun dispatch(
        request: QuickJsHostRequest,
        complete: (QuickJsHostResponse) -> Unit
    ): QuickJsHostCall
}

class AndroidQuickJsHostDispatcher(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
) : QuickJsHostDispatcher, Closeable {
    private val closed = AtomicBoolean(false)

    override fun dispatch(
        request: QuickJsHostRequest,
        complete: (QuickJsHostResponse) -> Unit
    ): QuickJsHostCall {
        if (closed.get()) {
            complete(QuickJsHostResponse.Failure("HOST_CLOSED", "Host dispatcher is closed"))
            return QuickJsHostCall {}
        }

        val future: ScheduledFuture<*> = when (request.method) {
            "getDeviceInfo" -> scheduler.schedule({
                val payload = JSONObject()
                    .put("manufacturer", Build.MANUFACTURER)
                    .put("model", Build.MODEL)
                    .put("sdkInt", Build.VERSION.SDK_INT)
                    .put("supportedAbis", JSONArray(Build.SUPPORTED_ABIS.toList()))
                    .toString()
                complete(QuickJsHostResponse.Success(payload))
            }, 0, TimeUnit.MILLISECONDS)

            "delayEcho" -> {
                val args = runCatching { JSONObject(request.argsJson) }.getOrNull()
                val delayMs = args?.optLong("delayMs", -1) ?: -1
                if (args == null || !args.has("value") || delayMs !in 0..10_000) {
                    scheduler.schedule({
                        complete(
                            QuickJsHostResponse.Failure(
                                "INVALID_ARGUMENT",
                                "delayEcho expects { value, delayMs: 0..10000 }"
                            )
                        )
                    }, 0, TimeUnit.MILLISECONDS)
                } else {
                    val valueJson = jsonValueToString(args.opt("value"))
                    scheduler.schedule(
                        { complete(QuickJsHostResponse.Success(valueJson)) },
                        delayMs,
                        TimeUnit.MILLISECONDS
                    )
                }
            }

            else -> scheduler.schedule({
                complete(
                    QuickJsHostResponse.Failure(
                        "METHOD_NOT_FOUND",
                        "Unknown Android host method: ${request.method}"
                    )
                )
            }, 0, TimeUnit.MILLISECONDS)
        }

        return QuickJsHostCall { future.cancel(true) }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) scheduler.shutdownNow()
    }

    private fun jsonValueToString(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject, is JSONArray -> value.toString()
        is String -> JSONObject.quote(value)
        is Boolean, is Number -> value.toString()
        else -> JSONObject.quote(value.toString())
    }
}
