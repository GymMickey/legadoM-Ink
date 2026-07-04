@file:Suppress("DEPRECATION")

package io.legado.app.ui.rss.article

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.databinding.ActivityRssArtivlesBinding
import io.legado.app.help.source.sortUrls
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.model.blockrule.BlockRuleStore
import io.legado.app.ui.blockrule.BlockRuleConfigDialog
import io.legado.app.ui.rss.source.edit.RssSourceEditActivity
import io.legado.app.ui.widget.dialog.VariableDialog
import io.legado.app.utils.*
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.viewpager.widget.ViewPager
import io.legado.app.utils.startActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.constant.PreferKey
import io.legado.app.ui.theme.LegadoTheme

class RssSortActivity : VMBaseActivity<ActivityRssArtivlesBinding, RssSortViewModel>(),
    VariableDialog.Callback {

    override val binding by viewBinding(ActivityRssArtivlesBinding::inflate)
    override val viewModel by viewModels<RssSortViewModel>()
    private val adapter by lazy { TabFragmentPageAdapter() }
    private var sortUrls: List<Pair<String, String>>? = null
    private val sortList = mutableListOf<Pair<String, String>>()
    private val fragmentMap = hashMapOf<String, Fragment>()
    private val orientation by lazy { resources.configuration.orientation }
    private var menuPage: MenuItem? = null
    private val editSourceResult = registerForActivityResult(
        StartActivityContract(RssSourceEditActivity::class.java)
    ) {
        if (it.resultCode == RESULT_OK) {
            viewModel.initData(intent) {
                sortUrls = null
                upFragments()
            }
        }
    }

    // 添加类属性
    private val tabRows = mutableListOf<LinearLayout>()
    var maxTagsPerRow = 10 // 每行尽量容纳10个标签,横屏20
    private val tabScrollViews = mutableListOf<HorizontalScrollView>() // 添加滚动视图列表

    /** 是否显示屏蔽进度指示器 */
    private var showBlockProgress: Boolean
        get() = getPrefBoolean(PreferKey.blockRuleShowProgress, false)
        set(value) = putPrefBoolean(PreferKey.blockRuleShowProgress, value)
    /** 当前被屏蔽的文章数量，用于进度指示器 */
    private var blockedCount by mutableIntStateOf(0)
    /** 屏蔽进度悬浮芯片 ComposeView */
    private var blockProgressComposeView: ComposeView? = null

    private fun setupMultiLineTabs() {
        val tabsContainer = binding.tabsContainer
        tabsContainer.removeAllViews()
        tabRows.clear()
        tabScrollViews.clear()
        if (sortList.isEmpty()) {
            tabsContainer.gone()
            return
        }
        // 动态计算每行标签数量,最多3行
        var rowCount = when {
            sortList.size <= 10 -> 1
            sortList.size <= 20 -> 2
            else -> 3
        }
        if (rowCount > 1 && orientation == Configuration.ORIENTATION_LANDSCAPE) rowCount-- //横屏最多2行
        maxTagsPerRow = (sortList.size + rowCount - 1) / rowCount
        sortList.chunked(maxTagsPerRow).forEachIndexed { rowIndex, rowItems ->
            // 创建横向滚动容器
            val scrollView = HorizontalScrollView(this).apply {
                overScrollMode = View.OVER_SCROLL_NEVER
                isHorizontalScrollBarEnabled = false
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 6.dpToPx()
                }
                tabScrollViews.add(this)
            }
            // 创建行容器
            val rowLayout = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            // 添加标签到行
            rowItems.forEachIndexed { indexInRow, sort ->
                val globalIndex = rowIndex * maxTagsPerRow + indexInRow
                val tabView = createTabView(sort.first, globalIndex)
                rowLayout.addView(tabView)
            }
            scrollView.addView(rowLayout)
            tabsContainer.addView(scrollView)
            tabRows.add(rowLayout)
        }
        // 初始选中状态
        updateTabSelection(binding.viewPager.currentItem)
    }

    private fun createTabView(title: String, position: Int): TextView {
        return TextView(this).apply {
            text = title
            gravity = Gravity.CENTER
            textSize = 14f
            background = createTabBackground(accentColor, context)
            setPadding(12.dpToPx(), 6.dpToPx(), 12.dpToPx(), 6.dpToPx())
            tag = position
            setTextColor(context.getCompatColor( R.color.primaryText))
            // 宽度自适应内容
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 6.dpToPx()
            }
            setOnClickListener {
                setTextColor(context.getCompatColor(R.color.secondaryText)) //点击变色
                binding.viewPager.currentItem = position
                updateTabSelection(position)
            }
        }
    }

    private fun createTabBackground(accentColor: Int, context: Context): Drawable {
        val radius = 16f.dpToPx()
        val strokeWidth = 1f.dpToPx()

        val selectedDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setStroke(strokeWidth.toInt(), accentColor)
        }

        val defaultDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
        }

        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_selected), selectedDrawable)
            addState(intArrayOf(), defaultDrawable)
        }
    }

    //更新选中状态
    private fun updateTabSelection(position: Int) {
        if (!isDestroyed && !isFinishing) {
            tabRows.forEachIndexed { rowIndex, row ->
                for (i in 0 until row.childCount) {
                    val tabIndex = rowIndex * maxTagsPerRow + i
                    val tabView = row.getChildAt(i) as? TextView
                    tabView?.isSelected = tabIndex == position
                }
            }
            // 确保选中标签在视图内
            ensureTabVisible(position)
        }
    }

    private fun ensureTabVisible(position: Int) {
        if (position < 0 || position >= sortList.size) return
        val rowIndex = position / maxTagsPerRow
        if (rowIndex >= tabScrollViews.size) return
        val scrollView = tabScrollViews[rowIndex]
        val rowLayout = tabRows[rowIndex]
        val indexInRow = position % maxTagsPerRow
        if (indexInRow >= rowLayout.childCount) return

        val tabView = rowLayout.getChildAt(indexInRow)
        scrollView.post {
            val tabLeft = tabView.left
            val tabRight = tabView.right
            val scrollViewWidth = scrollView.width
            val padding = 12.dpToPx()
            when {
                tabLeft - padding < scrollView.scrollX ->
                    scrollView.smoothScrollTo(tabLeft - padding, 0)
                tabRight + padding > scrollView.scrollX + scrollViewWidth ->
                    scrollView.smoothScrollTo(tabRight - scrollViewWidth + padding, 0)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // 更新当前intent
        // 重新初始化数据，复用时重建
        viewModel.initData(intent) {
            upFragments()
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.viewPager.adapter = adapter
        binding.viewPager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                updateTabSelection(position)
                currentArticlesFragment()?.let {
                    updatePageMenu(it.getCurrentPage(), it.showPageMenu())
                }
                // 切换分类时刷新屏蔽进度芯片，仅显示当前分类的屏蔽计数
                updateCurrentBlockedCount()
            }
        })
        viewModel.initData(intent) {
            upFragments()
        }
        onBackPressedDispatcher.addCallback(this) { //监听返回
            if (viewModel.searchKey != null) {
                // 退出搜索
                viewModel.searchKey = null
                upFragments()
                return@addCallback
            }
            finish()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            currentFocus?.let {
                if (it.shouldHideSoftInput(ev)) {
                    it.hideSoftInput()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // 保存当前选中位置
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("CURRENT_POSITION", binding.viewPager.currentItem)
    }

    // 恢复状态
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        val position = savedInstanceState.getInt("CURRENT_POSITION", 0)
        binding.viewPager.currentItem = position
        updateTabSelection(position)
    }

    // 在onDestroy中释放资源
    override fun onDestroy() {
        super.onDestroy()
        fragmentMap.clear()
        tabScrollViews.clear()
        tabRows.clear()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.rss_articles, menu)
        menuPage = menu.findItem(R.id.menu_page)
        menu.findItem(R.id.menu_search)?.apply {
            val source = viewModel.rssSource
            val searchUrl = source?.searchUrl ?: return@apply
            val hasSearchUrl = searchUrl.isNotBlank()
            isVisible = hasSearchUrl
            if (hasSearchUrl) {
                (actionView as? SearchView)?.apply {
                    isSubmitButtonEnabled = true
                    setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                        override fun onQueryTextSubmit(query: String): Boolean {
                            clearFocus()
                            start(this@RssSortActivity ,null,source.sourceUrl, query)
                            return true
                        }

                        override fun onQueryTextChange(newText: String): Boolean {
                            return false
                        }
                    })
                    setOnQueryTextFocusChangeListener { _, hasFocus ->
                        if (!hasFocus) {
                            isIconified = true
                        }
                    }
                }
            }
        }
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        currentArticlesFragment()?.let {
            updatePageMenu(it.getCurrentPage(), it.showPageMenu())
        }
        menu.findItem(R.id.menu_login)?.isVisible =
            !viewModel.rssSource?.loginUrl.isNullOrBlank()
        return super.onMenuOpened(featureId, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_page -> currentArticlesFragment()?.showPagePicker()
            R.id.menu_login -> startActivity<SourceLoginActivity> {
                putExtra("type", "rssSource")
                putExtra("key", viewModel.rssSource?.sourceUrl)
            }

            R.id.menu_refresh_sort -> {
                sortUrls = null
                viewModel.clearSortCache { upFragments() }
            }
            R.id.menu_set_source_variable -> setSourceVariable()
            R.id.menu_edit_source -> viewModel.rssSource?.let {
                editSourceResult.launch {
                    putExtra("sourceUrl", it.sourceUrl)
                }
            }

            R.id.menu_clear -> {
                viewModel.url?.let {
                    viewModel.clearArticles()
                }
            }

            R.id.menu_switch_layout -> {
                viewModel.switchLayout()
                upFragments()
            }

            R.id.menu_block_rule -> showBlockRuleConfig()

            R.id.menu_read_record -> showDialogFragment(ReadRecordDialog(viewModel.rssSource?.sourceUrl))
        }
        return super.onCompatOptionsItemSelected(item)
    }

    fun updatePageMenu(page: Int, visible: Boolean) {
        menuPage?.isVisible = visible
        if (visible) {
            menuPage?.title = getString(R.string.menu_page, page)
        }
    }

    private fun upFragments() {
        lifecycleScope.launch {
            val source = viewModel.rssSource ?: return@launch
            if (viewModel.searchKey != null) {
                sortList.apply {
                    val name = "搜索"
                    val url = source.searchUrl ?: return@apply
                    clear()
                    add(Pair(name, url))
                }
                upFragmentsView()
                return@launch
            }
            viewModel.sortUrl?.takeIf { it.isNotBlank() }?.let { url ->
                val urls: List<Pair<String, String>> = try {
                    if (url.isJsonObject()) {
                        GSONStrict.fromJsonObject<Map<String, String>>(url)
                            .getOrThrow()
                            .map { Pair(it.key, it.value) }
                    } else {
                        listOf(Pair("", url))
                    }
                } catch (_: Exception) {
                    listOf(Pair("", url))
                }
                sortList.apply {
                    clear()
                    addAll(urls)
                }
                upFragmentsView()
                return@launch
            }
            if (sortUrls == null) {
                sortUrls = source.sortUrls()
            }
            sortUrls?.let { urls ->
                sortList.apply {
                    clear()
                    addAll(urls)
                }
                upFragmentsView()
                return@launch
            }
        }
    }
    private fun upFragmentsView() {
        if (sortList.size == 1) {
            sortList.first().first.takeIf { it.isNotEmpty() }?.let {
                binding.titleBar.title = viewModel.searchKey ?: it
            }
            binding.tabsContainer.gone()
        } else {
            binding.titleBar.title = viewModel.sourceName
            binding.tabsContainer.visible()
            setupMultiLineTabs()
        }
        adapter.notifyDataSetChanged()
        if (sortList.isNotEmpty()) {
            // 支持从首页模块箭头跳转时自动选中对应分类 Tab
            val targetSortName = intent.getStringExtra("sortName")
            if (!targetSortName.isNullOrBlank()) {
                val targetIndex = sortList.indexOfFirst { it.first == targetSortName }
                if (targetIndex >= 0) {
                    binding.viewPager.setCurrentItem(targetIndex, false)
                }
            }
            updateTabSelection(binding.viewPager.currentItem)
        }
    }

    private fun setSourceVariable() {
        lifecycleScope.launch {
            val source = viewModel.rssSource
            if (source == null) {
                toastOnUi("源不存在")
                return@launch
            }
            val comment =
                source.getDisplayVariableComment("源变量可在js中通过source.getVariable()获取")
            val variable = withContext(Dispatchers.IO) { source.getVariable() }
            showDialogFragment(
                VariableDialog(
                    getString(R.string.set_source_variable),
                    source.getKey(),
                    variable,
                    comment
                )
            )
        }
    }

    override fun setVariable(key: String, variable: String?) {
        viewModel.rssSource?.setVariable(variable)
    }

    private fun currentArticlesFragment(): RssArticlesFragment? {
        val position = binding.viewPager.currentItem
        val sortName = sortList.getOrNull(position)?.first ?: return null
        return fragmentMap[sortName] as? RssArticlesFragment
    }

    private inner class TabFragmentPageAdapter :
        FragmentStatePagerAdapter(supportFragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {

        override fun getItemPosition(`object`: Any): Int {
            return POSITION_NONE
        }

        override fun getPageTitle(position: Int): CharSequence {
            return sortList[position].first
        }

        override fun getItem(position: Int): Fragment {
            val sort = sortList[position]
            return RssArticlesFragment(sort.first, sort.second, viewModel.searchKey) //获取内容界面
        }

        override fun getCount(): Int {
            return sortList.size
        }

        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            val fragment = super.instantiateItem(container, position) as Fragment
            fragmentMap[sortList[position].first] = fragment
            return fragment
        }
    }

    companion object {
        fun start(context: Context, sortUrl: String?, sourceUrl: String, key: String? = null) {
            context.startActivity<RssSortActivity> {
                putExtra("sortUrl", sortUrl)
                putExtra("sourceUrl", sourceUrl)
                putExtra("key", key)
            }
        }
    }

    /**
     * 打开屏蔽规则配置弹窗
     */
    private fun showBlockRuleConfig() {
        val dialog = BlockRuleConfigDialog()
        dialog.sourceUrl = viewModel.rssSource?.sourceUrl.orEmpty()
        dialog.allBooks = emptyList()
        // 收集当前可见 fragment 的原始文章列表，用于"开启屏蔽规则后起效的规则"计数
        dialog.allRssArticles = currentArticlesFragment()?.rawArticles ?: emptyList()
        dialog.onRulesChanged = {
            BlockRuleStore.invalidateCache()
            // 通知所有已 attached 的 fragment 重新应用屏蔽规则
            fragmentMap.values.forEach { fragment ->
                val f = fragment as? RssArticlesFragment
                if (f?.isAdded == true) f.applyBlockRules()
            }
            // 进度芯片仅显示当前可见分类的屏蔽计数，避免跨分类干扰
            updateCurrentBlockedCount()
        }
        dialog.onShowProgressChanged = {
            showBlockProgress = it
            updateBlockProgressChip()
        }
        dialog.show(supportFragmentManager, "rssBlockRuleConfig")
    }

    /**
     * 更新屏蔽进度悬浮芯片的显示状态
     * 芯片位于内容上方右侧，点击可打开屏蔽规则配置
     */
    private fun updateBlockProgressChip() {
        val contentView = binding.contentView
        if (showBlockProgress && blockedCount > 0) {
            if (blockProgressComposeView == null) {
                blockProgressComposeView = ComposeView(this).also { composeView ->
                    composeView.setContent {
                        LegadoTheme {
                            Surface(
                                modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp),
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                shadowElevation = 4.dp,
                                onClick = { showBlockRuleConfig() }
                            ) {
                                Text(
                                    text = getString(R.string.explore_block_rule_progress_text, blockedCount),
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                    val params = android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = android.view.Gravity.TOP or android.view.Gravity.END
                    }
                    contentView.addView(composeView, params)
                }
            }
        } else {
            blockProgressComposeView?.let {
                contentView.removeView(it)
                blockProgressComposeView = null
            }
        }
    }

    /**
     * 由 RssArticlesFragment 调用，更新屏蔽进度芯片。
     * 仅统计当前可见 fragment 的屏蔽计数，隔离不同分类之间的屏蔽状态，
     * 防止预加载的相邻 fragment 干扰进度显示。
     */
    fun updateBlockedCount() {
        updateCurrentBlockedCount()
    }

    /**
     * 仅使用当前可见 fragment 的屏蔽计数更新进度芯片
     */
    private fun updateCurrentBlockedCount() {
        blockedCount = currentArticlesFragment()?.getBlockedCount() ?: 0
        updateBlockProgressChip()
    }

}
