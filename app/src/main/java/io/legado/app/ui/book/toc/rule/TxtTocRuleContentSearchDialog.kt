package io.legado.app.ui.book.toc.rule

import androidx.fragment.app.viewModels
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.ui.source.BaseContentSearchDialog
import io.legado.app.ui.source.ContentSearchType
import io.legado.app.ui.source.SourceFieldItem
import io.legado.app.utils.GSON
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class TxtTocRuleContentSearchDialog : BaseContentSearchDialog() {

    private val viewModel by viewModels<TxtTocRuleContentSearchViewModel>()

    private var allRules: List<TxtTocRule> = emptyList()
    private var cachedJsonStrings: Map<String, String> = emptyMap()

    companion object {
        private val TAB_NAMES = mapOf(
            "base" to "基本",
            "rule" to "规则"
        )

        private val TAB_FIELDS = mapOf(
            "base" to listOf(
                "name" to "名称",
                "enable" to "启用状态",
                "id" to "ID",
                "serialNumber" to "排序"
            ),
            "rule" to listOf(
                "rule" to "目录正则",
                "replacement" to "替换规则",
                "example" to "示例"
            )
        )
    }

    override fun getDialogTitle() = "TXT目录规则内容查询"

    override fun getSearchHint() = "输入关键词搜索TXT目录规则"

    override fun getContentSearchType() = ContentSearchType.TXT_TOC_RULE

    override fun loadSourceItems(allSources: Boolean, callback: (List<SourceFieldItem>) -> Unit) {
        viewModel.loadRules(allSources) { rules ->
            allRules = rules
            cachedJsonStrings = rules.associate { it.id.toString() to GSON.toJson(it) }
            val items = mutableListOf<SourceFieldItem>()
            for (rule in rules) {
                val ruleId = rule.id.toString()
                for ((tabKey, fields) in TAB_FIELDS) {
                    for ((fieldKey, fieldName) in fields) {
                        val value = getFieldValue(rule, fieldKey) ?: continue
                        if (value.isNotBlank()) {
                            items.add(
                                SourceFieldItem(
                                    sourceName = rule.name,
                                    sourceUrl = ruleId,
                                    tabKey = tabKey,
                                    tabName = TAB_NAMES[tabKey] ?: tabKey,
                                    fieldKey = fieldKey,
                                    fieldName = fieldName,
                                    value = value
                                )
                            )
                        }
                    }
                }
            }
            callback(items)
        }
    }

    override suspend fun performSearch(
        query: String,
        allItems: List<SourceFieldItem>
    ): List<SourceFieldItem> {
        val queryLower = query.lowercase()
        val contextChars = 50
        return if (searchByRuleField) {
            searchRuleFields(queryLower, query.length, contextChars, allItems)
        } else {
            searchJsonFull(queryLower, query.length, contextChars, allItems)
        }
    }

    override fun navigateToEdit(sourceUrl: String) {
        sourceUrl.toLongOrNull()?.let {
            showDialogFragment(TxtTocRuleEditDialog(it))
        }
    }

    override fun getTabNames(): Map<String, String> = TAB_NAMES

    override fun exportSources(sourceUrls: List<String>) {
        val ruleIds = sourceUrls.mapNotNull { it.toLongOrNull() }
        viewModel.exportRules(ruleIds) { file ->
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
            val value = item.value
            val valueLower = value.lowercase()
            if (!valueLower.contains(queryLower)) continue

            var startIndex = 0
            while (true) {
                val matchIndex = valueLower.indexOf(queryLower, startIndex)
                if (matchIndex == -1) break

                val start = maxOf(0, matchIndex - contextChars)
                val end = minOf(value.length, matchIndex + queryLen + contextChars)
                val contextText = buildString {
                    if (start > 0) append("...")
                    append(value.substring(start, end))
                    if (end < value.length) append("...")
                }
                results.add(item.copy(value = contextText))
                startIndex = matchIndex + 1
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
        val ruleIds = allItems.map { it.sourceUrl }.distinct()
        for (ruleId in ruleIds) {
            currentCoroutineContext().ensureActive()
            val rule = allRules.find { it.id.toString() == ruleId } ?: continue
            val jsonStr = cachedJsonStrings[ruleId] ?: continue
            val jsonLower = jsonStr.lowercase()
            if (!jsonLower.contains(queryLower)) continue

            var startIndex = 0
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
                results.add(
                    SourceFieldItem(
                        sourceName = rule.name,
                        sourceUrl = ruleId,
                        tabKey = "json",
                        tabName = "JSON",
                        fieldKey = "json",
                        fieldName = "JSON全文",
                        value = contextText,
                        fullValue = jsonStr
                    )
                )
                startIndex = matchIndex + 1
            }
        }
        return results
    }

    private fun getFieldValue(rule: TxtTocRule, fieldKey: String): String? {
        return when (fieldKey) {
            "name" -> rule.name
            "enable" -> if (rule.enable) "启用" else "禁用"
            "id" -> rule.id.toString()
            "serialNumber" -> rule.serialNumber.toString()
            "rule" -> rule.rule
            "replacement" -> rule.replacement
            "example" -> rule.example
            else -> null
        }
    }
}
