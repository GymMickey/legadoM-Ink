package io.legado.app.ui.dict.rule

import androidx.fragment.app.viewModels
import io.legado.app.data.entities.DictRule
import io.legado.app.ui.source.BaseContentSearchDialog
import io.legado.app.ui.source.ContentSearchType
import io.legado.app.ui.source.SourceFieldItem
import io.legado.app.utils.GSON
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class DictRuleContentSearchDialog : BaseContentSearchDialog() {

    private val viewModel by viewModels<DictRuleContentSearchViewModel>()

    private var allRules: List<DictRule> = emptyList()
    private var cachedJsonStrings: Map<String, String> = emptyMap()

    companion object {
        private val TAB_NAMES = mapOf(
            "base" to "基本",
            "rule" to "规则"
        )

        private val TAB_FIELDS = mapOf(
            "base" to listOf(
                "name" to "名称",
                "enabled" to "启用状态",
                "sortNumber" to "排序"
            ),
            "rule" to listOf(
                "urlRule" to "URL规则",
                "showRule" to "显示规则"
            )
        )
    }

    override fun getDialogTitle() = "字典规则内容查询"

    override fun getSearchHint() = "输入关键词搜索字典规则"

    override fun getContentSearchType() = ContentSearchType.DICT_RULE

    override fun loadSourceItems(allSources: Boolean, callback: (List<SourceFieldItem>) -> Unit) {
        viewModel.loadRules(allSources) { rules ->
            allRules = rules
            cachedJsonStrings = rules.associate { it.name to GSON.toJson(it) }
            val items = mutableListOf<SourceFieldItem>()
            for (rule in rules) {
                val ruleName = rule.name.ifBlank { "未命名" }
                for ((tabKey, fields) in TAB_FIELDS) {
                    for ((fieldKey, fieldName) in fields) {
                        val value = getFieldValue(rule, fieldKey) ?: continue
                        if (value.isNotBlank()) {
                            items.add(
                                SourceFieldItem(
                                    sourceName = ruleName,
                                    sourceUrl = rule.name,
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
        showDialogFragment(DictRuleEditDialog(sourceUrl))
    }

    override fun getTabNames(): Map<String, String> = TAB_NAMES

    override fun exportSources(sourceUrls: List<String>) {
        viewModel.exportRules(sourceUrls) { file ->
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
        val ruleNames = allItems.map { it.sourceUrl }.distinct()
        for (ruleName in ruleNames) {
            currentCoroutineContext().ensureActive()
            val rule = allRules.find { it.name == ruleName } ?: continue
            val jsonStr = cachedJsonStrings[ruleName] ?: continue
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
                        sourceName = rule.name.ifBlank { "未命名" },
                        sourceUrl = rule.name,
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

    private fun getFieldValue(rule: DictRule, fieldKey: String): String? {
        return when (fieldKey) {
            "name" -> rule.name
            "enabled" -> if (rule.enabled) "启用" else "禁用"
            "sortNumber" -> rule.sortNumber.toString()
            "urlRule" -> rule.urlRule
            "showRule" -> rule.showRule
            else -> null
        }
    }
}
