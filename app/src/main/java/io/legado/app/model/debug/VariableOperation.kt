package io.legado.app.model.debug

import androidx.compose.runtime.Immutable

/**
 * 变量操作类型
 */
enum class VariableOperationType(val displayName: String, val icon: String) {
    /** 读取变量 */
    READ("读取", "📥"),
    
    /** 写入变量 */
    WRITE("写入", "📤"),
    
    /** 删除变量 */
    DELETE("删除", "🗑️")
}

/**
 * 变量存储位置
 */
enum class VariableStorage(val displayName: String) {
    CHAPTER("章节变量"),
    BOOK("书籍变量"),
    SOURCE("书源变量"),
    RULE_DATA("规则数据变量"),
    UNKNOWN("未知位置")
}

/**
 * 变量操作记录
 *
 * 记录单次变量的读取或写入操作
 */
@Immutable
data class VariableOperation(
    val operationType: VariableOperationType,
    val key: String,
    val value: String? = null,
    val oldValue: String? = null,
    val storage: VariableStorage = VariableStorage.UNKNOWN,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toDisplayString(): String {
        return when (operationType) {
            VariableOperationType.READ -> "${operationType.icon} 读取 $key = ${value?.take(50) ?: "null"}"
            VariableOperationType.WRITE -> "${operationType.icon} 写入 $key = ${value?.take(50) ?: "null"}"
            VariableOperationType.DELETE -> "${operationType.icon} 删除 $key (原值: ${oldValue?.take(50) ?: "null"})"
        }
    }
}
