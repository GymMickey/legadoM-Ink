package io.legado.app.model.debug

import androidx.compose.runtime.Immutable
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import java.util.UUID

/**
 * 源类型，用于区分流程日志来自书源还是订阅源
 */
enum class SourceType(val displayName: String) {
    BOOK("书源"),
    RSS("订阅源")
}

/**
 * 流程日志项
 *
 * 记录源规则执行的每个步骤，包括：
 * - 基础信息：时间、阶段、操作类型
 * - 网络信息：URL、方法、状态码
 * - 规则信息：规则内容、执行结果
 * - 嵌套信息：规则执行路径树
 * - JS信息：JS执行环境状态
 */
@Immutable
data class FlowLogItem(
    val id: String = UUID.randomUUID().toString(),
    val requestId: String,
    val sourceUrl: String?,
    val sourceName: String?,
    val sourceType: SourceType = SourceType.BOOK,
    val stage: FlowStage,
    val operation: String?,
    val message: String,
    val detail: String? = null,
    val startTime: Long = System.currentTimeMillis(),
    val duration: Long? = null,
    val url: String? = null,
    val method: String? = null,
    val statusCode: Int? = null,
    val rule: String? = null,
    val result: String? = null,
    val originalValue: String? = null,
    val error: Throwable? = null,
    val executionTree: RuleExecutionTree? = null,
    val jsExecution: JsExecutionRecord? = null,
    val ruleType: RuleType? = null,
    val matchCount: Int? = null,
    val inputPreview: String? = null,
    val outputPreview: String? = null,
    val variableOperations: List<VariableOperation> = emptyList(),
    /** 网络请求时的请求头，由 AnalyzeUrl 在发起请求前传入，用于调试网络问题 */
    val requestHeaders: Map<String, String>? = null,
    /** 网络请求时的 Cookie 值，从 headerMap["Cookie"] 提取，便于独立展示 */
    val cookies: String? = null,
    /** 数据流转记录，记录Book对象在各阶段的填充过程 */
    val dataFlow: BookDataFlow? = null,
    /** 当前处理的书籍对象 */
    val book: Book? = null,
    /** 当前处理的章节对象 */
    val bookChapter: BookChapter? = null,
    /** 当前处理的书源对象 */
    val bookSource: BookSource? = null
) {
    /**
     * 格式化显示时间
     */
    fun formatTime(): String {
        return DebugLogUtils.formatShortTime(startTime)
    }

    fun hasExecutionTree(): Boolean = executionTree != null

    fun hasJsExecution(): Boolean = jsExecution != null

    fun hasVariableOperations(): Boolean = variableOperations.isNotEmpty()

    fun hasDataFlow(): Boolean = dataFlow != null

    fun getVariableSummary(): String {
        if (variableOperations.isEmpty()) return ""
        val reads = variableOperations.count { it.operationType == VariableOperationType.READ }
        val writes = variableOperations.count { it.operationType == VariableOperationType.WRITE }
        val deletes = variableOperations.count { it.operationType == VariableOperationType.DELETE }
        val parts = mutableListOf<String>()
        if (reads > 0) parts.add("读${reads}")
        if (writes > 0) parts.add("写${writes}")
        if (deletes > 0) parts.add("删${deletes}")
        return parts.joinToString(" ")
    }

    fun getSummaryText(): String {
        val parts = mutableListOf<String>()
        ruleType?.let { parts.add("${it.icon}${it.displayName}") }
        matchCount?.let { parts.add("匹配${it}个") }
        DebugLogUtils.formatDuration(duration)?.let { parts.add(it) }
        return parts.filter { it.isNotBlank() }.joinToString(" | ")
    }
}
