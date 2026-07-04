package io.legado.app.ui.book.explore

import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.databinding.FragmentExploreShowBinding
import io.legado.app.databinding.ViewLoadMoreBinding
import io.legado.app.domain.model.BookShelfState
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.ui.widget.recycler.LoadMoreView
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.widget.FrameLayout
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.BookBottomSheet

/**
 * Fragment显示单个分类的书籍列表
 * 参考 RssArticlesFragment 的实现
 */
class ExploreShowFragment() : VMBaseFragment<ExploreShowFragmentViewModel>(R.layout.fragment_explore_show),
    ExploreShowAdapter.CallBack {

    constructor(exploreKindName: String, exploreUrl: String, sourceUrl: String) : this() {
        arguments = Bundle().apply {
            putString("exploreKindName", exploreKindName)
            putString("exploreUrl", exploreUrl)
            putString("sourceUrl", sourceUrl)
        }
    }

    private var isResumed = false
    private val binding by viewBinding(FragmentExploreShowBinding::bind)
    private val activityViewModel by activityViewModels<ExploreShowViewModel>()
    override val viewModel by viewModels<ExploreShowFragmentViewModel>()
    private val orientation by lazy { resources.configuration.orientation }

    private val adapter: ExploreShowAdapter by lazy {
        ExploreShowAdapter(requireContext(), this).apply {
            layoutMode = activityViewModel.layoutMode
            columnCount = activityViewModel.columnCount
        }
    }
    private val loadMoreView: LoadMoreView by lazy {
        LoadMoreView(requireContext())
    }
    private val loadMoreViewTop: LoadMoreView by lazy {
        LoadMoreView(requireContext())
    }
    private var oldPage = -1
    private var isClearAll = false
    private var lastLoadTime = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var loadRetryScheduled = false
    private var fullRefresh = true  // 添加fullRefresh标记，参考订阅源实现

    /** 书籍底部弹窗状态 */
    private var showBookSheet by mutableStateOf(false)
    private var selectedBook by mutableStateOf<io.legado.app.data.entities.SearchBook?>(null)
    private var selectedBookShelfState by mutableStateOf(BookShelfState.NOT_IN_SHELF)
    private var bookSheetComposeView: ComposeView? = null

    /** 当前被屏蔽的书籍数量（内部使用） */
    private var _blockedCount by mutableIntStateOf(0)
    /** 公开的屏蔽书籍数量getter */
    fun getBlockedCount(): Int = _blockedCount
    /** 屏蔽进度悬浮芯片 ComposeView */
    private var blockProgressComposeView: ComposeView? = null

    val isGridLayout: Boolean
        get() = activityViewModel.layoutMode != ExploreShowActivity.LAYOUT_LIST

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        viewModel.init(arguments, activityViewModel.bookSource)
        initView()
        initData()
    }

    /**
     * 保存 RecyclerView 当前滚动位置到 Activity ViewModel，跨 Fragment 重建恢复
     */
    private fun saveScrollToViewModel() {
        val lm = binding.recyclerView.layoutManager ?: return
        val pos = when (lm) {
            is LinearLayoutManager -> lm.findFirstVisibleItemPosition().coerceAtLeast(0)
            is StaggeredGridLayoutManager -> {
                val positions = IntArray(lm.spanCount)
                lm.findFirstVisibleItemPositions(positions)
                positions.minOrNull()?.coerceAtLeast(0) ?: 0
            }
            else -> return
        }
        if (pos > 0) {
            activityViewModel.scrollPositions[viewModel.stableExploreUrl] = pos
        }
    }

    private fun initView() = binding.run {
        recyclerView.setEdgeEffectColor(primaryColor)
        recyclerView.applyNavigationBarPadding()

        // 设置布局管理器
        applyLayoutManager()

        recyclerView.adapter = adapter
        adapter.addFooterView {
            ViewLoadMoreBinding.bind(loadMoreView)
        }
        adapter.addHeaderView {
            ViewLoadMoreBinding.bind(loadMoreViewTop)
        }

        loadMoreView.startLoad()
        loadMoreView.setOnClickListener {
            if (!loadMoreView.isLoading) {
                scrollToBottom(true)
            }
        }

        // 初始状态下隐藏loadMoreViewTop（第一页不需要向上翻页）
        val topLayoutParams = loadMoreViewTop.layoutParams
            ?: FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 0)
        topLayoutParams.height = 0
        loadMoreViewTop.layoutParams = topLayoutParams

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (!recyclerView.canScrollVertically(1)) {
                    scrollToBottom()
                } else if (!recyclerView.canScrollVertically(-1) && dy < 0) {
                    scrollToTop()
                }
            }
        })

        // 预加载模式：立即开始加载
        if (activityViewModel.isPreload) {
            refreshLayout.post {
                viewModel.loadBooks()
            }
            return@run
        }

        // 非预加载模式：等待Fragment可见后加载
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.loadBooks()
                this@launch.cancel()
            }
        }
    }

    private fun initData() {
        viewModel.booksData.observe(viewLifecycleOwner) { books ->
            upData(books)
        }
        viewModel.addBooksData.observe(viewLifecycleOwner) { books ->
            upDataTop(books)
        }
        viewModel.errorLiveData.observe(viewLifecycleOwner) {
            loadMoreView.error(it)
        }
        viewModel.errorTopLiveData.observe(viewLifecycleOwner) {
            loadMoreViewTop.error(it)
        }
        viewModel.loadFinallyLiveData.observe(viewLifecycleOwner) { hasMore ->
            if (!hasMore) {
                loadMoreView.noMore()
            }
        }
        viewModel.pageLiveData.observe(viewLifecycleOwner) { page ->
            (activity as? ExploreShowActivity)?.updatePageMenu(page, showPageMenu())
        }
        activityViewModel.upAdapterLiveData.observe(viewLifecycleOwner) {
            adapter.notifyItemRangeChanged(0, adapter.itemCount, Bundle().apply { putString(it, null) })
        }
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
    }

    override fun onPause() {
        saveScrollToViewModel()
        isResumed = false
        super.onPause()
    }

    private fun scrollToBottom(forceLoad: Boolean = false) {
        if (viewModel.isLoading) return
        fullRefresh = false  // 滚动加载时设置为false，参考订阅源实现
        
        val now = SystemClock.elapsedRealtime()
        val currentCount = activityViewModel.columnCount
        if (activityViewModel.layoutMode != ExploreShowActivity.LAYOUT_LIST && currentCount > 3 && now - lastLoadTime < ExploreShowActivity.LOAD_COOLDOWN_MS) {
            scheduleLoadRetry(ExploreShowActivity.LOAD_COOLDOWN_MS - (now - lastLoadTime))
            return
        }
        if ((loadMoreView.hasMore && !loadMoreView.isLoading && !loadMoreViewTop.isLoading) || forceLoad) {
            loadRetryScheduled = false
            lastLoadTime = now
            loadMoreView.hasMore()
            viewModel.loadMore()
        }
    }

    private fun scrollToTop(forceLoad: Boolean = false) {
        if (viewModel.isLoading) return
        if ((oldPage > 1 && !loadMoreView.isLoading && !loadMoreViewTop.isLoading) || forceLoad) {
            loadMoreViewTop.hasMore()
            oldPage--
            viewModel.loadPrevious()
        }
    }

    private fun scheduleLoadRetry(delayMs: Long) {
        if (loadRetryScheduled) return
        loadRetryScheduled = true
        handler.postDelayed({
            loadRetryScheduled = false
            scrollToBottom()
        }, delayMs)
    }

    private fun upData(books: List<io.legado.app.data.entities.SearchBook>) {
        // stopLoad() 延迟到数据更新之后调用，防止 RecyclerView 布局过程中的
        // 滚动回调触发 scrollToBottom() 时，isLoading 已被过早复位导致重复加载
        if (books.isEmpty() && adapter.isEmpty()) {
            loadMoreView.noMore(getString(R.string.empty))
        } else if (adapter.getActualItemCount() == books.size) {
            loadMoreView.noMore()
        } else {
            val oldCount = adapter.getActualItemCount()
            // 使用fullRefresh标记，参考订阅源实现
            if (oldCount == 0 || fullRefresh) {
                adapter.setItems(books)
                fullRefresh = false  // 设置完成后重置标记
                // 恢复跨 Fragment 重建保存的滚动位置
                val savedPos = activityViewModel.scrollPositions.remove(viewModel.stableExploreUrl)
                if (savedPos != null && savedPos > 0) {
                    val lm = binding.recyclerView.layoutManager
                    when (lm) {
                        is LinearLayoutManager -> lm.scrollToPositionWithOffset(savedPos, 0)
                        is StaggeredGridLayoutManager -> lm.scrollToPositionWithOffset(savedPos, 0)
                    }
                }
            } else if (oldCount > books.size) {
                // 屏蔽规则过滤后书籍数量减少，重置整个列表
                // 参考 RssArticlesFragment 的实现方式
                adapter.setItems(books)
            } else {
                // 有新增书籍，添加新项目
                val newItems = books.subList(oldCount, books.size)
                adapter.addItems(newItems)
            }
            if (isClearAll) {
                val layoutManager = binding.recyclerView.layoutManager as? LinearLayoutManager
                layoutManager?.scrollToPositionWithOffset(1, 0)
                isClearAll = false
            }
        }
        // 数据更新完成后停止加载动画（noMore() 分支内部已调用 stopLoad()，此处对 else 分支确保 isLoading 复位）
        loadMoreView.stopLoad()
        // 更新屏蔽计数
        _blockedCount = viewModel.getBlockedCount()
        updateBlockProgressChip()
        (activity as? ExploreShowActivity)?.updateBlockedCount()
    }

    private fun upDataTop(books: List<io.legado.app.data.entities.SearchBook>) {
        // 先添加数据到列表顶部（此时 isLoading 仍为 true，防止 addItems() 触发的
        // RecyclerView 布局回调重复进入 scrollToTop() 导致加载图标卡死）
        adapter.addItems(0, books)

        // 滚动到合适的位置
        val layoutManager = binding.recyclerView.layoutManager as? LinearLayoutManager
        if (layoutManager != null && layoutManager.findFirstVisibleItemPosition() <= 1) {
            layoutManager.scrollToPositionWithOffset(books.size, 0)
        }

        // 数据更新完成后停止加载动画
        loadMoreViewTop.stopLoad()

        // 如果已经是第一页，隐藏loadMoreViewTop
        if (oldPage <= 1) {
            val layoutParams = loadMoreViewTop.layoutParams as? FrameLayout.LayoutParams
            if (layoutParams != null) {
                layoutParams.height = 0
                loadMoreViewTop.layoutParams = layoutParams
            }
            loadMoreViewTop.noMore()  // 没有更多上一页数据
        }
        // 还有上一页数据时，hasMore会保持之前在scrollToTop()中通过hasMore()方法设置的true状态
    }

    fun getCurrentPage(): Int {
        return viewModel.pageLiveData.value ?: viewModel.page
    }

    fun showPageMenu(): Boolean {
        return true // 发现页总是支持页数跳转
    }

    fun showPagePicker() {
        val currentPage = getCurrentPage()
        NumberPickerDialog(requireContext())
            .setTitle(getString(R.string.change_page))
            .setMinValue(1)
            .setMaxValue(999)
            .setValue(currentPage)
            .show { targetPage ->
                if (targetPage != currentPage) {
                    fullRefresh = true  // 页数跳转时设置为true，参考订阅源实现
                    
                    // loadMoreViewTop已经在initView中添加为header，不需要重复添加
                    // 只需要根据目标页数控制其显示状态
                    if (targetPage != 1) {
                        val layoutParams = loadMoreViewTop.layoutParams as? FrameLayout.LayoutParams
                        if (layoutParams != null && layoutParams.height == 0) {
                            layoutParams.height = FrameLayout.LayoutParams.WRAP_CONTENT
                            loadMoreViewTop.layoutParams = layoutParams
                        }
                    } else {
                        // 如果跳转到第一页，隐藏loadMoreViewTop
                        val layoutParams = loadMoreViewTop.layoutParams as? FrameLayout.LayoutParams
                        if (layoutParams != null) {
                            layoutParams.height = 0
                            loadMoreViewTop.layoutParams = layoutParams
                        }
                    }
                    oldPage = targetPage
                    viewModel.skipPage(targetPage)
                    isClearAll = true
                    adapter.clearItems()
                    // 重置loadMoreView状态，防止自动触发loadMore导致页数错误
                    loadMoreView.hasMore()
                    loadMoreView.startLoad()
                    viewModel.loadBooks(targetPage)
                    binding.recyclerView.scrollToPosition(0)
                }
            }
    }

    override fun getBookShelfState(book: io.legado.app.data.entities.SearchBook): BookShelfState {
        return activityViewModel.getBookShelfState(book)
    }

    override fun showBookInfo(book: io.legado.app.data.entities.SearchBook) {
        startActivity<BookInfoActivity> {
            putExtra("name", book.name)
            putExtra("author", book.author)
            putExtra("bookUrl", book.bookUrl)
        }
    }

    override fun onBookLongClick(book: io.legado.app.data.entities.SearchBook) {
        selectedBook = book
        selectedBookShelfState = activityViewModel.getBookShelfState(book)
        showBookSheet = true
        updateBookSheetView()
    }

    /**
     * 更新书籍底部弹窗的显示状态
     */
    private fun updateBookSheetView() {
        val contentView = binding.refreshLayout
        if (showBookSheet) {
            if (bookSheetComposeView == null) {
                bookSheetComposeView = ComposeView(requireContext()).also { composeView ->
                    composeView.setContent {
                        LegadoTheme {
                            BookBottomSheet(
                                show = showBookSheet,
                                book = selectedBook,
                                shelfState = selectedBookShelfState,
                                onDismiss = {
                                    showBookSheet = false
                                    updateBookSheetView()
                                },
                                onAddToShelf = { book ->
                                    activityViewModel.addToShelf(book)
                                },
                                onShowInfo = { book ->
                                    startActivity<BookInfoActivity> {
                                        putExtra("name", book.name)
                                        putExtra("author", book.author)
                                        putExtra("bookUrl", book.bookUrl)
                                    }
                                }
                            )
                        }
                    }
                    val params = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    contentView.addView(composeView, params)
                }
            }
        } else {
            bookSheetComposeView?.let {
                contentView.removeView(it)
                bookSheetComposeView = null
            }
        }
    }

    /**
     * 更新屏蔽进度悬浮芯片的显示状态
     */
    private fun updateBlockProgressChip() {
        val contentView = binding.refreshLayout
        if (activityViewModel.showBlockProgress && _blockedCount > 0) {
            if (blockProgressComposeView == null) {
                blockProgressComposeView = ComposeView(requireContext()).also { composeView ->
                    composeView.setContent {
                        LegadoTheme {
                            Surface(
                                modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp),
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                shadowElevation = 4.dp,
                                onClick = {
                                    (activity as? ExploreShowActivity)?.showBlockRuleConfig()
                                }
                            ) {
                                Text(
                                    text = getString(R.string.explore_block_rule_progress_text, _blockedCount),
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                    val params = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
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
     * 屏蔽规则变化后重新过滤当前书籍列表
     * 由 ExploreShowActivity 调用
     */
    fun applyBlockRules() {
        if (!isAdded) return
        viewModel.applyBlockRules()
    }

    /**
     * 获取当前书籍数量
     * 由 ExploreShowActivity 调用
     */
    fun getBooksCount(): Int = viewModel.getBooksCount()

    /**
     * 获取所有书籍列表（用于屏蔽规则配置）
     * 由 ExploreShowActivity 调用
     */
    fun getAllBooks(): List<io.legado.app.data.entities.SearchBook> = viewModel.getAllBooks()

    /**
     * 应用布局管理器
     */
    private fun applyLayoutManager() {
        // 检查Fragment生命周期，避免访问已销毁的binding
        if (!isAdded || view == null) return
        
        val count = activityViewModel.columnCount
        
        // 保存当前滚动位置，切换布局后恢复
        val savedPosition = saveScrollPosition()
        
        // 清除旧的ItemDecoration和padding，避免重复添加
        while (binding.recyclerView.itemDecorationCount > 0) {
            binding.recyclerView.removeItemDecorationAt(0)
        }
        binding.recyclerView.setPadding(0, 0, 0, 0)
        
        binding.recyclerView.layoutManager = when {
            activityViewModel.layoutMode == ExploreShowActivity.LAYOUT_LIST || count <= 1 -> {
                binding.recyclerView.itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator()
                binding.recyclerView.addItemDecoration(VerticalDivider(requireContext()))
                LinearLayoutManager(requireContext())
            }
            activityViewModel.layoutMode == ExploreShowActivity.LAYOUT_WATERFALL -> {
                binding.recyclerView.itemAnimator = null
                binding.recyclerView.setPadding(8.dpToPx(), 0, 8.dpToPx(), 0)
                binding.recyclerView.addItemDecoration(object : RecyclerView.ItemDecoration() {
                    override fun getItemOffsets(
                        outRect: Rect,
                        view: View,
                        parent: RecyclerView,
                        state: RecyclerView.State
                    ) {
                        // 移除顶部边距，确保第一行书籍紧贴Tab
                        outRect.set(8, 0, 8, 12)
                    }
                })
                // 根据用户选择的列数设置，考虑横屏和竖屏
                val waterfallCount = if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    (count + 1).coerceAtMost(5) // 横屏可以多一列，最多5列
                } else {
                    count.coerceAtLeast(2) // 竖屏至少2列
                }
                StaggeredGridLayoutManager(waterfallCount, StaggeredGridLayoutManager.VERTICAL)
            }
            else -> {
                binding.recyclerView.itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator()
                binding.recyclerView.setPadding(8.dpToPx(), 0, 8.dpToPx(), 0)
                // 确保 adapter.columnCount 与 GridLayoutManager spanCount 同步
                // 否则 getSpanSize() 中 header/footer 占用的列数可能小于实际列数
                adapter.columnCount = count
                GridLayoutManager(requireContext(), count).apply {
                    spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                        override fun getSpanSize(position: Int): Int {
                            return if (position < adapter.getHeaderCount()
                                || position >= adapter.getActualItemCount() + adapter.getHeaderCount()
                            ) {
                                count
                            } else {
                                1
                            }
                        }
                    }
                }
            }
        }
        
        // 恢复滚动位置（延迟到布局完成）
        if (savedPosition > 0) {
            binding.recyclerView.post {
                val lm = binding.recyclerView.layoutManager
                when (lm) {
                    is LinearLayoutManager -> lm.scrollToPositionWithOffset(savedPosition, 0)
                    is StaggeredGridLayoutManager -> lm.scrollToPositionWithOffset(savedPosition, 0)
                }
            }
        }
    }

    /**
     * 保存当前 RecyclerView 滚动位置
     * @return 第一个可见 item 的 adapter 位置，无数据时返回 0
     */
    private fun saveScrollPosition(): Int {
        val lm = binding.recyclerView.layoutManager ?: return 0
        return when (lm) {
            is LinearLayoutManager -> lm.findFirstVisibleItemPosition().coerceAtLeast(0)
            is StaggeredGridLayoutManager -> {
                val positions = IntArray(lm.spanCount)
                lm.findFirstVisibleItemPositions(positions)
                positions.minOrNull()?.coerceAtLeast(0) ?: 0
            }
            else -> 0
        }
    }

    /**
     * 更新布局模式（由Activity调用）
     */
    fun updateLayoutMode(mode: Int, columnCount: Int) {
        // 检查Fragment生命周期，避免在Fragment未attached时执行
        if (!isAdded) return
        
        adapter.layoutMode = mode
        adapter.columnCount = columnCount
        applyLayoutManager()
    }
}