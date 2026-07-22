#include <jni.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <mutex>
#include <sstream>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

extern "C" {
#include "quickjs.h"
}

namespace {

using Clock = std::chrono::steady_clock;

struct Completion {
    int64_t call_id;
    bool success;
    std::string payload;
};

struct PendingCall {
    JSValue resolve;
    JSValue reject;
};

struct Session {
    JavaVM *vm = nullptr;
    jobject host_bridge = nullptr;
    jmethodID dispatch_request = nullptr;
    int64_t timeout_ms = 0;
    size_t memory_limit = 0;
    size_t stack_limit = 0;
    std::atomic<bool> cancelled{false};
    std::atomic<bool> timed_out{false};
    std::atomic<bool> finished{false};
    Clock::time_point deadline;
    std::mutex mutex;
    std::condition_variable condition;
    std::deque<Completion> completions;
    std::unordered_map<int64_t, PendingCall> pending_calls;
    int64_t next_call_id = 1;
    std::vector<std::string> logs;
};

struct Outcome {
    bool ok = false;
    std::string value;
    std::string value_type;
    std::string kind = "ENGINE_ERROR";
    std::string message;
    std::string stack;
    int64_t duration_ms = 0;
    int64_t memory_used_bytes = 0;
};

Session *from_handle(jlong handle) {
    return reinterpret_cast<Session *>(static_cast<intptr_t>(handle));
}

void append_json_string(std::ostringstream &out, const std::string &value) {
    static const char hex[] = "0123456789abcdef";
    out << '"';
    for (unsigned char ch : value) {
        switch (ch) {
            case '"': out << "\\\""; break;
            case '\\': out << "\\\\"; break;
            case '\b': out << "\\b"; break;
            case '\f': out << "\\f"; break;
            case '\n': out << "\\n"; break;
            case '\r': out << "\\r"; break;
            case '\t': out << "\\t"; break;
            default:
                if (ch < 0x20) {
                    out << "\\u00" << hex[ch >> 4] << hex[ch & 0x0f];
                } else {
                    out << static_cast<char>(ch);
                }
        }
    }
    out << '"';
}

std::string encode_outcome(const Outcome &outcome, const Session &session) {
    std::ostringstream out;
    out << "{\"ok\":" << (outcome.ok ? "true" : "false");
    if (outcome.ok) {
        out << ",\"value\":";
        append_json_string(out, outcome.value);
        out << ",\"valueType\":";
        append_json_string(out, outcome.value_type);
    } else {
        out << ",\"kind\":";
        append_json_string(out, outcome.kind);
        out << ",\"message\":";
        append_json_string(out, outcome.message);
        out << ",\"stack\":";
        append_json_string(out, outcome.stack);
    }
    out << ",\"logs\":[";
    for (size_t index = 0; index < session.logs.size(); ++index) {
        if (index != 0) out << ',';
        append_json_string(out, session.logs[index]);
    }
    out << "],\"durationMs\":" << outcome.duration_ms
        << ",\"memoryUsedBytes\":" << outcome.memory_used_bytes << '}';
    return out.str();
}

jbyteArray to_byte_array(JNIEnv *env, const std::string &value) {
    auto result = env->NewByteArray(static_cast<jsize>(value.size()));
    if (result != nullptr && !value.empty()) {
        env->SetByteArrayRegion(
            result,
            0,
            static_cast<jsize>(value.size()),
            reinterpret_cast<const jbyte *>(value.data()));
    }
    return result;
}

std::string from_byte_array(JNIEnv *env, jbyteArray value) {
    if (value == nullptr) return {};
    const jsize size = env->GetArrayLength(value);
    std::string result(static_cast<size_t>(size), '\0');
    if (size > 0) {
        env->GetByteArrayRegion(
            value,
            0,
            size,
            reinterpret_cast<jbyte *>(result.data()));
    }
    return result;
}

std::string js_to_string(JSContext *ctx, JSValueConst value) {
    size_t length = 0;
    const char *text = JS_ToCStringLen(ctx, &length, value);
    if (text == nullptr) return {};
    std::string result(text, length);
    JS_FreeCString(ctx, text);
    return result;
}

std::string value_type(JSContext *ctx, JSValueConst value) {
    if (JS_IsUndefined(value)) return "undefined";
    if (JS_IsNull(value)) return "null";
    if (JS_IsBool(value)) return "boolean";
    if (JS_IsNumber(value)) return "number";
    if (JS_IsBigInt(value)) return "bigint";
    if (JS_IsString(value)) return "string";
    if (JS_IsSymbol(value)) return "symbol";
    if (JS_IsFunction(ctx, value)) return "function";
    if (JS_IsObject(value)) return "object";
    return "unknown";
}

bool format_value(JSContext *ctx, JSValueConst value, Outcome *outcome) {
    outcome->value_type = value_type(ctx, value);
    if (JS_IsObject(value) && !JS_IsFunction(ctx, value)) {
        JSValue json = JS_JSONStringify(ctx, value, JS_UNDEFINED, JS_UNDEFINED);
        if (JS_IsException(json)) {
            JSValue error = JS_GetException(ctx);
            outcome->kind = "ENGINE_ERROR";
            outcome->message = "Result serialization failed: " + js_to_string(ctx, error);
            JS_FreeValue(ctx, error);
            return false;
        }
        if (!JS_IsUndefined(json)) outcome->value = js_to_string(ctx, json);
        JS_FreeValue(ctx, json);
    }
    if (outcome->value.empty() && !JS_IsString(value)) {
        outcome->value = js_to_string(ctx, value);
    } else if (JS_IsString(value)) {
        outcome->value = js_to_string(ctx, value);
    }
    return true;
}

void describe_error(JSContext *ctx, JSValueConst error, Outcome *outcome) {
    std::string code;
    if (JS_IsObject(error)) {
        JSValue code_value = JS_GetPropertyStr(ctx, error, "code");
        if (JS_IsString(code_value)) code = js_to_string(ctx, code_value);
        JS_FreeValue(ctx, code_value);

        JSValue stack_value = JS_GetPropertyStr(ctx, error, "stack");
        if (JS_IsString(stack_value)) outcome->stack = js_to_string(ctx, stack_value);
        JS_FreeValue(ctx, stack_value);
    }
    outcome->kind = code.empty() ? "SCRIPT_ERROR" : "HOST_ERROR";
    outcome->message = js_to_string(ctx, error);
    if (!code.empty() && outcome->message.find(code) == std::string::npos) {
        outcome->message = code + ": " + outcome->message;
    }
    if (outcome->message.empty()) outcome->message = "JavaScript error";
}

int interrupt_handler(JSRuntime *, void *opaque) {
    auto *session = static_cast<Session *>(opaque);
    if (session->cancelled.load(std::memory_order_relaxed)) return 1;
    if (Clock::now() >= session->deadline) {
        session->timed_out.store(true, std::memory_order_relaxed);
        return 1;
    }
    return 0;
}

std::string console_argument(JSContext *ctx, JSValueConst value) {
    if (JS_IsString(value)) return js_to_string(ctx, value);
    if (JS_IsObject(value)) {
        JSValue json = JS_JSONStringify(ctx, value, JS_UNDEFINED, JS_UNDEFINED);
        if (!JS_IsException(json) && !JS_IsUndefined(json)) {
            std::string text = js_to_string(ctx, json);
            JS_FreeValue(ctx, json);
            return text;
        }
        if (JS_IsException(json)) {
            JSValue ignored = JS_GetException(ctx);
            JS_FreeValue(ctx, ignored);
        }
        JS_FreeValue(ctx, json);
    }
    return js_to_string(ctx, value);
}

JSValue console_log(
    JSContext *ctx,
    JSValueConst,
    int argc,
    JSValueConst *argv) {
    auto *session = static_cast<Session *>(JS_GetContextOpaque(ctx));
    std::ostringstream line;
    for (int index = 0; index < argc; ++index) {
        if (index != 0) line << ' ';
        line << console_argument(ctx, argv[index]);
    }
    session->logs.push_back(line.str());
    return JS_UNDEFINED;
}

void enqueue_completion(Session *session, Completion completion) {
    {
        std::lock_guard<std::mutex> guard(session->mutex);
        session->completions.push_back(std::move(completion));
    }
    session->condition.notify_all();
}

JSValue android_invoke(
    JSContext *ctx,
    JSValueConst,
    int argc,
    JSValueConst *argv) {
    auto *session = static_cast<Session *>(JS_GetContextOpaque(ctx));
    if (argc < 1 || !JS_IsString(argv[0])) {
        return JS_ThrowTypeError(ctx, "android.invoke expects a method name");
    }

    const std::string method = js_to_string(ctx, argv[0]);
    JSValue args_value = argc >= 2 ? JS_DupValue(ctx, argv[1]) : JS_NewObject(ctx);
    JSValue args_json = JS_JSONStringify(ctx, args_value, JS_UNDEFINED, JS_UNDEFINED);
    JS_FreeValue(ctx, args_value);
    if (JS_IsException(args_json)) return args_json;
    std::string args = JS_IsUndefined(args_json) ? "null" : js_to_string(ctx, args_json);
    JS_FreeValue(ctx, args_json);

    JSValue resolving[2];
    JSValue promise = JS_NewPromiseCapability(ctx, resolving);
    if (JS_IsException(promise)) return promise;

    const int64_t call_id = session->next_call_id++;
    session->pending_calls.emplace(
        call_id,
        PendingCall{resolving[0], resolving[1]});

    JNIEnv *env = nullptr;
    if (session->vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        enqueue_completion(
            session,
            Completion{call_id, false, "{\"code\":\"JNI_ERROR\",\"message\":\"JNI environment unavailable\"}"});
        return promise;
    }

    jbyteArray method_bytes = to_byte_array(env, method);
    jbyteArray args_bytes = to_byte_array(env, args);
    env->CallVoidMethod(
        session->host_bridge,
        session->dispatch_request,
        static_cast<jlong>(call_id),
        method_bytes,
        args_bytes);
    env->DeleteLocalRef(method_bytes);
    env->DeleteLocalRef(args_bytes);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        enqueue_completion(
            session,
            Completion{call_id, false, "{\"code\":\"HOST_CALLBACK_ERROR\",\"message\":\"Host callback threw\"}"});
    }
    return promise;
}

bool install_host_api(JSContext *ctx) {
    JSValue global = JS_GetGlobalObject(ctx);

    JSValue console = JS_NewObject(ctx);
    JS_SetPropertyStr(ctx, console, "log", JS_NewCFunction(ctx, console_log, "log", 1));
    JS_SetPropertyStr(ctx, global, "console", console);

    JSValue android = JS_NewObject(ctx);
    JS_SetPropertyStr(ctx, android, "invoke", JS_NewCFunction(ctx, android_invoke, "invoke", 2));
    JS_SetPropertyStr(ctx, global, "android", android);
    JS_FreeValue(ctx, global);

    static constexpr char helpers[] =
        "android.getDeviceInfo = function() {"
        "  return android.invoke('getDeviceInfo', {});"
        "};"
        "android.delayEcho = function(value, delayMs) {"
        "  return android.invoke('delayEcho', { value: value, delayMs: delayMs });"
        "};";
    JSValue result = JS_Eval(
        ctx,
        helpers,
        sizeof(helpers) - 1,
        "<android-host>",
        JS_EVAL_TYPE_GLOBAL);
    const bool ok = !JS_IsException(result);
    JS_FreeValue(ctx, result);
    return ok;
}

void reject_host_call(JSContext *ctx, JSValueConst reject, const std::string &payload) {
    JSValue details = JS_ParseJSON(ctx, payload.data(), payload.size(), "<host-error>");
    if (JS_IsException(details)) {
        JSValue ignored = JS_GetException(ctx);
        JS_FreeValue(ctx, ignored);
    }
    JSValue error = JS_NewError(ctx);
    if (!JS_IsException(details) && JS_IsObject(details)) {
        JSValue code = JS_GetPropertyStr(ctx, details, "code");
        JSValue message = JS_GetPropertyStr(ctx, details, "message");
        JS_SetPropertyStr(ctx, error, "code", JS_DupValue(ctx, code));
        JS_SetPropertyStr(ctx, error, "message", JS_DupValue(ctx, message));
        JS_FreeValue(ctx, code);
        JS_FreeValue(ctx, message);
    } else {
        JS_SetPropertyStr(ctx, error, "code", JS_NewString(ctx, "HOST_ERROR"));
        JS_SetPropertyStr(ctx, error, "message", JS_NewStringLen(ctx, payload.data(), payload.size()));
    }
    JS_FreeValue(ctx, details);
    JSValue call_result = JS_Call(ctx, reject, JS_UNDEFINED, 1, &error);
    JS_FreeValue(ctx, call_result);
    JS_FreeValue(ctx, error);
}

void resolve_host_call(JSContext *ctx, JSValueConst resolve, const std::string &payload) {
    JSValue value = JS_ParseJSON(ctx, payload.data(), payload.size(), "<host-result>");
    if (JS_IsException(value)) {
        JSValue ignored = JS_GetException(ctx);
        JS_FreeValue(ctx, ignored);
        value = JS_UNDEFINED;
    }
    JSValue call_result = JS_Call(ctx, resolve, JS_UNDEFINED, 1, &value);
    JS_FreeValue(ctx, call_result);
    JS_FreeValue(ctx, value);
}

void process_completions(JSContext *ctx, Session *session) {
    std::deque<Completion> completions;
    {
        std::lock_guard<std::mutex> guard(session->mutex);
        completions.swap(session->completions);
    }
    for (const Completion &completion : completions) {
        auto pending = session->pending_calls.find(completion.call_id);
        if (pending == session->pending_calls.end()) continue;
        if (completion.success) {
            resolve_host_call(ctx, pending->second.resolve, completion.payload);
        } else {
            reject_host_call(ctx, pending->second.reject, completion.payload);
        }
        JS_FreeValue(ctx, pending->second.resolve);
        JS_FreeValue(ctx, pending->second.reject);
        session->pending_calls.erase(pending);
    }
}

void free_pending_calls(JSContext *ctx, Session *session) {
    for (auto &entry : session->pending_calls) {
        JS_FreeValue(ctx, entry.second.resolve);
        JS_FreeValue(ctx, entry.second.reject);
    }
    session->pending_calls.clear();
}

void set_interrupt_outcome(Session *session, Outcome *outcome) {
    if (session->cancelled.load(std::memory_order_relaxed)) {
        outcome->kind = "CANCELLED";
        outcome->message = "Execution cancelled";
    } else {
        outcome->kind = "TIMEOUT";
        outcome->message = "Execution exceeded the configured timeout";
    }
}

Outcome evaluate(Session *session, const std::string &source) {
    Outcome outcome;
    const Clock::time_point started = Clock::now();
    session->deadline = started + std::chrono::milliseconds(session->timeout_ms);

    JSRuntime *runtime = JS_NewRuntime();
    if (runtime == nullptr) {
        outcome.message = "Failed to create QuickJS runtime";
        return outcome;
    }
    JS_SetMemoryLimit(runtime, session->memory_limit);
    JS_SetMaxStackSize(runtime, session->stack_limit);
    JS_SetInterruptHandler(runtime, interrupt_handler, session);

    JSContext *ctx = JS_NewContext(runtime);
    if (ctx == nullptr) {
        outcome.message = "Failed to create QuickJS context";
        JS_FreeRuntime(runtime);
        return outcome;
    }
    JS_SetContextOpaque(ctx, session);

    JSValue value = JS_UNDEFINED;
    if (!install_host_api(ctx)) {
        value = JS_GetException(ctx);
        describe_error(ctx, value, &outcome);
        outcome.kind = "ENGINE_ERROR";
        JS_FreeValue(ctx, value);
        value = JS_UNDEFINED;
    } else {
        value = JS_Eval(
            ctx,
            source.data(),
            source.size(),
            "playground.js",
            JS_EVAL_TYPE_GLOBAL);

        if (JS_IsException(value)) {
            JS_FreeValue(ctx, value);
            value = JS_UNDEFINED;
            JSValue error = JS_GetException(ctx);
            if (session->cancelled.load() || session->timed_out.load()) {
                set_interrupt_outcome(session, &outcome);
            } else {
                describe_error(ctx, error, &outcome);
            }
            JS_FreeValue(ctx, error);
        } else if (JS_IsPromise(value)) {
            bool settled = false;
            while (!settled) {
                process_completions(ctx, session);

                JSContext *job_ctx = ctx;
                int job_result = 0;
                while (JS_IsJobPending(runtime) && (job_result = JS_ExecutePendingJob(runtime, &job_ctx)) > 0) {
                    process_completions(ctx, session);
                }
                if (job_result < 0) {
                    JSValue error = JS_GetException(job_ctx);
                    describe_error(job_ctx, error, &outcome);
                    JS_FreeValue(job_ctx, error);
                    settled = true;
                    break;
                }

                process_completions(ctx, session);
                const JSPromiseStateEnum state = JS_PromiseState(ctx, value);
                if (state == JS_PROMISE_FULFILLED) {
                    JSValue resolved = JS_PromiseResult(ctx, value);
                    outcome.ok = format_value(ctx, resolved, &outcome);
                    JS_FreeValue(ctx, resolved);
                    settled = true;
                } else if (state == JS_PROMISE_REJECTED) {
                    JSValue rejected = JS_PromiseResult(ctx, value);
                    describe_error(ctx, rejected, &outcome);
                    JS_FreeValue(ctx, rejected);
                    settled = true;
                } else if (session->cancelled.load() || Clock::now() >= session->deadline) {
                    if (Clock::now() >= session->deadline) session->timed_out.store(true);
                    set_interrupt_outcome(session, &outcome);
                    settled = true;
                } else if (session->pending_calls.empty() && !JS_IsJobPending(runtime)) {
                    outcome.kind = "UNRESOLVED_PROMISE";
                    outcome.message = "Promise is pending with no QuickJS jobs or Android host calls";
                    settled = true;
                } else {
                    std::unique_lock<std::mutex> lock(session->mutex);
                    session->condition.wait_until(lock, session->deadline, [session] {
                        return session->cancelled.load() || !session->completions.empty();
                    });
                }
            }
        } else {
            outcome.ok = format_value(ctx, value, &outcome);
        }
    }

    JS_FreeValue(ctx, value);
    free_pending_calls(ctx, session);

    JSMemoryUsage memory{};
    JS_ComputeMemoryUsage(runtime, &memory);
    outcome.memory_used_bytes = memory.memory_used_size;
    outcome.duration_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        Clock::now() - started).count();

    JS_FreeContext(ctx);
    JS_FreeRuntime(runtime);
    session->finished.store(true);
    return outcome;
}

jlong native_create(
    JNIEnv *env,
    jobject,
    jobject host_bridge,
    jlong timeout_ms,
    jlong memory_limit,
    jlong stack_limit) {
    if (host_bridge == nullptr || timeout_ms <= 0 || memory_limit <= 0 || stack_limit <= 0) return 0;
    auto *session = new Session();
    env->GetJavaVM(&session->vm);
    session->host_bridge = env->NewGlobalRef(host_bridge);
    jclass bridge_class = env->GetObjectClass(host_bridge);
    session->dispatch_request = env->GetMethodID(bridge_class, "dispatchRequest", "(J[B[B)V");
    env->DeleteLocalRef(bridge_class);
    if (session->host_bridge == nullptr || session->dispatch_request == nullptr) {
        if (session->host_bridge != nullptr) env->DeleteGlobalRef(session->host_bridge);
        delete session;
        return 0;
    }
    session->timeout_ms = timeout_ms;
    session->memory_limit = static_cast<size_t>(memory_limit);
    session->stack_limit = static_cast<size_t>(stack_limit);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(session));
}

jbyteArray native_eval(JNIEnv *env, jobject, jlong handle, jbyteArray source_utf8) {
    Session *session = from_handle(handle);
    if (session == nullptr || source_utf8 == nullptr) {
        Outcome error;
        error.message = "Invalid QuickJS session or source";
        Session empty;
        return to_byte_array(env, encode_outcome(error, empty));
    }
    Outcome outcome = evaluate(session, from_byte_array(env, source_utf8));
    return to_byte_array(env, encode_outcome(outcome, *session));
}

jboolean native_complete_host_call(
    JNIEnv *env,
    jobject,
    jlong handle,
    jlong call_id,
    jboolean success,
    jbyteArray payload_utf8) {
    Session *session = from_handle(handle);
    if (session == nullptr || session->finished.load() || session->cancelled.load()) return JNI_FALSE;
    enqueue_completion(
        session,
        Completion{call_id, success == JNI_TRUE, from_byte_array(env, payload_utf8)});
    return JNI_TRUE;
}

void native_cancel(JNIEnv *, jobject, jlong handle) {
    Session *session = from_handle(handle);
    if (session == nullptr) return;
    session->cancelled.store(true);
    session->condition.notify_all();
}

void native_destroy(JNIEnv *env, jobject, jlong handle) {
    Session *session = from_handle(handle);
    if (session == nullptr) return;
    session->cancelled.store(true);
    session->condition.notify_all();
    if (session->host_bridge != nullptr) env->DeleteGlobalRef(session->host_bridge);
    delete session;
}

static const JNINativeMethod methods[] = {
    {"create", "(Ldev/hehe/sketch/feat/quickjs/QuickJsToHostBridge;JJJ)J", reinterpret_cast<void *>(native_create)},
    {"eval", "(J[B)[B", reinterpret_cast<void *>(native_eval)},
    {"completeHostCall", "(JJZ[B)Z", reinterpret_cast<void *>(native_complete_host_call)},
    {"cancel", "(J)V", reinterpret_cast<void *>(native_cancel)},
    {"destroy", "(J)V", reinterpret_cast<void *>(native_destroy)},
};

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass native_class = env->FindClass("dev/hehe/sketch/feat/quickjs/QuickJsNative");
    if (native_class == nullptr) return JNI_ERR;
    const int result = env->RegisterNatives(
        native_class,
        methods,
        static_cast<jint>(sizeof(methods) / sizeof(methods[0])));
    env->DeleteLocalRef(native_class);
    return result == JNI_OK ? JNI_VERSION_1_6 : JNI_ERR;
}
