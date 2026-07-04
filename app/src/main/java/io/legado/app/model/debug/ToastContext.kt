package io.legado.app.model.debug

import androidx.compose.runtime.Immutable

/**
 * Toast 上下文信息
 *
 * 记录 Toast 显示时的上下文，包括：
 * - 显示界面（Activity 名称）
 * - 源信息（如果是书源/订阅源相关）
 * - 规则信息（哪个规则的哪一行触发）
 */
@Immutable
data class ToastContext(
    val activityName: String? = null,
    val sourceName: String? = null,
    val sourceType: ToastSourceType? = null,
    val ruleType: ToastRuleType? = null,
    val ruleLine: Int? = null
) {
    fun hasSourceContext(): Boolean {
        return sourceName != null || sourceType != null || ruleType != null
    }
    
    fun toTagsMap(): Map<String, String> {
        val tags = mutableMapOf<String, String>()
        activityName?.let { tags[KEY_ACTIVITY] = it }
        sourceName?.let { tags[KEY_SOURCE_NAME] = it }
        sourceType?.let { tags[KEY_SOURCE_TYPE] = it.displayName }
        ruleType?.let { tags[KEY_RULE_TYPE] = it.displayName }
        ruleLine?.let { tags[KEY_RULE_LINE] = it.toString() }
        return tags
    }
    
    companion object {
        const val KEY_ACTIVITY = "activity"
        const val KEY_SOURCE_NAME = "sourceName"
        const val KEY_SOURCE_TYPE = "sourceType"
        const val KEY_RULE_TYPE = "ruleType"
        const val KEY_RULE_LINE = "ruleLine"
        
        fun fromTagsMap(tags: Map<String, String>): ToastContext {
            return ToastContext(
                activityName = tags[KEY_ACTIVITY],
                sourceName = tags[KEY_SOURCE_NAME],
                sourceType = tags[KEY_SOURCE_TYPE]?.let { 
                    ToastSourceType.entries.find { type -> type.displayName == it }
                },
                ruleType = tags[KEY_RULE_TYPE]?.let {
                    ToastRuleType.entries.find { type -> type.displayName == it }
                },
                ruleLine = tags[KEY_RULE_LINE]?.toIntOrNull()
            )
        }
    }
}

enum class ToastSourceType(val displayName: String) {
    BOOK("书源"),
    RSS("订阅源")
}

enum class ToastRuleType(val displayName: String) {
    SEARCH("搜索规则"),
    EXPLORE("发现规则"),
    BOOK_INFO("书籍信息规则"),
    TOC("目录规则"),
    CONTENT("正文规则"),
    RSS_INFO("订阅源信息规则"),
    RSS_ARTICLE("文章规则"),
    RSS_CONTENT("内容规则"),
    OTHER("其他规则")
}
