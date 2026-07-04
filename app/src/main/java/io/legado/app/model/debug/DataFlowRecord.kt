package io.legado.app.model.debug

import androidx.compose.runtime.Immutable
import java.util.UUID

/**
 * 数据流转阶段
 *
 * 定义书籍数据流转的各个阶段
 */
@Immutable
enum class DataFlowStage(val displayName: String, val icon: String, val order: Int) {
    SEARCH("搜索阶段", "🔍", 1),
    EXPLORE("发现阶段", "📚", 2),
    BOOK_INFO("详情阶段", "📖", 3),
    TOC("目录阶段", "📑", 4),
    CONTENT("正文阶段", "📄", 5);

    companion object {
        fun fromOrder(order: Int): DataFlowStage? = entries.find { it.order == order }
    }
}

/**
 * 字段填充记录
 *
 * 记录单个字段的填充操作，包括：
 * - 字段名称
 * - 填充规则
 * - 填充结果
 * - 原始值（如果有）
 * - 填充时间
 */
@Immutable
data class FieldFillRecord(
    val fieldName: String,
    val rule: String? = null,
    val result: String? = null,
    val originalValue: String? = null,
    val time: Long = System.currentTimeMillis(),
    val isError: Boolean = false,
    val errorMessage: String? = null
) {
    fun hasChange(): Boolean = result != originalValue

    fun getResultPreview(maxLength: Int = 50): String? {
        return result?.take(maxLength)?.let { if (result.length > maxLength) "$it..." else it }
    }

    fun getRulePreview(maxLength: Int = 30): String? {
        return rule?.take(maxLength)?.let { if (rule.length > maxLength) "$it..." else it }
    }
}

/**
 * 阶段数据流转记录
 *
 * 记录单个阶段的所有字段填充操作
 */
@Immutable
data class StageDataFlow(
    val stage: DataFlowStage,
    val fields: List<FieldFillRecord> = emptyList(),
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long? = null
) {
    val duration: Long? get() = endTime?.let { it - startTime }

    fun getChangedFields(): List<FieldFillRecord> = fields.filter { it.hasChange() }

    fun getErrorFields(): List<FieldFillRecord> = fields.filter { it.isError }

    fun hasAnyChange(): Boolean = fields.any { it.hasChange() }

    fun hasAnyError(): Boolean = fields.any { it.isError }

    fun getFieldByName(name: String): FieldFillRecord? = fields.find { it.fieldName == name }
}

/**
 * Book对象数据流转记录
 *
 * 记录Book对象在各阶段的填充过程，提供全局视图
 */
@Immutable
data class BookDataFlow(
    val id: String = UUID.randomUUID().toString(),
    val bookUrl: String? = null,
    val bookName: String? = null,
    val author: String? = null,
    val sourceUrl: String? = null,
    val sourceName: String? = null,
    val stages: List<StageDataFlow> = emptyList(),
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long? = null
) {
    val duration: Long? get() = endTime?.let { it - startTime }

    fun getStage(stage: DataFlowStage): StageDataFlow? = stages.find { it.stage == stage }

    fun getAllFields(): List<Pair<DataFlowStage, FieldFillRecord>> {
        return stages.flatMap { stage ->
            stage.fields.map { field -> Pair(stage.stage, field) }
        }
    }

    fun getAllChangedFields(): List<Pair<DataFlowStage, FieldFillRecord>> {
        return stages.flatMap { stage ->
            stage.getChangedFields().map { field -> Pair(stage.stage, field) }
        }
    }

    fun getAllErrors(): List<Pair<DataFlowStage, FieldFillRecord>> {
        return stages.flatMap { stage ->
            stage.getErrorFields().map { field -> Pair(stage.stage, field) }
        }
    }

    fun hasAnyError(): Boolean = stages.any { it.hasAnyError() }

    fun getSummary(): String {
        val changedCount = getAllChangedFields().size
        val errorCount = getAllErrors().size
        return "共${stages.size}个阶段，${changedCount}个字段变更" +
               if (errorCount > 0) "，${errorCount}个错误" else ""
    }

    fun formatDisplay(): String {
        return buildString {
            appendLine("┌─────────────────────────────────────────────────────┐")
            appendLine("│                    Book 对象填充过程                  │")
            appendLine("├─────────────────────────────────────────────────────┤")
            appendLine("│                                                     │")

            stages.sortedBy { it.stage.order }.forEach { stage ->
                appendLine("│  [${stage.stage.icon}${stage.stage.displayName}]")
                stage.fields.forEach { field ->
                    val changeIndicator = if (field.hasChange()) "←" else "="
                    val ruleStr = field.getRulePreview()?.let { " rule: $it" } ?: ""
                    val resultStr = field.getResultPreview() ?: "(空)"
                    val errorStr = if (field.isError) " ❌" else ""
                    appendLine("│  ├── ${field.fieldName}: \"$resultStr\" $changeIndicator$ruleStr$errorStr")
                }
                appendLine("│                                                     │")
            }

            appendLine("└─────────────────────────────────────────────────────┘")
        }
    }
}

/** 向列表中添加一条字段填充记录。original为空串时自动视为null */
fun MutableList<FieldFillRecord>.recordField(
    fieldName: String,
    rule: String? = null,
    result: String? = null,
    original: String? = null,
    truncate: Boolean = false,
    isError: Boolean = false,
    errorMessage: String? = null
) {
    val truncateFn: (String?) -> String? = if (truncate) { v -> v?.take(100) } else { v -> v }
    add(FieldFillRecord(
        fieldName = fieldName,
        rule = rule,
        result = truncateFn(result),
        originalValue = truncateFn(original?.ifEmpty { null }),
        isError = isError,
        errorMessage = errorMessage
    ))
}

