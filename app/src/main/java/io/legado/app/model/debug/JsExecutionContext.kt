package io.legado.app.model.debug

import androidx.compose.runtime.Immutable

/**
 * JS执行环境状态
 *
 * 记录JS执行时的上下文信息，包括：
 * - 可用变量及其值
 * - 变量变化过程
 * - 执行耗时
 *
 * 示例：
 * ```
 * JS执行环境:
 * ├── result: "原始内容..."
 * ├── src: "<html>...</html>"
 * ├── baseUrl: "https://example.com"
 * ├── book: {name: "斗破苍穹", author: "天蚕土豆"}
 * ├── chapter: {title: "第一章", index: 1}
 * └── source: {name: "示例书源", url: "https://..."}
 * ```
 */
@Immutable
data class JsExecutionContext(
    val result: String? = null,
    val src: String? = null,
    val baseUrl: String? = null,
    val book: BookContext? = null,
    val chapter: ChapterContext? = null,
    val source: SourceContext? = null,
    val variables: Map<String, String> = emptyMap(),
    val nextChapterUrl: String? = null,
    val fromBookInfo: Boolean = false
) {
    fun toDisplayString(): String {
        val sb = StringBuilder()
        sb.append("JS执行环境:\n")
        result?.let { sb.append("├── result: ${it.take(100)}${if (it.length > 100) "..." else ""}\n") }
        src?.let { sb.append("├── src: ${it.take(100)}${if (it.length > 100) "..." else ""}\n") }
        baseUrl?.let { sb.append("├── baseUrl: $it\n") }
        book?.let { sb.append("├── book: $it\n") }
        chapter?.let { sb.append("├── chapter: $it\n") }
        source?.let { sb.append("├── source: $it\n") }
        nextChapterUrl?.let { sb.append("├── nextChapterUrl: $it\n") }
        if (fromBookInfo) sb.append("├── fromBookInfo: true\n")
        variables.forEach { (key, value) ->
            sb.append("├── $key: ${value.take(50)}${if (value.length > 50) "..." else ""}\n")
        }
        return sb.toString().trimEnd('\n')
    }

    fun toSummaryString(): String {
        val parts = mutableListOf<String>()
        result?.let { parts.add("result=${it.take(20)}...") }
        book?.let { parts.add("book=${it.name}") }
        chapter?.let { parts.add("chapter=${it.title}") }
        return parts.joinToString(", ")
    }
}

/**
 * 书籍上下文
 */
@Immutable
data class BookContext(
    val name: String? = null,
    val author: String? = null,
    val bookUrl: String? = null,
    val coverUrl: String? = null,
    val intro: String? = null,
    val tocUrl: String? = null,
    val variableMap: Map<String, String> = emptyMap()
) {
    override fun toString(): String {
        val parts = mutableListOf<String>()
        name?.let { parts.add("name=\"$it\"") }
        author?.let { parts.add("author=\"$it\"") }
        return "{${parts.joinToString(", ")}}"
    }
}

/**
 * 章节上下文
 */
@Immutable
data class ChapterContext(
    val title: String? = null,
    val url: String? = null,
    val index: Int? = null,
    val variableMap: Map<String, String> = emptyMap()
) {
    override fun toString(): String {
        val parts = mutableListOf<String>()
        title?.let { parts.add("title=\"$it\"") }
        index?.let { parts.add("index=$it") }
        return "{${parts.joinToString(", ")}}"
    }
}

/**
 * 书源上下文
 */
@Immutable
data class SourceContext(
    val name: String? = null,
    val url: String? = null,
    val group: String? = null
) {
    override fun toString(): String {
        val parts = mutableListOf<String>()
        name?.let { parts.add("name=\"$it\"") }
        group?.let { parts.add("group=\"$it\"") }
        return "{${parts.joinToString(", ")}}"
    }
}

/**
 * JS执行记录
 *
 * 记录单次JS执行的完整信息
 */
@Immutable
data class JsExecutionRecord(
    val jsCode: String,
    val context: JsExecutionContext,
    val result: String? = null,
    val duration: Long? = null,
    val error: Throwable? = null,
    val startTime: Long = System.currentTimeMillis()
) {
    fun formatTime(): String {
        return DebugLogUtils.formatShortTime(startTime)
    }

    fun isSuccess(): Boolean = error == null

    fun toDisplayString(): String {
        val sb = StringBuilder()
        sb.append("JS执行记录:\n")
        sb.append("├── 代码: ${jsCode.take(100)}${if (jsCode.length > 100) "..." else ""}\n")
        sb.append("├── 环境: ${context.toSummaryString()}\n")
        result?.let { sb.append("├── 结果: ${it.take(100)}${if (it.length > 100) "..." else ""}\n") }
        DebugLogUtils.formatDuration(duration)?.let { sb.append("├── 耗时: ${it}\n") }
        error?.let { sb.append("└── 错误: ${it.localizedMessage}\n") }
        return sb.toString().trimEnd('\n')
    }
}
