package io.legado.app.model.debug

import androidx.compose.runtime.Immutable
import java.util.UUID

/**
 * 调试事件模型
 *
 * 用于统一采集和展示应用内的各类调试信息。
 */
data class DebugEvent(
    val id: String = UUID.randomUUID().toString(),
    val time: Long = System.currentTimeMillis(),
    val level: DebugLevel,
    val category: DebugCategory,
    val subCategory: SourceSubCategory? = null,
    val message: String,
    val detail: String? = null,
    val sourceName: String? = null,
    val sourceUrl: String? = null,
    val requestId: String? = null,
    val traceId: String? = null,
    val url: String? = null,
    val method: String? = null,
    val statusCode: Int? = null,
    val duration: Long? = null,
    val requestHeaders: Map<String, String>? = null,
    val userAgent: String? = null,
    val cookies: String? = null,
    val throwable: Throwable? = null,
    val tags: Map<String, String> = emptyMap(),
    val dialogName: String? = null
) {
    /**
     * 转换为旧版AppLog格式（兼容性）
     */
    fun toLegacyFormat(): Triple<Long, String, Throwable?> {
        return Triple(time, message, throwable)
    }

    /**
     * 转换为旧版Debug日志格式（兼容性）
     */
    fun toDebugLogFormat(sourceUrl: String?): String {
        val timeStr = java.text.SimpleDateFormat(
            "[mm:ss.SSS]",
            java.util.Locale.getDefault()
        ).format(java.util.Date(System.currentTimeMillis()))
        return "$timeStr $message"
    }
}
