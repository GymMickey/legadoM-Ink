package io.legado.app.ui.source

import android.os.Bundle
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogRuleSearchBinding
import io.legado.app.databinding.ItemRuleSearchHeaderBinding
import io.legado.app.databinding.ItemRuleSearchResultBinding
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.book.source.manage.SourceContentSearchDialog
import io.legado.app.ui.book.toc.rule.TxtTocRuleContentSearchDialog
import io.legado.app.ui.dict.rule.DictRuleContentSearchDialog
import io.legado.app.ui.replace.ReplaceRuleContentSearchDialog
import io.legado.app.ui.rss.source.manage.RssSourceContentSearchDialog
import io.legado.app.utils.applyTint
import io.legado.app.utils.getPrefString
import io.legado.app.utils.gone
import io.legado.app.utils.putPrefString
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setLayout
import io.legado.app.utils.visible
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 内容查询对话窗基类。
 * 子类只需实现数据加载和搜索逻辑，UI 由基类统一管理。
 */
abstract class BaseContentSearchDialog : BaseDialogFragment(R.layout.dialog_rule_search) {

    protected val binding by viewBinding(DialogRuleSearchBinding::bind)

    protected var searchJob: Job? = null
    protected var currentSearchTerm = ""
    protected val expandedGroups = mutableSetOf<String>()
    protected val adapter by lazy { SearchAdapter() }

    protected var searchByRuleField = true
    protected var searchAllSources = true

    /** 所有可搜索的字段条目，由子类通过 loadSourceItems 填充 */
    protected var allSourceItems: List<SourceFieldItem> = emptyList()
    protected var sourcesLoaded = false
    protected var lastResults: List<SourceFieldItem> = emptyList()

    /** 当前选中的分类 key，"__ALL__" 表示全部分类 */
    protected var selectedTab: String = "__ALL__"
    private var historyRecyclerView: RecyclerView? = null
    private var historyContainer: LinearLayout? = null

    // ========== 子类实现 ==========

    /** 对话窗标题 */
    abstract fun getDialogTitle(): String

    /** 搜索输入框 hint */
    abstract fun getSearchHint(): String

    /**
     * 加载所有源的可搜索字段。
     * 子类应根据 allSources 决定加载范围，通过 callback 返回结果。
     */
    abstract fun loadSourceItems(allSources: Boolean, callback: (List<SourceFieldItem>) -> Unit)

    abstract suspend fun performSearch(query: String, allItems: List<SourceFieldItem>): List<SourceFieldItem>

    /** 点击"跳转"后导航到对应的源编辑界面 */
    abstract fun navigateToEdit(sourceUrl: String)

    abstract fun getTabNames(): Map<String, String>

    abstract fun exportSources(sourceUrls: List<String>)

    open fun getContentSearchType(): ContentSearchType? = null

    // ========== 生命周期 ==========

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, 0.9f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.toolBar.title = getDialogTitle()
        binding.toolBar.inflateMenu(R.menu.dialog_help_search)
        binding.toolBar.menu.findItem(R.id.menu_close)?.setTitle(R.string.switch_rule)
        binding.toolBar.menu.applyTint(requireContext())
        binding.toolBar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_expand_all -> {
                    adapter.expandAll()
                    true
                }
                R.id.menu_collapse_all -> {
                    adapter.collapseAll()
                    true
                }
                R.id.menu_copy_source_urls -> {
                    copyMatchedSourceUrls()
                    true
                }
                R.id.menu_export_sources -> {
                    exportMatchedSources()
                    true
                }
                R.id.menu_close -> {
                    showContentSearchSwitcher()
                    true
                }
                else -> false
            }
        }

        val tabNames = getTabNames()
        selectedTab = "__ALL__"

        setupToggleBar()
        setupTabFilterChips(tabNames)

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        setupSearchInput()
        loadSources()
    }

    private fun showContentSearchSwitcher() {
        val currentType = getContentSearchType()
        val items = ContentSearchType.entries.map { type ->
            SelectItem(if (type == currentType) "${type.title} ✓" else type.title, type)
        }
        requireContext().selector(R.string.switch_rule, items) { dialog, item, _ ->
            dialog.dismiss()
            val targetType = item.value
            if (targetType == currentType) return@selector
            val fragmentManager = parentFragmentManager
            dismissAllowingStateLoss()
            targetType.createDialog().show(fragmentManager, targetType.tag)
        }
    }

    // ========== Toggle 栏 ==========

    private fun setupToggleBar() {
        val rootLayout = binding.root as ViewGroup
        val searchBarIndex = rootLayout.indexOfChild(binding.searchBarLayout)

        val toggleLayout = LinearLayout(requireContext()).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val modeRow = createToggleRow(
            "模式",
            listOf("规则字段" to true, "JSON全文" to false),
            selectedValue = searchByRuleField
        ) { value ->
            searchByRuleField = value
            val query = binding.searchEditText.text.toString().trim()
            if (query.isNotEmpty()) doSearch(query)
        }
        toggleLayout.addView(modeRow)

        val scopeRow = createToggleRow(
            "范围",
            listOf("所有源" to true, "仅启用" to false),
            selectedValue = searchAllSources
        ) { value ->
            searchAllSources = value
            loadSources()
        }
        toggleLayout.addView(scopeRow)

        val toggleLp = ConstraintLayout.LayoutParams(
            ConstraintLayout.LayoutParams.MATCH_PARENT,
            ConstraintLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topToBottom = binding.toolBar.id
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        }
        rootLayout.addView(toggleLayout, searchBarIndex, toggleLp)

        val searchBarLp = binding.searchBarLayout.layoutParams as ConstraintLayout.LayoutParams
        searchBarLp.topToBottom = toggleLayout.id
        binding.searchBarLayout.layoutParams = searchBarLp
    }

    private fun <T> createToggleRow(
        label: String,
        options: List<Pair<String, T>>,
        selectedValue: T,
        onSelectionChanged: (T) -> Unit
    ): View {
        val context = requireContext()
        val scrollView = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(12), dpToPx(4), dpToPx(12), dpToPx(4))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val labelView = TextView(context).apply {
            text = label
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.secondaryText))
            setPadding(0, 0, dpToPx(8), 0)
        }
        row.addView(labelView)

        val buttons = mutableListOf<TextView>()
        val allValues = options.map { it.second }

        for ((text, _) in options) {
            val btn = TextView(context).apply {
                this.text = text
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = dpToPx(6)
                }
                isClickable = true
                isFocusable = true
            }
            buttons.add(btn)
            row.addView(btn)
        }

        for ((index, btn) in buttons.withIndex()) {
            btn.setOnClickListener {
                val current = allValues[index]
                updateToggleButtons(buttons, current, allValues)
                onSelectionChanged(current)
            }
        }

        updateToggleButtons(buttons, selectedValue, allValues)
        scrollView.addView(row)
        return scrollView
    }

    private fun <T> updateToggleButtons(
        buttons: List<TextView>,
        selectedValue: T,
        allValues: List<T>
    ) {
        buttons.forEachIndexed { index, btn ->
            val isSelected = allValues[index] == selectedValue
            if (isSelected) {
                btn.setTextColor(accentColor)
                btn.background = createChipBackground(true)
            } else {
                btn.setTextColor(ContextCompat.getColor(btn.context, R.color.primaryText))
                btn.background = createChipBackground(false)
            }
        }
    }

    // ========== Tab 过滤 ==========

    private fun setupTabFilterChips(tabNames: Map<String, String>) {
        if (tabNames.isEmpty()) return

        val rootLayout = binding.root as ViewGroup
        val searchBarIndex = rootLayout.indexOfChild(binding.searchBarLayout)

        val chipRow = HorizontalScrollView(requireContext()).apply {
            id = View.generateViewId()
            isHorizontalScrollBarEnabled = false
            layoutParams = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_PARENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topToBottom = binding.searchBarLayout.id
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            }
        }

        val chipLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(12), dpToPx(2), dpToPx(12), dpToPx(2))
        }

        val allLabel = TextView(requireContext()).apply {
            text = "分类"
            textSize = 13f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.secondaryText))
            setPadding(0, 0, dpToPx(8), 0)
        }
        chipLayout.addView(allLabel)

        val allBtn = TextView(requireContext()).apply {
            text = "全部"
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
            isClickable = true
            isFocusable = true
            tag = "__ALL__"
        }
        allBtn.setOnClickListener {
            if (selectedTab != "__ALL__") {
                selectedTab = "__ALL__"
                updateTabChipStyles(chipLayout)
                val query = binding.searchEditText.text.toString().trim()
                if (query.isNotEmpty()) doSearch(query)
            }
        }
        chipLayout.addView(allBtn)

        val chipButtons = mutableListOf<TextView>()
        for ((tabKey, tabName) in tabNames) {
            val btn = TextView(requireContext()).apply {
                text = tabName
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = dpToPx(6)
                }
                isClickable = true
                isFocusable = true
                tag = tabKey
            }
            btn.setOnClickListener {
                val key = btn.tag as String
                if (selectedTab != key) {
                    selectedTab = key
                    updateTabChipStyles(chipLayout)
                    val query = binding.searchEditText.text.toString().trim()
                    if (query.isNotEmpty()) doSearch(query)
                }
            }
            chipButtons.add(btn)
            chipLayout.addView(btn)
        }

        chipRow.addView(chipLayout)
        rootLayout.addView(chipRow, searchBarIndex + 1)

        val resultCountLp = binding.resultCountText.layoutParams as ConstraintLayout.LayoutParams
        resultCountLp.topToBottom = chipRow.id
        binding.resultCountText.layoutParams = resultCountLp

        updateTabChipStyles(chipLayout)
    }

    private fun updateTabChipStyles(chipLayout: LinearLayout) {
        for (i in 0 until chipLayout.childCount) {
            val child = chipLayout.getChildAt(i)
            if (child is TextView && child.tag != null) {
                val tag = child.tag as String
                val isSelected = tag == selectedTab
                if (isSelected) {
                    child.setTextColor(accentColor)
                    child.background = createChipBackground(true)
                } else {
                    child.setTextColor(ContextCompat.getColor(child.context, R.color.primaryText))
                    child.background = createChipBackground(false)
                }
            }
        }
    }

    protected fun dpToPx(dp: Int): Int {
        return (dp * requireContext().resources.displayMetrics.density).toInt()
    }

    /**
     * 动态创建 Chip 背景。
     * 选中：accentColor 填充 + 圆角（Material Chip 风格）。
     * 未选中：透明背景 + 细边框（继承默认文字颜色）。
     */
    private fun createChipBackground(selected: Boolean): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dpToPx(16).toFloat()
            if (selected) {
                val color = accentColor
                setColor(android.graphics.Color.argb(
                    30,
                    android.graphics.Color.red(color),
                    android.graphics.Color.green(color),
                    android.graphics.Color.blue(color)
                ))
                setStroke(dpToPx(1), color)
            } else {
                val ctx = requireContext()
                val borderColor = ContextCompat.getColor(ctx, R.color.divider)
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(dpToPx(1), borderColor)
            }
        }
    }

    // ========== 搜索历史 ==========

    private fun getSearchHistoryKey(): String = "content_search_history_${getDialogTitle()}"

    private fun loadSearchHistory(): List<String> {
        val json = getPrefString(getSearchHistoryKey(), "")
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveSearchHistory(query: String) {
        val history = loadSearchHistory().toMutableList()
        history.remove(query)
        history.add(0, query)
        if (history.size > MAX_HISTORY_SIZE) {
            history.removeAt(history.lastIndex)
        }
        saveSearchHistory(history)
    }

    private fun saveSearchHistory(history: List<String>) {
        val arr = org.json.JSONArray()
        history.forEach { arr.put(it) }
        putPrefString(getSearchHistoryKey(), arr.toString())
    }

    private fun showSearchHistory() {
        val history = loadSearchHistory()
        if (history.isEmpty()) return

        dismissHistoryPopup()

        val context = requireContext()
        val rootLayout = binding.root as ViewGroup

        // 创建历史面板容器
        val container = LinearLayout(context).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            layoutParams = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_PARENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topToBottom = binding.searchBarLayout.id
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            }
            setPadding(dpToPx(12), dpToPx(4), dpToPx(12), dpToPx(4))
        }

        // 标题行
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val titleView = TextView(context).apply {
            text = "搜索历史"
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.secondaryText))
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        headerRow.addView(titleView)

        val clearBtn = TextView(context).apply {
            text = "清空"
            textSize = 12f
            setTextColor(accentColor)
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
            isClickable = true
            isFocusable = true
        }
        clearBtn.setOnClickListener {
            putPrefString(getSearchHistoryKey(), "")
            dismissHistoryPopup()
        }
        headerRow.addView(clearBtn)
        container.addView(headerRow)

        // RecyclerView
        val recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            val maxHeight = (context.resources.displayMetrics.heightPixels * 0.25).toInt()
            if (history.size > 5) {
                layoutParams.height = maxHeight
            }
        }
        val mutableHistory = history.toMutableList()
        val adapter = HistoryAdapter(
            mutableHistory,
            onItemClick = { query ->
                binding.searchEditText.setText(query)
                binding.searchEditText.setSelection(query.length)
                dismissHistoryPopup()
                currentSearchTerm = query
                doSearch(query)
            },
            onItemDelete = { position ->
                mutableHistory.removeAt(position)
                if (mutableHistory.isEmpty()) {
                    putPrefString(getSearchHistoryKey(), "")
                    dismissHistoryPopup()
                } else {
                    saveSearchHistory(mutableHistory)
                    recyclerView.adapter?.notifyDataSetChanged()
                }
            }
        )
        recyclerView.adapter = adapter
        container.addView(recyclerView)

        // 圆角背景 - 和主窗口同色 + 边框
        container.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dpToPx(8).toFloat()
            setColor(ContextCompat.getColor(context, R.color.background))
            setStroke(dpToPx(1), ContextCompat.getColor(context, R.color.divider))
        }

        // 插入到 searchBarLayout 下方，覆盖在分类上方
        val chipRowIndex = rootLayout.indexOfChild(binding.recyclerView)
        rootLayout.addView(container, chipRowIndex)

        historyContainer = container
        historyRecyclerView = recyclerView
    }


    private fun dismissHistoryPopup() {
        historyContainer?.let { container ->
            val rootLayout = binding.root as ViewGroup
            rootLayout.removeView(container)
        }
        historyContainer = null
        historyRecyclerView = null
    }

    // ========== 搜索输入 ==========

    private fun setupSearchInput() {
        binding.searchEditText.hint = getSearchHint()
        binding.searchEditText.setOnEditorActionListener(TextView.OnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                val query = binding.searchEditText.text.toString().trim()
                if (query.isNotEmpty()) {
                    searchJob?.cancel()
                    currentSearchTerm = query
                    saveSearchHistory(query)
                    dismissHistoryPopup()
                    doSearch(query)
                }
                true
            } else {
                false
            }
        })

        binding.searchEditText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && binding.searchEditText.text.isEmpty()) {
                showSearchHistory()
            } else if (!hasFocus) {
                dismissHistoryPopup()
            }
        }

        binding.searchEditText.setOnClickListener {
            if (binding.searchEditText.text.isEmpty()) {
                showSearchHistory()
            }
        }

        binding.searchEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                searchJob?.cancel()
                binding.clearBtn.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE

                val query = s?.toString()?.trim() ?: ""
                if (query.isEmpty()) {
                    dismissHistoryPopup()
                    showInitialState()
                    return
                }

                dismissHistoryPopup()

                searchJob = lifecycleScope.launch {
                    delay(DEBOUNCE_DELAY)
                    currentSearchTerm = query
                    doSearch(query)
                }
            }
        })

        binding.clearBtn.setOnClickListener {
            binding.searchEditText.text.clear()
            binding.clearBtn.visibility = View.GONE
            showInitialState()
        }
    }

    // ========== 数据加载 ==========

    private fun loadSources() {
        sourcesLoaded = false
        showLoadingState()
        loadSourceItems(searchAllSources) { items ->
            allSourceItems = items
            sourcesLoaded = true
            hideLoadingState()
            val query = binding.searchEditText.text.toString().trim()
            if (query.isNotEmpty()) {
                doSearch(query)
            } else {
                showInitialState()
            }
        }
    }

    // ========== 搜索与结果展示 ==========

    private fun doSearch(query: String) {
        if (!sourcesLoaded || allSourceItems.isEmpty()) return

        binding.initialStateLayout.visibility = View.GONE
        binding.emptyStateLayout.visibility = View.GONE
        binding.recyclerView.visibility = View.GONE
        binding.resultCountText.visibility = View.GONE

        val filteredItems = if (selectedTab == "__ALL__") {
            allSourceItems
        } else {
            allSourceItems.filter { it.tabKey == selectedTab }
        }

        searchJob = lifecycleScope.launch(Dispatchers.IO) {
            val results = performSearch(query, filteredItems)
            withContext(Dispatchers.Main) {
                showResults(results)
            }
        }
    }

    protected fun showResults(results: List<SourceFieldItem>) {
        lastResults = results
        if (results.isEmpty()) {
            showEmptyState()
            return
        }

        binding.initialStateLayout.visibility = View.GONE
        binding.emptyStateLayout.visibility = View.GONE
        binding.recyclerView.visibility = View.VISIBLE
        binding.resultCountText.visibility = View.VISIBLE

        val totalCount = results.size
        val grouped = results.groupBy { it.sourceName }
        val sourceCount = grouped.size
        binding.resultCountText.text = "在 $sourceCount 个源中找到 $totalCount 个匹配"

        expandedGroups.clear()
        grouped.keys.forEach { expandedGroups.add(it) }

        adapter.setData(grouped)
        binding.recyclerView.scrollToPosition(0)
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

    private fun showLoadingState() {
        binding.recyclerView.visibility = View.GONE
        binding.emptyStateLayout.visibility = View.GONE
        binding.resultCountText.visibility = View.GONE
        binding.initialStateLayout.visibility = View.GONE
        binding.rotateLoading.visible()
    }

    private fun hideLoadingState() {
        binding.rotateLoading.gone()
    }

    // ========== 高亮 ==========

    protected fun highlightText(text: String, searchTerm: String): SpannableString {
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
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            startIndex = index + searchTerm.length
        }
        return spannable
    }

    // ========== 预览弹窗 ==========

    private fun showPreviewDialog(item: SourceFieldItem) {
        val scrollView = android.widget.ScrollView(requireContext()).apply {
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(8))
        }
        val textView = TextView(requireContext()).apply {
            text = highlightRuleSyntax(item.fullValue)
            textSize = 14f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.primaryText))
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        scrollView.addView(textView)

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("${item.tabName} · ${item.fieldName}")
            .setView(scrollView)
            .setPositiveButton("跳转") { _, _ ->
                navigateToEdit(item.sourceUrl)
            }
            .setNeutralButton("复制") { _, _ ->
                requireContext().sendToClip(item.fullValue)
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun highlightRuleSyntax(text: String): SpannableString {
        val spannable = SpannableString(text)
        val accentColor = ContextCompat.getColor(requireContext(), R.color.accent)
        val rulePrefixes = listOf("@css:", "@get:", "@json:", "@xpath:", "@js:", "@put:", "@xhtml:")
        for (prefix in rulePrefixes) {
            var startIndex = 0
            while (true) {
                val index = text.indexOf(prefix, startIndex, ignoreCase = true)
                if (index == -1) break
                spannable.setSpan(
                    ForegroundColorSpan(accentColor),
                    index,
                    index + prefix.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                startIndex = index + prefix.length
            }
        }
        val jsPatterns = listOf("{{", "}}")
        for (pattern in jsPatterns) {
            var startIndex = 0
            while (true) {
                val index = text.indexOf(pattern, startIndex)
                if (index == -1) break
                spannable.setSpan(
                    ForegroundColorSpan(accentColor),
                    index,
                    index + pattern.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                startIndex = index + pattern.length
            }
        }
        return spannable
    }

    private fun copyMatchedSourceUrls() {
        if (lastResults.isEmpty()) return
        val urls = lastResults.map { it.sourceUrl }.distinct()
        requireContext().sendToClip(urls.joinToString("\n"))
    }

    private fun exportMatchedSources() {
        if (lastResults.isEmpty()) return
        val urls = lastResults.map { it.sourceUrl }.distinct()
        exportSources(urls)
    }

    // ========== Adapter ==========

    protected inner class SearchAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val items = mutableListOf<SearchListItem>()
        private var groupedResults: Map<String, List<SourceFieldItem>> = emptyMap()

        fun setData(grouped: Map<String, List<SourceFieldItem>>) {
            groupedResults = grouped
            rebuildItems()
            notifyDataSetChanged()
        }

        /**
         * 展开所有分组
         */
        fun expandAll() {
            expandedGroups.addAll(groupedResults.keys)
            rebuildItems()
            notifyDataSetChanged()
        }

        /**
         * 收起所有分组
         */
        fun collapseAll() {
            expandedGroups.clear()
            rebuildItems()
            notifyDataSetChanged()
        }

        private fun rebuildItems() {
            items.clear()
            for ((sourceName, fieldItems) in groupedResults) {
                items.add(SearchListItem.Header(sourceName, fieldItems.size))
                if (expandedGroups.contains(sourceName)) {
                    fieldItems.forEach { field ->
                        items.add(SearchListItem.Item(field))
                    }
                }
            }
        }

        override fun getItemViewType(position: Int) = when (items[position]) {
            is SearchListItem.Header -> VIEW_TYPE_HEADER
            is SearchListItem.Item -> VIEW_TYPE_RESULT
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
                is SearchListItem.Header -> (holder as HeaderViewHolder).bind(item)
                is SearchListItem.Item -> (holder as ResultViewHolder).bind(item)
            }
        }

        override fun getItemCount() = items.size

        private inner class HeaderViewHolder(
            private val binding: ItemRuleSearchHeaderBinding
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(header: SearchListItem.Header) {
                binding.tabNameText.text = header.sourceName
                binding.matchCountText.text = "${header.matchCount} 个匹配"

                val isExpanded = expandedGroups.contains(header.sourceName)
                binding.expandIcon.rotation = if (isExpanded) 180f else 0f

                binding.root.setOnClickListener {
                    val key = header.sourceName
                    if (expandedGroups.contains(key)) {
                        expandedGroups.remove(key)
                    } else {
                        expandedGroups.add(key)
                    }
                    rebuildItems()
                    notifyDataSetChanged()
                }
            }
        }

        private inner class ResultViewHolder(
            private val binding: ItemRuleSearchResultBinding
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(item: SearchListItem.Item) {
                val field = item.field
                binding.fieldNameText.text = "[${field.tabName}] ${field.fieldName}"
                binding.matchedTextText.text = highlightText(field.value, currentSearchTerm)

                binding.root.setOnClickListener {
                    showPreviewDialog(field)
                }
            }
        }
    }

    protected sealed class SearchListItem {
        data class Header(val sourceName: String, val matchCount: Int) : SearchListItem()
        data class Item(val field: SourceFieldItem) : SearchListItem()
    }

    companion object {
        protected const val DEBOUNCE_DELAY = 300L
        protected const val VIEW_TYPE_HEADER = 0
        protected const val VIEW_TYPE_RESULT = 1
        private const val MAX_HISTORY_SIZE = 10
    }

    private inner class HistoryAdapter(
        private val history: MutableList<String>,
        private val onItemClick: (String) -> Unit,
        private val onItemDelete: (Int) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder>() {

        inner class HistoryViewHolder(val row: LinearLayout) : RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
            val context = parent.context
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
            }

            val textView = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
                setTextColor(ContextCompat.getColor(context, R.color.primaryText))
                textSize = 14f
            }
            row.addView(textView)

            val deleteBtn = TextView(context).apply {
                text = "✕"
                textSize = 12f
                setTextColor(ContextCompat.getColor(context, R.color.secondaryText))
                setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
                isClickable = true
                isFocusable = true
            }
            row.addView(deleteBtn)

            return HistoryViewHolder(row)
        }

        override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
            val query = history[position]
            val textView = holder.row.getChildAt(0) as TextView
            val deleteBtn = holder.row.getChildAt(1) as TextView

            textView.text = query
            textView.setOnClickListener {
                onItemClick(query)
            }
            deleteBtn.setOnClickListener {
                onItemDelete(position)
            }
        }

        override fun getItemCount() = history.size
    }
}

enum class ContentSearchType(
    val title: String,
    val tag: String,
    private val dialogFactory: () -> BaseContentSearchDialog
) {
    BOOK_SOURCE("书源内容查询", "SourceContentSearchDialog", { SourceContentSearchDialog() }),
    RSS_SOURCE("订阅源内容查询", "RssSourceContentSearchDialog", { RssSourceContentSearchDialog() }),
    TXT_TOC_RULE("TXT目录规则内容查询", "TxtTocRuleContentSearchDialog", { TxtTocRuleContentSearchDialog() }),
    REPLACE_RULE("替换净化规则内容查询", "ReplaceRuleContentSearchDialog", { ReplaceRuleContentSearchDialog() }),
    DICT_RULE("字典规则内容查询", "DictRuleContentSearchDialog", { DictRuleContentSearchDialog() });

    fun createDialog(): DialogFragment = dialogFactory()
}

/**
 * 可搜索的源字段条目。
 * @param value     显示文本（可含上下文截断），用于列表展示和高亮
 * @param fullValue 完整字段文本，用于预览弹窗
 */
data class SourceFieldItem(
    val sourceName: String,
    val sourceUrl: String,
    val tabKey: String,
    val tabName: String,
    val fieldKey: String,
    val fieldName: String,
    val value: String,
    val fullValue: String = value
)
