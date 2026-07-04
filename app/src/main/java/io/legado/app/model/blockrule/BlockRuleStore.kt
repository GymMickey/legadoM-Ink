package io.legado.app.model.blockrule

import android.content.Context
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.SearchBook
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import java.util.UUID

/**
 * 屏蔽规则存储管理
 *
 * 使用 SharedPreferences + JSON 序列化存储屏蔽规则列表，
 * 提供规则的加载、保存、过滤和清洗功能。
 * 内置内存缓存避免频繁反序列化。
 */
object BlockRuleStore {

    /** 内存缓存，避免频繁读取 SharedPreferences 和反序列化 */
    @Volatile
    private var cachedRules: List<BlockRule>? = null

    /**
     * 加载所有屏蔽规则
     * 优先从缓存读取，缓存未命中时从 SharedPreferences 反序列化
     */
    fun load(context: Context): MutableList<BlockRule> {
        cachedRules?.let { return it.toMutableList() }
        val stored = context.getPrefString(PreferKey.blockRuleItems)
        if (stored.isNullOrBlank()) {
            return mutableListOf()
        }
        val rules = GSON.fromJsonArray<BlockRule>(stored).getOrNull()?.toMutableList()
        if (rules != null) {
            val sanitized = rules.map { sanitizeRule(it) }
            cachedRules = sanitized
            BlockRuleGroupStore.ensureFromRules(context, sanitized)
            return sanitized.toMutableList()
        }
        return mutableListOf()
    }

    /** 加载已启用且有匹配模式的规则 */
    fun loadEnabled(context: Context): List<BlockRule> {
        return load(context).filter { it.enabled && it.pattern.isNotBlank() }
    }

    /**
     * 保存规则列表
     * 同时更新缓存和 SharedPreferences，并同步分组信息
     * 自动处理 id 重复的情况，确保每个规则的 id 都是唯一的
     */
    fun save(context: Context, rules: List<BlockRule>) {
        // 检查并修复 id 重复的情况
        val usedIds = mutableSetOf<String>()
        val normalized = rules.map { rule ->
            var sanitized = sanitizeRule(rule)
            // 如果 id 已被使用，生成新的唯一 id
            while (sanitized.id in usedIds) {
                sanitized = sanitized.copyWithNewId()
            }
            usedIds.add(sanitized.id)
            sanitized
        }
        cachedRules = normalized
        context.putPrefString(PreferKey.blockRuleItems, GSON.toJson(normalized))
        BlockRuleGroupStore.ensureFromRules(context, normalized)
    }

    /**
     * 核心过滤方法：返回被屏蔽规则过滤后的书籍列表
     * 遍历所有已启用的规则，移除匹配的书籍
     */
    fun filterBooks(context: Context, books: List<SearchBook>, sourceUrl: String): List<SearchBook> {
        if (!context.getPrefBoolean(PreferKey.blockRuleEnabled, true)) return books
        val rules = loadEnabled(context)
        if (rules.isEmpty()) return books
        return books.filterNot { book ->
            rules.any { rule -> rule.matches(book) && rule.matchesScope(sourceUrl) }
        }
    }

    /**
     * 搜索结果过滤：每本书有独立的书源URL，按各自origin匹配作用域
     */
    fun filterSearchBooks(context: Context, books: List<SearchBook>): List<SearchBook> {
        if (!context.getPrefBoolean(PreferKey.blockRuleEnabled, true)) return books
        val rules = loadEnabled(context)
        if (rules.isEmpty()) return books
        return books.filterNot { book ->
            rules.any { rule -> rule.matches(book) && rule.matchesScope(book.origin) }
        }
    }

    /**
     * RSS文章过滤：标题匹配 SCOPE_RSS_TITLE，时间匹配 SCOPE_RSS_TIME
     * 使用 rssScope 字段匹配订阅源作用域
     */
    fun filterRssArticles(context: Context, articles: List<RssArticle>, sourceUrl: String): List<RssArticle> {
        if (!context.getPrefBoolean(PreferKey.blockRuleEnabled, true)) return articles
        val rules = loadEnabled(context)
        if (rules.isEmpty()) return articles
        return articles.filterNot { article ->
            rules.any { rule -> rule.matchesRssArticle(article) && rule.matchesRssScope(sourceUrl) }
        }
    }

    /**
     * 获取实际匹配到书籍的规则列表
     * 返回在指定书籍列表和书源下，至少匹配了一本书的规则
     */
    fun getMatchedRules(context: Context, books: List<SearchBook>, sourceUrl: String): List<BlockRule> {
        val rules = loadEnabled(context)
        if (rules.isEmpty() || books.isEmpty()) return emptyList()
        return rules.filter { rule ->
            rule.matchesScope(sourceUrl) && books.any { book -> rule.matches(book) }
        }
    }

    /**
     * 获取实际匹配到RSS文章的规则列表
     * 返回在指定文章列表和订阅源下，至少匹配了一篇文章的规则
     */
    fun getMatchedRssRules(context: Context, articles: List<RssArticle>, sourceUrl: String): List<BlockRule> {
        val rules = loadEnabled(context)
        if (rules.isEmpty() || articles.isEmpty()) return emptyList()
        return rules.filter { rule ->
            rule.matchesRssScope(sourceUrl) && articles.any { article -> rule.matchesRssArticle(article) }
        }
    }

    /** 清除缓存，下次加载时重新从 SharedPreferences 读取 */
    fun invalidateCache() {
        cachedRules = null
    }

    /**
     * 清洗规则数据，确保字段合法
     * 处理缺失字段、空值、越界值等情况
     */
    fun sanitizeRule(
        rule: BlockRule,
        fallbackGroup: String = BlockRuleGroupStore.DEFAULT_GROUP,
    ): BlockRule {
        val name = runCatching { rule.name }.getOrNull().orEmpty()
        val pattern = runCatching { rule.pattern }.getOrNull().orEmpty()
        val group = runCatching { rule.group }.getOrNull().orEmpty().ifBlank { fallbackGroup }
        val id = runCatching { rule.id }.getOrNull().orEmpty().ifBlank {
            UUID.randomUUID().toString()
        }
        val scope = runCatching { rule.scope }.getOrNull()?.takeIf { it.isNotBlank() }
        val rssScope = runCatching { rule.rssScope }.getOrNull()?.takeIf { it.isNotBlank() }

        // 处理作用范围字段，包含旧版数据迁移
        var bookScope = runCatching { rule.targetScope }.getOrDefault(0)
        var rssScopeFlags = runCatching { rule.rssTargetScope }.getOrDefault(0)

        // 迁移旧版数据：旧版 targetScope 同时包含书源和订阅源的位标志
        // 仅当 rssTargetScope 为 0（新字段未设置）且 targetScope 含旧版 RSS 特有高位（SCOPE_RSS_TIME_LEGACY=16）时才迁移。
        if (rssScopeFlags == 0 && (bookScope and BlockRule.SCOPE_RSS_TIME_LEGACY) != 0) {
            // 旧版 SCOPE_TITLE 同时表示书源标题和订阅源标题
            if ((bookScope and BlockRule.SCOPE_TITLE) != 0) {
                rssScopeFlags = rssScopeFlags or BlockRule.SCOPE_RSS_TITLE
            }
            // 旧版 SCOPE_INTRO 同时表示书源简介和订阅源描述
            if ((bookScope and BlockRule.SCOPE_INTRO) != 0) {
                rssScopeFlags = rssScopeFlags or BlockRule.SCOPE_RSS_INTRO
            }
            // 旧版 SCOPE_RSS_TIME_LEGACY (bit 16) 仅用于订阅源
            if ((bookScope and BlockRule.SCOPE_RSS_TIME_LEGACY) != 0) {
                rssScopeFlags = rssScopeFlags or BlockRule.SCOPE_RSS_TIME
                bookScope = bookScope and BlockRule.SCOPE_RSS_TIME_LEGACY.inv() // 清除旧版 RSS 位
            }
        }

        // 清除已废弃的 SCOPE_RSS_INTRO 位（订阅源简介无实际内容）
        rssScopeFlags = rssScopeFlags and BlockRule.SCOPE_RSS_INTRO.inv()

        bookScope = bookScope.coerceIn(0, BlockRule.SCOPE_BOOK_ALL)
        rssScopeFlags = rssScopeFlags.coerceIn(0, BlockRule.SCOPE_RSS_ALL)

        // 后端安全验证：作用范围位掩码为零时，对应的指定源列表无意义，予以清除
        val validatedScope = if (bookScope == 0) null else scope
        val validatedRssScope = if (rssScopeFlags == 0) null else rssScope

        return BlockRule(
            id = id,
            name = name,
            pattern = pattern,
            isRegex = runCatching { rule.isRegex }.getOrDefault(false),
            group = group,
            targetScope = bookScope,
            rssTargetScope = rssScopeFlags,
            enabled = runCatching { rule.enabled }.getOrDefault(true),
            scope = validatedScope,
            rssScope = validatedRssScope,
        )
    }
}
