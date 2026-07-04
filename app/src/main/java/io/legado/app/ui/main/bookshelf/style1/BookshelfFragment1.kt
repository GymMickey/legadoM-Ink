@file:Suppress("DEPRECATION")

package io.legado.app.ui.main.bookshelf.style1

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.viewpager.widget.ViewPager
import com.google.android.material.tabs.TabLayout
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.FragmentBookshelf1Binding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.book.group.GroupEditDialog
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.main.bookshelf.BaseBookshelfFragment
import io.legado.app.ui.main.bookshelf.style1.books.BooksFragment
import io.legado.app.utils.isCreated
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlin.collections.set

/**
 * 书架界面
 * 支持两种分组切换模式：
 * 1. TabLayout 模式（下拉选择分组开关未勾选）：显示所有分组标签，可滑动点击切换
 * 2. 下拉选择模式（下拉选择分组开关勾选）：点击标题栏弹出下拉选择分组菜单
 */
class BookshelfFragment1() : BaseBookshelfFragment(R.layout.fragment_bookshelf1),
    TabLayout.OnTabSelectedListener,
    SearchView.OnQueryTextListener {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    private val binding by viewBinding(FragmentBookshelf1Binding::bind)
    private val adapter by lazy { TabFragmentPageAdapter(childFragmentManager) }
    // 下拉选择模式相关控件
    private var titleSelect: LinearLayout? = null
    private var tvGroupName: TextView? = null
    private var ivArrow: ImageView? = null
    // TabLayout 模式相关控件
    private var tabLayout: TabLayout? = null
    private val bookGroups = mutableListOf<BookGroup>()
    private val fragmentMap = hashMapOf<Long, BooksFragment>()
    private var currentPosition = 0
    override val groupId: Long get() = selectedGroup?.groupId ?: 0

    override val books: List<Book>
        get() {
            val fragment = fragmentMap[groupId]
            return fragment?.getBooks() ?: emptyList()
        }

    override var onlyUpdateRead = false
    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setSupportToolbar(binding.titleBar.toolbar)
        initView()
        initBookGroupData()
    }

    private val selectedGroup: BookGroup?
        get() = bookGroups.getOrNull(currentPosition)

    private fun initView() {
        binding.viewPagerBookshelf.setEdgeEffectColor(primaryColor)
        binding.viewPagerBookshelf.offscreenPageLimit = 2
        binding.viewPagerBookshelf.adapter = adapter
        // 根据"下拉选择分组"开关动态添加布局到 TitleBar
        if (AppConfig.dropdownSelectGroup) {
            // 下拉选择模式：添加 view_group_selector 布局
            val groupSelectorView = LayoutInflater.from(requireContext())
                .inflate(R.layout.view_group_selector, binding.titleBar.toolbar, false)
            binding.titleBar.toolbar.addView(groupSelectorView)
            titleSelect = groupSelectorView.findViewById(R.id.title_select)
            tvGroupName = groupSelectorView.findViewById(R.id.tv_group_name)
            ivArrow = groupSelectorView.findViewById(R.id.iv_arrow)
            // 监听 ViewPager 页面切换，更新当前分组名称显示
            binding.viewPagerBookshelf.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
                override fun onPageSelected(position: Int) {
                    currentPosition = position
                    AppConfig.saveTabPosition = position
                    tvGroupName?.text = bookGroups.getOrNull(position)?.groupName ?: ""
                }
                override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}
                override fun onPageScrollStateChanged(state: Int) {}
            })
            initTitleSelect()
            updateTitleColor()
        } else {
            // TabLayout 模式：添加 view_tab_layout_min 布局
            val tabLayoutView = LayoutInflater.from(requireContext())
                .inflate(R.layout.view_tab_layout_min, binding.titleBar.toolbar, false)
            binding.titleBar.toolbar.addView(tabLayoutView)
            tabLayout = tabLayoutView.findViewById(R.id.tab_layout)
            tabLayout?.let { tab ->
                tab.isTabIndicatorFullWidth = false
                tab.tabMode = TabLayout.MODE_SCROLLABLE
                tab.setSelectedTabIndicatorColor(requireContext().accentColor)
                tab.setupWithViewPager(binding.viewPagerBookshelf)
            }
        }
    }

    private fun initTitleSelect() {
        // 下拉选择模式：点击标题栏弹出下拉选择分组菜单
        titleSelect?.setOnClickListener {
            if (bookGroups.isEmpty()) return@setOnClickListener
            val groupNames = bookGroups.map { it.groupName }
            val popup = ListPopupWindow(requireContext())
            popup.anchorView = titleSelect
            // 使用自定义适配器显示勾号
            popup.setAdapter(GroupSelectorAdapter(requireContext(), groupNames, currentPosition))
            // 手动测量最宽分组名的宽度
            val maxWidth = measureMaxTextWidth(groupNames)
            popup.width = maxWidth + 72 // 加上padding和勾号宽度
            popup.setOnItemClickListener { _, _, position, _ ->
                currentPosition = position
                AppConfig.saveTabPosition = position
                tvGroupName?.text = bookGroups[position].groupName
                binding.viewPagerBookshelf.setCurrentItem(position, false)
                popup.dismiss()
            }
            popup.show()
        }
    }

    private fun measureMaxTextWidth(items: List<String>): Int {
        val paint = tvGroupName?.paint ?: return 0
        var maxWidth = 0
        for (item in items) {
            val width = paint.measureText(item).toInt()
            if (width > maxWidth) maxWidth = width
        }
        return maxWidth
    }

    // 自定义适配器，显示勾号
    private class GroupSelectorAdapter(
        context: android.content.Context,
        items: List<String>,
        private val selectedPosition: Int
    ) : ArrayAdapter<String>(context, android.R.layout.simple_spinner_dropdown_item, items) {
        
        override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
            val view = super.getView(position, convertView, parent)
            if (view is TextView) {
                if (position == selectedPosition) {
                    view.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_check, 0, 0, 0)
                } else {
                    view.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
                }
            }
            return view
        }
    }

    private fun updateTitleColor() {
        val textColor = primaryTextColor
        tvGroupName?.setTextColor(textColor)
        ivArrow?.setColorFilter(textColor)
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        SearchActivity.start(requireContext(), query)
        return false
    }

    override fun onQueryTextChange(newText: String?): Boolean {
        return false
    }

    @Synchronized
    override fun upGroup(data: List<BookGroup>) {
        if (data.isEmpty()) {
            appDb.bookGroupDao.enableGroup(BookGroup.IdAll)
        } else {
            if (data != bookGroups) {
                bookGroups.clear()
                bookGroups.addAll(data)
                // 在 notifyDataSetChanged 之前保存位置，因为 notifyDataSetChanged
                // 会触发 ViewPager/TabLayout 自动选中 position 0，
                // 进而调用 onTabSelected(0) 覆盖 saveTabPosition
                val lastPosition = AppConfig.saveTabPosition
                adapter.notifyDataSetChanged()
                if (AppConfig.dropdownSelectGroup) {
                    updateTitleSelect()
                    selectLastGroup(lastPosition)
                } else {
                    selectLastTab(lastPosition)
                    // 设置长按分组标签编辑分组
                    for (i in 0 until adapter.count) {
                        tabLayout?.getTabAt(i)?.view?.setOnLongClickListener {
                            showDialogFragment(GroupEditDialog(bookGroups[i]))
                            true
                        }
                    }
                }
            }
        }
    }

    override fun upSort() {
        adapter.notifyDataSetChanged()
    }

    private fun updateTitleSelect() {
        if (bookGroups.isNotEmpty()) {
            val position = currentPosition.coerceIn(0, bookGroups.size - 1)
            tvGroupName?.text = bookGroups[position].groupName
        }
    }

    private fun selectLastGroup(lastPosition: Int) {
        titleSelect?.post {
            val position = lastPosition.coerceIn(0, bookGroups.size - 1)
            currentPosition = position
            AppConfig.saveTabPosition = position
            tvGroupName?.text = bookGroups.getOrNull(position)?.groupName ?: ""
            binding.viewPagerBookshelf.setCurrentItem(position, false)
        }
    }

    // TabLayout 模式：选择上次保存的分组
    // 注意：removeOnTabSelectedListener 后再 select() 不会触发 onTabSelected，
    // 因此需要显式设置 currentPosition 和 saveTabPosition
    private fun selectLastTab(lastPosition: Int) {
        tabLayout?.post {
            val position = lastPosition.coerceIn(0, bookGroups.size - 1)
            tabLayout?.removeOnTabSelectedListener(this)
            currentPosition = position
            AppConfig.saveTabPosition = position
            tabLayout?.getTabAt(position)?.select()
            tabLayout?.addOnTabSelectedListener(this)
        }
    }

    // TabLayout 模式：Tab 选中回调
    override fun onTabSelected(tab: TabLayout.Tab) {
        currentPosition = tab.position
        AppConfig.saveTabPosition = tab.position
    }

    // TabLayout 模式：Tab 未选中回调
    override fun onTabUnselected(tab: TabLayout.Tab) = Unit

    // TabLayout 模式：Tab 再次选中回调（显示分组书籍数量）
    override fun onTabReselected(tab: TabLayout.Tab) {
        selectedGroup?.let { group ->
            fragmentMap[group.groupId]?.let {
                toastOnUi("${group.groupName}(${it.getBooksCount()})")
            }
        }
    }

    override fun gotoTop() {
        fragmentMap[groupId]?.gotoTop()
    }

    private inner class TabFragmentPageAdapter(fm: FragmentManager) :
        FragmentStatePagerAdapter(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {

        /**
         * 确定视图位置是否更改时调用
         * @return POSITION_NONE 已更改,刷新视图. POSITION_UNCHANGED 未更改,不刷新视图
         */
        override fun getItemPosition(any: Any): Int {
            val fragment = any as BooksFragment
            val position = fragment.position
            val group = bookGroups.getOrNull(position)
            if (fragment.groupId != group?.groupId) {
                return POSITION_NONE
            }
            val bookSort = group.getRealBookSort()
            fragment.setEnableRefresh(group.enableRefresh)
            if (fragment.bookSort != bookSort) {
                fragment.upBookSort(bookSort)
            }
            return POSITION_UNCHANGED
        }

        override fun getItem(position: Int): Fragment {
            val group = bookGroups[position]
            onlyUpdateRead = group.onlyUpdateRead
            return BooksFragment(position, group)
        }

        override fun getCount(): Int {
            return bookGroups.size
        }

        // TabLayout 模式：返回分组名称作为 Tab 标题
        override fun getPageTitle(position: Int): CharSequence {
            return bookGroups[position].groupName
        }

        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            var fragment = super.instantiateItem(container, position) as BooksFragment
            val group = bookGroups[position]
            /**
             * Activity recreate 会复用之前的 Fragment，不正确的需要重新创建
             */
            if (fragment.isCreated && getItemPosition(fragment) == POSITION_NONE) {
                destroyItem(container, position, fragment)
                fragment = super.instantiateItem(container, position) as BooksFragment
            }
            fragmentMap[group.groupId] = fragment
            return fragment
        }

    }
}