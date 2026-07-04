package io.legado.app.ui.rss.source.manage

import androidx.fragment.app.viewModels
import io.legado.app.data.entities.RssSource
import io.legado.app.ui.rss.source.edit.RssSourceEditActivity
import io.legado.app.ui.source.BaseContentSearchDialog
import io.legado.app.ui.source.ContentSearchType
import io.legado.app.ui.source.SourceFieldItem
import io.legado.app.utils.GSON
import io.legado.app.utils.share
import io.legado.app.utils.startActivity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 订阅源内容查询对话窗
 */
class RssSourceContentSearchDialog : BaseContentSearchDialog() {

    private val viewModel by viewModels<RssSourceContentSearchViewModel>()

    private var allRssSources: List<RssSource> = emptyList()
    private var cachedJsonStrings: Map<String, String> = emptyMap()

    companion object {
        private val TAB_NAMES = mapOf(
            "base" to "基础",
            "start" to "启动",
            "list" to "列表",
            "webview" to "正文"
        )

        private val TAB_FIELDS = mapOf(
            "base" to listOf(
                "sourceUrl" to "源地址",
                "sourceName" to "源名称",
                "sourceGroup" to "源分组",
                "sourceComment" to "源注释",
                "searchUrl" to "搜索地址",
                "sortUrl" to "分类地址",
                "loginUrl" to "登录地址",
                "loginUi" to "登录界面",
                "loginCheckJs" to "登录检查JS",
                "header" to "请求头",
                "variableComment" to "变量说明",
                "concurrentRate" to "并发率",
                "jsLib" to "jsLib"
            ),
            "start" to listOf(
                "startHtml" to "启动页HTML",
                "startStyle" to "启动页样式",
                "startJs" to "启动页JS",
                "preloadJs" to "预注入JS"
            ),
            "list" to listOf(
                "ruleArticles" to "列表规则",
                "ruleNextPage" to "列表下一页规则",
                "ruleTitle" to "标题规则",
                "rulePubDate" to "时间规则",
                "ruleDescription" to "描述规则",
                "ruleImage" to "图片URL规则",
                "ruleLink" to "链接规则"
            ),
            "webview" to listOf(
                "ruleContent" to "正文规则",
                "style" to "正文样式",
                "injectJs" to "注入JS",
                "shouldOverrideUrlLoading" to "URL拦截",
                "contentWhitelist" to "白名单",
                "contentBlacklist" to "黑名单"
            )
        )
    }

    override fun getDialogTitle() = "订阅源内容查询"

    override fun getSearchHint() = "输入关键词搜索所有订阅源"

    override fun getContentSearchType() = ContentSearchType.RSS_SOURCE

    override fun loadSourceItems(allSources: Boolean, callback: (List<SourceFieldItem>) -> Unit) {
        viewModel.loadSources(allSources) { sources ->
            allRssSources = sources
            cachedJsonStrings = sources.associate { it.sourceUrl to GSON.toJson(it) }
            val items = mutableListOf<SourceFieldItem>()
            for (source in sources) {
                for ((tabKey, fields) in TAB_FIELDS) {
                    for ((fieldKey, fieldName) in fields) {
                        val value = getFieldValue(source, fieldKey) ?: continue
                        if (value.isNotBlank()) {
                            items.add(SourceFieldItem(
                                sourceName = source.sourceName,
                                sourceUrl = source.sourceUrl,
                                tabKey = tabKey,
                                tabName = TAB_NAMES[tabKey] ?: tabKey,
                                fieldKey = fieldKey,
                                fieldName = fieldName,
                                value = value
                            ))
                        }
                    }
                }
            }
            callback(items)
        }
    }

    override suspend fun performSearch(query: String, allItems: List<SourceFieldItem>): List<SourceFieldItem> {
        val queryLower = query.lowercase()
        val contextChars = 50

        return if (searchByRuleField) {
            searchRuleFields(queryLower, query.length, contextChars, allItems)
        } else {
            searchJsonFull(queryLower, query.length, contextChars, allItems)
        }
    }

    override fun navigateToEdit(sourceUrl: String) {
        startActivity<RssSourceEditActivity> {
            putExtra("sourceUrl", sourceUrl)
        }
    }

    override fun getTabNames(): Map<String, String> = TAB_NAMES

    override fun exportSources(sourceUrls: List<String>) {
        viewModel.exportSources(sourceUrls) { file ->
            activity?.share(file)
        }
    }

    private suspend fun searchRuleFields(
        queryLower: String,
        queryLen: Int,
        contextChars: Int,
        allItems: List<SourceFieldItem>
    ): List<SourceFieldItem> {
        val results = mutableListOf<SourceFieldItem>()

        for (item in allItems) {
            currentCoroutineContext().ensureActive()
            if (item.value.lowercase().contains(queryLower)) {
                var startIndex = 0
                val valueLower = item.value.lowercase()
                while (true) {
                    val matchIndex = valueLower.indexOf(queryLower, startIndex)
                    if (matchIndex == -1) break

                    val start = maxOf(0, matchIndex - contextChars)
                    val end = minOf(item.value.length, matchIndex + queryLen + contextChars)
                    val contextText = buildString {
                        if (start > 0) append("...")
                        append(item.value.substring(start, end))
                        if (end < item.value.length) append("...")
                    }

                    results.add(item.copy(value = contextText))
                    startIndex = matchIndex + 1
                }
            }
        }

        return results
    }

    private suspend fun searchJsonFull(
        queryLower: String,
        queryLen: Int,
        contextChars: Int,
        allItems: List<SourceFieldItem>
    ): List<SourceFieldItem> {
        val results = mutableListOf<SourceFieldItem>()
        val sourceUrls = allItems.map { it.sourceUrl }.distinct()

        for (sourceUrl in sourceUrls) {
            currentCoroutineContext().ensureActive()
            val source = allRssSources.find { it.sourceUrl == sourceUrl } ?: continue
            val jsonStr = cachedJsonStrings[source.sourceUrl] ?: continue
            if (!jsonStr.lowercase().contains(queryLower)) continue

            var startIndex = 0
            val jsonLower = jsonStr.lowercase()
            while (true) {
                val matchIndex = jsonLower.indexOf(queryLower, startIndex)
                if (matchIndex == -1) break

                val start = maxOf(0, matchIndex - contextChars)
                val end = minOf(jsonStr.length, matchIndex + queryLen + contextChars)
                val contextText = buildString {
                    if (start > 0) append("...")
                    append(jsonStr.substring(start, end))
                    if (end < jsonStr.length) append("...")
                }

                results.add(SourceFieldItem(
                    sourceName = source.sourceName,
                    sourceUrl = source.sourceUrl,
                    tabKey = "json",
                    tabName = "JSON",
                    fieldKey = "json",
                    fieldName = "JSON全文",
                    value = contextText,
                    fullValue = jsonStr
                ))
                startIndex = matchIndex + 1
            }
        }

        return results
    }

    private fun getFieldValue(source: RssSource, fieldKey: String): String? {
        return when (fieldKey) {
            "sourceUrl" -> source.sourceUrl
            "sourceName" -> source.sourceName
            "sourceGroup" -> source.sourceGroup
            "sourceComment" -> source.sourceComment
            "searchUrl" -> source.searchUrl
            "sortUrl" -> source.sortUrl
            "loginUrl" -> source.loginUrl
            "loginUi" -> source.loginUi
            "loginCheckJs" -> source.loginCheckJs
            "header" -> source.header
            "variableComment" -> source.variableComment
            "concurrentRate" -> source.concurrentRate
            "jsLib" -> source.jsLib
            "startHtml" -> source.startHtml
            "startStyle" -> source.startStyle
            "startJs" -> source.startJs
            "preloadJs" -> source.preloadJs
            "ruleArticles" -> source.ruleArticles
            "ruleNextPage" -> source.ruleNextPage
            "ruleTitle" -> source.ruleTitle
            "rulePubDate" -> source.rulePubDate
            "ruleDescription" -> source.ruleDescription
            "ruleImage" -> source.ruleImage
            "ruleLink" -> source.ruleLink
            "ruleContent" -> source.ruleContent
            "style" -> source.style
            "injectJs" -> source.injectJs
            "shouldOverrideUrlLoading" -> source.shouldOverrideUrlLoading
            "contentWhitelist" -> source.contentWhitelist
            "contentBlacklist" -> source.contentBlacklist
            else -> null
        }
    }
}
