package io.legado.app.model.blockrule

import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.SearchBook
import java.util.UUID

/**
 * 屏蔽规则数据模型
 *
 * 用于屏蔽发现、搜索、订阅列表中匹配关键词或正则表达式的内容。
 * 书源作用范围（targetScope）和订阅源作用范围（rssTargetScope）各自独立控制，
 * 支持仅作用于书源、仅作用于订阅源，或同时作用于两者。
 *
 * 存储方式：SharedPreferences + JSON 序列化，由 [BlockRuleStore] 管理
 */
data class BlockRule(
    /** 唯一标识，使用 UUID 确保唯一性 */
    var id: String = UUID.randomUUID().toString(),
    /** 规则名称 */
    var name: String = "",
    /** 匹配模式：关键词或正则表达式 */
    var pattern: String = "",
    /** 是否启用正则匹配，false=关键词匹配 */
    var isRegex: Boolean = false,
    /** 所属分组 */
    var group: String = BlockRuleGroupStore.DEFAULT_GROUP,
    /** 书源作用范围（位掩码），0=不作用于书源 */
    var targetScope: Int = 0,
    /** 订阅源作用范围（位掩码），0=不作用于订阅源 */
    var rssTargetScope: Int = 0,
    /** 是否启用该规则 */
    var enabled: Boolean = true,
    /** 作用的书源，书源URL分号分隔，为空则对所有书源生效 */
    var scope: String? = null,
    /** 作用的订阅源，订阅源URL分号分隔，为空则对所有订阅源生效 */
    var rssScope: String? = null,
) {

    /** 检查书源作用范围是否包含指定的标志 */
    fun hasScope(flag: Int): Boolean = (targetScope and flag) != 0

    /** 检查订阅源作用范围是否包含指定的标志 */
    fun hasRssScope(flag: Int): Boolean = (rssTargetScope and flag) != 0

    /**
     * 判断书籍是否匹配该屏蔽规则
     * 根据书源作用范围（位掩码）选择匹配字段，仅当 targetScope != 0 时生效
     */
    fun matches(book: SearchBook): Boolean {
        if (targetScope == 0) return false
        val searchTargets = mutableListOf<String>()
        if (hasScope(SCOPE_TITLE)) searchTargets.add(book.name)
        if (hasScope(SCOPE_AUTHOR)) searchTargets.add(book.author)
        if (hasScope(SCOPE_KIND)) searchTargets.add(book.kind.orEmpty())
        if (hasScope(SCOPE_INTRO)) searchTargets.add(book.intro.orEmpty())
        if (hasScope(SCOPE_WORD_COUNT)) searchTargets.add(book.wordCount.orEmpty())
        if (searchTargets.isEmpty()) return false
        return searchTargets.any { text ->
            if (isRegex) {
                runCatching { Regex(pattern).containsMatchIn(text) }.getOrDefault(false)
            } else {
                text.contains(pattern)
            }
        }
    }

    /**
     * 判断规则是否对指定书源生效
     * - scope 为空时，默认对所有书源生效
     * - scope 非空时，仅对匹配书源URL的书源生效
     */
    fun matchesScope(sourceUrl: String): Boolean {
        val scopeVal = scope
        if (!scopeVal.isNullOrBlank()) {
            val items = scopeVal.split(";").map { it.trim() }.filter { it.isNotBlank() }
            if (!items.any { sourceUrl.contains(it) }) return false
        }
        return true
    }

    /**
     * 判断规则是否对指定订阅源生效
     * - rssScope 为空时，默认对所有订阅源生效
     * - rssScope 非空时，仅对匹配订阅源URL的订阅源生效
     */
    fun matchesRssScope(sourceUrl: String): Boolean {
        val rssScopeVal = rssScope
        if (!rssScopeVal.isNullOrBlank()) {
            val items = rssScopeVal.split(";").map { it.trim() }.filter { it.isNotBlank() }
            if (!items.any { sourceUrl.contains(it) }) return false
        }
        return true
    }

    /**
     * 判断RSS文章是否匹配该屏蔽规则
     * 根据订阅源作用范围（位掩码）选择匹配字段，仅当 rssTargetScope != 0 时生效
     * 标题对应 SCOPE_RSS_TITLE，时间对应 SCOPE_RSS_TIME
     */
    fun matchesRssArticle(article: RssArticle): Boolean {
        if (rssTargetScope == 0) return false
        val searchTargets = mutableListOf<String>()
        if (hasRssScope(SCOPE_RSS_TITLE)) searchTargets.add(article.title)
        if (hasRssScope(SCOPE_RSS_TIME)) searchTargets.add(article.pubDate.orEmpty())
        if (searchTargets.isEmpty()) return false
        return searchTargets.any { text ->
            if (isRegex) {
                runCatching { Regex(pattern).containsMatchIn(text) }.getOrDefault(false)
            } else {
                text.contains(pattern)
            }
        }
    }

    /** 返回作用范围摘要文本，分别显示书源和订阅源的作用范围 */
    fun scopeSummary(): String {
        val bookParts = mutableListOf<String>()
        if (hasScope(SCOPE_TITLE)) bookParts.add("标题")
        if (hasScope(SCOPE_AUTHOR)) bookParts.add("作者")
        if (hasScope(SCOPE_KIND)) bookParts.add("分类")
        if (hasScope(SCOPE_INTRO)) bookParts.add("简介")
        if (hasScope(SCOPE_WORD_COUNT)) bookParts.add("字数")

        val rssParts = mutableListOf<String>()
        if (hasRssScope(SCOPE_RSS_TITLE)) rssParts.add("标题")
        if (hasRssScope(SCOPE_RSS_TIME)) rssParts.add("时间")

        val result = mutableListOf<String>()
        if (bookParts.isNotEmpty()) result.add("书源: ${bookParts.joinToString(", ")}")
        if (rssParts.isNotEmpty()) result.add("订阅源: ${rssParts.joinToString(", ")}")
        return if (result.isEmpty()) "无" else result.joinToString(" | ")
    }

    /** 返回匹配模式标签："关键词" 或 "正则" */
    fun modeLabel(): String {
        return if (isRegex) "正则" else "关键词"
    }

    /** 复制规则并生成新的ID，用于导入时避免ID冲突 */
    fun copyWithNewId(): BlockRule {
        return copy(id = UUID.randomUUID().toString())
    }

    companion object {
        // 书源作用范围位标志（用于 targetScope 字段）
        const val SCOPE_TITLE = 1       // 0b00001 标题
        const val SCOPE_AUTHOR = 2      // 0b00010 作者
        const val SCOPE_KIND = 4         // 0b00100 分类
        const val SCOPE_INTRO = 8       // 0b01000 简介
        const val SCOPE_WORD_COUNT = 32 // 0b100000 字数
        const val SCOPE_BOOK_ALL = 47   // 0b101111 书源全部（标题|作者|分类|简介|字数）

        // 订阅源作用范围位标志（用于 rssTargetScope 字段）
        const val SCOPE_RSS_TITLE = 1   // 0b0001 标题
        const val SCOPE_RSS_TIME = 4    // 0b0100 时间
        const val SCOPE_RSS_ALL = 5     // 0b0101 订阅源全部（标题+时间）

        // 旧版常量（仅用于数据迁移）
        @Deprecated("订阅源简介无实际内容，已废弃")
        const val SCOPE_RSS_INTRO = 2           // 旧版订阅源简介位（已废弃）
        const val SCOPE_RSS_TIME_LEGACY = 16   // 旧版订阅源时间位（在 targetScope 中）
        const val SCOPE_ALL = 31                // 旧版全部位（兼容旧数据）
    }
}
