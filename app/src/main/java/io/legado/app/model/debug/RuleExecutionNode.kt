package io.legado.app.model.debug

import androidx.compose.runtime.Immutable
import java.util.UUID

/**
 * 规则执行路径树节点
 *
 * 记录规则执行的完整路径，包括：
 * - 规则内容和类型
 * - 输入输出数据
 * - 执行耗时
 * - 嵌套子规则
 *
 * 示例：
 * ```
 * 规则: @css:.content@textNodes##{{js}}##regex
 *
 * 执行路径:
 * ├── [1] @css:.content (CSS选择器)
 * │   ├── 输入: <html>...</html>
 * │   ├── 匹配: 3 个元素
 * │   └── 输出: <div class="content">...</div>
 * │
 * ├── [2] @textNodes (文本节点提取)
 * │   ├── 输入: <div class="content">...</div>
 * │   └── 输出: "第一段\n第二段\n第三段"
 * │
 * ├── [3] ##{{js}} (JS替换)
 * │   ├── 输入: "第一段\n第二段\n第三段"
 * │   ├── JS 环境: {result: "...", src: "..."}
 * │   └── 输出: "处理后内容"
 * │
 * └── [4] ##regex (正则替换)
 *     ├── 输入: "处理后内容"
 *     ├── 匹配分组: ["group1", "group2"]
 *     └── 输出: "最终结果"
 * ```
 */
@Immutable
data class RuleExecutionNode(
    val id: String = UUID.randomUUID().toString(),
    val stepIndex: Int,
    val ruleType: RuleType,
    val ruleContent: String,
    val input: String? = null,
    val output: String? = null,
    val matchCount: Int? = null,
    val duration: Long? = null,
    val jsContext: JsExecutionContext? = null,
    val regexGroups: List<String>? = null,
    val children: List<RuleExecutionNode> = emptyList(),
    val error: Throwable? = null,
    val startTime: Long = System.currentTimeMillis()
) {
    fun formatTime(): String {
        return DebugLogUtils.formatShortTime(startTime)
    }

    fun isSuccess(): Boolean = error == null && children.all { it.isSuccess() }

    fun totalDuration(): Long {
        return (duration ?: 0) + children.sumOf { it.totalDuration() }
    }
}

/**
 * 规则类型枚举
 */
enum class RuleType(val displayName: String, val icon: String) {
    CSS("CSS选择器", "🎨"),
    XPATH("XPath", "🔍"),
    JSONPATH("JSONPath", "📊"),
    JS("JavaScript", "📜"),
    WEB_JS("WebJS", "🌐"),
    REGEX("正则表达式", "🔄"),
    REPLACE("数据替换", "✏️"),
    GET("变量读取", "📥"),
    PUT("变量写入", "📤"),
    DEFAULT("默认规则", "⚙️"),
    ROOT("根节点", "🌳");

    companion object {
        fun fromMode(mode: String): RuleType {
            return when (mode.uppercase()) {
                "CSS", "DEFAULT" -> CSS
                "XPATH" -> XPATH
                "JSONPATH", "JSON" -> JSONPATH
                "JS" -> JS
                "WEBJS", "WEB_JS" -> WEB_JS
                "REGEX" -> REGEX
                else -> DEFAULT
            }
        }
    }
}

/**
 * 规则执行路径树
 *
 * 包含完整的规则执行路径，用于调试展示
 */
@Immutable
data class RuleExecutionTree(
    val id: String = UUID.randomUUID().toString(),
    val sourceUrl: String?,
    val sourceName: String?,
    val operation: String?,
    val fullRule: String,
    val root: RuleExecutionNode,
    val startTime: Long = System.currentTimeMillis(),
    val totalDuration: Long = root.totalDuration()
) {
    fun formatTime(): String {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(startTime))
    }

    fun formatTotalDuration(): String {
        return when {
            totalDuration < 1000 -> "${totalDuration}ms"
            totalDuration < 60000 -> "${totalDuration / 1000.0}s"
            else -> "${totalDuration / 60000}m ${totalDuration % 60000 / 1000}s"
        }
    }

    fun isSuccess(): Boolean = root.isSuccess()

    fun flatten(): List<RuleExecutionNode> {
        val result = mutableListOf<RuleExecutionNode>()
        fun collect(node: RuleExecutionNode) {
            result.add(node)
            node.children.forEach { collect(it) }
        }
        collect(root)
        return result
    }
}
