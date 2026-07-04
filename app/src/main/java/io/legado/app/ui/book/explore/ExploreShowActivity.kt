package io.legado.app.ui.book.explore

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.viewpager.widget.ViewPager
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.ActivityExploreShowBinding
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.blockrule.BlockRuleConfigDialog
import io.legado.app.ui.book.group.GroupSelectDialog
import io.legado.app.utils.*
import io.legado.app.utils.viewbindingdelegate.viewBinding
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
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.model.blockrule.BlockRuleStore

/**
 * 发现列表
 * 使用 ViewPager + Fragment 架构，参考 RssSortActivity 的实现
 */
@Suppress("DEPRECATION")
class ExploreShowActivity : VMBaseActivity<ActivityExploreShowBinding, ExploreShowViewModel>(),
    GroupSelectDialog.CallBack {

    companion object {
        private const val REQUEST_CODE_ADD_ALL_TO_SHELF = 1001
        const val LOAD_COOLDOWN_MS = 2000L
        const val LAYOUT_LIST = 0
        const val LAYOUT_GRID = 1
        const val LAYOUT_WATERFALL = 2

        fun start(context: Context, exploreUrl: String?, exploreName: String?, sourceUrl: String) {
            context.startActivity<ExploreShowActivity> {
                putExtra("exploreUrl", exploreUrl)
                putExtra("exploreName", exploreName)
                putExtra("sourceUrl", sourceUrl)
            }
        }
    }

    override val binding by viewBinding(ActivityExploreShowBinding::inflate)
    override val viewModel by viewModels<ExploreShowViewModel>()

    private val adapter by lazy { TabFragmentPageAdapter() }
    private val exploreKinds = mutableListOf<ExploreKind>()
    private val fragmentMap = hashMapOf<String, Fragment>()
    private val tabRows = mutableListOf<LinearLayout>()
    private val tabScrollViews = mutableListOf<HorizontalScrollView>()
    private var maxTagsPerRow = 10
    private val orientation by lazy { resources.configuration.orientation }
    private var menuPage: MenuItem? = null
    private var menuSwitchLayout: MenuItem? = null
    private var menuSelectColumn: MenuItem? = null
    private var menuShowCategoryTab: MenuItem? = null
    private var menuPreload: MenuItem? = null

    /** 当前书源 URL */
    private val sourceUrl: String by lazy { intent.getStringExtra("sourceUrl") ?: "" }

    /** 是否显示屏蔽进度指示器 */
    private var showBlockProgress: Boolean
        get() = getPrefBoolean(PreferKey.blockRuleShowProgress, false)
        set(value) = putPrefBoolean(PreferKey.blockRuleShowProgress, value)

    /** 当前被屏蔽的书籍数量 */
    private var blockedCount by mutableIntStateOf(0)
    /** 屏蔽进度悬浮芯片 ComposeView */
    private var blockProgressComposeView: ComposeView? = null

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.title = intent.getStringExtra("exploreName")
        binding.viewPager.adapter = adapter
        binding.viewPager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                updateTabSelection(position)
                currentExploreFragment()?.let {
                    updatePageMenu(it.getCurrentPage(), it.showPageMenu())
                }
                updateCurrentBlockedCount()
            }
        })
        viewModel.initData(intent)
        viewModel.exploreKindsData.observe(this) { kinds ->
            exploreKinds.clear()
            exploreKinds.addAll(kinds)
            if (viewModel.showCategoryTab && exploreKinds.isNotEmpty()) {
                setupMultiLineTabs()
                binding.tabsContainer.visible()
            } else {
                binding.tabsContainer.gone()
            }
            adapter.notifyDataSetChanged()
            // 支持从首页模块箭头跳转时自动选中对应分类 Tab
            val targetUrl = intent.getStringExtra("exploreUrl")
            if (!targetUrl.isNullOrBlank() && exploreKinds.isNotEmpty()) {
                val targetIndex = exploreKinds.indexOfFirst { it.url == targetUrl }
                if (targetIndex >= 0) {
                    binding.viewPager.setCurrentItem(targetIndex, false)
                }
            }
            updateTabSelection(binding.viewPager.currentItem)
        }
        viewModel.addAllToShelfResult.observe(this) { count ->
            if (count == 0) {
                toastOnUi(R.string.all_books_in_shelf)
            } else {
                toastOnUi(getString(R.string.add_books_success, count))
            }
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.explore_show, menu)
        menuPage = menu.findItem(R.id.menu_page)
        menuSwitchLayout = menu.findItem(R.id.menu_switch_layout)
        menuSelectColumn = menu.findItem(R.id.menu_select_column)
        menuShowCategoryTab = menu.findItem(R.id.menu_show_category_tab)
        menuPreload = menu.findItem(R.id.menu_preload)
        
        if (viewModel.layoutMode != LAYOUT_LIST) {
            menuSelectColumn?.isVisible = true
            updateColumnMenuTitle()
        }
        updateSwitchLayoutTitle()
        
        menuShowCategoryTab?.isChecked = viewModel.showCategoryTab
        menuPreload?.isVisible = viewModel.showCategoryTab
        menuPreload?.isChecked = viewModel.isPreload
        
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        updateSwitchLayoutTitle()
        return super.onMenuOpened(featureId, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_page -> currentExploreFragment()?.showPagePicker()
            R.id.menu_add_all_to_shelf -> {
                showDialogFragment(GroupSelectDialog(0, REQUEST_CODE_ADD_ALL_TO_SHELF))
            }
            R.id.menu_switch_layout -> handleSwitchLayout()
            R.id.menu_select_column -> handleSelectColumn()
            R.id.menu_show_category_tab -> {
                viewModel.showCategoryTab = !viewModel.showCategoryTab
                item.isChecked = viewModel.showCategoryTab
                menuPreload?.isVisible = viewModel.showCategoryTab
                if (viewModel.showCategoryTab && exploreKinds.isNotEmpty()) {
                    setupMultiLineTabs()
                    binding.tabsContainer.visible()
                } else {
                    binding.tabsContainer.gone()
                }
            }
            R.id.menu_preload -> {
                viewModel.isPreload = !viewModel.isPreload
                item.isChecked = viewModel.isPreload
            }
            R.id.menu_block_rule -> showBlockRuleConfig()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun upGroup(requestCode: Int, groupId: Long) {
        if (requestCode == REQUEST_CODE_ADD_ALL_TO_SHELF) {
            toastOnUi(getString(R.string.adding_books, currentExploreFragment()?.getBooksCount() ?: 0))
            viewModel.addAllToShelf(groupId)
        }
    }

    fun updatePageMenu(page: Int, visible: Boolean) {
        menuPage?.isVisible = visible
        if (visible) {
            menuPage?.title = getString(R.string.menu_page, page)
        }
    }

    /**
     * 设置多行Tab布局（参考订阅源界面的实现）
     * 最多3行，横屏最多2行
     */
    private fun setupMultiLineTabs() {
        val tabsContainer = binding.tabsContainer
        tabsContainer.removeAllViews()
        tabRows.clear()
        tabScrollViews.clear()
        if (exploreKinds.isEmpty()) {
            tabsContainer.gone()
            return
        }
        var rowCount = when {
            exploreKinds.size <= 10 -> 1
            exploreKinds.size <= 20 -> 2
            else -> 3
        }
        if (rowCount > 1 && orientation == Configuration.ORIENTATION_LANDSCAPE) rowCount--
        maxTagsPerRow = (exploreKinds.size + rowCount - 1) / rowCount
        exploreKinds.chunked(maxTagsPerRow).forEachIndexed { rowIndex, rowItems ->
            val scrollView = HorizontalScrollView(this).apply {
                overScrollMode = View.OVER_SCROLL_NEVER
                isHorizontalScrollBarEnabled = false
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = if (rowIndex < rowCount - 1) 2.dpToPx() else 0
                }
                tabScrollViews.add(this)
            }
            val rowLayout = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            rowItems.forEachIndexed { indexInRow, kind ->
                val globalIndex = rowIndex * maxTagsPerRow + indexInRow
                val tabView = createTabView(kind.title, globalIndex)
                rowLayout.addView(tabView)
            }
            scrollView.addView(rowLayout)
            tabsContainer.addView(scrollView)
            tabRows.add(rowLayout)
        }
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
            setTextColor(context.getCompatColor(R.color.primaryText))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 6.dpToPx()
            }
            setOnClickListener {
                setTextColor(context.getCompatColor(R.color.secondaryText))
                binding.viewPager.currentItem = position
                updateTabSelection(position)
            }
        }
    }

    private fun createTabBackground(accentColor: Int, context: Context): android.graphics.drawable.Drawable {
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

    private fun updateTabSelection(position: Int) {
        if (!isDestroyed && !isFinishing) {
            tabRows.forEachIndexed { rowIndex, row ->
                for (i in 0 until row.childCount) {
                    val tabIndex = rowIndex * maxTagsPerRow + i
                    val tabView = row.getChildAt(i) as? TextView
                    tabView?.isSelected = tabIndex == position
                }
            }
            ensureTabVisible(position)
        }
    }

    private fun ensureTabVisible(position: Int) {
        if (position < 0 || position >= exploreKinds.size) return
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

    private fun handleSwitchLayout() {
        viewModel.layoutMode = (viewModel.layoutMode + 1) % 3
        when (viewModel.layoutMode) {
            LAYOUT_LIST -> {
                menuSelectColumn?.isVisible = false
            }
            LAYOUT_GRID -> {
                menuSelectColumn?.isVisible = true
                updateColumnMenuTitle()
            }
            LAYOUT_WATERFALL -> {
                menuSelectColumn?.isVisible = true
                updateColumnMenuTitle()
            }
        }
        updateSwitchLayoutTitle()
        // 通知所有Fragment更新布局（只通知已attached的Fragment）
        fragmentMap.values.forEach { fragment ->
            (fragment as? ExploreShowFragment)?.let { 
                if (it.isAdded) {
                    it.updateLayoutMode(viewModel.layoutMode, viewModel.columnCount)
                }
            }
        }
    }

    private fun handleSelectColumn() {
        NumberPickerDialog(this)
            .setTitle(getString(R.string.select_column_count))
            .setMaxValue(10)
            .setMinValue(1)
            .setValue(viewModel.columnCount)
            .show { selectedCount ->
                viewModel.columnCount = selectedCount
                updateColumnMenuTitle()
                // 通知所有Fragment更新列数（只通知已attached的Fragment）
                fragmentMap.values.forEach { fragment ->
                    (fragment as? ExploreShowFragment)?.let {
                        if (it.isAdded) {
                            it.updateLayoutMode(viewModel.layoutMode, selectedCount)
                        }
                    }
                }
            }
    }

    private fun updateColumnMenuTitle() {
        menuSelectColumn?.title = viewModel.columnCount.toString()
    }

    private fun updateSwitchLayoutTitle() {
        val modeName = when (viewModel.layoutMode) {
            LAYOUT_GRID -> getString(R.string.switch_layout_grid)
            LAYOUT_WATERFALL -> getString(R.string.switch_layout_waterfall)
            else -> getString(R.string.switch_layout_list)
        }
        menuSwitchLayout?.title = "${getString(R.string.switch_layout)}(当前:$modeName)"
    }

    /**
     * 打开屏蔽规则配置弹窗
     */
    fun showBlockRuleConfig() {
        val dialog = BlockRuleConfigDialog()
        dialog.sourceUrl = sourceUrl
        dialog.allBooks = currentExploreFragment()?.getAllBooks() ?: emptyList()
        dialog.onRulesChanged = {
            BlockRuleStore.invalidateCache()
            fragmentMap.values.forEach { fragment ->
                (fragment as? ExploreShowFragment)?.applyBlockRules()
            }
            updateCurrentBlockedCount()
        }
        dialog.onShowProgressChanged = {
            showBlockProgress = it
            updateBlockProgressChip()
        }
        dialog.show(supportFragmentManager, "exploreBlockRuleConfig")
    }

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
     * 由 ExploreShowFragment 调用，更新屏蔽进度芯片
     */
    fun updateBlockedCount() {
        updateCurrentBlockedCount()
    }

    private fun updateCurrentBlockedCount() {
        blockedCount = currentExploreFragment()?.getBlockedCount() ?: 0
        updateBlockProgressChip()
    }

    private fun currentExploreFragment(): ExploreShowFragment? {
        val position = binding.viewPager.currentItem
        val kind = exploreKinds.getOrNull(position) ?: return null
        return fragmentMap[kind.url] as? ExploreShowFragment
    }

    override fun onDestroy() {
        super.onDestroy()
        fragmentMap.clear()
        tabScrollViews.clear()
        tabRows.clear()
    }

    private inner class TabFragmentPageAdapter :
        FragmentStatePagerAdapter(supportFragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {

        override fun getItemPosition(`object`: Any): Int {
            return POSITION_NONE
        }

        override fun getPageTitle(position: Int): CharSequence {
            return exploreKinds[position].title
        }

        override fun getItem(position: Int): Fragment {
            val kind = exploreKinds[position]
            return ExploreShowFragment(kind.title, kind.url ?: "", sourceUrl)
        }

        override fun getCount(): Int {
            return exploreKinds.size
        }

        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            val fragment = super.instantiateItem(container, position) as Fragment
            val kindUrl = exploreKinds[position].url
            if (kindUrl != null) {
                fragmentMap[kindUrl] = fragment
            }
            return fragment
        }
    }
}