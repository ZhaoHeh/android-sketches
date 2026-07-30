package dev.hehe.sketch.feat.adk

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class AndroidSystemMcpEndpoint(
    private val calendarBackend: CalendarEventBackend,
    private val timerBackend: TimerBackend
) : McpEndpoint {
    constructor(context: Context) : this(
        calendarBackend = AndroidCalendarEventBackend(context),
        timerBackend = AndroidTimerBackend(context)
    )
    private val resultMutex = Mutex()
    private val completedCalls = mutableMapOf<String, McpToolResult>()

    override val id: String = "android-system"
    override val displayName: String = "Android 系统能力"

    override suspend fun listTools(): List<McpToolDescriptor> = listOf(
        McpToolDescriptor(
            name = CALENDAR_TOOL_NAME,
            description =
                "直接向用户的主要可写系统日历添加事件。时间必须是带 UTC offset 的 RFC 3339，" +
                    "例如 2026-07-30T15:00:00+08:00。信息不完整时先询问用户。",
            inputSchema = McpSchemas.objectSchema(
                properties = mapOf(
                    "title" to McpSchemas.string("日程标题。"),
                    "start_time" to McpSchemas.string("带 UTC offset 的 RFC 3339 开始时间。"),
                    "duration_minutes" to McpSchemas.integer("持续分钟数，范围 1 到 1440。"),
                    "description" to McpSchemas.string("可选的日程说明。"),
                    "location" to McpSchemas.string("可选的地点。")
                ),
                required = listOf("title", "start_time", "duration_minutes")
            ),
            annotations = McpToolAnnotations(
                readOnlyHint = false,
                destructiveHint = false,
                idempotentHint = false,
                openWorldHint = true
            ),
            approvalPolicy = McpApprovalPolicy.ALWAYS
        ),
        McpToolDescriptor(
            name = TIMER_TOOL_NAME,
            description = "通过 Android 系统时钟立即启动一个计时器。",
            inputSchema = McpSchemas.objectSchema(
                properties = mapOf(
                    "duration_seconds" to McpSchemas.integer("计时秒数，范围 1 到 86400。"),
                    "label" to McpSchemas.string("可选的计时器标签。")
                ),
                required = listOf("duration_seconds")
            ),
            annotations = McpToolAnnotations(
                readOnlyHint = false,
                destructiveHint = false,
                idempotentHint = false,
                openWorldHint = true
            ),
            approvalPolicy = McpApprovalPolicy.ALWAYS
        )
    )

    override suspend fun callTool(
        name: String,
        arguments: Map<String, Any?>,
        context: McpCallContext
    ): McpToolResult {
        val callId = context.functionCallId
            ?: return McpToolResult.error(
                "MISSING_CALL_ID",
                "副作用工具必须由带稳定 function-call ID 的 Agent 调用"
            )
        val cacheKey = "$name:$callId"
        return resultMutex.withLock {
            completedCalls[cacheKey]?.let { return@withLock it }
            val result = when (name) {
                CALENDAR_TOOL_NAME -> createCalendarEvent(arguments)
                TIMER_TOOL_NAME -> setTimer(arguments)
                else -> McpToolResult.error("METHOD_NOT_FOUND", "未知工具：$name")
            }
            completedCalls[cacheKey] = result
            result
        }
    }

    override fun resetSession() {
        completedCalls.clear()
    }

    private suspend fun createCalendarEvent(arguments: Map<String, Any?>): McpToolResult {
        val title = arguments.requiredText("title")
            ?: return McpToolResult.error("INVALID_ARGUMENT", "title 不能为空")
        val startText = arguments.requiredText("start_time")
            ?: return McpToolResult.error("INVALID_ARGUMENT", "start_time 不能为空")
        val duration = arguments.integer("duration_minutes")
            ?: return McpToolResult.error("INVALID_ARGUMENT", "duration_minutes 必须是整数")
        if (duration !in 1..1_440) {
            return McpToolResult.error("INVALID_ARGUMENT", "duration_minutes 必须在 1 到 1440 之间")
        }
        val start = try {
            OffsetDateTime.parse(startText)
        } catch (_: DateTimeParseException) {
            return McpToolResult.error(
                "INVALID_ARGUMENT",
                "start_time 必须是带 UTC offset 的 RFC 3339 时间"
            )
        }
        val request = CalendarEventRequest(
            title = title,
            start = start,
            durationMinutes = duration,
            description = arguments.optionalText("description"),
            location = arguments.optionalText("location")
        )
        return calendarBackend.createEvent(request)
    }

    private suspend fun setTimer(arguments: Map<String, Any?>): McpToolResult {
        val duration = arguments.integer("duration_seconds")
            ?: return McpToolResult.error("INVALID_ARGUMENT", "duration_seconds 必须是整数")
        if (duration !in 1..86_400) {
            return McpToolResult.error("INVALID_ARGUMENT", "duration_seconds 必须在 1 到 86400 之间")
        }
        return timerBackend.setTimer(
            TimerRequest(
                durationSeconds = duration,
                label = arguments.optionalText("label")
            )
        )
    }

    private fun Map<String, Any?>.requiredText(name: String): String? =
        (get(name) as? String)?.trim()?.takeIf(String::isNotEmpty)

    private fun Map<String, Any?>.optionalText(name: String): String? =
        (get(name) as? String)?.trim()?.takeIf(String::isNotEmpty)

    private fun Map<String, Any?>.integer(name: String): Int? {
        val number = get(name) as? Number ?: return null
        val longValue = number.toLong()
        return longValue.takeIf {
            number.toDouble() == it.toDouble() &&
                it >= Int.MIN_VALUE.toLong() &&
                it <= Int.MAX_VALUE.toLong()
        }
            ?.toInt()
    }

    companion object {
        const val CALENDAR_TOOL_NAME = "calendar_create_event"
        const val TIMER_TOOL_NAME = "timer_set"
    }
}

internal data class CalendarEventRequest(
    val title: String,
    val start: OffsetDateTime,
    val durationMinutes: Int,
    val description: String?,
    val location: String?
)

internal fun interface CalendarEventBackend {
    suspend fun createEvent(request: CalendarEventRequest): McpToolResult
}

internal class AndroidCalendarEventBackend(
    context: Context
) : CalendarEventBackend {
    private val appContext = context.applicationContext

    override suspend fun createEvent(request: CalendarEventRequest): McpToolResult =
        withContext(Dispatchers.IO) {
            if (!hasCalendarPermissions()) {
                return@withContext McpToolResult.error(
                    "PERMISSION_DENIED",
                    "缺少 READ_CALENDAR 或 WRITE_CALENDAR 权限"
                )
            }

            val calendarId = findWritableCalendarId()
                ?: return@withContext McpToolResult.error(
                    "NO_WRITABLE_CALENDAR",
                    "设备上没有可写的系统日历"
                )
            val startMillis = request.start.toInstant().toEpochMilli()
            val endMillis = request.start.plusMinutes(request.durationMinutes.toLong())
                .toInstant()
                .toEpochMilli()
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, request.title)
                put(CalendarContract.Events.DTSTART, startMillis)
                put(CalendarContract.Events.DTEND, endMillis)
                put(CalendarContract.Events.EVENT_TIMEZONE, request.start.offset.id)
                request.description?.let { put(CalendarContract.Events.DESCRIPTION, it) }
                request.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            }
            val uri = try {
                appContext.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            } catch (error: SecurityException) {
                return@withContext McpToolResult.error(
                    "PERMISSION_DENIED",
                    error.message ?: "系统日历拒绝写入"
                )
            } catch (error: RuntimeException) {
                return@withContext McpToolResult.error(
                    "CALENDAR_INSERT_FAILED",
                    error.message ?: "系统日历写入失败"
                )
            } ?: return@withContext McpToolResult.error(
                "CALENDAR_INSERT_FAILED",
                "系统日历没有返回新事件 URI"
            )

            val eventId = uri.lastPathSegment?.toLongOrNull()
            val structured = mapOf(
                "ok" to true,
                "event_id" to eventId,
                "uri" to uri.toString(),
                "title" to request.title,
                "start_time" to request.start.toString(),
                "end_time" to request.start.plusMinutes(request.durationMinutes.toLong()).toString()
            )
            McpToolResult.success("日程已写入系统日历：${request.title}", structured)
        }

    private fun hasCalendarPermissions(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    private fun findWritableCalendarId(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        )
        val selection =
            "${CalendarContract.Calendars.VISIBLE}=? AND " +
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=?"
        val args = arrayOf(
            "1",
            CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()
        )
        return try {
            val cursor = appContext.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                selection,
                args,
                null
            ) ?: return null
            cursor.use {
                val idIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
                val primaryIndex = cursor.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)
                var fallback: Long? = null
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    if (fallback == null) fallback = id
                    if (primaryIndex >= 0 && cursor.getInt(primaryIndex) == 1) return@use id
                }
                fallback
            }
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }
}

internal data class TimerRequest(
    val durationSeconds: Int,
    val label: String?
)

internal fun interface TimerBackend {
    suspend fun setTimer(request: TimerRequest): McpToolResult
}

internal class AndroidTimerBackend(
    context: Context
) : TimerBackend {
    private val appContext = context.applicationContext

    override suspend fun setTimer(request: TimerRequest): McpToolResult =
        withContext(Dispatchers.Main) {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, request.durationSeconds)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                request.label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val handler = appContext.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?: return@withContext McpToolResult.error(
                    "HANDLER_NOT_FOUND",
                    "设备上没有能够设置计时器的时钟应用"
                )
            try {
                appContext.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                return@withContext McpToolResult.error(
                    "HANDLER_NOT_FOUND",
                    "设备上没有能够设置计时器的时钟应用"
                )
            } catch (error: SecurityException) {
                return@withContext McpToolResult.error(
                    "TIMER_DISPATCH_FAILED",
                    error.message ?: "系统拒绝启动计时器"
                )
            }
            McpToolResult.success(
                text = "计时器请求已发送给系统时钟",
                structuredContent = mapOf(
                    "ok" to true,
                    "dispatched" to true,
                    "duration_seconds" to request.durationSeconds,
                    "label" to request.label,
                    "handler_package" to handler.activityInfo.packageName
                )
            )
        }
}
