package io.legado.app.ui.code

import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogRuleSearchBinding
import io.legado.app.databinding.ItemRuleSearchHeaderBinding
import io.legado.app.databinding.ItemRuleSearchResultBinding
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.applyTint
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 规则搜索对话框
 * 用于搜索当前源中所有规则字段的内容
 */
class RuleSearchDialog(
    private val sourceJson: String,
    private val sourceType: String,
    private val onFieldSelected: (tabKey: String, fieldKey: String, cursorPosition: Int) -> Unit
) : BaseDialogFragment(R.layout.dialog_rule_search) {

    private val binding by viewBinding(DialogRuleSearchBinding::bind)

    private var searchJob: Job? = null
    private var currentSearchTerm = ""
    private var sourceJsonObject: JsonObject? = null

    private val expandedGroups = mutableSetOf<String>()
    private val adapter by lazy { SearchAdapter() }

    companion object {
        private const val DEBOUNCE_DELAY = 300L
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_RESULT = 1
    }

    /**
     * 板块配置：板块标识 -> 板块名称
     */
    private val tabNames: Map<String, String> by lazy {
        if (sourceType == "rssSource") {
            mapOf(
                "base" to "基本",
                "start" to "启动",
                "list" to "列表",
                "webview" to "WEB_VIEW"
            )
        } else {
            mapOf(
                "base" to "基本",
                "search" to "搜索",
                "explore" to "发现",
                "info" to "详情",
                "toc" to "目录",
                "content" to "正文"
            )
        }
    }

    /**
     * 字段配置：板块标识 -> 字段列表(字段标识, 字段名称)
     */
    private val tabFields: Map<String, List<Pair<String, String>>> by lazy {
        if (sourceType == "rssSource") {
            mapOf(
                "base" to listOf(
                    "sourceName" to "源名称",
                    "sourceUrl" to "源URL",
                    "sourceIcon" to "图标",
                    "sourceGroup" to "源分组",
                    "sourceComment" to "源注释",
                    "searchUrl" to "搜索地址",
                    "sortUrl" to "分类URL",
                    "loginUrl" to "登录URL",
                    "loginUi" to "登录UI",
                    "loginCheckJs" to "登录检查JS",
                    "coverDecodeJs" to "封面解密",
                    "header" to "请求头",
                    "variableComment" to "变量说明",
                    "concurrentRate" to "并发率",
                    "jsLib" to "jsLib",
                    "startHtml" to "起始页HTML",
                    "startStyle" to "起始页样式",
                    "startJs" to "起始页JS",
                    "preloadJs" to "预加载JS",
                    "ruleArticles" to "列表规则",
                    "ruleNextArticles" to "列表下一页规则",
                    "ruleTitle" to "标题规则",
                    "rulePubDate" to "时间规则",
                    "ruleDescription" to "描述规则",
                    "ruleImage" to "图片URL规则",
                    "ruleLink" to "链接规则",
                    "ruleContent" to "内容规则",
                    "style" to "样式",
                    "injectJs" to "注入JS",
                    "contentWhitelist" to "内容白名单",
                    "contentBlacklist" to "内容黑名单",
                    "shouldOverrideUrlLoading" to "URL跳转拦截"
                )
            )
        } else {
            mapOf(
                "base" to listOf(
                    "bookSourceUrl" to "源地址",
                    "bookSourceName" to "源名称",
                    "bookSourceGroup" to "源分组",
                    "bookSourceComment" to "源注释",
                    "loginUrl" to "登录地址",
                    "loginUi" to "登录界面",
                    "loginCheckJs" to "登录检查JS",
                    "coverDecodeJs" to "封面解密JS",
                    "bookUrlPattern" to "书籍URL正则",
                    "header" to "请求头",
                    "variableComment" to "变量说明",
                    "concurrentRate" to "并发率",
                    "jsLib" to "jsLib"
                ),
                "search" to listOf(
                    "searchUrl" to "搜索地址",
                    "checkKeyWord" to "校验关键字",
                    "bookList" to "书籍列表",
                    "name" to "书名",
                    "author" to "作者",
                    "kind" to "分类",
                    "wordCount" to "字数",
                    "lastChapter" to "最新章节",
                    "intro" to "简介规则",
                    "coverUrl" to "封面规则",
                    "bookUrl" to "书籍URL"
                ),
                "explore" to listOf(
                    "exploreUrl" to "发现地址",
                    "bookList" to "书籍列表",
                    "name" to "书名",
                    "author" to "作者",
                    "kind" to "分类",
                    "wordCount" to "字数",
                    "lastChapter" to "最新章节",
                    "intro" to "简介规则",
                    "coverUrl" to "封面规则",
                    "bookUrl" to "书籍URL"
                ),
                "info" to listOf(
                    "init" to "初始化",
                    "name" to "书名",
                    "author" to "作者",
                    "kind" to "分类",
                    "wordCount" to "字数",
                    "lastChapter" to "最新章节",
                    "intro" to "简介规则",
                    "coverUrl" to "封面规则",
                    "tocUrl" to "目录URL",
                    "canReName" to "允许修改书名作者",
                    "downloadUrls" to "下载地址"
                ),
                "toc" to listOf(
                    "preUpdateJs" to "更新之前JS",
                    "chapterList" to "目录列表规则",
                    "chapterName" to "章节名称",
                    "chapterUrl" to "章节URL",
                    "formatJs" to "格式化规则",
                    "isVolume" to "Volume标识",
                    "updateTime" to "更新时间",
                    "isVip" to "是否VIP",
                    "isPay" to "购买标识",
                    "nextTocUrl" to "目录下一页规则"
                ),
                "content" to listOf(
                    "content" to "正文内容",
                    "nextContentUrl" to "正文下一页URL规则",
                    "subContent" to "副文规则",
                    "replaceRegex" to "替换正则",
                    "ChapterName" to "章节名称规则",
                    "sourceRegex" to "资源正则",
                    "imageStyle" to "图片样式",
                    "imageDecode" to "图片解密",
                    "webJs" to "WebView JS",
                    "payAction" to "购买操作",
                    "callBackJs" to "回调操作"
                )
            )
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, 0.9f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.toolBar.title = "查询规则"
        binding.toolBar.inflateMenu(R.menu.dialog_help_search)
        binding.toolBar.menu.applyTint(requireContext())
        binding.toolBar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_close -> {
                    dismissAllowingStateLoss()
                    true
                }
                else -> false
            }
        }

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        setupSearchInput()
        parseSourceJson()
    }

    /**
     * 解析源JSON
     */
    private fun parseSourceJson() {
        try {
            sourceJsonObject = JsonParser.parseString(sourceJson).asJsonObject
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 设置搜索输入框
     */
    private fun setupSearchInput() {
        binding.searchEditText.setOnEditorActionListener(TextView.OnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                val query = binding.searchEditText.text.toString().trim()
                if (query.isNotEmpty()) {
                    searchJob?.cancel()
                    performSearch(query)
                }
                true
            } else {
                false
            }
        })

        binding.searchEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                searchJob?.cancel()
                binding.clearBtn.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE

                val query = s?.toString()?.trim() ?: ""
                if (query.isEmpty()) {
                    showInitialState()
                    return
                }

                searchJob = lifecycleScope.launch {
                    delay(DEBOUNCE_DELAY)
                    currentSearchTerm = query
                    performSearch(query)
                }
            }
        })

        binding.clearBtn.setOnClickListener {
            binding.searchEditText.text.clear()
            binding.clearBtn.visibility = View.GONE
            showInitialState()
        }
    }

    /**
     * 执行搜索
     */
    private fun performSearch(query: String) {
        val jsonObj = sourceJsonObject ?: return

        lifecycleScope.launch {
            val results = withContext(Dispatchers.Default) {
                searchAllFields(jsonObj, query)
            }
            updateResults(results)
        }
    }

    /**
     * 搜索所有字段
     */
    private fun searchAllFields(jsonObj: JsonObject, query: String): List<TabSearchResult> {
        val results = mutableListOf<TabSearchResult>()
        val queryLower = query.lowercase()
        val contextChars = 50

        for ((tabKey, fields) in tabFields) {
            val matchedFields = mutableListOf<FieldSearchResult>()

            for ((fieldKey, fieldName) in fields) {
                val value = getFieldValue(jsonObj, tabKey, fieldKey) ?: continue
                if (value.lowercase().contains(queryLower)) {
                    // 查找所有匹配位置
                    var startIndex = 0
                    val valueLower = value.lowercase()
                    while (true) {
                        val matchIndex = valueLower.indexOf(queryLower, startIndex)
                        if (matchIndex == -1) break

                        val start = maxOf(0, matchIndex - contextChars)
                        val end = minOf(value.length, matchIndex + query.length + contextChars)
                        val contextText = buildString {
                            if (start > 0) append("...")
                            append(value.substring(start, end))
                            if (end < value.length) append("...")
                        }

                        matchedFields.add(FieldSearchResult(
                            fieldKey = fieldKey,
                            fieldName = fieldName,
                            matchedText = contextText,
                            fullValue = value,
                            searchTerm = query,
                            matchIndex = matchIndex
                        ))
                        startIndex = matchIndex + 1
                    }
                }
            }

            if (matchedFields.isNotEmpty()) {
                results.add(TabSearchResult(
                    tabKey = tabKey,
                    tabName = tabNames[tabKey] ?: tabKey,
                    fields = matchedFields
                ))
            }
        }

        return results
    }

    /**
     * 获取字段值
     */
    private fun getFieldValue(jsonObj: JsonObject, tabKey: String, fieldKey: String): String? {
        if (sourceType == "rssSource") {
            if (!jsonObj.has(fieldKey)) return null
            val element = jsonObj.get(fieldKey)
            return when {
                element.isJsonNull -> null
                element.isJsonPrimitive -> element.asString
                else -> element.toString()
            }
        }
        
        return when (tabKey) {
            "base" -> {
                if (!jsonObj.has(fieldKey)) return null
                val element = jsonObj.get(fieldKey)
                when {
                    element.isJsonNull -> null
                    element.isJsonPrimitive -> element.asString
                    else -> element.toString()
                }
            }
            "search" -> {
                if (fieldKey == "searchUrl") {
                    if (!jsonObj.has("searchUrl")) return null
                    val element = jsonObj.get("searchUrl")
                    when {
                        element.isJsonNull -> null
                        element.isJsonPrimitive -> element.asString
                        else -> element.toString()
                    }
                } else {
                    val rule = jsonObj.getAsJsonObject("ruleSearch")
                    if (rule == null || !rule.has(fieldKey)) return null
                    val element = rule.get(fieldKey)
                    when {
                        element.isJsonNull -> null
                        element.isJsonPrimitive -> element.asString
                        else -> element.toString()
                    }
                }
            }
            "explore" -> {
                if (fieldKey == "exploreUrl") {
                    if (!jsonObj.has("exploreUrl")) return null
                    val element = jsonObj.get("exploreUrl")
                    when {
                        element.isJsonNull -> null
                        element.isJsonPrimitive -> element.asString
                        else -> element.toString()
                    }
                } else {
                    val rule = jsonObj.getAsJsonObject("ruleExplore")
                    if (rule == null || !rule.has(fieldKey)) return null
                    val element = rule.get(fieldKey)
                    when {
                        element.isJsonNull -> null
                        element.isJsonPrimitive -> element.asString
                        else -> element.toString()
                    }
                }
            }
            "info" -> {
                val rule = jsonObj.getAsJsonObject("ruleBookInfo")
                if (rule == null || !rule.has(fieldKey)) return null
                val element = rule.get(fieldKey)
                when {
                    element.isJsonNull -> null
                    element.isJsonPrimitive -> element.asString
                    else -> element.toString()
                }
            }
            "toc" -> {
                val rule = jsonObj.getAsJsonObject("ruleToc")
                if (rule == null || !rule.has(fieldKey)) return null
                val element = rule.get(fieldKey)
                when {
                    element.isJsonNull -> null
                    element.isJsonPrimitive -> element.asString
                    else -> element.toString()
                }
            }
            "content" -> {
                val rule = jsonObj.getAsJsonObject("ruleContent")
                if (rule == null || !rule.has(fieldKey)) return null
                val element = rule.get(fieldKey)
                when {
                    element.isJsonNull -> null
                    element.isJsonPrimitive -> element.asString
                    else -> element.toString()
                }
            }
            "article" -> {
                val rule = jsonObj.getAsJsonObject("ruleArticle")
                if (rule == null || !rule.has(fieldKey)) return null
                val element = rule.get(fieldKey)
                when {
                    element.isJsonNull -> null
                    element.isJsonPrimitive -> element.asString
                    else -> element.toString()
                }
            }
            else -> null
        }
    }

    /**
     * 更新搜索结果
     */
    private fun updateResults(results: List<TabSearchResult>) {
        if (results.isEmpty()) {
            showEmptyState()
        } else {
            showResultsState(results)
        }
    }

    private fun showInitialState() {
        binding.recyclerView.visibility = View.GONE
        binding.emptyStateLayout.visibility = View.GONE
        binding.resultCountText.visibility = View.GONE
        binding.initialStateLayout.visibility = View.VISIBLE
    }

    private fun showEmptyState() {
        binding.recyclerView.visibility = View.GONE
        binding.initialStateLayout.visibility = View.GONE
        binding.resultCountText.visibility = View.GONE
        binding.emptyStateLayout.visibility = View.VISIBLE
    }

    private fun showResultsState(results: List<TabSearchResult>) {
        binding.initialStateLayout.visibility = View.GONE
        binding.emptyStateLayout.visibility = View.GONE
        binding.recyclerView.visibility = View.VISIBLE
        binding.resultCountText.visibility = View.VISIBLE

        val totalCount = results.sumOf { it.fields.size }
        binding.resultCountText.text = "找到 $totalCount 个匹配结果"

        expandedGroups.clear()
        results.forEach { expandedGroups.add(it.tabKey) }

        adapter.setData(results)
        binding.recyclerView.scrollToPosition(0)
    }

    /**
     * 高亮显示匹配文本
     */
    private fun highlightText(text: String, searchTerm: String): SpannableString {
        val spannable = SpannableString(text)
        val termLower = searchTerm.lowercase()
        val textLower = text.lowercase()
        var startIndex = 0
        val highlightColor = ContextCompat.getColor(requireContext(), R.color.accent)
        val bgColor = android.graphics.Color.argb(
            60,
            android.graphics.Color.red(highlightColor),
            android.graphics.Color.green(highlightColor),
            android.graphics.Color.blue(highlightColor)
        )

        while (true) {
            val index = textLower.indexOf(termLower, startIndex)
            if (index == -1) break
            spannable.setSpan(
                BackgroundColorSpan(bgColor),
                index,
                index + searchTerm.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            startIndex = index + searchTerm.length
        }
        return spannable
    }

    /**
     * 搜索适配器
     */
    private inner class SearchAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val items = mutableListOf<SearchListItem>()
        private var tabResults: List<TabSearchResult> = emptyList()

        fun setData(results: List<TabSearchResult>) {
            tabResults = results
            rebuildItems()
            notifyDataSetChanged()
        }

        private fun rebuildItems() {
            items.clear()
            for (result in tabResults) {
                items.add(SearchListItem.Header(result))
                if (expandedGroups.contains(result.tabKey)) {
                    result.fields.forEach { field ->
                        items.add(SearchListItem.Result(result, field))
                    }
                }
            }
        }

        override fun getItemViewType(position: Int): Int {
            return when (items[position]) {
                is SearchListItem.Header -> VIEW_TYPE_HEADER
                is SearchListItem.Result -> VIEW_TYPE_RESULT
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when (viewType) {
                VIEW_TYPE_HEADER -> {
                    val binding = ItemRuleSearchHeaderBinding.inflate(
                        LayoutInflater.from(parent.context), parent, false
                    )
                    HeaderViewHolder(binding)
                }
                else -> {
                    val binding = ItemRuleSearchResultBinding.inflate(
                        LayoutInflater.from(parent.context), parent, false
                    )
                    ResultViewHolder(binding)
                }
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is SearchListItem.Header -> {
                    (holder as HeaderViewHolder).bind(item.result)
                }
                is SearchListItem.Result -> {
                    (holder as ResultViewHolder).bind(item.tabResult, item.fieldResult)
                }
            }
        }

        override fun getItemCount() = items.size

        private inner class HeaderViewHolder(
            private val binding: ItemRuleSearchHeaderBinding
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(result: TabSearchResult) {
                binding.tabNameText.text = result.tabName
                binding.matchCountText.text = "${result.fields.size} 个匹配"

                val isExpanded = expandedGroups.contains(result.tabKey)
                binding.expandIcon.rotation = if (isExpanded) 180f else 0f

                binding.root.setOnClickListener {
                    val tabKey = result.tabKey
                    if (expandedGroups.contains(tabKey)) {
                        expandedGroups.remove(tabKey)
                    } else {
                        expandedGroups.add(tabKey)
                    }
                    rebuildItems()
                    notifyDataSetChanged()
                }
            }
        }

        private inner class ResultViewHolder(
            private val binding: ItemRuleSearchResultBinding
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(tabResult: TabSearchResult, fieldResult: FieldSearchResult) {
                binding.fieldNameText.text = fieldResult.fieldName
                binding.matchedTextText.text = highlightText(fieldResult.matchedText, fieldResult.searchTerm)

                binding.root.setOnClickListener {
                    onFieldSelected(tabResult.tabKey, fieldResult.fieldKey, fieldResult.matchIndex)
                    dismiss()
                }
            }
        }
    }
}

private sealed class SearchListItem {
    data class Header(val result: TabSearchResult) : SearchListItem()
    data class Result(val tabResult: TabSearchResult, val fieldResult: FieldSearchResult) : SearchListItem()
}

private data class TabSearchResult(
    val tabKey: String,
    val tabName: String,
    val fields: List<FieldSearchResult>
)

private data class FieldSearchResult(
    val fieldKey: String,
    val fieldName: String,
    val matchedText: String,
    val fullValue: String,
    val searchTerm: String,
    val matchIndex: Int = 0
)
