package io.legado.app.data.repository.debug

import com.google.gson.Gson
import io.legado.app.help.config.AppConfig
import io.legado.app.model.debug.DebugEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.ArrayDeque

/**
 * 调试事件中心
 *
 * 统一管理和分发调试事件的核心单例。
 * 职责：
 * - 接收所有调试事件
 * - 提供实时事件流（SharedFlow）
 * - 维护内存环形缓冲区（ArrayDeque）
 * - 根据配置决定是否持久化
 * - 提供清空、导出、查询最近日志等能力
 */
object DebugEventCenter {

    /**
     * 实时事件流
     */
    private val _eventFlow = MutableSharedFlow<DebugEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** 对外暴露的只读事件流 */
    val eventFlow: SharedFlow<DebugEvent> = _eventFlow.asSharedFlow()

    /** 内存环形缓冲区 */
    private val _events = ArrayDeque<DebugEvent>()

    /** 内存中最大保留的事件数量 */
    const val MAX_EVENTS = 500
    private const val MAX_MESSAGE_CHARS = 4096
    private const val MAX_DETAIL_CHARS = 4096

    /** 当前内存中的事件总数 */
    val eventCount: Int get() = synchronized(_events) { _events.size }

    val isEnabled: Boolean get() = AppConfig.debugLogFloatingBall

    suspend fun emit(event: DebugEvent) {
        val boundedEvent = event.copy(
            message = event.message.limitForEvent(MAX_MESSAGE_CHARS),
            detail = event.detail?.limitForEvent(MAX_DETAIL_CHARS)
        )
        synchronized(_events) {
            _events.addFirst(boundedEvent)
            while (_events.size > MAX_EVENTS) {
                _events.removeLast()
            }
        }
        _eventFlow.emit(boundedEvent)
    }

    private fun String.limitForEvent(maxChars: Int): String {
        if (length <= maxChars) return this
        return take(maxChars) + "\n...[truncated ${length - maxChars} chars]"
    }

    /**
     * 获取最近的N条日志
     */
    fun getRecentLogs(limit: Int = MAX_EVENTS): List<DebugEvent> {
        return synchronized(_events) {
            _events.take(limit)
        }
    }

    /**
     * 获取所有分类的最新一条日志
     */
    fun getLatestLogByCategory(): Map<io.legado.app.model.debug.DebugCategory, DebugEvent> {
        return synchronized(_events) {
            _events
                .filter { it.category != io.legado.app.model.debug.DebugCategory.ALL }
                .groupBy { it.category }
                .mapValues { (_, events) -> events.first() }
        }
    }

    /**
     * 按分类过滤日志
     */
    fun getLogsByCategory(
        category: io.legado.app.model.debug.DebugCategory,
        limit: Int = MAX_EVENTS
    ): List<DebugEvent> {
        return synchronized(_events) {
            if (category == io.legado.app.model.debug.DebugCategory.ALL) {
                _events.take(limit)
            } else {
                _events.filter { it.category == category }.take(limit)
            }
        }
    }

    /**
     * 按级别过滤日志
     */
    fun getLogsByMinLevel(
        level: io.legado.app.model.debug.DebugLevel,
        limit: Int = MAX_EVENTS
    ): List<DebugEvent> {
        return synchronized(_events) {
            _events
                .filter { it.level.priority >= level.priority }
                .take(limit)
        }
    }

    /**
     * 按traceId查询关联日志
     */
    fun getLogsByTraceId(traceId: String): List<DebugEvent> {
        return synchronized(_events) {
            _events.filter { it.traceId == traceId }
        }
    }

    /**
     * 清空所有日志
     */
    suspend fun clear() {
        synchronized(_events) {
            _events.clear()
        }
    }

    /** 同步清空 */
    fun clearSync() {
        synchronized(_events) {
            _events.clear()
        }
    }

    /**
     * 导出为JSON格式
     */
    fun exportToJson(): String {
        val gson = Gson()
        return synchronized(_events) {
            gson.toJson(_events.map { event ->
                mapOf(
                    "id" to event.id,
                    "time" to event.time,
                    "level" to event.level.name,
                    "category" to event.category.name,
                    "message" to event.message,
                    "detail" to event.detail,
                    "url" to event.url,
                    "statusCode" to event.statusCode,
                    "duration" to event.duration
                )
            })
        }
    }

    /**
     * 导出为纯文本格式
     */
    fun exportToText(): String {
        return synchronized(_events) {
            buildString {
                append("=== 调试日志导出 ===\n")
                append("导出时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}\n")
                append("总条数: ${_events.size}\n\n")

                _events.forEachIndexed { index, event ->
                    append("--- [${index + 1}] ---\n")
                    append("[${event.level.displayName}] [${event.category.displayName}]\n")
                    append("时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(java.util.Date(event.time))}\n")
                    append("消息: ${event.message}\n")

                    event.url?.let { append("URL: $it\n") }
                    event.method?.let { append("方法: $it\n") }
                    event.statusCode?.let { append("状态码: $it\n") }
                    event.duration?.let { append("耗时: ${it}ms\n") }
                    event.sourceName?.let { append("书源: $it\n") }
                    event.sourceUrl?.let { append("书源URL: $it\n") }
                    event.traceId?.let { append("TraceID: $it\n") }

                    event.detail?.let {
                        append("\n详情:\n$it\n")
                    }

                    event.throwable?.let {
                        append("\n异常:\n${it.stackTraceToString()}\n")
                    }

                    append("\n")
                }
            }
        }
    }

    /**
     * 设置兼容旧版AppLog的回调（需在协程中调用）
     */
    suspend fun setLegacyCallback(callback: (Triple<Long, String, Throwable?>) -> Unit) {
        eventFlow.collect { event ->
            callback(event.toLegacyFormat())
        }
    }

    /**
     * 设置兼容旧版Debug的回调（需在协程中调用）
     */
    suspend fun setDebugLegacyCallback(callback: (Int, String) -> Unit) {
        eventFlow.collect { event ->
            if (event.category == io.legado.app.model.debug.DebugCategory.RULE ||
                event.category == io.legado.app.model.debug.DebugCategory.SOURCE ||
                event.category == io.legado.app.model.debug.DebugCategory.RSS
            ) {
                callback(1, event.toDebugLogFormat(event.sourceUrl))
            }
        }
    }
}
